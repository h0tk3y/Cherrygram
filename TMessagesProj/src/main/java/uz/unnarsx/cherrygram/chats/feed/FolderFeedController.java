/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.chats.feed;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;

import uz.unnarsx.cherrygram.core.configs.CherrygramChatsConfig;

/**
 * Loads the posts of every broadcast channel in a chat folder and merges them into a single
 * time-ordered stream that {@link ChatActivity} renders in {@link ChatActivity#MODE_FOLDER_FEED}.
 * <p>
 * Telegram has no server method returning the history of a folder, so each channel's history is
 * paged separately with {@code messages.getHistory} and merged on the client. Every channel keeps a
 * "frontier": an upper bound for the date of its not-yet-fetched posts (initially the date of the
 * channel's last message as known from the dialogs list, afterwards the date of the oldest fetched
 * post). A fetched post is only emitted once its date is not older than the frontier of every
 * channel that may still have unfetched posts, so the merged order is exact while channels whose
 * posts are too old for the current screen are never requested.
 * <p>
 * Messages from different channels may share ids, but {@link ChatActivity} keys messages by id, so
 * every emitted message gets a synthetic, strictly decreasing id ({@code messageOwner.id}) and its
 * real id is kept in {@code messageOwner.realId} (see {@link MessageObject#getRealId()}), the same
 * approach the hashtag search uses.
 */
public class FolderFeedController implements NotificationCenter.NotificationCenterDelegate {

    public static final int MIN_CHANNELS = 2;

    private static final int PAGE_SIZE = 20;
    private static final int FETCH_LIMIT = 20;
    private static final int MAX_PARALLEL_REQUESTS = 3;
    private static final int MAX_RETRIES = 3;
    /** The first load continues past one page to reach the oldest unread post, up to this many posts. */
    private static final int FIRST_LOAD_CAP = 300;
    private static final int MAX_FETCH_LIMIT = 100;
    private static final long CHANNELS_CACHE_MS = 1000;

    private static final FolderFeedController[] Instance = new FolderFeedController[UserConfig.MAX_ACCOUNT_COUNT];

    public static FolderFeedController getInstance(int account) {
        FolderFeedController localInstance = Instance[account];
        if (localInstance == null) {
            synchronized (FolderFeedController.class) {
                localInstance = Instance[account];
                if (localInstance == null) {
                    Instance[account] = localInstance = new FolderFeedController(account);
                }
            }
        }
        return localInstance;
    }

    private final int currentAccount;
    /** Sessions keyed by the classGuid of the ChatActivity that displays them. */
    private final SparseArray<Session> sessions = new SparseArray<>();
    private boolean observing;

    private FolderFeedController(int account) {
        currentAccount = account;
    }

    // region Folder helpers

    public static boolean isFeatureEnabled() {
        return CherrygramChatsConfig.INSTANCE.getFolderChannelsFeed();
    }

    private static final Object channelsCacheLock = new Object();
    private static int cachedAccount = -1;
    private static MessagesController.DialogFilter cachedFilter;
    private static int cachedDialogsCount = -1;
    private static long cachedTime;
    private static ArrayList<TLRPC.Chat> cachedChannels;

    /** Broadcast channels (not groups) that belong to the folder, in dialogs-list order. */
    public static ArrayList<TLRPC.Chat> getFeedChannels(int account, @Nullable MessagesController.DialogFilter filter) {
        if (filter == null || filter.isDefault()) {
            return new ArrayList<>();
        }
        // The dialogs list and its rows ask often while scrolling; a short-lived cache keeps this cheap.
        int dialogsCount = MessagesController.getInstance(account).getAllDialogs().size();
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (channelsCacheLock) {
            if (cachedChannels != null && cachedAccount == account && cachedFilter == filter && cachedDialogsCount == dialogsCount && now - cachedTime < CHANNELS_CACHE_MS) {
                return new ArrayList<>(cachedChannels);
            }
        }
        ArrayList<TLRPC.Chat> result = computeFeedChannels(account, filter);
        synchronized (channelsCacheLock) {
            cachedAccount = account;
            cachedFilter = filter;
            cachedDialogsCount = dialogsCount;
            cachedTime = now;
            cachedChannels = new ArrayList<>(result);
        }
        return result;
    }

    private static ArrayList<TLRPC.Chat> computeFeedChannels(int account, MessagesController.DialogFilter filter) {
        ArrayList<TLRPC.Chat> result = new ArrayList<>();
        AccountInstance accountInstance = AccountInstance.getInstance(account);
        MessagesController controller = accountInstance.getMessagesController();
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(controller.getAllDialogs());
        HashSet<Long> added = new HashSet<>();
        for (TLRPC.Dialog dialog : dialogs) {
            if (dialog == null || dialog instanceof TLRPC.TL_dialogFolder || dialog.id >= 0) {
                continue;
            }
            TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (!ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat)) {
                continue;
            }
            if (filter.includesDialog(accountInstance, dialog.id, dialog) && added.add(dialog.id)) {
                result.add(chat);
            }
        }
        return result;
    }

    public static int getFeedChannelsCount(int account, @Nullable MessagesController.DialogFilter filter) {
        return getFeedChannels(account, filter).size();
    }

    /** Whether the merged feed entry points should be shown for the folder. */
    public static boolean isAvailableForFilter(int account, @Nullable MessagesController.DialogFilter filter) {
        return isFeatureEnabled() && filter != null && !filter.isDefault() && getFeedChannelsCount(account, filter) >= MIN_CHANNELS;
    }

    @Nullable
    public static MessagesController.DialogFilter findFilter(int account, int filterId) {
        return MessagesController.getInstance(account).dialogFiltersById.get(filterId);
    }

    // endregion

    // region Sessions

    private static class Source {
        final long dialogId;
        final TLRPC.InputPeer peer;
        /** Real id of the oldest fetched post; 0 means nothing was fetched yet. */
        int offsetId;
        /** Upper bound of the date of every post that is not fetched yet. */
        int frontier;
        boolean exhausted;
        boolean inFlight;
        int reqId;
        int retries;
        /** Read pointer and unread count when the feed was opened (posts with id > readMax are unread). */
        int readMax;
        int unreadCount;
        final ArrayList<TLRPC.Message> buffer = new ArrayList<>();

        Source(long dialogId, TLRPC.InputPeer peer, int frontier) {
            this.dialogId = dialogId;
            this.peer = peer;
            this.frontier = frontier;
        }
    }

    private static class Session {
        final int guid;
        final int filterId;
        final ArrayList<Source> sources = new ArrayList<>();
        final HashSet<Long> dialogIds = new HashSet<>();
        int nextGeneratedId = Integer.MAX_VALUE - 1;
        /** The first load opens the feed at the oldest unread post, like a channel opens at its first unread. */
        boolean firstLoad = true;
        boolean loading;
        boolean endReached;
        int pendingLoadIndex;

        Session(int guid, int filterId) {
            this.guid = guid;
            this.filterId = filterId;
        }
    }

    /** Starts (or restarts) the feed displayed by the ChatActivity with the given classGuid. */
    public void start(int guid, int filterId, int loadIndex) {
        stop(guid);
        Session session = new Session(guid, filterId);
        MessagesController controller = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Chat> channels = getFeedChannels(currentAccount, findFilter(currentAccount, filterId));
        for (TLRPC.Chat chat : channels) {
            long dialogId = -chat.id;
            TLRPC.InputPeer peer = controller.getInputPeer(dialogId);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                continue;
            }
            TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            int frontier = dialog != null && dialog.last_message_date > 0 ? dialog.last_message_date : Integer.MAX_VALUE;
            Source source = new Source(dialogId, peer, frontier);
            if (dialog != null) {
                Integer localReadMax = controller.dialogs_read_inbox_max.get(dialogId);
                source.readMax = Math.max(dialog.read_inbox_max_id, localReadMax != null ? localReadMax : 0);
                source.unreadCount = dialog.unread_count;
            }
            session.sources.add(source);
            session.dialogIds.add(dialogId);
        }
        sessions.put(guid, session);
        updateObserving();
        loadMore(guid, loadIndex);
    }

    public void stop(int guid) {
        Session session = sessions.get(guid);
        if (session == null) {
            return;
        }
        for (Source source : session.sources) {
            if (source.inFlight) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(source.reqId, false);
            }
        }
        sessions.remove(guid);
        updateObserving();
    }

    public boolean isEndReached(int guid) {
        Session session = sessions.get(guid);
        return session == null || session.endReached;
    }

    public boolean isLoading(int guid) {
        Session session = sessions.get(guid);
        return session != null && session.loading;
    }

    public int getChannelsCount(int guid) {
        Session session = sessions.get(guid);
        return session == null ? 0 : session.sources.size();
    }

    /** Loads the next page of older posts. The result arrives as {@link NotificationCenter#messagesDidLoad}. */
    public void loadMore(int guid, int loadIndex) {
        Session session = sessions.get(guid);
        if (session == null || session.loading || session.endReached) {
            return;
        }
        session.loading = true;
        session.pendingLoadIndex = loadIndex;
        process(session);
    }

    private void process(Session session) {
        if (sessions.get(session.guid) != session || !session.loading) {
            return;
        }
        ArrayList<TLRPC.Message> ready = collectReady(session);
        boolean pageReady = ready.size() >= PAGE_SIZE;
        if (pageReady && session.firstLoad && ready.size() < FIRST_LOAD_CAP && !oldestUnreadReady(session)) {
            // Keep loading so the feed can open at the oldest unread post.
            pageReady = false;
        }
        if (pageReady || noMoreToFetch(session)) {
            emit(session, ready);
            return;
        }
        // Not enough posts for a page yet: keep them buffered and lower the watermark by fetching
        // the channels with the newest frontiers, they are the ones holding the merge back.
        restoreToBuffers(session, ready);
        int inFlight = 0;
        ArrayList<Source> candidates = new ArrayList<>();
        for (Source source : session.sources) {
            if (source.inFlight) {
                inFlight++;
            } else if (!source.exhausted) {
                candidates.add(source);
            }
        }
        Collections.sort(candidates, (a, b) -> Integer.compare(b.frontier, a.frontier));
        for (int i = 0; i < candidates.size() && inFlight < MAX_PARALLEL_REQUESTS; i++) {
            fetch(session, candidates.get(i));
            inFlight++;
        }
        if (inFlight == 0) {
            emit(session, collectReady(session));
        }
    }

    /**
     * Whether every unread post (as of opening the feed) has been fetched and is ready to be shown,
     * i.e. no channel with unread posts still has unfetched or watermark-held unread posts.
     * Call after {@link #collectReady}, which removes the ready posts from the buffers.
     */
    private boolean oldestUnreadReady(Session session) {
        for (Source source : session.sources) {
            if (source.unreadCount <= 0) {
                continue;
            }
            if (!source.exhausted && (source.offsetId == 0 || source.offsetId > source.readMax + 1)) {
                return false;
            }
            for (TLRPC.Message message : source.buffer) {
                if (message.id > source.readMax) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean noMoreToFetch(Session session) {
        for (Source source : session.sources) {
            if (!source.exhausted) {
                return false;
            }
        }
        return true;
    }

    /** Date watermark: posts not older than it can be shown, no channel can have newer unfetched posts. */
    private int watermark(Session session) {
        int watermark = 0;
        for (Source source : session.sources) {
            if (!source.exhausted) {
                watermark = Math.max(watermark, source.frontier);
            }
        }
        return watermark;
    }

    /** Removes and returns every buffered post that can be shown now, newest first. */
    private ArrayList<TLRPC.Message> collectReady(Session session) {
        int watermark = watermark(session);
        ArrayList<TLRPC.Message> ready = new ArrayList<>();
        for (Source source : session.sources) {
            Iterator<TLRPC.Message> iterator = source.buffer.iterator();
            while (iterator.hasNext()) {
                TLRPC.Message message = iterator.next();
                if (message.date >= watermark) {
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

    private void restoreToBuffers(Session session, ArrayList<TLRPC.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            TLRPC.Message message = messages.get(i);
            long dialogId = MessageObject.getDialogId(message);
            for (Source source : session.sources) {
                if (source.dialogId == dialogId) {
                    source.buffer.add(0, message);
                    break;
                }
            }
        }
        for (Source source : session.sources) {
            sortNewestFirst(source.buffer);
        }
    }

    private static Source findSource(Session session, long dialogId) {
        for (Source source : session.sources) {
            if (source.dialogId == dialogId) {
                return source;
            }
        }
        return null;
    }

    private static void sortNewestFirst(ArrayList<TLRPC.Message> messages) {
        Collections.sort(messages, (a, b) -> {
            if (a.date != b.date) {
                return Integer.compare(b.date, a.date);
            }
            long da = MessageObject.getDialogId(a), db = MessageObject.getDialogId(b);
            if (da != db) {
                return Long.compare(da, db);
            }
            return Integer.compare(b.id, a.id);
        });
    }

    private void fetch(Session session, Source source) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        req.offset_id = source.offsetId;
        final int limit = session.firstLoad && source.unreadCount >= FETCH_LIMIT ? Math.min(MAX_FETCH_LIMIT, source.unreadCount + 1) : FETCH_LIMIT;
        req.limit = limit;
        source.inFlight = true;
        source.reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            source.inFlight = false;
            if (sessions.get(session.guid) != session) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                if (res.messages.isEmpty()) {
                    source.exhausted = true;
                } else {
                    TLRPC.Message oldest = null;
                    for (TLRPC.Message message : res.messages) {
                        if (message == null || message instanceof TLRPC.TL_messageEmpty || MessageObject.getDialogId(message) != source.dialogId) {
                            continue;
                        }
                        if (oldest == null || message.id < oldest.id) {
                            oldest = message;
                        }
                        if (message instanceof TLRPC.TL_messageService) {
                            continue;
                        }
                        source.buffer.add(message);
                    }
                    sortNewestFirst(source.buffer);
                    if (oldest == null) {
                        source.exhausted = true;
                    } else {
                        source.offsetId = oldest.id;
                        source.frontier = oldest.date;
                        if (res.messages.size() < limit) {
                            source.exhausted = true;
                        }
                    }
                }
            } else {
                if (error != null) {
                    FileLog.e("FolderFeed: getHistory failed for " + source.dialogId + ": " + error.text);
                }
                if (source.retries < MAX_RETRIES) {
                    // Transient failures (e.g. FLOOD_WAIT): retry the same page later instead of dropping the channel.
                    source.retries++;
                    int delay = 1000 * source.retries;
                    if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                        try {
                            delay = Math.max(delay, Integer.parseInt(error.text.substring("FLOOD_WAIT_".length())) * 1000);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                    source.inFlight = true;
                    AndroidUtilities.runOnUIThread(() -> {
                        source.inFlight = false;
                        if (sessions.get(session.guid) == session) {
                            process(session);
                        }
                    }, Math.min(delay, 30_000));
                    return;
                }
                source.exhausted = true;
            }
            process(session);
        }));
    }

    private void emit(Session session, ArrayList<TLRPC.Message> ready) {
        ArrayList<MessageObject> messageObjects = new ArrayList<>(ready.size());
        // On the first page, find the oldest unread post: ChatActivity scrolls there and shows the
        // "Unread messages" separator, exactly as for a channel's first unread message.
        int firstUnreadId = 0;
        int unreadCount = 0;
        final boolean firstLoad = session.firstLoad;
        session.firstLoad = false;
        for (TLRPC.Message message : ready) {
            // SEARCH_PUBLIC_POSTS makes the cell draw the channel avatar and the "go to post" side button.
            MessageObject messageObject = new MessageObject(currentAccount, message, null, null, null, null, null, true, true, 0, false, false, false, ChatActivity.SEARCH_PUBLIC_POSTS);
            messageObject.isFolderFeedMessage = true;
            message.realId = message.id;
            message.id = session.nextGeneratedId--;
            messageObjects.add(messageObject);
            if (firstLoad) {
                Source source = findSource(session, MessageObject.getDialogId(message));
                if (source != null && source.unreadCount > 0 && message.realId > source.readMax) {
                    firstUnreadId = message.id;
                    unreadCount++;
                }
            }
        }
        boolean hasMore = false;
        for (Source source : session.sources) {
            if (!source.exhausted || !source.buffer.isEmpty()) {
                hasMore = true;
                break;
            }
        }
        session.loading = false;
        session.endReached = !hasMore;
        final int loadIndex = session.pendingLoadIndex;
        final boolean endReached = session.endReached;
        final int fnid = firstUnreadId;
        final int unreadToLoad = unreadCount;
        // Always asynchronous, like regular history loads: loadMore() may be called from scroll callbacks.
        AndroidUtilities.runOnUIThread(() -> {
            if (sessions.get(session.guid) != session) {
                return;
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesDidLoad,
                    0L, messageObjects.size(), messageObjects, false, fnid, 0, unreadToLoad, 0, 2, endReached,
                    session.guid, loadIndex, 0, 0, ChatActivity.MODE_FOLDER_FEED);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedLoaded,
                    session.guid, endReached);
        });
    }

    /**
     * Returns the list with every merged-feed message replaced by a copy that carries its real id,
     * for code that sends message ids to the server (e.g. forwarding). Other messages are kept.
     */
    public static boolean hasFeedMessages(ArrayList<MessageObject> messages) {
        if (messages == null) {
            return false;
        }
        for (MessageObject messageObject : messages) {
            if (messageObject != null && messageObject.isFolderFeedMessage) {
                return true;
            }
        }
        return false;
    }

    public static ArrayList<MessageObject> withRealIds(ArrayList<MessageObject> messages) {
        if (!hasFeedMessages(messages)) {
            return messages;
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (MessageObject messageObject : messages) {
            if (messageObject == null || !messageObject.isFolderFeedMessage) {
                result.add(messageObject);
                continue;
            }
            TLRPC.Message original = messageObject.messageOwner;
            SerializedData data = new SerializedData(original.getObjectSize());
            original.serializeToStream(data);
            SerializedData input = new SerializedData(data.toByteArray());
            TLRPC.Message copy = TLRPC.Message.TLdeserialize(input, input.readInt32(false), false);
            data.cleanup();
            input.cleanup();
            if (copy == null) {
                result.add(messageObject);
                continue;
            }
            copy.id = messageObject.getRealId();
            copy.realId = 0;
            MessageObject copyObject = new MessageObject(messageObject.currentAccount, copy, false, false);
            result.add(copyObject);
        }
        return result;
    }

    // endregion

    // region Live updates

    private void updateObserving() {
        boolean need = sessions.size() > 0;
        if (need == observing) {
            return;
        }
        observing = need;
        if (need) {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        } else {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            int count = 0;
            for (MessageObject messageObject : messages) {
                if (messageObject != null && messageObject.getId() > 0 && !messageObject.isSponsored() && messageObject.messageOwner.action == null) {
                    count++;
                }
            }
            if (count == 0) {
                return;
            }
            for (int i = 0; i < sessions.size(); i++) {
                Session session = sessions.valueAt(i);
                if (session.dialogIds.contains(dialogId)) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedNewPosts, session.guid, count);
                }
            }
        }
    }

    // endregion
}
