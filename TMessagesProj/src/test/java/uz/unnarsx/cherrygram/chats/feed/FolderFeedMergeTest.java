package uz.unnarsx.cherrygram.chats.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import uz.unnarsx.cherrygram.chats.feed.FakeHistory.Post;
import uz.unnarsx.cherrygram.chats.feed.FolderFeedMerge.Channel;
import uz.unnarsx.cherrygram.chats.feed.FolderFeedMerge.DialogTop;

/** Unit tests of {@link FolderFeedMerge}'s building blocks. */
public class FolderFeedMergeTest {

    private static final long A = -100, B = -200, C = -300;
    private final FolderFeedMerge<Post> merge = new FolderFeedMerge<>(FakeHistory.POSTS);

    private static Post p(long dialogId, int id, int date) {
        return Post.post(dialogId, id, date);
    }

    private static List<String> keys(List<Post> posts) {
        ArrayList<String> keys = new ArrayList<>();
        for (Post post : posts) {
            keys.add(post.dialogId + "#" + post.id);
        }
        return keys;
    }

    private static Channel<Post> channel(long dialogId) {
        return new Channel<>(dialogId);
    }

    // region Window scans

    @Test
    public void scanWindowCountsAroundAnIdPivotAndSkipsOtherChatsAndEmptyMessages() {
        Channel<Post> a = channel(A);
        List<Post> window = Arrays.asList(p(A, 12, 120), Post.service(A, 11, 110), p(A, 10, 100), p(B, 11, 111),
                Post.empty(A, 9), p(A, 8, 80));
        FolderFeedMerge.WindowScan<Post> scan = merge.scanWindow(window, a, 10, 0);
        assertEquals(4, scan.messages.size());
        assertEquals(8, scan.minId);
        assertEquals(12, scan.maxId);
        assertEquals("from the pivot id on", 3, scan.newerCount);
        assertEquals(1, scan.olderCount);
        assertEquals("the first post after the pivot, not the service message", 12, scan.firstPostAfterPivot.id);
    }

    @Test
    public void scanWindowWithADatePivot() {
        Channel<Post> a = channel(A);
        FolderFeedMerge.WindowScan<Post> scan = merge.scanWindow(Arrays.asList(p(A, 3, 300), p(A, 2, 200), p(A, 1, 100)), a, 0, 200);
        assertEquals(2, scan.newerCount);
        assertEquals(1, scan.olderCount);
        assertNull("only an id pivot has a first post after it", scan.firstPostAfterPivot);
    }

    // endregion

    // region Watermark merge

    @Test
    public void olderPostsAreReadyDownToTheHighestFrontierOfChannelsWithMoreToLoad() {
        Channel<Post> a = channel(A), b = channel(B), c = channel(C);
        a.older.buffer.addAll(Arrays.asList(p(A, 5, 500), p(A, 4, 400), p(A, 3, 300)));
        a.older.frontier = 300;
        b.older.buffer.addAll(Arrays.asList(p(B, 9, 450), p(B, 8, 350)));
        b.older.frontier = 350;
        // C has nothing buffered but may still have posts up to 400 unfetched.
        c.older.frontier = 400;
        // An exhausted edge's frontier does not hold anything back.
        Channel<Post> done = channel(-400);
        done.older.exhausted = true;
        done.older.frontier = 1_000;
        List<Channel<Post>> channels = Arrays.asList(a, b, c, done);

        ArrayList<Post> ready = merge.collectReady(channels, false);
        assertEquals("posts at the watermark's own second are included", Arrays.asList("-100#5", "-200#9", "-100#4"), keys(ready));
        assertEquals(Arrays.asList("-100#3"), keys(a.older.buffer));
        assertEquals(Arrays.asList("-200#8"), keys(b.older.buffer));

        c.older.exhausted = true;
        ready = merge.collectReady(channels, false);
        assertEquals("with C done, B's frontier is the watermark", Arrays.asList("-200#8"), keys(ready));
    }

    @Test
    public void newerPostsAreReadyUpToTheLowestFrontier() {
        Channel<Post> a = channel(A), b = channel(B);
        a.newer.buffer.addAll(Arrays.asList(p(A, 1, 100), p(A, 2, 200), p(A, 3, 300)));
        a.newer.frontier = 300;
        b.newer.buffer.addAll(Arrays.asList(p(B, 1, 150), p(B, 2, 250)));
        b.newer.frontier = 250;
        ArrayList<Post> ready = merge.collectReady(Arrays.asList(a, b), true);
        assertEquals("newest first", Arrays.asList("-200#2", "-100#2", "-200#1", "-100#1"), keys(ready));
        assertEquals(Arrays.asList("-100#3"), keys(a.newer.buffer));
    }

    @Test
    public void sameSecondPostsAreOrderedByChannelThenId() {
        ArrayList<Post> posts = new ArrayList<>(Arrays.asList(p(A, 1, 100), p(B, 7, 100), p(A, 2, 100), p(C, 1, 101)));
        merge.sortNewestFirst(posts);
        assertEquals(Arrays.asList("-300#1", "-200#7", "-100#2", "-100#1"), keys(posts));
        merge.sortOldestFirst(posts);
        assertEquals(Arrays.asList("-100#1", "-100#2", "-200#7", "-300#1"), keys(posts));
    }

    private static List<Channel<Post>> channelsWithReady(int count, boolean newer) {
        Channel<Post> a = channel(A), b = channel(B);
        for (int i = 1; i <= count; i++) {
            Channel<Post> channel = i % 2 == 0 ? a : b;
            channel.edge(newer).buffer.add(p(channel.dialogId, i, 1000 + i));
        }
        for (Channel<Post> channel : Arrays.asList(a, b)) {
            channel.edge(newer).exhausted = true;
        }
        FolderFeedMerge<Post> merge = new FolderFeedMerge<>(FakeHistory.POSTS);
        merge.sortNewestFirst(a.older.buffer);
        merge.sortNewestFirst(b.older.buffer);
        merge.sortOldestFirst(a.newer.buffer);
        merge.sortOldestFirst(b.newer.buffer);
        return Arrays.asList(a, b);
    }

    @Test
    public void capPageKeepsTheOlderPagesNewestPostsAndBuffersTheRest() {
        List<Channel<Post>> channels = channelsWithReady(70, false);
        ArrayList<Post> page = merge.takePage(channels, false);
        assertEquals(FolderFeedMerge.MAX_PAGE_SIZE, page.size());
        assertEquals("continues from the loaded range: the newest", 1070, page.get(0).date);
        assertEquals(1011, page.get(page.size() - 1).date);
        ArrayList<Post> rest = merge.takePage(channels, false);
        assertEquals(10, rest.size());
        assertEquals(1010, rest.get(0).date);
        assertEquals(1001, rest.get(9).date);
        assertFalse(FolderFeedMerge.hasMore(channels, false));
    }

    @Test
    public void capPageKeepsTheNewerPagesOldestPosts() {
        List<Channel<Post>> channels = channelsWithReady(70, true);
        ArrayList<Post> page = merge.takePage(channels, true);
        assertEquals(FolderFeedMerge.MAX_PAGE_SIZE, page.size());
        assertEquals("continues from the loaded range: the oldest", 1001, page.get(page.size() - 1).date);
        assertEquals(1060, page.get(0).date);
        ArrayList<Post> rest = merge.takePage(channels, true);
        assertEquals(10, rest.size());
        assertEquals(1070, rest.get(0).date);
        assertEquals(1061, rest.get(9).date);
    }

    @Test
    public void takePageWaitsForAFullPageWhileChannelsHaveMore() {
        Channel<Post> a = channel(A), b = channel(B);
        for (int i = 1; i <= 5; i++) {
            a.older.buffer.add(0, p(A, i, 100 + i));
        }
        a.older.frontier = 101;
        b.older.frontier = 50;
        List<Channel<Post>> channels = Arrays.asList(a, b);
        assertNull(merge.takePage(channels, false));
        assertEquals("posts stay buffered", 5, a.older.buffer.size());
        assertEquals("newest first in the buffer", 105, a.older.buffer.get(0).date);

        a.older.exhausted = true;
        b.older.exhausted = true;
        ArrayList<Post> page = merge.takePage(channels, false);
        assertEquals("a short page at the end of history", 5, page.size());
    }

    @Test
    public void fetchCandidatesHoldingTheMergeBackComeFirst() {
        Channel<Post> a = channel(A), b = channel(B), c = channel(C), d = channel(-400);
        a.older.frontier = 100;
        b.older.frontier = 300;
        c.older.frontier = 200;
        c.newer.frontier = 50;
        d.older.frontier = 400;
        d.older.inFlight = true;
        a.newer.frontier = 70;
        d.newer.frontier = 80;
        b.newer.exhausted = true;
        List<Channel<Post>> channels = Arrays.asList(a, b, c, d);
        assertEquals("older: newest frontier first, in-flight skipped", Arrays.asList(b, c, a), FolderFeedMerge.fetchCandidates(channels, false));
        assertEquals("newer: oldest frontier first, exhausted skipped", Arrays.asList(c, a, d), FolderFeedMerge.fetchCandidates(channels, true));
    }

    // endregion

    // region Edge pages

    @Test
    public void edgeQueries() {
        Channel<Post> a = channel(A);
        a.older.offsetId = 50;
        a.newer.offsetId = 60;
        FolderFeedMerge.Query older = FolderFeedMerge.edgeQuery(a, false);
        assertEquals(50, older.offsetId);
        assertEquals(0, older.addOffset);
        assertEquals(FolderFeedMerge.FETCH_LIMIT, older.limit);
        FolderFeedMerge.Query newer = FolderFeedMerge.edgeQuery(a, true);
        assertEquals(60, newer.offsetId);
        assertEquals(-FolderFeedMerge.FETCH_LIMIT, newer.addOffset);
        assertEquals(FolderFeedMerge.FETCH_LIMIT + 1, newer.limit);
    }

    @Test
    public void olderPageMovesTheEdgeAndSkipsServiceMessagesAndKnownPosts() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 50; i++) {
            server.add(i % 10 == 0 ? Post.service(A, i, 1000 + i) : p(A, i, 1000 + i));
        }
        Channel<Post> a = channel(A);
        a.older.offsetId = 45;
        a.seen.add(44);
        merge.onEdgePage(a, false, 45, server.getHistory(A, FolderFeedMerge.edgeQuery(a, false)), 0, 0);
        assertEquals("ids 25..44", 25, a.older.offsetId);
        assertEquals(1025, a.older.frontier);
        assertFalse(a.older.exhausted);
        assertEquals("posts only, the known one skipped", 17, a.older.buffer.size());
        assertEquals("newest first", 43, a.older.buffer.get(0).id);

        merge.onEdgePage(a, false, 25, server.getHistory(A, FolderFeedMerge.edgeQuery(a, false)), 0, 0);
        assertEquals(5, a.older.offsetId);
        assertFalse(a.older.exhausted);
        merge.onEdgePage(a, false, 5, server.getHistory(A, FolderFeedMerge.edgeQuery(a, false)), 0, 0);
        assertTrue("fewer than a full page: the start of history", a.older.exhausted);
        assertEquals(1, a.older.offsetId);
    }

    @Test
    public void newerPageWithOrWithoutTheOffsetPost() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 60; i++) {
            server.add(p(A, i, 1000 + i));
        }
        Channel<Post> a = channel(A);
        a.newer.offsetId = 10;
        merge.onEdgePage(a, true, 10, server.getHistory(A, FolderFeedMerge.edgeQuery(a, true)), 0, 0);
        assertEquals("the offset post exists: FETCH_LIMIT - 1 newer posts", 29, a.newer.offsetId);
        assertEquals(19, a.newer.buffer.size());
        assertEquals("oldest first", 11, a.newer.buffer.get(0).id);
        assertFalse(a.newer.exhausted);

        // The offset post was deleted: the window holds FETCH_LIMIT newer posts.
        server.history(A).removeIf(post -> post.id == 29);
        merge.onEdgePage(a, true, 29, server.getHistory(A, FolderFeedMerge.edgeQuery(a, true)), 0, 0);
        assertEquals(49, a.newer.offsetId);
        assertFalse(a.newer.exhausted);
        merge.onEdgePage(a, true, 49, server.getHistory(A, FolderFeedMerge.edgeQuery(a, true)), 0, 0);
        assertEquals(60, a.newer.offsetId);
        assertTrue("the newest post was reached", a.newer.exhausted);
        assertEquals("19 + 20 + 11 posts", 50, a.newer.buffer.size());
    }

    @Test
    public void aNewPostDuringANewerRequestKeepsTheEdgeOpen() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 5; i++) {
            server.add(p(A, i, 1000 + i));
        }
        Channel<Post> a = channel(A);
        a.newer.offsetId = 3;
        int liveVersion = a.liveVersion;
        List<Post> response = server.getHistory(A, FolderFeedMerge.edgeQuery(a, true));
        // A post arrives before the (older) response is handled.
        server.add(p(A, 6, 1006));
        FolderFeedMerge.onNewPosts(a, 6, 1006, true);
        merge.onEdgePage(a, true, 3, response, 0, liveVersion);
        assertFalse("more to load", a.newer.exhausted);
        merge.onEdgePage(a, true, 5, server.getHistory(A, FolderFeedMerge.edgeQuery(a, true)), 0, a.liveVersion);
        assertTrue(a.newer.exhausted);
        assertEquals(Arrays.asList("-100#4", "-100#5", "-100#6"), keys(a.newer.buffer));
    }

    @Test
    public void olderPagesSplitPostsFromTheOpeningPointOnToTheNewerEdge() {
        // A channel set up at its top (its last post was older than the opening point), which then got 45
        // new posts: its older edge pages down from the new top, through three pages of posts that all
        // belong above the opening point.
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 30; i++) {
            server.add(p(A, i, 100 + i));
        }
        int anchor = 500;
        Channel<Post> a = channel(A);
        merge.initAtDialogTop(a, server.top(A));
        for (int i = 31; i <= 75; i++) {
            server.add(p(A, i, 1000 + i));
        }
        FolderFeedMerge.onNewPosts(a, 31, 1031, true);
        for (int page = 0; page < 3; page++) {
            merge.onEdgePage(a, false, a.older.offsetId, server.getHistory(A, FolderFeedMerge.edgeQuery(a, false)), anchor, a.liveVersion);
        }
        for (Post post : a.older.buffer) {
            assertTrue("older edge holds only posts before the opening point: " + post, post.date < anchor);
        }
        for (Post post : a.newer.buffer) {
            assertTrue(post.date >= anchor);
        }
        assertEquals("all 45 new posts went to the newer side", 45, a.newer.buffer.size());
        for (int i = 1; i < a.newer.buffer.size(); i++) {
            assertTrue("newer buffer oldest first", a.newer.buffer.get(i - 1).id < a.newer.buffer.get(i).id);
        }
        for (int i = 1; i < a.older.buffer.size(); i++) {
            assertTrue("older buffer newest first", a.older.buffer.get(i - 1).id > a.older.buffer.get(i).id);
        }
        assertEquals(15, a.older.buffer.size());
    }

    @Test
    public void openingAtLatestDoesNotSplit() {
        assertFalse(FolderFeedMerge.splitsAtAnchor(false, 0));
        assertFalse(FolderFeedMerge.splitsAtAnchor(true, 123));
        assertTrue(FolderFeedMerge.splitsAtAnchor(false, 123));
    }

    // endregion

    // region Opening

    @Test
    public void aroundWindowSplitsAtTheOpeningPoint() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 100; i++) {
            server.add(p(A, i, 1000 + i));
        }
        Channel<Post> a = channel(A);
        merge.onAroundPage(a, server.getHistory(A, FolderFeedMerge.aroundQuery(1050)), 1050, 0, server.top(A));
        assertTrue(a.aroundDone);
        assertEquals("AROUND_OLDER before", FolderFeedMerge.AROUND_OLDER, a.older.buffer.size());
        assertEquals("AROUND_NEWER from the point on", FolderFeedMerge.AROUND_NEWER, a.newer.buffer.size());
        assertEquals(40, a.older.offsetId);
        assertEquals(69, a.newer.offsetId);
        assertFalse(a.older.exhausted);
        assertFalse(a.newer.exhausted);
    }

    @Test
    public void aroundWindowNearTheEndsOfHistory() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 8; i++) {
            server.add(p(A, i, 1000 + i));
        }
        Channel<Post> a = channel(A);
        merge.onAroundPage(a, server.getHistory(A, FolderFeedMerge.aroundQuery(1005)), 1005, 0, server.top(A));
        assertTrue(a.older.exhausted);
        assertTrue(a.newer.exhausted);
        assertEquals(4, a.older.buffer.size());
        assertEquals(4, a.newer.buffer.size());

        // Nothing at or after the point yet: newer posts, once they arrive, follow the newest one.
        Channel<Post> b = channel(B);
        server.add(p(B, 5, 900));
        merge.onAroundPage(b, server.getHistory(B, FolderFeedMerge.aroundQuery(1005)), 1005, 0, server.top(B));
        assertEquals(5, b.newer.offsetId);
        assertEquals(900, b.newer.frontier);
        assertTrue(b.newer.exhausted);
    }

    @Test
    public void aroundRequestThatFailsLeavesTheChannelOut() {
        Channel<Post> a = channel(A);
        merge.onAroundGaveUp(a, new DialogTop(true, 77, 7700));
        assertTrue(a.aroundDone);
        assertTrue(a.older.exhausted);
        assertTrue(a.newer.exhausted);
        assertEquals("live posts would continue after the top", 77, a.newer.offsetId);
    }

    @Test
    public void initAtDialogTop() {
        Channel<Post> a = channel(A);
        merge.initAtDialogTop(a, new DialogTop(true, 42, 4200));
        assertEquals(0, a.older.offsetId);
        assertEquals(4200, a.older.frontier);
        assertTrue(a.newer.exhausted);
        assertEquals(42, a.newer.offsetId);
        Channel<Post> unknown = channel(B);
        merge.initAtDialogTop(unknown, DialogTop.UNKNOWN);
        assertEquals("unknown top: may hold posts of any time", Integer.MAX_VALUE, unknown.older.frontier);
        assertEquals(0, unknown.newer.offsetId);
    }

    @Test
    public void probeFindsTheFirstUnreadPostSkippingServiceMessages() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 35; i++) {
            server.add(i == 21 || i == 22 ? Post.service(A, i, 1000 + i) : p(A, i, 1000 + i));
        }
        Channel<Post> a = channel(A);
        a.readMax = 20;
        a.unreadCount = 18;
        merge.onProbePage(a, server.getHistory(A, FolderFeedMerge.probeQuery(a)), 0);
        assertTrue(a.probeDone);
        assertEquals("the first unread post, not the unread service messages before it", 1023, a.firstUnreadDate);
        assertFalse("10 messages before the read pointer: more may exist", a.probeOlderEnd);
        assertTrue("fewer than AROUND_NEWER after it: the top", a.probeNewerEnd);
        assertEquals("the read post, 15 after it, 10 before it", 26, a.probeWindow.size());

        // Exactly AROUND_NEWER messages after the read pointer: more may follow.
        server.add(p(A, 36, 1036));
        server.add(p(A, 37, 1037));
        server.add(p(A, 38, 1038));
        server.add(p(A, 39, 1039));
        server.add(p(A, 40, 1040));
        Channel<Post> again = channel(A);
        again.readMax = 20;
        again.unreadCount = 18;
        merge.onProbePage(again, server.getHistory(A, FolderFeedMerge.probeQuery(again)), 0);
        assertFalse(again.probeNewerEnd);
    }

    @Test
    public void aChannelWithOnlyUnreadServiceMessagesDoesNotSetTheOpeningPoint() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 10; i++) {
            server.add(p(A, i, 1000 + i));
        }
        server.add(Post.service(A, 11, 1011));
        Channel<Post> a = channel(A);
        a.readMax = 10;
        a.unreadCount = 1;
        merge.onProbePage(a, server.getHistory(A, FolderFeedMerge.probeQuery(a)), 0);
        assertEquals(0, a.firstUnreadDate);
        assertEquals(0, FolderFeedMerge.anchorFromProbes(Arrays.asList(a)));
    }

    /**
     * Regression: a channel's first unread message was a service message long before its first unread post.
     * The feed opened at the service message's time, and a busy channel's posts from then on filled the first
     * page, so no unread post was in it and the feed did not open at the first unread post.
     */
    @Test
    public void opensAtTheFirstUnreadPostWhenAServiceMessagePrecedesIt() {
        FakeHistory server = new FakeHistory();
        server.add(p(A, 1, 1000));
        server.add(Post.service(A, 2, 1001)); // e.g. "post pinned"
        server.add(p(A, 3, 5000));
        for (int i = 1; i <= 100; i++) {
            server.add(p(B, i, 990 + i * 10));
        }
        FeedDriver feed = new FeedDriver(server, new Random(1), 4);
        feed.setRead(A, 1);
        feed.setRead(B, 100);
        assertTrue(feed.openAtFirstUnread());
        assertTrue(feed.initial.firstUnreadIndex >= 0);
        Post at = feed.initial.messages.get(feed.initial.firstUnreadIndex);
        assertEquals(A, at.dialogId);
        assertEquals(3, at.id);
        feed.pageToEnd(true);
        feed.pageToEnd(false);
        feed.assertCompleteAndOrdered();
    }

    @Test
    public void openingPointIsTheOldestFirstUnreadPost() {
        Channel<Post> a = channel(A), b = channel(B), c = channel(C);
        a.firstUnreadDate = 300;
        b.firstUnreadDate = 200;
        assertEquals(200, FolderFeedMerge.anchorFromProbes(Arrays.asList(a, b, c)));
        assertEquals(0, FolderFeedMerge.anchorFromProbes(Arrays.asList(c)));
    }

    @Test
    public void prepareAroundReusesTheProbeWindowOrSkipsChannelsThatEndedBefore() {
        FakeHistory server = new FakeHistory();
        for (int i = 1; i <= 50; i++) {
            server.add(p(A, i, 1000 + 10 * i));
        }
        Channel<Post> a = channel(A);
        a.readMax = 30;
        a.unreadCount = 20;
        merge.onProbePage(a, server.getHistory(A, FolderFeedMerge.probeQuery(a)), 0);
        // The probe window starts at 1210, before the opening point 1250: reused.
        merge.prepareAround(a, 1250, server.top(A));
        assertTrue(a.aroundDone);
        assertNull(a.probeWindow);
        assertEquals("posts from the opening point on", 25, a.newer.buffer.size());
        assertEquals("posts of the probe window before it (ids 20..24)", 5, a.older.buffer.size());

        // Window starts after the opening point: an around request is needed.
        Channel<Post> late = channel(A);
        late.readMax = 30;
        late.unreadCount = 20;
        merge.onProbePage(late, server.getHistory(A, FolderFeedMerge.probeQuery(late)), 0);
        merge.prepareAround(late, 1100, server.top(A));
        assertFalse(late.aroundDone);

        // A channel whose last post is before the opening point starts at its top.
        Channel<Post> quiet = channel(B);
        merge.prepareAround(quiet, 5000, new DialogTop(true, 9, 4000));
        assertTrue(quiet.aroundDone);
        assertEquals(4000, quiet.older.frontier);
        assertTrue(quiet.newer.exhausted);

        // Otherwise it needs a request.
        Channel<Post> other = channel(C);
        merge.prepareAround(other, 5000, new DialogTop(true, 9, 6000));
        assertFalse(other.aroundDone);
    }

    @Test
    public void cacheVerdicts() {
        Channel<Post> a = channel(A);
        FolderFeedMerge.WindowScan<Post> scan = merge.scanWindow(Arrays.asList(p(A, 12, 12), p(A, 11, 11), p(A, 10, 10)), a, 10, 0);
        assertEquals(FolderFeedMerge.CACHE_USABLE, FolderFeedMerge.probeCacheVerdict(scan, 10));
        assertEquals("starts after the read pointer", FolderFeedMerge.CACHE_MISS_NO_EARLIER, FolderFeedMerge.probeCacheVerdict(scan, 9));
        FolderFeedMerge.WindowScan<Post> noUnread = merge.scanWindow(Arrays.asList(Post.service(A, 11, 11), p(A, 10, 10)), a, 10, 0);
        assertEquals("no unread post in it", FolderFeedMerge.CACHE_MISS_NO_LATER, FolderFeedMerge.probeCacheVerdict(noUnread, 10));

        FolderFeedMerge.WindowScan<Post> around = merge.scanWindow(Arrays.asList(p(A, 12, 120), p(A, 11, 110)), a, 0, 115);
        assertEquals(FolderFeedMerge.CACHE_USABLE, FolderFeedMerge.aroundCacheVerdict(around, false));
        FolderFeedMerge.WindowScan<Post> allNewer = merge.scanWindow(Arrays.asList(p(A, 12, 120)), a, 0, 115);
        assertEquals(FolderFeedMerge.CACHE_MISS_NO_EARLIER, FolderFeedMerge.aroundCacheVerdict(allNewer, true));
        FolderFeedMerge.WindowScan<Post> allOlder = merge.scanWindow(Arrays.asList(p(A, 11, 110)), a, 0, 115);
        assertEquals(FolderFeedMerge.CACHE_MISS_NO_LATER, FolderFeedMerge.aroundCacheVerdict(allOlder, false));
        assertEquals("nothing newer exists", FolderFeedMerge.CACHE_USABLE, FolderFeedMerge.aroundCacheVerdict(allOlder, true));
    }

    @Test
    public void seedLatestOnlyFromTheTopOfHistory() {
        List<Post> cached = Arrays.asList(p(A, 9, 90), Post.service(A, 8, 80), p(A, 7, 70));
        Channel<Post> a = channel(A);
        assertFalse("does not reach the top", merge.seedLatest(a, cached, new DialogTop(true, 10, 100)));
        assertTrue(a.older.buffer.isEmpty());
        assertTrue(merge.seedLatest(a, cached, new DialogTop(true, 9, 90)));
        assertEquals(Arrays.asList("-100#9", "-100#7"), keys(a.older.buffer));
        assertEquals(7, a.older.offsetId);
        assertEquals(70, a.older.frontier);
        assertFalse("only once", merge.seedLatest(a, cached, new DialogTop(true, 9, 90)));
        assertFalse(merge.seedLatest(channel(B), cached, DialogTop.UNKNOWN));
    }

    @Test
    public void initialPageFindsTheSavedPostOrTheOldestPostFromItsTimeOn() {
        Channel<Post> a = channel(A), b = channel(B);
        a.newer.buffer.addAll(Arrays.asList(p(A, 5, 500), p(A, 6, 600)));
        b.newer.buffer.addAll(Arrays.asList(p(B, 1, 550)));
        a.older.buffer.add(p(A, 4, 400));
        for (Channel<Post> channel : Arrays.asList(a, b)) {
            channel.older.exhausted = true;
            channel.newer.exhausted = true;
        }
        FolderFeedMerge.Initial<Post> initial = merge.takeInitial(Arrays.asList(a, b), B, 1, 550);
        assertEquals(Arrays.asList("-100#6", "-200#1", "-100#5", "-100#4"), keys(initial.messages));
        assertEquals("the saved post itself", 1, initial.restoreIndex);
        assertEquals(-1, initial.firstUnreadIndex);

        a.newer.buffer.addAll(Arrays.asList(p(A, 5, 500), p(A, 6, 600)));
        a.older.buffer.add(p(A, 4, 400));
        initial = merge.takeInitial(Arrays.asList(a, b), B, 99, 450);
        assertEquals("deleted: the oldest post from its time on", 1, initial.restoreIndex);
        assertEquals("-100#5", keys(initial.messages).get(initial.restoreIndex));
    }

    @Test
    public void initialPageFindsTheOldestUnreadPost() {
        Channel<Post> a = channel(A), b = channel(B);
        a.readMax = 4;
        a.unreadCount = 2;
        b.readMax = 7;
        b.unreadCount = 0; // read
        a.newer.buffer.addAll(Arrays.asList(p(A, 5, 500), p(A, 6, 600)));
        b.newer.buffer.addAll(Arrays.asList(p(B, 8, 450)));
        a.older.buffer.add(p(A, 4, 400));
        for (Channel<Post> channel : Arrays.asList(a, b)) {
            channel.older.exhausted = true;
            channel.newer.exhausted = true;
        }
        FolderFeedMerge.Initial<Post> initial = merge.takeInitial(Arrays.asList(a, b), 0, 0, 450);
        assertEquals(Arrays.asList("-100#6", "-100#5", "-200#8", "-100#4"), keys(initial.messages));
        assertEquals(1, initial.firstUnreadIndex);
        assertEquals(-1, initial.restoreIndex);
    }

    // endregion

    // region Live posts and ids

    @Test
    public void newPostsReopenTheNewerEdge() {
        Channel<Post> a = channel(A);
        a.newer.offsetId = 10;
        a.newer.exhausted = true;
        FolderFeedMerge.onNewPosts(a, 11, 1100, true);
        assertEquals(1, a.liveVersion);
        assertFalse(a.newer.exhausted);
        assertEquals(10, a.newer.offsetId);

        Channel<Post> unknown = channel(B);
        unknown.newer.exhausted = true;
        FolderFeedMerge.onNewPosts(unknown, 7, 700, false);
        assertTrue("before its window is known, the opening request will see the post", unknown.newer.exhausted);
        FolderFeedMerge.onNewPosts(unknown, 7, 700, true);
        assertEquals("history starts right before the new posts", 6, unknown.newer.offsetId);
        assertEquals(700, unknown.newer.frontier);
        assertFalse(unknown.newer.exhausted);
    }

    @Test
    public void syntheticIdsGrowOutwardsFromTheFirstPage() {
        FolderFeedMerge.SyntheticIds ids = new FolderFeedMerge.SyntheticIds();
        int top = ids.initial(5);
        assertEquals(FolderFeedMerge.ID_BASE + 4, top);
        assertEquals("older page continues below the first page's oldest id", FolderFeedMerge.ID_BASE - 1, ids.page(3, false));
        assertEquals(FolderFeedMerge.ID_BASE - 4, ids.page(2, false));
        assertEquals("newer page: its oldest post right above the first page", FolderFeedMerge.ID_BASE + 5 + 2, ids.page(3, true));
        assertEquals(FolderFeedMerge.ID_BASE + 8, ids.page(1, true));

        FolderFeedMerge.SyntheticIds latest = new FolderFeedMerge.SyntheticIds();
        assertEquals("opening at the newest posts: the first page is an older page", FolderFeedMerge.ID_BASE, latest.page(4, false));
        assertEquals(FolderFeedMerge.ID_BASE + 1, latest.page(1, true));
    }

    @Test
    public void postsOfOtherChatsAndEmptyMessagesAreNeverShown() {
        Channel<Post> a = channel(A);
        assertTrue(merge.isPost(p(A, 1, 1), a));
        assertFalse(merge.isPost(p(B, 1, 1), a));
        assertFalse(merge.isPost(Post.service(A, 1, 1), a));
        assertTrue(merge.isFromSource(Post.service(A, 1, 1), a));
        assertFalse(merge.isFromSource(Post.empty(A, 1), a));
        assertFalse(merge.isFromSource(null, a));
        assertSame(a.older, a.edge(false));
        assertSame(a.newer, a.edge(true));
    }

    // endregion
}
