package uz.unnarsx.cherrygram.chats.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Unit tests of {@link FolderFeedCacheCore}: hole trimming of cached windows and the in-memory stretches. */
public class FolderFeedCacheCoreTest {

    /** A cached message; copies are distinct objects, so tests can tell a copy from the original. */
    static final class Msg {
        final int id;
        final int date;
        int copies;

        Msg(int id, int date) {
            this.id = id;
            this.date = date;
        }

        @Override
        public String toString() {
            return id + "@" + date;
        }
    }

    static final FolderFeedCacheCore.Ids<Msg> IDS = new FolderFeedCacheCore.Ids<Msg>() {
        @Override
        public int id(Msg message) {
            return message.id;
        }

        @Override
        public int date(Msg message) {
            return message.date;
        }

        @Override
        public Msg copy(Msg message) {
            message.copies++;
            return message.id < 0 ? null : new Msg(message.id, message.date);
        }
    };

    private static Msg m(int id) {
        return new Msg(id, 1000 + id * 10);
    }

    /** Messages with the given ids, newest first. */
    private static List<Msg> page(int from, int to) {
        ArrayList<Msg> page = new ArrayList<>();
        for (int id = to; id >= from; id--) {
            page.add(m(id));
        }
        return page;
    }

    private static List<Integer> ids(List<Msg> messages) {
        ArrayList<Integer> ids = new ArrayList<>();
        for (Msg message : messages) {
            ids.add(message.id);
        }
        return ids;
    }

    private static List<Integer> range(int to, int from) {
        ArrayList<Integer> ids = new ArrayList<>();
        for (int id = to; id >= from; id--) {
            ids.add(id);
        }
        return ids;
    }

    // region Holes

    private static List<Msg> window(int... ids) {
        ArrayList<Msg> window = new ArrayList<>();
        for (int id : ids) {
            window.add(m(id));
        }
        Collections.shuffle(window, new Random(ids.length));
        return window;
    }

    @Test
    public void windowWithoutHolesIsKeptNewestFirst() {
        List<Msg> trimmed = FolderFeedCacheCore.trimToHoles(IDS, window(10, 11, 12, 13), 11, Collections.<int[]>emptyList());
        assertEquals(Arrays.asList(13, 12, 11, 10), ids(trimmed));
    }

    @Test
    public void holesCutTheWindowOnEitherSideOfThePivot() {
        // Messages strictly between start and end are missing: a hole (12, 20) separates 12 and 20.
        List<int[]> holes = Arrays.asList(new int[]{12, 20}, new int[]{3, 5});
        List<Msg> trimmed = FolderFeedCacheCore.trimToHoles(IDS, window(2, 3, 5, 6, 10, 12, 20, 21), 10, holes);
        assertEquals("the hole's end ids themselves are cached and connected", Arrays.asList(12, 10, 6, 5), ids(trimmed));
    }

    @Test
    public void holesEndingOrStartingAtThePivot() {
        List<int[]> holes = Arrays.asList(new int[]{3, 10}, new int[]{12, 20});
        assertEquals("a hole ending at the pivot cuts below it", Arrays.asList(12, 11, 10),
                ids(FolderFeedCacheCore.trimToHoles(IDS, window(1, 2, 3, 10, 11, 12), 10, holes)));
        assertEquals("a hole starting at the pivot cuts above it", Arrays.asList(12, 11, 10),
                ids(FolderFeedCacheCore.trimToHoles(IDS, window(10, 11, 12, 20, 21), 12, holes)));
    }

    @Test
    public void pivotInsideAHoleGivesNothing() {
        List<Msg> trimmed = FolderFeedCacheCore.trimToHoles(IDS, window(5, 6, 30, 31), 20, Arrays.asList(new int[]{6, 30}));
        assertTrue(trimmed.isEmpty());
    }

    @Test
    public void holesOutsideTheWindowAreIgnored() {
        List<int[]> holes = Arrays.asList(new int[]{0, 5}, new int[]{40, 50}, new int[]{20, 25});
        List<Msg> trimmed = FolderFeedCacheCore.trimToHoles(IDS, window(5, 6, 7, 20), 6, holes);
        assertEquals("(0,5) ends at the window's oldest id, (20,25) starts at its newest", Arrays.asList(20, 7, 6, 5), ids(trimmed));
    }

    @Test
    public void nearestHolesWin() {
        List<int[]> holes = Arrays.asList(new int[]{2, 4}, new int[]{4, 6}, new int[]{14, 16}, new int[]{12, 13});
        List<Msg> trimmed = FolderFeedCacheCore.trimToHoles(IDS, window(1, 2, 4, 6, 8, 12, 13, 14, 16), 8, holes);
        assertEquals(Arrays.asList(12, 8, 6), ids(trimmed));
    }

    @Test
    public void emptyWindow() {
        assertTrue(FolderFeedCacheCore.trimToHoles(IDS, new ArrayList<Msg>(), 5, Arrays.asList(new int[]{1, 9})).isEmpty());
    }

    @Test
    public void holePivotForIdAndDateRequests() {
        // newer: read oldest first (ASC); older: newest first (DESC), as readWindow queries them.
        List<Msg> newer = Arrays.asList(m(20), m(21));
        List<Msg> older = Arrays.asList(m(19), m(18));
        assertEquals(17, FolderFeedCacheCore.holePivot(IDS, newer, older, 17));
        assertEquals("date request: the oldest message at or after the date", 20, FolderFeedCacheCore.holePivot(IDS, newer, older, 0));
        assertEquals("nothing after the date: the newest before it", 19, FolderFeedCacheCore.holePivot(IDS, new ArrayList<Msg>(), older, 0));
    }

    // endregion

    // region Stretches

    @Test
    public void separatePagesStaySeparateStretches() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(1, 10), 0, 1);
        stretches.remember(page(20, 30), 0, 2);
        assertEquals(2, stretches.list.size());
        // A window around 25 stays within its stretch: nothing from the unconnected 1..10.
        assertEquals(range(30, 21), ids(stretches.window(25, 0, 20, 20, 3).subList(0, 10)));
        assertEquals("never crosses into another stretch", range(30, 20), ids(stretches.window(25, 0, 20, 20, 3)));
    }

    @Test
    public void overlappingAndContinuingPagesMerge() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(1, 10), 0, 1);
        stretches.remember(page(20, 30), 0, 2);
        // An older page below 20 continues the stretch holding its offset post 20 (no shared id).
        stretches.remember(page(12, 19), 20, 3);
        assertEquals(2, stretches.list.size());
        // A page overlapping both remaining stretches joins them into one.
        stretches.remember(page(9, 13), 0, 4);
        assertEquals(1, stretches.list.size());
        assertEquals(range(30, 1), ids(stretches.window(15, 0, 100, 100, 5)));
    }

    @Test
    public void pagesSharingOneMessageMerge() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(1, 10), 0, 1);
        stretches.remember(page(10, 20), 0, 2);
        assertEquals(1, stretches.list.size());
        assertEquals(range(20, 1), ids(stretches.window(10, 0, 50, 50, 3)));
    }

    @Test
    public void storesCopiesAndReturnsCopies() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        List<Msg> original = page(1, 3);
        original = new ArrayList<>(original);
        original.add(new Msg(-1, 0)); // cannot be copied: skipped
        stretches.remember(original, 0, 1);
        assertEquals(1, original.get(0).copies);
        List<Msg> window = stretches.window(2, 0, 5, 5, 2);
        assertEquals(Arrays.asList(3, 2, 1), ids(window));
        assertNotSame(original.get(0), window.get(0));
        assertNotSame("a fresh copy every time", window.get(0), stretches.window(2, 0, 5, 5, 3).get(0));
    }

    @Test
    public void windowLimitsAroundAnIdPivot() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(1, 50), 0, 1);
        assertEquals("3 from the pivot on, 2 before it", Arrays.asList(27, 26, 25, 24, 23), ids(stretches.window(25, 0, 2, 3, 2)));
    }

    @Test
    public void windowAroundADatePivot() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(1, 50), 0, 1);
        // m(id).date = 1000 + 10 * id: date 1255 falls between 25 and 26.
        assertEquals(Arrays.asList(27, 26, 25, 24), ids(stretches.window(0, 1255, 2, 2, 2)));
        assertEquals("from the top", Arrays.asList(50, 49, 48), ids(stretches.window(0, Integer.MAX_VALUE, 3, 0, 3)));
    }

    @Test
    public void windowPrefersTheStretchSpanningThePivotElseTheNewest() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(40, 50), 0, 1);
        stretches.remember(page(1, 10), 0, 2);
        stretches.remember(page(20, 30), 0, 3);
        assertEquals(range(10, 1), ids(stretches.window(5, 0, 20, 20, 4)));
        assertEquals("pivot in no stretch: the newest stretch, from its first message after the pivot",
                range(50, 40), ids(stretches.window(35, 0, 20, 20, 5)));
        assertEquals("pivot above everything: the newest stretch's newest messages", range(50, 40), ids(stretches.window(60, 0, 20, 20, 6)));
    }

    @Test
    public void emptyMemory() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        assertNull(stretches.window(5, 0, 5, 5, 1));
        stretches.remember(new ArrayList<Msg>(), 0, 1);
        assertNull(stretches.window(5, 0, 5, 5, 1));
    }

    @Test
    public void leastRecentlyUsedStretchesAreDroppedFirst() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(1, 50), 0, 1);
        stretches.remember(page(101, 150), 0, 2);
        // Reading the first stretch makes it recently used.
        stretches.window(25, 0, 5, 5, 3);
        stretches.remember(page(201, 250), 0, 4); // 150 > MEMORY_PER_CHANNEL: one stretch goes
        assertEquals(2, stretches.list.size());
        assertEquals(range(50, 1), ids(stretches.window(25, 0, 100, 100, 5)));
        assertEquals(range(250, 201), ids(stretches.window(225, 0, 100, 100, 6)));
        assertEquals("101..150 was dropped: the newest stretch is used instead", range(250, 201), ids(stretches.window(125, 0, 100, 100, 7)));
    }

    @Test
    public void anOversizedStretchDropsTheEndFarFromTheNewPage() {
        FolderFeedCacheCore.Stretches<Msg> stretches = new FolderFeedCacheCore.Stretches<>(IDS);
        stretches.remember(page(1, 100), 0, 1);
        stretches.remember(page(81, 140), 0, 2); // merged: 1..140, the new page is near the top
        assertEquals(1, stretches.list.size());
        assertEquals(FolderFeedCacheCore.MEMORY_PER_CHANNEL, stretches.list.get(0).byId.size());
        assertEquals("the old bottom goes", 21, stretches.list.get(0).minId());
        stretches.remember(page(1, 40), 21, 3); // continues at 21: merged 1..140 again, new page at the bottom
        assertEquals(1, stretches.list.size());
        assertEquals(1, stretches.list.get(0).minId());
        assertEquals("the top goes now", 120, stretches.list.get(0).maxId());
    }

    // endregion
}
