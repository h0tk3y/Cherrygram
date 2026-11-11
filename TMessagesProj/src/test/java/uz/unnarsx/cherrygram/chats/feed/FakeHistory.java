package uz.unnarsx.cherrygram.chats.feed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Test fixture: channel histories and a server that answers messages.getHistory the way Telegram does.
 * Messages are {@link Post}s; {@link #POSTS} is how {@link FolderFeedMerge} reads them.
 */
final class FakeHistory {

    /** A message of a channel: a post, or a service message (not shown in the feed). */
    static final class Post {
        final long dialogId;
        final int id;
        final int date;
        final boolean service;
        final boolean empty;

        Post(long dialogId, int id, int date, boolean service, boolean empty) {
            this.dialogId = dialogId;
            this.id = id;
            this.date = date;
            this.service = service;
            this.empty = empty;
        }

        static Post post(long dialogId, int id, int date) {
            return new Post(dialogId, id, date, false, false);
        }

        static Post service(long dialogId, int id, int date) {
            return new Post(dialogId, id, date, true, false);
        }

        static Post empty(long dialogId, int id) {
            return new Post(dialogId, id, 0, false, true);
        }

        @Override
        public String toString() {
            return (service ? "svc" : empty ? "empty" : "post") + "(" + dialogId + "#" + id + "@" + date + ")";
        }
    }

    static final FolderFeedMerge.Posts<Post> POSTS = new FolderFeedMerge.Posts<Post>() {
        @Override
        public int id(Post message) {
            return message.id;
        }

        @Override
        public int date(Post message) {
            return message.date;
        }

        @Override
        public long dialogId(Post message) {
            return message.dialogId;
        }

        @Override
        public boolean isEmpty(Post message) {
            return message.empty;
        }

        @Override
        public boolean isService(Post message) {
            return message.service;
        }
    };

    /** Histories by channel, oldest first. Ids grow with dates within a channel, like in real channels. */
    final Map<Long, List<Post>> histories = new LinkedHashMap<>();

    List<Post> history(long dialogId) {
        List<Post> history = histories.get(dialogId);
        if (history == null) {
            history = new ArrayList<>();
            histories.put(dialogId, history);
        }
        return history;
    }

    void add(Post post) {
        List<Post> history = history(post.dialogId);
        if (!history.isEmpty()) {
            Post last = history.get(history.size() - 1);
            if (post.id <= last.id || post.date < last.date) {
                throw new IllegalArgumentException("history must grow: " + last + " then " + post);
            }
        }
        history.add(post);
    }

    /**
     * messages.getHistory: the history newest first, starting at the first message older than offset_id
     * (or, without one, sent before offset_date), shifted by add_offset (negative values reach newer
     * messages), at most limit messages.
     */
    List<Post> getHistory(long dialogId, FolderFeedMerge.Query query) {
        List<Post> newestFirst = new ArrayList<>(history(dialogId));
        Collections.reverse(newestFirst);
        int p = 0;
        if (query.offsetId != 0) {
            while (p < newestFirst.size() && newestFirst.get(p).id >= query.offsetId) {
                p++;
            }
        } else if (query.offsetDate != 0) {
            while (p < newestFirst.size() && newestFirst.get(p).date >= query.offsetDate) {
                p++;
            }
        }
        int start = p + query.addOffset;
        int end = start + query.limit;
        start = Math.max(start, 0);
        end = Math.min(end, newestFirst.size());
        return start >= end ? new ArrayList<Post>() : new ArrayList<>(newestFirst.subList(start, end));
    }

    /** The channel's top as the dialogs list knows it. */
    FolderFeedMerge.DialogTop top(long dialogId) {
        List<Post> history = history(dialogId);
        if (history.isEmpty()) {
            return new FolderFeedMerge.DialogTop(true, 0, 0);
        }
        Post last = history.get(history.size() - 1);
        return new FolderFeedMerge.DialogTop(true, last.id, last.date);
    }

    /** Every post (not service message) of the given channels, any order. */
    List<Post> allPosts() {
        List<Post> result = new ArrayList<>();
        for (List<Post> history : histories.values()) {
            for (Post post : history) {
                if (!post.service && !post.empty) {
                    result.add(post);
                }
            }
        }
        return result;
    }

    /**
     * Random histories: {@code channels} channels with up to {@code maxPosts} messages each, sharing one
     * clock, with deleted ids, service messages and same-second messages (also across channels).
     */
    static FakeHistory random(Random rng, int channels, int maxPosts, int startTime) {
        FakeHistory history = new FakeHistory();
        for (int c = 0; c < channels; c++) {
            long dialogId = -(1_000_000_000L + c * 7919L);
            history.history(dialogId);
            int count = rng.nextInt(4) == 0 ? rng.nextInt(5) : rng.nextInt(maxPosts + 1);
            // Channels post at different paces; some start long after the others.
            int date = startTime + (rng.nextInt(3) == 0 ? rng.nextInt(20_000) : rng.nextInt(200));
            int id = 1 + rng.nextInt(1000);
            int pace = 1 + rng.nextInt(rng.nextBoolean() ? 30 : 600);
            for (int i = 0; i < count; i++) {
                boolean service = rng.nextInt(10) == 0;
                history.add(new Post(dialogId, id, date, service, false));
                id += 1 + (rng.nextInt(5) == 0 ? rng.nextInt(4) : 0); // deleted ids
                date += rng.nextInt(4) == 0 ? 0 : rng.nextInt(pace + 1); // same-second posts
            }
        }
        return history;
    }
}
