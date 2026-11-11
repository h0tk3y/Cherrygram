package uz.unnarsx.cherrygram.chats.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import uz.unnarsx.cherrygram.chats.feed.FakeHistory.Post;
import uz.unnarsx.cherrygram.chats.feed.FolderFeedMerge.Channel;

/**
 * Test driver: runs {@link FolderFeedMerge} the way {@link FolderFeedController} does (opening, then
 * paging in either direction with several requests in flight), against a {@link FakeHistory} server.
 * Responses arrive in random order, one at a time, and the driver tries to emit a page after each one,
 * like the controller's pump(). It records the feed as the user would see it, newest first.
 */
final class FeedDriver {

    final FolderFeedMerge<Post> merge = new FolderFeedMerge<>(FakeHistory.POSTS);
    final FakeHistory server;
    final Random rng;
    /** Requests in flight at most (the controller's maxParallel). */
    final int parallel;
    final ArrayList<Channel<Post>> channels = new ArrayList<>();
    final FolderFeedMerge.SyntheticIds ids = new FolderFeedMerge.SyntheticIds();

    /** The feed as shown, newest first, with the synthetic id of each post. */
    final ArrayList<Post> shown = new ArrayList<>();
    final ArrayList<Integer> shownIds = new ArrayList<>();
    /** Every page emitted, newest first within a page, and whether it was a newer page. */
    final ArrayList<List<Post>> pages = new ArrayList<>();
    final ArrayList<Boolean> pageNewer = new ArrayList<>();
    int anchorDate;
    FolderFeedMerge.Initial<Post> initial;
    /** Requests sent, for checks on how much the feed fetches. */
    int requests;

    private static final class Pending {
        final Channel<Post> channel;
        final boolean newer;
        final int requestOffsetId;
        final int liveVersion;
        final int anchorDate;
        final List<Post> response;

        Pending(Channel<Post> channel, boolean newer, int requestOffsetId, int liveVersion, int anchorDate, List<Post> response) {
            this.channel = channel;
            this.newer = newer;
            this.requestOffsetId = requestOffsetId;
            this.liveVersion = liveVersion;
            this.anchorDate = anchorDate;
            this.response = response;
        }
    }

    private final ArrayList<Pending> pending = new ArrayList<>();

    FeedDriver(FakeHistory server, Random rng, int parallel) {
        this.server = server;
        this.rng = rng;
        this.parallel = parallel;
        for (Long dialogId : server.histories.keySet()) {
            channels.add(new Channel<Post>(dialogId));
        }
    }

    Channel<Post> channel(long dialogId) {
        for (Channel<Post> channel : channels) {
            if (channel.dialogId == dialogId) {
                return channel;
            }
        }
        throw new AssertionError("no channel " + dialogId);
    }

    /** Sets the read pointer of a channel, with the unread count the server would report. */
    void setRead(long dialogId, int readMax) {
        Channel<Post> channel = channel(dialogId);
        channel.readMax = readMax;
        int unread = 0;
        for (Post post : server.history(dialogId)) {
            if (post.id > readMax && !post.service) {
                unread++;
            }
        }
        channel.unreadCount = unread;
    }

    // region Opening

    /** Opening at the newest posts: every channel at its top, the first page loads older posts. */
    void openAtLatest() {
        for (Channel<Post> channel : channels) {
            merge.initAtDialogTop(channel, server.top(channel.dialogId));
        }
        List<Post> page = nextPage(false);
        recordInitial(page, ids.page(page.size(), false));
        initial = null;
    }

    /** Opening at a saved position (the restore path: no probes). */
    void openAtSaved(long restoreDialogId, int restoreMessageId, int restoreDate) {
        anchorDate = restoreDate;
        loadAround();
        initial = merge.takeInitial(channels, restoreDialogId, restoreMessageId, anchorDate);
        recordInitial(initial.messages, ids.initial(initial.messages.size()));
    }

    /**
     * Opening at the first unread post: probes around each unread channel's read pointer (responses in
     * random order), then loads every channel around the oldest first unread post. Returns false when
     * nothing is unread (the controller then opens at the newest posts).
     */
    boolean openAtFirstUnread() {
        boolean anyUnread = false;
        ArrayList<Channel<Post>> probes = new ArrayList<>();
        for (Channel<Post> channel : channels) {
            channel.probeDone = !channel.hasUnread();
            anyUnread |= channel.hasUnread();
            if (channel.hasUnread()) {
                probes.add(channel);
            }
        }
        if (!anyUnread) {
            openAtLatest();
            return false;
        }
        java.util.Collections.shuffle(probes, rng);
        for (Channel<Post> channel : probes) {
            requests++;
            merge.onProbePage(channel, server.getHistory(channel.dialogId, FolderFeedMerge.probeQuery(channel)), channel.liveVersion);
        }
        anchorDate = FolderFeedMerge.anchorFromProbes(channels);
        if (anchorDate == 0) {
            openAtLatest();
            return false;
        }
        loadAround();
        initial = merge.takeInitial(channels, 0, 0, anchorDate);
        recordInitial(initial.messages, ids.initial(initial.messages.size()));
        return true;
    }

    private void loadAround() {
        for (Channel<Post> channel : channels) {
            merge.prepareAround(channel, anchorDate, server.top(channel.dialogId));
        }
        ArrayList<Channel<Post>> needed = new ArrayList<>();
        for (Channel<Post> channel : channels) {
            if (!channel.aroundDone) {
                needed.add(channel);
            }
        }
        java.util.Collections.shuffle(needed, rng);
        for (Channel<Post> channel : needed) {
            requests++;
            merge.onAroundPage(channel, server.getHistory(channel.dialogId, FolderFeedMerge.aroundQuery(anchorDate)), anchorDate,
                    channel.liveVersion, server.top(channel.dialogId));
        }
        for (Channel<Post> channel : channels) {
            assertTrue("every channel has its starting window", channel.aroundDone);
        }
    }

    private void recordInitial(List<Post> page, int topId) {
        assertTrue("first page", shown.isEmpty());
        pages.add(page);
        pageNewer.add(false);
        for (int i = 0; i < page.size(); i++) {
            shown.add(page.get(i));
            shownIds.add(topId - i);
        }
    }

    // endregion

    // region Paging

    boolean hasMore(boolean newer) {
        return FolderFeedMerge.hasMore(channels, newer);
    }

    /** Loads and records the next page in the given direction. */
    List<Post> page(boolean newer) {
        List<Post> page = nextPage(newer);
        pages.add(page);
        pageNewer.add(newer);
        int firstId = ids.page(page.size(), newer);
        if (newer) {
            ArrayList<Post> above = new ArrayList<>(page);
            ArrayList<Integer> aboveIds = new ArrayList<>();
            for (int i = 0; i < page.size(); i++) {
                aboveIds.add(firstId - i);
            }
            shown.addAll(0, above);
            shownIds.addAll(0, aboveIds);
        } else {
            for (int i = 0; i < page.size(); i++) {
                shown.add(page.get(i));
                shownIds.add(firstId - i);
            }
        }
        return page;
    }

    /** Pages in the direction until nothing is left. */
    void pageToEnd(boolean newer) {
        int guard = 0;
        while (hasMore(newer)) {
            page(newer);
            if (++guard > 10_000) {
                fail("paging does not end");
            }
        }
    }

    /** Pages emitted with exactly MAX_PAGE_SIZE posts (more were ready). */
    int cappedPages;

    private List<Post> nextPage(boolean newer) {
        for (int guard = 0; guard < 100_000; guard++) {
            ArrayList<Post> page = merge.takePage(channels, newer);
            if (page != null) {
                checkPage(page, newer);
                if (page.size() == FolderFeedMerge.MAX_PAGE_SIZE) {
                    cappedPages++;
                }
                return page;
            }
            for (Channel<Post> channel : FolderFeedMerge.fetchCandidates(channels, newer)) {
                if (pending.size() >= parallel) {
                    break;
                }
                send(channel, newer);
            }
            if (pending.isEmpty()) {
                fail("no page and nothing to fetch (" + (newer ? "newer" : "older") + ")");
            }
            deliver(pending.remove(rng.nextInt(pending.size())));
        }
        throw new AssertionError("no page after many responses");
    }

    private void send(Channel<Post> channel, boolean newer) {
        FolderFeedMerge.Edge<Post> edge = channel.edge(newer);
        assertFalse("one request per edge", edge.inFlight);
        assertFalse("no request on an exhausted edge", edge.exhausted);
        FolderFeedMerge.Query query = FolderFeedMerge.edgeQuery(channel, newer);
        edge.inFlight = true;
        requests++;
        // The response is built now: posts that arrive later are not in it.
        pending.add(new Pending(channel, newer, query.offsetId, channel.liveVersion, anchorDate, server.getHistory(channel.dialogId, query)));
    }

    private void deliver(Pending response) {
        response.channel.edge(response.newer).inFlight = false;
        merge.onEdgePage(response.channel, response.newer, response.requestOffsetId, response.response, response.anchorDate, response.liveVersion);
    }

    /** Delivers every response still in flight (as when the user stops scrolling). */
    void settle() {
        while (!pending.isEmpty()) {
            deliver(pending.remove(rng.nextInt(pending.size())));
        }
    }

    private void checkPage(List<Post> page, boolean newer) {
        assertTrue("page size " + page.size(), page.size() <= FolderFeedMerge.MAX_PAGE_SIZE);
        if (page.size() < FolderFeedMerge.PAGE_SIZE) {
            assertFalse("a short page only at the end of history", FolderFeedMerge.anyEdgeLeft(channels, newer));
        }
        for (int i = 1; i < page.size(); i++) {
            assertTrue("page newest first: " + page.get(i - 1) + " then " + page.get(i), compareNewestFirst(page.get(i - 1), page.get(i)) < 0);
        }
    }

    static int compareNewestFirst(Post a, Post b) {
        if (a.date != b.date) {
            return Integer.compare(b.date, a.date);
        }
        if (a.dialogId != b.dialogId) {
            return Long.compare(a.dialogId, b.dialogId);
        }
        return Integer.compare(b.id, a.id);
    }

    // endregion

    // region Live posts

    /** New posts arrive in a channel (as didReceiveNewMessages) while the feed is open. */
    void livePosts(long dialogId, int count, int date) {
        List<Post> history = server.history(dialogId);
        // Id 1 of a channel is always its "channel created" service message, so a post is never id 1.
        int id = history.isEmpty() ? 2 : history.get(history.size() - 1).id + 1;
        if (!history.isEmpty()) {
            date = Math.max(date, history.get(history.size() - 1).date);
        }
        int minId = id;
        for (int i = 0; i < count; i++) {
            server.add(Post.post(dialogId, id++, date));
        }
        FolderFeedMerge.onNewPosts(channel(dialogId), minId, date, true);
    }

    // endregion

    // region Checks

    /** The whole feed was loaded: every post exactly once, in time order, ids growing upwards. */
    void assertCompleteAndOrdered() {
        assertOrdered();
        java.util.TreeSet<String> missing = new java.util.TreeSet<>(sortedKeys(server.allPosts()));
        java.util.TreeSet<String> extra = new java.util.TreeSet<>(sortedKeys(shown));
        java.util.TreeSet<String> both = new java.util.TreeSet<>(missing);
        both.retainAll(extra);
        missing.removeAll(both);
        extra.removeAll(both);
        assertTrue("every post shown: missing " + missing, missing.isEmpty());
        assertTrue("only posts of the channels shown: extra " + extra, extra.isEmpty());
    }

    /** What is shown is in time order (newest first), each channel's posts by id, synthetic ids unique and growing upwards. */
    void assertOrdered() {
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        java.util.HashMap<Long, Integer> lastIdByChannel = new java.util.HashMap<>();
        for (int i = 0; i < shown.size(); i++) {
            Post post = shown.get(i);
            assertFalse("not a service message: " + post, post.service || post.empty);
            assertTrue("shown twice: " + post, keys.add(key(post)));
            if (i > 0) {
                Post above = shown.get(i - 1);
                assertTrue("time order: " + above + " above " + post + " at " + i, above.date >= post.date);
                assertTrue("synthetic ids grow upwards at " + i, shownIds.get(i - 1) > shownIds.get(i));
            }
            Integer last = lastIdByChannel.get(post.dialogId);
            assertTrue("channel order: " + post, last == null || last > post.id);
            lastIdByChannel.put(post.dialogId, post.id);
        }
    }

    static String key(Post post) {
        return post.dialogId + "#" + post.id;
    }

    static List<String> sortedKeys(List<Post> posts) {
        ArrayList<String> keys = new ArrayList<>();
        for (Post post : posts) {
            keys.add(key(post));
        }
        java.util.Collections.sort(keys);
        return keys;
    }

    // endregion
}
