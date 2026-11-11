/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.chats.feed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The decisions of {@link FolderFeedCache} that do not touch the database: which part of a window read
 * from {@code messages_v2} is contiguous history given the channel's {@code messages_holes}, and the
 * memory of the pages loaded in this app session (contiguous stretches per channel).
 * <p>
 * Test seam: like {@link FolderFeedMerge}, this class uses nothing but {@code java.util} and is unit-tested
 * on a plain JVM. It is generic in the message type; the app passes {@code TLRPC.Message} accessors and
 * {@link FolderFeedCache#copyOf} as the copier.
 */
final class FolderFeedCacheCore {

    private FolderFeedCacheCore() {
    }

    /** What the cache needs to know about a message. */
    interface Ids<M> {
        int id(M message);

        int date(M message);

        /** A copy the caller may change, or null if the message cannot be copied. */
        M copy(M message);
    }

    // region Holes

    /**
     * The id that a window read around a pivot must stay connected to: the pivot id, or for a date pivot
     * the oldest message at or after the date ({@code newer} is oldest first), else the newest before it
     * ({@code older} is newest first).
     */
    static <M> int holePivot(Ids<M> ids, List<M> newer, List<M> older, int pivotId) {
        return pivotId != 0 ? pivotId : (!newer.isEmpty() ? ids.id(newer.get(0)) : ids.id(older.get(0)));
    }

    /**
     * Keeps the part of the window (newer and older reads around the pivot) that no hole separates from the
     * pivot, newest first; empty when the pivot itself lies in a hole. A hole {start, end} means the messages
     * strictly between start and end are missing. Holes that do not overlap the window's id range are
     * ignored (the database query selects only those that do).
     */
    static <M> ArrayList<M> trimToHoles(Ids<M> ids, List<M> window, int pivot, List<int[]> holes) {
        if (window.isEmpty()) {
            return new ArrayList<>();
        }
        int minId = Integer.MAX_VALUE, maxId = 0;
        for (M message : window) {
            minId = Math.min(minId, ids.id(message));
            maxId = Math.max(maxId, ids.id(message));
        }
        int lower = 0, upper = Integer.MAX_VALUE;
        for (int[] hole : holes) {
            int start = hole[0];
            int end = hole[1];
            if (!(end > minId && start < maxId)) {
                continue;
            }
            if (start < pivot && pivot < end) {
                return new ArrayList<>();
            } else if (end <= pivot) {
                lower = Math.max(lower, end);
            } else {
                upper = Math.min(upper, start);
            }
        }
        ArrayList<M> contiguous = new ArrayList<>(window.size());
        for (M message : window) {
            int id = ids.id(message);
            if (id >= lower && id <= upper) {
                contiguous.add(message);
            }
        }
        Collections.sort(contiguous, (a, b) -> Integer.compare(ids.id(b), ids.id(a)));
        return contiguous;
    }

    // endregion

    // region Memory of this app session

    /** Messages kept per channel; the stretch used least recently goes first, then the farthest messages. */
    static final int MEMORY_PER_CHANNEL = 120;

    /** A contiguous stretch of a channel's history, keyed by message id. */
    static final class Stretch<M> {
        final TreeMap<Integer, M> byId = new TreeMap<>();
        long lastUsed;
        /** The stretch's oldest message is the channel's first one: nothing exists before it. */
        boolean startReached;

        int minId() {
            return byId.firstKey();
        }

        int maxId() {
            return byId.lastKey();
        }

        boolean overlaps(Stretch<M> other) {
            return minId() <= other.maxId() && other.minId() <= maxId();
        }
    }

    /**
     * The pages one channel's feed fetched in this app session, as contiguous stretches of history. Each
     * stored message is a private copy. Not thread-safe (the app uses it on the UI thread).
     */
    static final class Stretches<M> {
        private final Ids<M> ids;
        final ArrayList<Stretch<M>> list = new ArrayList<>();

        Stretches(Ids<M> ids) {
            this.ids = ids;
        }

        /** Adds a contiguous page (newest first); {@code connectsTo} is a message id it continues, or 0. */
        void remember(List<M> page, int connectsTo, long now) {
            remember(page, connectsTo, now, false);
        }

        /**
         * Like {@link #remember(List, int, long)}; {@code reachesStart}: the page reaches the channel's first
         * message (the server had nothing older). An empty page that reaches the start marks the stretch it
         * continues (the one starting at {@code connectsTo}).
         */
        void remember(List<M> page, int connectsTo, long now, boolean reachesStart) {
            if (page.isEmpty()) {
                if (reachesStart && connectsTo != 0) {
                    for (Stretch<M> stretch : list) {
                        if (stretch.minId() == connectsTo) {
                            stretch.startReached = true;
                        }
                    }
                }
                return;
            }
            Stretch<M> merged = new Stretch<>();
            merged.startReached = reachesStart;
            for (M message : page) {
                M copy = ids.copy(message);
                if (copy != null) {
                    merged.byId.put(ids.id(copy), copy);
                }
            }
            if (merged.byId.isEmpty()) {
                return;
            }
            int pageCenter = (merged.minId() + merged.maxId()) / 2;
            for (int i = list.size() - 1; i >= 0; i--) {
                Stretch<M> stretch = list.get(i);
                boolean continues = connectsTo != 0 && stretch.minId() <= connectsTo && connectsTo <= stretch.maxId();
                if (continues || stretch.overlaps(merged)) {
                    // Overlapping stretches are each contiguous, so together they are too. The result starts
                    // at the channel's first message if a part holding its oldest message did (either, when
                    // both start at the same message).
                    boolean startReached = stretch.minId() < merged.minId() ? stretch.startReached
                            : merged.minId() < stretch.minId() ? merged.startReached
                            : stretch.startReached || merged.startReached;
                    stretch.byId.putAll(merged.byId);
                    stretch.startReached = startReached;
                    merged = stretch;
                    list.remove(i);
                    i = list.size();
                }
            }
            merged.lastUsed = now;
            list.add(merged);
            trim(merged, pageCenter);
        }

        private void trim(Stretch<M> keep, int center) {
            int total = 0;
            for (Stretch<M> stretch : list) {
                total += stretch.byId.size();
            }
            Collections.sort(list, (a, b) -> Long.compare(a.lastUsed, b.lastUsed));
            while (total > MEMORY_PER_CHANNEL && list.size() > 1 && list.get(0) != keep) {
                total -= list.remove(0).byId.size();
            }
            while (keep.byId.size() > MEMORY_PER_CHANNEL) {
                // Drop the end farther from the page just added.
                if (center - keep.minId() > keep.maxId() - center) {
                    keep.byId.pollFirstEntry();
                    keep.startReached = false;
                } else {
                    keep.byId.pollLastEntry();
                }
            }
        }

        /** Whether the window (from {@link #window}) holds the channel's first message, so nothing is older. */
        boolean reachesStart(List<M> window) {
            if (window == null || window.isEmpty()) {
                return false;
            }
            int minId = Integer.MAX_VALUE;
            for (M message : window) {
                minId = Math.min(minId, ids.id(message));
            }
            for (Stretch<M> stretch : list) {
                if (stretch.startReached && stretch.minId() == minId) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The window around a pivot (a message id, or if 0 a date) within one stretch, like the database's:
         * up to {@code newerLimit} messages from the pivot on and {@code olderLimit} before it, newest first,
         * as fresh copies. Null when nothing is remembered.
         */
        ArrayList<M> window(int pivotId, int pivotDate, int olderLimit, int newerLimit, long now) {
            if (list.isEmpty()) {
                return null;
            }
            // The stretch holding the first message at or after the pivot, else the newest one before it.
            Stretch<M> best = null;
            Integer bestFirstNewer = null;
            for (Stretch<M> stretch : list) {
                Integer firstNewer = null;
                for (M message : stretch.byId.values()) {
                    if (pivotId != 0 ? ids.id(message) >= pivotId : ids.date(message) >= pivotDate) {
                        firstNewer = ids.id(message);
                        break;
                    }
                }
                boolean hasOlder = pivotId != 0 ? stretch.minId() < pivotId : ids.date(stretch.byId.firstEntry().getValue()) < pivotDate;
                if (firstNewer != null && hasOlder) {
                    best = stretch;
                    bestFirstNewer = firstNewer;
                    break;
                }
                if (best == null || stretch.maxId() > best.maxId()) {
                    best = stretch;
                    bestFirstNewer = firstNewer;
                }
            }
            best.lastUsed = now;
            ArrayList<M> window = new ArrayList<>();
            NavigableMap<Integer, M> newer = bestFirstNewer != null ? best.byId.tailMap(bestFirstNewer, true) : new TreeMap<Integer, M>();
            NavigableMap<Integer, M> older = bestFirstNewer != null ? best.byId.headMap(bestFirstNewer, false).descendingMap() : best.byId.descendingMap();
            int count = 0;
            for (M message : newer.values()) {
                if (count++ >= newerLimit) {
                    break;
                }
                window.add(ids.copy(message));
            }
            count = 0;
            for (M message : older.values()) {
                if (count++ >= olderLimit) {
                    break;
                }
                window.add(ids.copy(message));
            }
            window.removeAll(Collections.singleton(null));
            Collections.sort(window, (a, b) -> Integer.compare(ids.id(b), ids.id(a)));
            return window;
        }
    }

    // endregion
}
