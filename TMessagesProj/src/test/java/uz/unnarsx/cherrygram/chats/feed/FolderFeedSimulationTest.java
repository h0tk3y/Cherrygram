package uz.unnarsx.cherrygram.chats.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import uz.unnarsx.cherrygram.chats.feed.FakeHistory.Post;
import uz.unnarsx.cherrygram.chats.feed.FolderFeedMerge.Channel;

/**
 * Property tests of the merged feed over many random folders: whatever the channels' paces, gaps,
 * service messages, same-second posts, request parallelism and response order, the feed shows every post
 * of every channel exactly once, in time order, with synthetic ids growing upwards; and it opens where it
 * should (newest posts, saved post, first unread post).
 */
public class FolderFeedSimulationTest {

    private static final int SEEDS = 300;
    private static final int[] PARALLEL = {1, 2, 8};
    private static final int START = 1_700_000_000;

    private static String context(int seed, int parallel) {
        return "seed " + seed + ", parallel " + parallel;
    }

    @Test
    public void openAtLatestThenPageOlderShowsEveryPostInOrder() {
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int parallel : PARALLEL) {
                Random rng = new Random(seed * 31L + parallel);
                FakeHistory server = FakeHistory.random(rng, 2 + rng.nextInt(7), 150, START);
                FeedDriver feed = new FeedDriver(server, rng, parallel);
                try {
                    feed.openAtLatest();
                    feed.pageToEnd(false);
                    assertFalse(feed.hasMore(true));
                    feed.assertCompleteAndOrdered();
                } catch (AssertionError e) {
                    throw new AssertionError(context(seed, parallel) + ": " + e.getMessage(), e);
                }
            }
        }
    }

    @Test
    public void openAtFirstUnreadThenPageBothWaysShowsEveryPostInOrder() {
        int openedAtUnread = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int parallel : PARALLEL) {
                Random rng = new Random(seed * 17L + parallel);
                FakeHistory server = FakeHistory.random(rng, 2 + rng.nextInt(7), 150, START);
                FeedDriver feed = new FeedDriver(server, rng, parallel);
                markRandomReadPointers(feed, server, rng);
                try {
                    boolean atUnread = feed.openAtFirstUnread();
                    if (atUnread) {
                        openedAtUnread++;
                        assertOpenedAtFirstUnread(feed);
                    }
                    // Scroll both ways in random steps, as a reader would.
                    while (feed.hasMore(false) || feed.hasMore(true)) {
                        boolean newer = feed.hasMore(true) && (!feed.hasMore(false) || rng.nextBoolean());
                        feed.page(newer);
                        feed.assertOrdered();
                    }
                    feed.assertCompleteAndOrdered();
                    if (atUnread) {
                        assertNoUnreadBelowFirstUnread(feed);
                    }
                } catch (AssertionError e) {
                    throw new AssertionError(context(seed, parallel) + ": " + e.getMessage(), e);
                }
            }
        }
        assertTrue("most random folders have unread posts: " + openedAtUnread, openedAtUnread > SEEDS);
    }

    @Test
    public void openAtSavedPostThenPageBothWays() {
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int parallel : PARALLEL) {
                Random rng = new Random(seed * 13L + parallel);
                FakeHistory server = FakeHistory.random(rng, 2 + rng.nextInt(7), 150, START);
                List<Post> all = server.allPosts();
                if (all.isEmpty()) {
                    continue;
                }
                Post saved = all.get(rng.nextInt(all.size()));
                // Sometimes the saved post was deleted since: the feed opens at the oldest post from its time on.
                boolean deleted = rng.nextInt(4) == 0;
                if (deleted) {
                    server.history(saved.dialogId).remove(saved);
                }
                FeedDriver feed = new FeedDriver(server, rng, parallel);
                try {
                    feed.openAtSaved(saved.dialogId, saved.id, saved.date);
                    FolderFeedMerge.Initial<Post> initial = feed.initial;
                    if (!deleted) {
                        assertTrue("the saved post is in the first page", initial.restoreIndex >= 0);
                        Post at = initial.messages.get(initial.restoreIndex);
                        assertEquals(saved.dialogId, at.dialogId);
                        assertEquals(saved.id, at.id);
                    } else if (initial.restoreIndex >= 0) {
                        Post at = initial.messages.get(initial.restoreIndex);
                        assertTrue("opens at a post from the saved time on", at.date >= saved.date);
                        for (Post post : initial.messages) {
                            if (post.date >= saved.date) {
                                assertTrue("no later post below the opening one", DriverOrder.atOrAbove(initial.messages, at, post));
                            }
                        }
                    }
                    while (feed.hasMore(false) || feed.hasMore(true)) {
                        feed.page(feed.hasMore(true) && (!feed.hasMore(false) || rng.nextBoolean()));
                    }
                    feed.assertCompleteAndOrdered();
                } catch (AssertionError e) {
                    throw new AssertionError(context(seed, parallel) + ": " + e.getMessage(), e);
                }
            }
        }
    }

    @Test
    public void livePostsArriveThroughNewerPagesInOrder() {
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int parallel : PARALLEL) {
                Random rng = new Random(seed * 7L + parallel);
                FakeHistory server = FakeHistory.random(rng, 2 + rng.nextInt(5), 80, START);
                FeedDriver feed = new FeedDriver(server, rng, parallel);
                markRandomReadPointers(feed, server, rng);
                try {
                    feed.openAtFirstUnread();
                    int now = START + 100_000;
                    for (int round = 0; round < 6; round++) {
                        // Posts arrive in random channels, sometimes while requests are in flight.
                        Channel<Post> channel = feed.channels.get(rng.nextInt(feed.channels.size()));
                        now += rng.nextInt(50);
                        feed.livePosts(channel.dialogId, 1 + rng.nextInt(30), now);
                        if (feed.hasMore(true)) {
                            feed.page(true);
                        }
                        if (rng.nextBoolean() && feed.hasMore(false)) {
                            feed.page(false);
                        }
                    }
                    feed.pageToEnd(true);
                    feed.settle();
                    feed.pageToEnd(true);
                    feed.pageToEnd(false);
                    feed.assertCompleteAndOrdered();
                } catch (AssertionError e) {
                    throw new AssertionError(context(seed, parallel) + ": " + e.getMessage(), e);
                }
            }
        }
    }

    @Test
    public void pagesNeverExceedTheCapAndCarryTheRestOver() {
        // Five busy channels, and one whose newest post is newer than all of theirs but whose next one is far
        // older: until its response arrives it holds the watermark, so the busy channels' pages pile up and
        // more than MAX_PAGE_SIZE posts become ready at once.
        int capped = 0;
        for (int seed = 0; seed < 50; seed++) {
            FakeHistory server = new FakeHistory();
            for (int c = 1; c <= 5; c++) {
                for (int i = 1; i <= 100; i++) {
                    server.add(Post.post(-c, i, START + 10_000 + 10 * i + c));
                }
            }
            server.add(Post.post(-9, 2, START));
            server.add(Post.post(-9, 3, START + 20_000));
            FeedDriver feed = new FeedDriver(server, new Random(seed), 8);
            feed.openAtLatest();
            feed.pageToEnd(false);
            feed.assertCompleteAndOrdered();
            capped += feed.cappedPages;
        }
        assertTrue("some pages are capped: " + capped, capped > 0);
    }

    @Test
    public void quietChannelsAreNotFetchedForRecentPages() {
        // A channel whose newest post is far older than a page of the others is not requested for it.
        FakeHistory server = new FakeHistory();
        long a = -1, b = -2, old = -3;
        for (int i = 1; i <= 200; i++) {
            server.add(Post.post(a, i, START + 10 * i));
            server.add(Post.post(b, i, START + 10 * i + 5));
        }
        for (int i = 1; i <= 50; i++) {
            server.add(Post.post(old, i, START - 100_000 + i));
        }
        FeedDriver feed = new FeedDriver(server, new Random(2), 1);
        feed.openAtLatest();
        feed.page(false);
        Channel<Post> quiet = feed.channel(old);
        assertEquals("the old channel was not fetched", 0, quiet.older.offsetId);
        assertTrue(quiet.older.buffer.isEmpty());
        feed.pageToEnd(false);
        feed.assertCompleteAndOrdered();
    }

    private static void markRandomReadPointers(FeedDriver feed, FakeHistory server, Random rng) {
        for (Channel<Post> channel : feed.channels) {
            List<Post> history = server.history(channel.dialogId);
            if (history.isEmpty()) {
                continue;
            }
            if (rng.nextBoolean()) {
                Post last = history.get(history.size() - 1);
                feed.setRead(channel.dialogId, last.id); // read up to the top
            } else {
                Post read = history.get(rng.nextInt(history.size()));
                // The read post itself may be deleted later on, or the pointer may sit in a gap.
                feed.setRead(channel.dialogId, read.id + (rng.nextInt(3) == 0 ? 1 : 0));
            }
        }
    }

    /** The first page opens at the oldest unread post across the channels. */
    private static void assertOpenedAtFirstUnread(FeedDriver feed) {
        FolderFeedMerge.Initial<Post> initial = feed.initial;
        Post oldestUnread = null;
        for (Channel<Post> channel : feed.channels) {
            if (!channel.hasUnread()) {
                continue;
            }
            for (Post post : feed.server.history(channel.dialogId)) {
                if (post.id > channel.readMax && !post.service) {
                    if (oldestUnread == null || FeedDriver.compareNewestFirst(post, oldestUnread) > 0) {
                        oldestUnread = post;
                    }
                    break;
                }
            }
        }
        if (oldestUnread == null) {
            assertEquals("no unread post: no unread divider", -1, initial.firstUnreadIndex);
            return;
        }
        assertTrue("opens at an unread post", initial.firstUnreadIndex >= 0);
        Post at = initial.messages.get(initial.firstUnreadIndex);
        assertEquals("opens at the oldest unread post", FeedDriver.key(oldestUnread), FeedDriver.key(at));
    }

    /** Every unread post is shown at or above the post the feed opened at. */
    private static void assertNoUnreadBelowFirstUnread(FeedDriver feed) {
        FolderFeedMerge.Initial<Post> initial = feed.initial;
        if (initial.firstUnreadIndex < 0) {
            return;
        }
        Post at = initial.messages.get(initial.firstUnreadIndex);
        int position = feed.shown.indexOf(at);
        for (int i = position + 1; i < feed.shown.size(); i++) {
            Post post = feed.shown.get(i);
            Channel<Post> channel = feed.channel(post.dialogId);
            assertFalse("unread post " + post + " below the first unread " + at, channel.hasUnread() && post.id > channel.readMax);
        }
    }

    /** Helpers on the order of a newest-first list. */
    static final class DriverOrder {
        static boolean atOrAbove(List<Post> newestFirst, Post a, Post b) {
            return newestFirst.indexOf(b) <= newestFirst.indexOf(a);
        }
    }
}
