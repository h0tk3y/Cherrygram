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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/**
 * The merge and paging core of {@link FolderFeedController}: per-channel edges with frontiers, the
 * watermark merge, page capping, opening windows and synthetic ids.
 * <p>
 * Test seam: this class uses nothing but {@code java.util}, so it is unit-tested on a plain JVM
 * (TMessagesProj/src/test, run by ci/unit-tests/run.sh) against a simulated getHistory server. It is
 * generic in the message type {@code M}: the app uses {@code TLRPC.Message} (see
 * {@link FolderFeedController}'s {@code TL_POSTS}), the tests a small fake. The controller keeps
 * everything that needs Telegram's singletons (requests, the dialogs list, the database, notifications)
 * and calls into this class for every decision about which posts to show and what to fetch next.
 */
final class FolderFeedMerge<M> {

    /** Posts a page waits for before it is shown (fewer only at the end of history). */
    static final int PAGE_SIZE = 20;
    /** Posts shown at most in one page; the rest stays buffered for the next one. */
    static final int MAX_PAGE_SIZE = 60;
    /** Posts requested per channel when paging (a page usually takes posts from several channels). */
    static final int FETCH_LIMIT = 20;
    /** Opening: posts fetched per channel at or after / before the opening point. */
    static final int AROUND_NEWER = 20;
    static final int AROUND_OLDER = 10;

    /** Synthetic ids grow outwards from here: older posts count down, newer posts count up. */
    static final int ID_BASE = 1_000_000_000;

    /** What the core needs to know about a message. */
    interface Posts<M> {
        /** Message id within its channel. */
        int id(M message);

        int date(M message);

        long dialogId(M message);

        /** A placeholder that is no message at all (messageEmpty). */
        boolean isEmpty(M message);

        /** A service message: part of the history, but not shown in the feed. */
        boolean isService(M message);
    }

    /** A request slot: at most one getHistory request in flight (or waiting to be retried). */
    static class Slot {
        boolean inFlight;
        int reqId;
        int retries;
    }

    /** One edge (older or newer) of a channel's loaded range of history. */
    static final class Edge<M> extends Slot {
        /** Real id of the outermost fetched message on this edge (service messages included); 0 = none yet. */
        int offsetId;
        /** Bound for the date of every post on this edge that is not fetched yet: upper bound for the older
         *  edge, lower bound for the newer edge. */
        int frontier;
        boolean exhausted;
        /** Fetched posts not shown yet, ordered from the loaded range outwards: the older edge keeps them
         *  newest first, the newer edge oldest first. */
        final ArrayList<M> buffer = new ArrayList<>();
    }

    /** The paging state of one channel of the feed. */
    static class Channel<M> {
        final long dialogId;
        final Edge<M> older = new Edge<>();
        final Edge<M> newer = new Edge<>();
        /** Real ids of every post handed to a buffer, so the two edges never yield the same post twice. */
        final HashSet<Integer> seen = new HashSet<>();
        /** Read pointer and unread count when the feed was opened (posts with id > readMax are unread). */
        int readMax;
        int unreadCount;
        /** Opening: date of the channel's first unread message, 0 if it was not found. */
        int firstUnreadDate;
        /** Opening: the window around the read pointer (with the first unread message), reused as the
         *  channel's starting window when it covers the opening point. */
        ArrayList<M> probeWindow;
        boolean probeOlderEnd;
        boolean probeNewerEnd;
        int probeLiveVersion;
        boolean probeDone;
        boolean aroundDone;
        /** Bumped when the channel gets a new post, so a newer-edge response built before it does not
         *  mark the edge as exhausted for good. */
        int liveVersion;

        Channel(long dialogId) {
            this.dialogId = dialogId;
        }

        boolean hasUnread() {
            return unreadCount > 0 && readMax > 0;
        }

        Edge<M> edge(boolean newer) {
            return newer ? this.newer : older;
        }
    }

    /** The channel's top as known from the dialogs list (0 when the dialog is unknown). */
    static final class DialogTop {
        final boolean known;
        final int topMessage;
        final int lastMessageDate;

        DialogTop(boolean known, int topMessage, int lastMessageDate) {
            this.known = known;
            this.topMessage = topMessage;
            this.lastMessageDate = lastMessageDate;
        }

        static final DialogTop UNKNOWN = new DialogTop(false, 0, 0);
    }

    /** A messages.getHistory query: offset_id, offset_date, add_offset, limit. */
    static final class Query {
        final int offsetId;
        final int offsetDate;
        final int addOffset;
        final int limit;

        Query(int offsetId, int offsetDate, int addOffset, int limit) {
            this.offsetId = offsetId;
            this.offsetDate = offsetDate;
            this.addOffset = addOffset;
            this.limit = limit;
        }
    }

    final Posts<M> posts;
    private final Comparator<M> newestFirst;

    FolderFeedMerge(Posts<M> posts) {
        this.posts = posts;
        newestFirst = (a, b) -> {
            if (posts.date(a) != posts.date(b)) {
                return Integer.compare(posts.date(b), posts.date(a));
            }
            long da = posts.dialogId(a), db = posts.dialogId(b);
            if (da != db) {
                return Long.compare(da, db);
            }
            return Integer.compare(posts.id(b), posts.id(a));
        };
    }

    // region Messages

    boolean isFromSource(M message, Channel<M> channel) {
        return message != null && !posts.isEmpty(message) && posts.dialogId(message) == channel.dialogId;
    }

    /** A post shown in the feed: from this channel and not a service message. */
    boolean isPost(M message, Channel<M> channel) {
        return isFromSource(message, channel) && !posts.isService(message);
    }

    /** Merged order: newest first; same-second posts by channel, then by id. */
    void sortNewestFirst(ArrayList<M> messages) {
        Collections.sort(messages, newestFirst);
    }

    void sortOldestFirst(ArrayList<M> messages) {
        sortNewestFirst(messages);
        Collections.reverse(messages);
    }

    // endregion

    // region Opening

    /** What a contiguous window of a channel's history holds relative to a pivot (a message id, or else a date). */
    static final class WindowScan<M> {
        final ArrayList<M> messages = new ArrayList<>();
        int minId = Integer.MAX_VALUE;
        int maxId;
        /** Messages before / from the pivot on. */
        int olderCount;
        int newerCount;
        /** The first post (not a service message) after the pivot id (only with an id pivot). */
        M firstPostAfterPivot;
    }

    WindowScan<M> scanWindow(List<M> messages, Channel<M> channel, int pivotId, int pivotDate) {
        WindowScan<M> scan = new WindowScan<>();
        for (M message : messages) {
            if (!isFromSource(message, channel)) {
                continue;
            }
            int id = posts.id(message);
            scan.messages.add(message);
            scan.minId = Math.min(scan.minId, id);
            scan.maxId = Math.max(scan.maxId, id);
            boolean newer = pivotId != 0 ? id >= pivotId : posts.date(message) >= pivotDate;
            if (newer) {
                scan.newerCount++;
            } else {
                scan.olderCount++;
            }
            if (pivotId != 0 && id > pivotId && !posts.isService(message)
                    && (scan.firstPostAfterPivot == null || id < posts.id(scan.firstPostAfterPivot))) {
                scan.firstPostAfterPivot = message;
            }
        }
        return scan;
    }

    /** The query around the read pointer: AROUND_NEWER from the read post on (it may be gone), AROUND_OLDER before it. */
    static Query probeQuery(Channel<?> channel) {
        return new Query(channel.readMax, 0, -AROUND_NEWER, AROUND_NEWER + AROUND_OLDER);
    }

    /** The query around the opening point: AROUND_NEWER at or after it, AROUND_OLDER before it. */
    static Query aroundQuery(int anchorDate) {
        return new Query(0, anchorDate, -AROUND_NEWER, AROUND_NEWER + AROUND_OLDER);
    }

    void setProbeResult(Channel<M> channel, WindowScan<M> scan, boolean olderEnd, boolean newerEnd, int liveVersion) {
        // The first unread post, not an earlier unread service message: the feed opens at this time, and its
        // first page reaches only as far as the channels' windows from there, which may end before a post
        // that comes much later than the service message.
        channel.firstUnreadDate = scan.firstPostAfterPivot != null ? posts.date(scan.firstPostAfterPivot) : 0;
        channel.probeWindow = scan.messages;
        channel.probeOlderEnd = olderEnd;
        channel.probeNewerEnd = newerEnd;
        channel.probeLiveVersion = liveVersion;
        channel.probeDone = true;
    }

    /** A response to {@link #probeQuery}, requested when the channel's liveVersion was {@code liveVersion}. */
    void onProbePage(Channel<M> channel, List<M> messages, int liveVersion) {
        WindowScan<M> scan = scanWindow(messages, channel, channel.readMax, 0);
        setProbeResult(channel, scan, scan.olderCount < AROUND_OLDER, scan.newerCount < AROUND_NEWER, liveVersion);
    }

    /** Cache misses: the cached window does not reach back far enough / forward far enough. */
    static final int CACHE_USABLE = 0;
    static final int CACHE_MISS_NO_EARLIER = 1;
    static final int CACHE_MISS_NO_LATER = 2;

    /**
     * Whether a cached window around the read pointer can stand in for the probe request: it must start
     * at or before the read pointer (so its first unread post really is the first one) and contain it.
     */
    static int probeCacheVerdict(WindowScan<?> scan, int readMax) {
        return probeCacheVerdict(scan, readMax, false);
    }

    /** {@code startReached}: the window holds the channel's first message, so nothing exists before it. */
    static int probeCacheVerdict(WindowScan<?> scan, int readMax, boolean startReached) {
        if (scan.minId > readMax && !startReached) {
            return CACHE_MISS_NO_EARLIER;
        }
        if (scan.firstPostAfterPivot == null) {
            return CACHE_MISS_NO_LATER;
        }
        return CACHE_USABLE;
    }

    /**
     * Whether a cached window around the opening point can stand in for the around request: it must span
     * the opening point (posts on both sides of it), or nothing newer exists ({@code atTop}).
     */
    static int aroundCacheVerdict(WindowScan<?> scan, boolean atTop) {
        return aroundCacheVerdict(scan, atTop, false);
    }

    /**
     * {@code startReached}: the window holds the channel's first message. A channel whose history begins
     * after the opening point has no posts before it at all: its window from the opening point on covers it.
     */
    static int aroundCacheVerdict(WindowScan<?> scan, boolean atTop, boolean startReached) {
        if (scan.olderCount == 0 && !startReached) {
            return CACHE_MISS_NO_EARLIER;
        }
        if (scan.newerCount == 0 && !atTop) {
            return CACHE_MISS_NO_LATER;
        }
        return CACHE_USABLE;
    }

    /** The opening point: the oldest first-unread date across the channels, 0 if none was found. */
    static int anchorFromProbes(List<? extends Channel<?>> channels) {
        int anchor = 0;
        for (Channel<?> channel : channels) {
            if (channel.firstUnreadDate > 0 && (anchor == 0 || channel.firstUnreadDate < anchor)) {
                anchor = channel.firstUnreadDate;
            }
        }
        return anchor;
    }

    /**
     * Places the channel at the top of its history as known from the dialogs list: nothing newer to load,
     * the older edge starts from the newest post.
     */
    void initAtDialogTop(Channel<M> channel, DialogTop top) {
        int lastDate = top.known && top.lastMessageDate > 0 ? top.lastMessageDate : Integer.MAX_VALUE;
        channel.older.offsetId = 0;
        channel.older.frontier = lastDate;
        channel.newer.exhausted = true;
        setNewerStart(channel, null, top);
    }

    /** Where newer posts continue: after the given message, or after the dialog's top message. */
    void setNewerStart(Channel<M> channel, M newest, DialogTop top) {
        if (newest != null) {
            channel.newer.offsetId = posts.id(newest);
            channel.newer.frontier = posts.date(newest);
        } else {
            channel.newer.offsetId = top.known ? top.topMessage : 0;
            channel.newer.frontier = top.known ? top.lastMessageDate : 0;
        }
    }

    /**
     * Sets a channel's starting state from a contiguous window of its history (messages of the channel
     * only): posts from the opening point on go to the newer edge, earlier ones to the older edge.
     */
    void applyWindow(Channel<M> channel, List<M> messages, int anchorDate, boolean olderExhausted, boolean newerExhausted, DialogTop top) {
        M oldestOlder = null, newestNewer = null, newest = null;
        for (M message : messages) {
            int id = posts.id(message);
            if (newest == null || id > posts.id(newest)) newest = message;
            boolean newer = posts.date(message) >= anchorDate;
            if (newer) {
                if (newestNewer == null || id > posts.id(newestNewer)) newestNewer = message;
            } else {
                if (oldestOlder == null || id < posts.id(oldestOlder)) oldestOlder = message;
            }
            if (isPost(message, channel) && channel.seen.add(id)) {
                channel.edge(newer).buffer.add(message);
            }
        }
        sortNewestFirst(channel.older.buffer);
        sortOldestFirst(channel.newer.buffer);
        if (oldestOlder != null) {
            channel.older.offsetId = posts.id(oldestOlder);
            channel.older.frontier = posts.date(oldestOlder);
        }
        channel.older.exhausted = olderExhausted || oldestOlder == null;
        // With nothing at or after the opening point yet, newer posts, once they arrive, follow the newest one.
        setNewerStart(channel, newestNewer != null ? newestNewer : newest, top);
        channel.newer.exhausted = newerExhausted;
    }

    /** A response to {@link #aroundQuery}, requested when the channel's liveVersion was {@code liveVersion}. */
    void onAroundPage(Channel<M> channel, List<M> messages, int anchorDate, int liveVersion, DialogTop top) {
        WindowScan<M> scan = scanWindow(messages, channel, 0, anchorDate);
        applyWindow(channel, scan.messages, anchorDate, scan.olderCount < AROUND_OLDER,
                scan.newerCount < AROUND_NEWER && channel.liveVersion == liveVersion, top);
        channel.aroundDone = true;
    }

    /** The around request failed for good (e.g. the channel became private): leave it out of the feed. */
    void onAroundGaveUp(Channel<M> channel, DialogTop top) {
        channel.older.exhausted = true;
        channel.newer.exhausted = true;
        setNewerStart(channel, null, top);
        channel.aroundDone = true;
    }

    /**
     * Once the opening point is known, decides whether the channel needs a request around it: a channel
     * whose window around its read pointer already covers that point reuses it, and a channel whose last
     * message is older has nothing there, so its posts load only when the feed is scrolled back to them.
     * Afterwards {@code aroundDone} is false only for channels that need the around request.
     */
    void prepareAround(Channel<M> channel, int anchorDate, DialogTop top) {
        ArrayList<M> window = channel.probeWindow;
        channel.probeWindow = null;
        if (channel.aroundDone) {
            return;
        }
        if (window != null) {
            int oldestDate = Integer.MAX_VALUE;
            for (M message : window) {
                oldestDate = Math.min(oldestDate, posts.date(message));
            }
            if (channel.probeOlderEnd || oldestDate < anchorDate) {
                applyWindow(channel, window, anchorDate, channel.probeOlderEnd,
                        channel.probeNewerEnd && channel.liveVersion == channel.probeLiveVersion, top);
                channel.aroundDone = true;
                return;
            }
        }
        if (top.known && top.lastMessageDate > 0 && top.lastMessageDate < anchorDate) {
            initAtDialogTop(channel, top);
            channel.aroundDone = true;
        }
    }

    /**
     * Opening at the newest posts: seeds the channel's older edge with its newest cached posts, when they
     * reach the channel's top message as known from the dialogs list (the same top the network path
     * starts from). Returns false (nothing changed) when the window cannot be used.
     */
    boolean seedLatest(Channel<M> channel, List<M> window, DialogTop top) {
        WindowScan<M> scan = scanWindow(window, channel, 0, Integer.MAX_VALUE);
        if (!top.known || scan.maxId != top.topMessage || !channel.older.buffer.isEmpty() || channel.older.offsetId != 0) {
            return false;
        }
        M oldest = null;
        for (M message : scan.messages) {
            int id = posts.id(message);
            if (oldest == null || id < posts.id(oldest)) {
                oldest = message;
            }
            if (isPost(message, channel) && channel.seen.add(id)) {
                channel.older.buffer.add(message);
            }
        }
        sortNewestFirst(channel.older.buffer);
        // Older posts continue from the network, below the cached ones.
        channel.older.offsetId = posts.id(oldest);
        channel.older.frontier = posts.date(oldest);
        return true;
    }

    /** The first page of a feed opened around a point, and where it should open. */
    static final class Initial<M> {
        /** Newest first. */
        final ArrayList<M> messages;
        /** Index in {@link #messages} of the oldest unread post (opening at the first unread), or -1. */
        final int firstUnreadIndex;
        /** Index of the saved post, or if it is gone the oldest post at or after its time, or -1. */
        final int restoreIndex;

        Initial(ArrayList<M> messages, int firstUnreadIndex, int restoreIndex) {
            this.messages = messages;
            this.firstUnreadIndex = firstUnreadIndex;
            this.restoreIndex = restoreIndex;
        }
    }

    /**
     * Takes the first page once every channel has its starting window: every post that can be shown on
     * either side of the opening point. With a saved position ({@code restoreDialogId != 0}) it finds the
     * saved post, else the first unread post.
     */
    Initial<M> takeInitial(List<? extends Channel<M>> channels, long restoreDialogId, int restoreMessageId, int anchorDate) {
        ArrayList<M> newer = collectReady(channels, true);
        ArrayList<M> older = collectReady(channels, false);
        ArrayList<M> all = new ArrayList<>(newer.size() + older.size());
        all.addAll(newer);
        all.addAll(older);
        HashMap<Long, Channel<M>> byId = byDialogId(channels);
        int firstUnread = -1;
        int restore = -1;
        boolean restoreExact = false;
        for (int i = 0; i < all.size(); i++) {
            M message = all.get(i);
            long dialogId = posts.dialogId(message);
            Channel<M> channel = byId.get(dialogId);
            if (restoreDialogId != 0) {
                // The saved post, or if it is gone, the oldest post at or after its time.
                if (dialogId == restoreDialogId && posts.id(message) == restoreMessageId) {
                    restore = i;
                    restoreExact = true;
                } else if (!restoreExact && posts.date(message) >= anchorDate) {
                    restore = i;
                }
            } else if (channel != null && channel.hasUnread() && posts.id(message) > channel.readMax) {
                firstUnread = i;
            }
        }
        return new Initial<>(all, firstUnread, restore);
    }

    // endregion

    // region Paging on either edge: merge by date with per-channel frontiers

    static boolean hasMore(List<? extends Channel<?>> channels, boolean newer) {
        for (Channel<?> channel : channels) {
            Edge<?> edge = channel.edge(newer);
            if (!edge.exhausted || !edge.buffer.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static boolean anyEdgeLeft(List<? extends Channel<?>> channels, boolean newer) {
        for (Channel<?> channel : channels) {
            if (!channel.edge(newer).exhausted) {
                return true;
            }
        }
        return false;
    }

    /** Removes and returns every buffered post of the given edge that can be shown now, newest first. */
    ArrayList<M> collectReady(List<? extends Channel<M>> channels, boolean newer) {
        // Older edge: no channel can have unfetched posts newer than the highest older frontier.
        // Newer edge: no channel can have unfetched posts older than the lowest newer frontier.
        int watermark = newer ? Integer.MAX_VALUE : 0;
        for (Channel<M> channel : channels) {
            Edge<M> edge = channel.edge(newer);
            if (!edge.exhausted) {
                watermark = newer ? Math.min(watermark, edge.frontier) : Math.max(watermark, edge.frontier);
            }
        }
        ArrayList<M> ready = new ArrayList<>();
        for (Channel<M> channel : channels) {
            Iterator<M> iterator = channel.edge(newer).buffer.iterator();
            while (iterator.hasNext()) {
                M message = iterator.next();
                int date = posts.date(message);
                if (newer ? date <= watermark : date >= watermark) {
                    ready.add(message);
                    iterator.remove();
                } else {
                    break;
                }
            }
        }
        sortNewestFirst(ready);
        return ready;
    }

    void restoreToBuffers(List<? extends Channel<M>> channels, ArrayList<M> messages, boolean newer) {
        HashMap<Long, Channel<M>> byId = byDialogId(channels);
        for (M message : messages) {
            Channel<M> channel = byId.get(posts.dialogId(message));
            if (channel != null) {
                channel.edge(newer).buffer.add(message);
            }
        }
        for (Channel<M> channel : channels) {
            if (newer) {
                sortOldestFirst(channel.newer.buffer);
            } else {
                sortNewestFirst(channel.older.buffer);
            }
        }
    }

    /** Keeps at most MAX_PAGE_SIZE posts, those next to the loaded range; the rest goes back to the buffers. */
    ArrayList<M> capPage(List<? extends Channel<M>> channels, ArrayList<M> ready, boolean newer) {
        if (ready.size() <= MAX_PAGE_SIZE) {
            return ready;
        }
        // ready is newest first: an older page continues from its newest posts, a newer page from its oldest.
        int from = newer ? ready.size() - MAX_PAGE_SIZE : 0;
        ArrayList<M> page = new ArrayList<>(ready.subList(from, from + MAX_PAGE_SIZE));
        ArrayList<M> rest = new ArrayList<>(ready);
        rest.subList(from, from + MAX_PAGE_SIZE).clear();
        restoreToBuffers(channels, rest, newer);
        return page;
    }

    /**
     * The next page on the given edge (newest first), or null when not enough posts are ready yet: they
     * stay buffered and {@link #fetchCandidates} names the channels to fetch.
     */
    ArrayList<M> takePage(List<? extends Channel<M>> channels, boolean newer) {
        ArrayList<M> ready = collectReady(channels, newer);
        if (ready.size() >= PAGE_SIZE || !anyEdgeLeft(channels, newer)) {
            return capPage(channels, ready, newer);
        }
        restoreToBuffers(channels, ready, newer);
        return null;
    }

    /**
     * Channels that may be fetched on the edge, in the order they should be: newest frontier first for
     * older pages, oldest first for newer ones, so the ones holding the merge back are fetched first.
     */
    static <C extends Channel<?>> ArrayList<C> fetchCandidates(List<C> channels, boolean newer) {
        ArrayList<C> candidates = new ArrayList<>();
        for (C channel : channels) {
            Edge<?> edge = channel.edge(newer);
            if (!edge.inFlight && !edge.exhausted) {
                candidates.add(channel);
            }
        }
        if (newer) {
            Collections.sort(candidates, (a, b) -> Integer.compare(a.newer.frontier, b.newer.frontier));
        } else {
            Collections.sort(candidates, (a, b) -> Integer.compare(b.older.frontier, a.older.frontier));
        }
        return candidates;
    }

    /** The getHistory query that continues the edge. */
    static Query edgeQuery(Channel<?> channel, boolean newer) {
        Edge<?> edge = channel.edge(newer);
        if (newer) {
            // The posts right after offset_id. The window also covers offset_id itself and the post before
            // it (filtered out), whether or not the offset post still exists.
            return new Query(edge.offsetId, 0, -FETCH_LIMIT, FETCH_LIMIT + 1);
        }
        return new Query(edge.offsetId, 0, 0, FETCH_LIMIT);
    }

    /**
     * Whether an older page must move its posts from the opening point on to the newer edge. A channel set
     * up from the dialogs list around an opening point (its last post was older) pages its older edge down
     * from its current top: if posts arrived since (or the entry was stale), the first pages hold posts from
     * the opening point on, which belong to the newer side. That is not limited to the first page: more than
     * FETCH_LIMIT such posts span several. For every other channel the older edge only holds posts before
     * the opening point, so the split changes nothing there.
     */
    static boolean splitsAtAnchor(boolean newer, int anchorDate) {
        return !newer && anchorDate > 0;
    }

    /**
     * A response to {@link #edgeQuery}: buffers its posts on the edge and moves the edge's offset and
     * frontier. {@code requestOffsetId} and {@code liveVersion} are the edge's offset and the channel's
     * liveVersion when the query was built.
     */
    void onEdgePage(Channel<M> channel, boolean newer, int requestOffsetId, List<M> messages, int anchorDate, int liveVersion) {
        Edge<M> edge = channel.edge(newer);
        boolean splitAtAnchor = splitsAtAnchor(newer, anchorDate);
        M outermost = null;
        int count = 0;
        boolean movedBelow = false;
        for (M message : messages) {
            if (!isFromSource(message, channel)) {
                continue;
            }
            int id = posts.id(message);
            if (newer ? id <= requestOffsetId : (requestOffsetId != 0 && id >= requestOffsetId)) {
                continue;
            }
            count++;
            if (outermost == null || (newer ? id > posts.id(outermost) : id < posts.id(outermost))) {
                outermost = message;
            }
            if (!isPost(message, channel) || !channel.seen.add(id)) {
                continue;
            }
            if (splitAtAnchor && posts.date(message) >= anchorDate) {
                channel.newer.buffer.add(message);
                movedBelow = true;
            } else {
                edge.buffer.add(message);
            }
        }
        if (movedBelow) {
            sortOldestFirst(channel.newer.buffer);
        }
        if (newer) {
            sortOldestFirst(edge.buffer);
        } else {
            sortNewestFirst(edge.buffer);
        }
        if (outermost == null) {
            edge.exhausted = true;
        } else {
            edge.offsetId = posts.id(outermost);
            edge.frontier = posts.date(outermost);
            // A newer window holds FETCH_LIMIT - 1 new posts when the offset post still exists.
            if (count < (newer ? FETCH_LIMIT - 1 : FETCH_LIMIT)) {
                edge.exhausted = true;
            }
        }
        if (newer && channel.liveVersion != liveVersion) {
            // A post arrived after this response was built: there is more to load.
            edge.exhausted = false;
        }
    }

    /**
     * New posts arrived in the channel (the smallest id and date among them). {@code started}: the channel
     * has its starting window, or the feed is past opening.
     */
    static void onNewPosts(Channel<?> channel, int minId, int minDate, boolean started) {
        channel.liveVersion++;
        if (channel.newer.offsetId == 0 && started) {
            // Nothing was known of the channel: its history starts right before the new posts.
            channel.newer.offsetId = minId - 1;
            channel.newer.frontier = minDate;
        }
        if (channel.newer.offsetId != 0) {
            // The new posts arrive through forward paging, like scrolling down to newer posts.
            channel.newer.exhausted = false;
        }
    }

    private HashMap<Long, Channel<M>> byDialogId(List<? extends Channel<M>> channels) {
        HashMap<Long, Channel<M>> byId = new HashMap<>();
        for (Channel<M> channel : channels) {
            byId.put(channel.dialogId, channel);
        }
        return byId;
    }

    // endregion

    // region Synthetic ids

    /**
     * Synthetic ids of the shown posts: ChatActivity keys messages by id, and posts of different channels
     * may share ids. Ids grow from older to newer posts across all pages: the first page takes
     * ID_BASE..ID_BASE+n-1, older pages continue downwards, newer pages upwards.
     */
    static final class SyntheticIds {
        int nextOlderId = ID_BASE;
        int nextNewerId = ID_BASE + 1;

        /** The first page of n posts (newest first): returns the id of its newest post. */
        int initial(int n) {
            nextOlderId = ID_BASE - 1;
            nextNewerId = ID_BASE + n;
            return ID_BASE + n - 1;
        }

        /** A page of n posts (newest first) on the given edge: returns the id of its newest post. */
        int page(int n, boolean newer) {
            int firstId = newer ? nextNewerId + n - 1 : nextOlderId;
            if (newer) {
                nextNewerId += n;
            } else {
                nextOlderId -= n;
            }
            return firstId;
        }
    }

    // endregion
}
