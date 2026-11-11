/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.chats.feed;

import android.os.SystemClock;
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

import uz.unnarsx.cherrygram.core.configs.CherrygramAppearanceConfig;
import uz.unnarsx.cherrygram.core.configs.CherrygramChatsConfig;

/**
 * Loads the posts of every broadcast channel in a chat folder and merges them into a single
 * time-ordered stream that {@link ChatActivity} renders in {@link ChatActivity#MODE_FOLDER_FEED}.
 * <p>
 * Telegram has no server method returning the history of a folder, so each channel's history is
 * paged separately with {@code messages.getHistory} and merged on the client. Like a channel, the feed
 * opens at the oldest unread post across its channels: a short probe after each unread channel's read
 * pointer finds its first unread post, and every channel is then loaded around the time of the oldest
 * one. From there the feed pages in both directions.
 * <p>
 * Each channel keeps two edges of its loaded range. Each edge has a "frontier": a bound for the date of
 * the posts beyond it that are not fetched yet (the date of the outermost fetched post). A fetched post is
 * only shown once no channel that may still have unfetched posts on that side could have one between it
 * and the posts already shown, so the merged order is exact while channels whose posts are too far away
 * for the current screen are never requested.
 * <p>
 * Messages from different channels may share ids, but {@link ChatActivity} keys messages by id, so
 * every emitted message gets a synthetic id ({@code messageOwner.id}) that grows from older to newer
 * posts, and its real id is kept in {@code messageOwner.realId} (see {@link MessageObject#getRealId()}),
 * the same approach the hashtag search uses.
 */
public class FolderFeedController implements NotificationCenter.NotificationCenterDelegate {

    public static final int MIN_CHANNELS = 2;

    private static final int PAGE_SIZE = 20;
    private static final int FETCH_LIMIT = 20;
    /** Opening: posts fetched per channel at or after / before the time of the oldest first unread post. */
    private static final int AROUND_NEWER = 20;
    private static final int AROUND_OLDER = 10;
    /**
     * getHistory requests in flight at once. Telegram has no multi-chat history method, but parallel
     * requests share MTProto containers, so a few in flight cost little; after a FLOOD_WAIT the
     * controller falls back to a gentle limit and recovers once the server stops asking to wait.
     */
    private static final int MAX_PARALLEL_REQUESTS = 8;
    private static final int FLOOD_PARALLEL_REQUESTS = 2;
    private static final long FLOOD_CALM_MS = 30_000;
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
    private int maxParallel = MAX_PARALLEL_REQUESTS;
    /** After a FLOOD_WAIT the parallelism stays low until this time (elapsedRealtime), then recovers step by step. */
    private long floodCalmUntil;

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
        return isFeatureEnabled() && filter != null && !filter.isDefault() && !excludesReadChats(filter)
                && getFeedChannelsCount(account, filter) >= MIN_CHANNELS;
    }

    /**
     * Folders that hide read chats (e.g. Telegram's "Unread" preset) lose channels while the feed
     * marks posts as read, so a merged feed of them would shrink under the reader: not offered.
     */
    private static boolean excludesReadChats(MessagesController.DialogFilter filter) {
        return (filter.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0;
    }

    /**
     * Unread posts across the folder's feed channels, for the pinned entry's counter: {count, 1 if any
     * counted channel is unmuted else 0}. Muted channels are counted only when the folder-tab setting
     * "include muted chats in counters" is on, matching the folder tabs.
     */
    public static int[] getFeedUnreadState(int account, @Nullable MessagesController.DialogFilter filter) {
        boolean includeMuted = CherrygramAppearanceConfig.INSTANCE.getTabsIncludeMutedInCounter();
        MessagesController controller = MessagesController.getInstance(account);
        int count = 0;
        boolean anyUnmuted = false;
        for (TLRPC.Chat chat : getFeedChannels(account, filter)) {
            TLRPC.Dialog dialog = controller.dialogs_dict.get(-chat.id);
            if (dialog == null || dialog.unread_count <= 0) {
                continue;
            }
            boolean muted = controller.isDialogMuted(dialog.id, 0);
            if (muted && !includeMuted) {
                continue;
            }
            count += dialog.unread_count;
            if (!muted) {
                anyUnmuted = true;
            }
        }
        return new int[]{count, anyUnmuted ? 1 : 0};
    }

    /**
     * Unread posts across the feed's channels as counted by the server, muted channels included, like
     * a channel's own "next unread" counter.
     */
    public static int getFeedUnreadCount(int account, @Nullable MessagesController.DialogFilter filter) {
        MessagesController controller = MessagesController.getInstance(account);
        int count = 0;
        for (TLRPC.Chat chat : getFeedChannels(account, filter)) {
            TLRPC.Dialog dialog = controller.dialogs_dict.get(-chat.id);
            if (dialog != null && dialog.unread_count > 0) {
                count += dialog.unread_count;
            }
        }
        return count;
    }

    @Nullable
    public static MessagesController.DialogFilter findFilter(int account, int filterId) {
        return MessagesController.getInstance(account).dialogFiltersById.get(filterId);
    }

    // endregion

    // region Sessions

    /** One edge (older or newer) of a channel's loaded range of history. */
    private static class Edge {
        /** Real id of the outermost fetched post on this edge (including service messages); 0 = none yet. */
        int offsetId;
        /** Bound for the date of every post on this edge that is not fetched yet: upper bound for the older
         *  edge, lower bound for the newer edge. */
        int frontier;
        boolean exhausted;
        boolean inFlight;
        int reqId;
        int retries;
        /** Fetched posts not shown yet, ordered from the loaded range outwards: the older edge keeps them
         *  newest first, the newer edge oldest first. */
        final ArrayList<TLRPC.Message> buffer = new ArrayList<>();
    }

    private static class Source {
        final long dialogId;
        final TLRPC.InputPeer peer;
        final Edge older = new Edge();
        final Edge newer = new Edge();
        /** Real ids of every post handed to a buffer, so the two edges never yield the same post twice. */
        final HashSet<Integer> seen = new HashSet<>();
        /** Read pointer and unread count when the feed was opened (posts with id > readMax are unread). */
        int readMax;
        int unreadCount;
        /** Opening probe: date of the channel's first unread message, 0 if it was not found. */
        int firstUnreadDate;
        /** Opening probe: the posts around the read pointer fetched with the first unread one, reused as the
         *  channel's starting window when it covers the time the feed opens at. */
        ArrayList<TLRPC.Message> probeWindow;
        boolean probeOlderEnd;
        boolean probeNewerEnd;
        int probeLiveVersion;
        boolean probeDone;
        boolean aroundDone;
        boolean requestInFlight;
        int requestId;
        int requestRetries;
        /** Bumped when the channel gets a new post, so a newer-edge response built before it does not
         *  mark the edge as exhausted for good. */
        int liveVersion;

        Source(long dialogId, TLRPC.InputPeer peer) {
            this.dialogId = dialogId;
            this.peer = peer;
        }

        boolean hasUnread() {
            return unreadCount > 0 && readMax > 0;
        }
    }

    private static final int STATE_PROBING = 0;
    private static final int STATE_AROUND = 1;
    private static final int STATE_READY = 2;

    /** Direction reported with {@link NotificationCenter#cgFolderFeedLoaded}. */
    public static final int LOADED_INITIAL = 0;
    public static final int LOADED_OLDER = 1;
    public static final int LOADED_NEWER = 2;

    /** Synthetic ids grow outwards from here: older posts count down, newer posts count up. */
    private static final int ID_BASE = 1_000_000_000;

    private static class Session {
        final int guid;
        final int filterId;
        final ArrayList<Source> sources = new ArrayList<>();
        final HashSet<Long> dialogIds = new HashSet<>();
        int state;
        /** Date the feed opens at: the oldest first-unread post across the channels, or a saved position. */
        int anchorDate;
        /** Saved position to restore (channel and real id of the post), or 0 to open at the first unread. */
        long restoreDialogId;
        int restoreMessageId;
        int nextOlderId = ID_BASE;
        int nextNewerId = ID_BASE + 1;
        boolean initialPending = true;
        int initialLoadIndex;
        boolean loadingOlder;
        int olderLoadIndex;
        boolean loadingNewer;
        int newerLoadIndex;
        int progressPosted = -1;
        /** Local database reads before the network: 0 not started, 1 reading, 2 done. */
        int probeCacheState;
        int aroundCacheState;

        Session(int guid, int filterId) {
            this.guid = guid;
            this.filterId = filterId;
        }
    }

    /**
     * Starts (or restarts) the feed displayed by the ChatActivity with the given classGuid. Like a channel,
     * it opens at the saved position if one is given (the post's channel, real id and date), else at the
     * oldest unread post across the channels, loading every channel around that time; with {@code atLatest}
     * set, or when nothing is unread, it opens at the newest posts.
     */
    public void start(int guid, int filterId, int loadIndex, boolean atLatest, long restoreDialogId, int restoreMessageId, int restoreDate) {
        stop(guid);
        Session session = new Session(guid, filterId);
        session.initialLoadIndex = loadIndex;
        MessagesController controller = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Chat> channels = getFeedChannels(currentAccount, findFilter(currentAccount, filterId));
        boolean anyUnread = false;
        for (TLRPC.Chat chat : channels) {
            long dialogId = -chat.id;
            TLRPC.InputPeer peer = controller.getInputPeer(dialogId);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                continue;
            }
            Source source = new Source(dialogId, peer);
            TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog != null) {
                Integer localReadMax = controller.dialogs_read_inbox_max.get(dialogId);
                source.readMax = Math.max(dialog.read_inbox_max_id, localReadMax != null ? localReadMax : 0);
                source.unreadCount = dialog.unread_count;
            }
            anyUnread |= source.hasUnread();
            session.sources.add(source);
            session.dialogIds.add(dialogId);
        }
        sessions.put(guid, session);
        updateObserving();
        if (!atLatest && restoreDate > 0 && session.dialogIds.contains(restoreDialogId)) {
            session.restoreDialogId = restoreDialogId;
            session.restoreMessageId = restoreMessageId;
            session.anchorDate = restoreDate;
            session.state = STATE_AROUND;
            prepareAround(session);
        } else if (atLatest || !anyUnread) {
            startAtLatest(session);
        } else {
            session.state = STATE_PROBING;
            for (Source source : session.sources) {
                source.probeDone = !source.hasUnread();
            }
        }
        pump(session);
    }

    /** Opens at the newest posts: only the older edge loads, the newer edge is at the live end. */
    private void startAtLatest(Session session) {
        MessagesController controller = MessagesController.getInstance(currentAccount);
        session.state = STATE_READY;
        for (Source source : session.sources) {
            TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
            source.older.offsetId = 0;
            source.older.frontier = dialog != null && dialog.last_message_date > 0 ? dialog.last_message_date : Integer.MAX_VALUE;
            source.newer.exhausted = true;
            source.newer.offsetId = dialog != null ? dialog.top_message : 0;
            source.newer.frontier = source.older.frontier;
        }
        session.loadingOlder = true;
        session.olderLoadIndex = session.initialLoadIndex;
    }

    public void stop(int guid) {
        Session session = sessions.get(guid);
        if (session == null) {
            return;
        }
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
        for (Source source : session.sources) {
            if (source.requestInFlight) {
                connectionsManager.cancelRequest(source.requestId, false);
            }
            if (source.older.inFlight) {
                connectionsManager.cancelRequest(source.older.reqId, false);
            }
            if (source.newer.inFlight) {
                connectionsManager.cancelRequest(source.newer.reqId, false);
            }
        }
        sessions.remove(guid);
        updateObserving();
    }

    /** Whether no older posts remain to be loaded. */
    public boolean isEndReached(int guid) {
        Session session = sessions.get(guid);
        return session == null || !hasMore(session, false);
    }

    public boolean isLoading(int guid) {
        Session session = sessions.get(guid);
        return session != null && (session.initialPending || session.loadingOlder || session.loadingNewer);
    }

    /**
     * Newest message id (service messages included) fetched for the channel, when nothing newer is left
     * to load or show; 0 otherwise. Reading a channel up to it reads nothing the feed has not shown.
     */
    public int getNewestFetchedId(int guid, long dialogId) {
        Session session = sessions.get(guid);
        Source source = session != null ? findSource(session, dialogId) : null;
        if (source == null || !source.newer.exhausted || !source.newer.buffer.isEmpty() || source.newer.inFlight) {
            return 0;
        }
        return source.newer.offsetId;
    }

    /** Whether newer posts than the ones shown may be loaded, e.g. after new posts arrived. */
    public boolean hasNewerPosts(int guid) {
        Session session = sessions.get(guid);
        return session != null && session.state == STATE_READY && hasMore(session, true);
    }

    public int getChannelsCount(int guid) {
        Session session = sessions.get(guid);
        return session == null ? 0 : session.sources.size();
    }

    /** Loads the next page of older posts, if any. The result arrives as {@link NotificationCenter#messagesDidLoad}. */
    public boolean loadMore(int guid, int loadIndex) {
        Session session = sessions.get(guid);
        if (session == null || session.state != STATE_READY || session.initialPending || session.loadingOlder || !hasMore(session, false)) {
            return false;
        }
        session.loadingOlder = true;
        session.olderLoadIndex = loadIndex;
        pump(session);
        return true;
    }

    /** Loads the next page of newer posts (load_type 1), when the feed was opened away from the newest ones. */
    public boolean loadNewer(int guid, int loadIndex) {
        Session session = sessions.get(guid);
        if (session == null || session.state != STATE_READY || session.initialPending || session.loadingNewer || !hasMore(session, true)) {
            return false;
        }
        session.loadingNewer = true;
        session.newerLoadIndex = loadIndex;
        pump(session);
        return true;
    }

    private boolean hasMore(Session session, boolean newer) {
        for (Source source : session.sources) {
            Edge edge = newer ? source.newer : source.older;
            if (!edge.exhausted || !edge.buffer.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int inFlightCount(Session session) {
        int count = 0;
        for (Source source : session.sources) {
            if (source.requestInFlight) count++;
            if (source.older.inFlight) count++;
            if (source.newer.inFlight) count++;
        }
        return count;
    }

    /** Drives the session: launches requests within the parallelism limit and emits pages when ready. */
    private void pump(Session session) {
        if (sessions.get(session.guid) != session) {
            return;
        }
        if (session.state == STATE_PROBING) {
            if (session.probeCacheState != 2) {
                if (session.probeCacheState == 0) {
                    readProbeCache(session);
                }
                return;
            }
            boolean allDone = true;
            for (Source source : session.sources) {
                if (!source.probeDone) {
                    allDone = false;
                    if (!source.requestInFlight && inFlightCount(session) < maxParallel) {
                        probe(session, source);
                    }
                }
            }
            if (!allDone) {
                postProgress(session);
                return;
            }
            int anchor = 0;
            for (Source source : session.sources) {
                if (source.firstUnreadDate > 0 && (anchor == 0 || source.firstUnreadDate < anchor)) {
                    anchor = source.firstUnreadDate;
                }
            }
            if (anchor == 0) {
                // No first unread post could be found: open at the newest posts.
                startAtLatest(session);
            } else {
                session.anchorDate = anchor;
                session.state = STATE_AROUND;
                prepareAround(session);
            }
        }
        if (session.state == STATE_AROUND) {
            if (session.aroundCacheState != 2) {
                if (session.aroundCacheState == 0) {
                    readAroundCache(session);
                }
                return;
            }
            boolean allDone = true;
            for (Source source : session.sources) {
                if (!source.aroundDone) {
                    allDone = false;
                    if (!source.requestInFlight && inFlightCount(session) < maxParallel) {
                        loadAround(session, source);
                    }
                }
            }
            if (!allDone) {
                postProgress(session);
                return;
            }
            session.state = STATE_READY;
            emitInitialAround(session);
            return;
        }
        if (session.loadingOlder) {
            processEdge(session, false);
        }
        if (session.loadingNewer) {
            processEdge(session, true);
        }
    }

    // --- opening: find the oldest first unread post, then load every channel around its time ---

    /**
     * Opening, before any request: finds each unread channel's first unread post in the local database,
     * with the posts around its read pointer. Channels whose cached history covers it need no probe.
     */
    private void readProbeCache(Session session) {
        session.probeCacheState = 1;
        ArrayList<FolderFeedCache.Request> requests = new ArrayList<>();
        final int[] versions = new int[session.sources.size()];
        for (int i = 0; i < session.sources.size(); i++) {
            Source source = session.sources.get(i);
            versions[i] = source.liveVersion;
            if (!source.probeDone) {
                requests.add(new FolderFeedCache.Request(source.dialogId, source.readMax, 0, AROUND_OLDER, AROUND_NEWER));
            }
        }
        if (requests.isEmpty()) {
            session.probeCacheState = 2;
            pump(session);
            return;
        }
        FolderFeedCache.read(currentAccount, requests, windows -> {
            if (sessions.get(session.guid) != session) {
                return;
            }
            for (int i = 0; i < session.sources.size(); i++) {
                Source source = session.sources.get(i);
                ArrayList<TLRPC.Message> window = windows.get(source.dialogId);
                if (source.probeDone || window == null || window.isEmpty()) {
                    continue;
                }
                // Usable when it starts at or before the read pointer (so its first unread message really is
                // the first one) and contains it.
                TLRPC.Message first = null;
                int minId = Integer.MAX_VALUE;
                for (TLRPC.Message message : window) {
                    minId = Math.min(minId, message.id);
                    if (message.id > source.readMax && (first == null || message.id < first.id)) {
                        first = message;
                    }
                }
                if (minId > source.readMax || first == null) {
                    continue;
                }
                source.firstUnreadDate = first.date;
                source.probeWindow = window;
                // The database cannot tell where the channel's history begins; that costs at most one request
                // when scrolling back there.
                source.probeOlderEnd = false;
                // Not trusted for the newest end: for channels without live updates both the cache and the
                // dialog's top message can be stale, so one network page confirms where history ends.
                source.probeNewerEnd = false;
                source.probeLiveVersion = versions[i];
                source.probeDone = true;
            }
            session.probeCacheState = 2;
            pump(session);
        });
    }

    /**
     * Once the opening time is known: reads from the local database the posts around it of every channel
     * still without a starting window. Channels whose cached history covers that time need no request.
     */
    private void readAroundCache(Session session) {
        session.aroundCacheState = 1;
        ArrayList<FolderFeedCache.Request> requests = new ArrayList<>();
        final int[] versions = new int[session.sources.size()];
        for (int i = 0; i < session.sources.size(); i++) {
            Source source = session.sources.get(i);
            versions[i] = source.liveVersion;
            if (!source.aroundDone) {
                requests.add(new FolderFeedCache.Request(source.dialogId, 0, session.anchorDate, AROUND_OLDER, AROUND_NEWER));
            }
        }
        if (requests.isEmpty()) {
            session.aroundCacheState = 2;
            pump(session);
            return;
        }
        FolderFeedCache.read(currentAccount, requests, windows -> {
            if (sessions.get(session.guid) != session) {
                return;
            }
            MessagesController controller = MessagesController.getInstance(currentAccount);
            for (int i = 0; i < session.sources.size(); i++) {
                Source source = session.sources.get(i);
                ArrayList<TLRPC.Message> window = windows.get(source.dialogId);
                if (source.aroundDone || window == null || window.isEmpty()) {
                    continue;
                }
                TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
                boolean hasOlder = false, hasNewer = false;
                int maxId = 0;
                for (TLRPC.Message message : window) {
                    maxId = Math.max(maxId, message.id);
                    if (message.date < session.anchorDate) {
                        hasOlder = true;
                    } else {
                        hasNewer = true;
                    }
                }
                boolean atTop = dialog != null && maxId >= dialog.top_message;
                // Usable when it spans the opening time: posts on both sides of it, or nothing newer exists.
                if (!hasOlder || !hasNewer && !atTop) {
                    continue;
                }
                // As for the probe window, a network page confirms the newest end.
                applyWindow(session, source, window, false, false);
                source.aroundDone = true;
            }
            session.aroundCacheState = 2;
            pump(session);
        });
    }

    /** Saves a page fetched for a channel into the local database, like a channel's own pages. */
    private void saveToCache(Source source, ArrayList<TLRPC.Message> page, int loadType, int maxId) {
        MessagesController controller = MessagesController.getInstance(currentAccount);
        TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
        Integer localReadMax = controller.dialogs_read_inbox_max.get(source.dialogId);
        int readMax = Math.max(dialog != null ? dialog.read_inbox_max_id : 0, localReadMax != null ? localReadMax : 0);
        FolderFeedCache.write(currentAccount, source.dialogId, page, loadType, maxId, readMax);
    }

    private void probe(Session session, Source source) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        // The posts around the read pointer: AROUND_NEWER from the read post on (it may be gone), and
        // AROUND_OLDER before it. Besides the first unread post, this is usually the channel's whole
        // starting window, so it needs no second request once the feed's opening time is known.
        req.offset_id = source.readMax;
        req.add_offset = -AROUND_NEWER;
        req.limit = AROUND_NEWER + AROUND_OLDER;
        final int liveVersion = source.liveVersion;
        source.requestInFlight = true;
        source.requestId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            source.requestInFlight = false;
            if (sessions.get(session.guid) != session) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                putUsersAndChats(res);
                saveToCache(source, res.messages, MessagesController.LOAD_AROUND_DATE, 0);
                // Any unread message, service ones included: the feed only needs a time at or before the
                // channel's first unread post.
                TLRPC.Message first = null;
                ArrayList<TLRPC.Message> window = new ArrayList<>();
                int olderCount = 0, newerCount = 0;
                for (TLRPC.Message message : res.messages) {
                    if (!isFromSource(message, source)) {
                        continue;
                    }
                    window.add(message);
                    if (message.id < source.readMax) {
                        olderCount++;
                    } else {
                        newerCount++;
                    }
                    if (message.id > source.readMax && (first == null || message.id < first.id)) {
                        first = message;
                    }
                }
                source.firstUnreadDate = first != null ? first.date : 0;
                source.probeWindow = window;
                source.probeOlderEnd = olderCount < AROUND_OLDER;
                source.probeNewerEnd = newerCount < AROUND_NEWER;
                source.probeLiveVersion = liveVersion;
                source.probeDone = true;
                source.requestRetries = 0;
                onRequestSucceeded();
            } else if (!scheduleRetry(session, error, ++source.requestRetries, () -> source.requestInFlight = true, () -> source.requestInFlight = false)) {
                source.probeDone = true;
            } else {
                return;
            }
            pump(session);
        }));
    }

    private void loadAround(Session session, Source source) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        // Posts around the anchor time: AROUND_NEWER at or after it, AROUND_OLDER before it.
        req.offset_date = session.anchorDate;
        req.add_offset = -AROUND_NEWER;
        req.limit = AROUND_NEWER + AROUND_OLDER;
        final int liveVersion = source.liveVersion;
        source.requestInFlight = true;
        source.requestId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            source.requestInFlight = false;
            if (sessions.get(session.guid) != session) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                putUsersAndChats(res);
                saveToCache(source, res.messages, MessagesController.LOAD_AROUND_DATE, 0);
                int olderCount = 0, newerCount = 0;
                for (TLRPC.Message message : res.messages) {
                    if (isFromSource(message, source)) {
                        if (message.date >= session.anchorDate) {
                            newerCount++;
                        } else {
                            olderCount++;
                        }
                    }
                }
                applyWindow(session, source, res.messages, olderCount < AROUND_OLDER, newerCount < AROUND_NEWER && source.liveVersion == liveVersion);
                source.aroundDone = true;
                source.requestRetries = 0;
                onRequestSucceeded();
            } else if (!scheduleRetry(session, error, ++source.requestRetries, () -> source.requestInFlight = true, () -> source.requestInFlight = false)) {
                // The channel cannot be read (e.g. it became private): leave it out of the feed.
                source.older.exhausted = true;
                source.newer.exhausted = true;
                setNewerStart(source, null);
                source.aroundDone = true;
            } else {
                return;
            }
            pump(session);
        }));
    }

    /**
     * Decides, once the opening time is known, which channels need a request around it: a channel whose
     * window around its read pointer already covers that time reuses it, and a channel whose last
     * message is older has nothing there, so its posts load only when the feed is scrolled back to them.
     */
    private void prepareAround(Session session) {
        MessagesController controller = MessagesController.getInstance(currentAccount);
        for (Source source : session.sources) {
            ArrayList<TLRPC.Message> window = source.probeWindow;
            source.probeWindow = null;
            if (source.aroundDone) {
                continue;
            }
            if (window != null) {
                int oldestDate = Integer.MAX_VALUE;
                for (TLRPC.Message message : window) {
                    oldestDate = Math.min(oldestDate, message.date);
                }
                if (source.probeOlderEnd || oldestDate < session.anchorDate) {
                    applyWindow(session, source, window, source.probeOlderEnd, source.probeNewerEnd && source.liveVersion == source.probeLiveVersion);
                    source.aroundDone = true;
                    continue;
                }
            }
            TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
            if (dialog != null && dialog.last_message_date > 0 && dialog.last_message_date < session.anchorDate) {
                source.older.offsetId = 0;
                source.older.frontier = dialog.last_message_date;
                source.newer.exhausted = true;
                source.newer.offsetId = dialog.top_message;
                source.newer.frontier = dialog.last_message_date;
                source.aroundDone = true;
            }
        }
    }

    /**
     * Sets a channel's starting state from a contiguous window of its history: posts from the opening
     * time on go to the newer edge, earlier ones to the older edge.
     */
    private void applyWindow(Session session, Source source, ArrayList<TLRPC.Message> messages, boolean olderExhausted, boolean newerExhausted) {
        TLRPC.Message oldestOlder = null, newestNewer = null, newest = null;
        for (TLRPC.Message message : messages) {
            if (!isFromSource(message, source)) {
                continue;
            }
            if (newest == null || message.id > newest.id) newest = message;
            boolean newer = message.date >= session.anchorDate;
            if (newer) {
                if (newestNewer == null || message.id > newestNewer.id) newestNewer = message;
            } else {
                if (oldestOlder == null || message.id < oldestOlder.id) oldestOlder = message;
            }
            if (isPost(message, source) && source.seen.add(message.id)) {
                (newer ? source.newer : source.older).buffer.add(message);
            }
        }
        sortNewestFirst(source.older.buffer);
        sortOldestFirst(source.newer.buffer);
        if (oldestOlder != null) {
            source.older.offsetId = oldestOlder.id;
            source.older.frontier = oldestOlder.date;
        }
        source.older.exhausted = olderExhausted || oldestOlder == null;
        if (newestNewer != null) {
            source.newer.offsetId = newestNewer.id;
            source.newer.frontier = newestNewer.date;
        } else {
            // Nothing at or after the opening time yet: newer posts, once they arrive, follow the newest one.
            setNewerStart(source, newest);
        }
        source.newer.exhausted = newerExhausted;
    }

    private void postProgress(Session session) {
        int done = 0;
        for (Source source : session.sources) {
            if (source.aroundDone || session.state == STATE_PROBING && source.probeDone && source.hasUnread()) {
                done++;
            }
        }
        if (done == session.progressPosted) {
            return;
        }
        session.progressPosted = done;
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedProgress, session.guid, done, session.sources.size());
    }

    private void setNewerStart(Source source, TLRPC.Message newest) {
        if (newest != null) {
            source.newer.offsetId = newest.id;
            source.newer.frontier = newest.date;
        } else {
            TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(source.dialogId);
            source.newer.offsetId = dialog != null ? dialog.top_message : 0;
            source.newer.frontier = dialog != null ? dialog.last_message_date : 0;
        }
    }

    private void emitInitialAround(Session session) {
        ArrayList<TLRPC.Message> newer = collectReady(session, true);
        ArrayList<TLRPC.Message> older = collectReady(session, false);
        ArrayList<TLRPC.Message> all = new ArrayList<>(newer.size() + older.size());
        all.addAll(newer);
        all.addAll(older);
        // Newest first; ids grow outwards from ID_BASE: this page takes ID_BASE..ID_BASE+n-1.
        int n = all.size();
        session.nextOlderId = ID_BASE - 1;
        session.nextNewerId = ID_BASE + n;
        ArrayList<MessageObject> messageObjects = new ArrayList<>(n);
        int firstUnreadId = 0;
        int restoreId = 0;
        boolean restoreExact = false;
        for (int i = 0; i < n; i++) {
            TLRPC.Message message = all.get(i);
            MessageObject messageObject = toMessageObject(message, ID_BASE + n - 1 - i);
            messageObjects.add(messageObject);
            long dialogId = MessageObject.getDialogId(message);
            Source source = findSource(session, dialogId);
            if (session.restoreDialogId != 0) {
                // The saved post, or if it is gone, the oldest post at or after its time.
                if (dialogId == session.restoreDialogId && message.realId == session.restoreMessageId) {
                    restoreId = message.id;
                    restoreExact = true;
                } else if (!restoreExact && message.date >= session.anchorDate) {
                    restoreId = message.id;
                }
            } else if (source != null && source.hasUnread() && message.realId > source.readMax) {
                firstUnreadId = message.id;
            }
        }
        final int restoreMessageId = restoreId;
        session.initialPending = false;
        boolean olderEnd = !hasMore(session, false);
        boolean newerEnd = !hasMore(session, true);
        final int fnid = firstUnreadId;
        final int loadIndex = session.initialLoadIndex;
        AndroidUtilities.runOnUIThread(() -> {
            if (sessions.get(session.guid) != session) {
                return;
            }
            // first_unread_id makes ChatActivity open at that post with the "Unread messages" divider; a
            // non-zero last_message_id that matches no post tells it newer posts remain to be loaded.
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesDidLoad,
                    0L, messageObjects.size(), messageObjects, false, fnid, newerEnd ? 0 : Integer.MIN_VALUE, 0, 0, 2, olderEnd,
                    session.guid, loadIndex, 0, 0, ChatActivity.MODE_FOLDER_FEED);
            // A restored position is applied by ChatActivity before the first layout (args[4]).
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedLoaded,
                    session.guid, olderEnd, LOADED_INITIAL, newerEnd, restoreMessageId);
        });
    }

    // --- paging on either edge: merge by date with per-channel frontiers ---

    private void processEdge(Session session, boolean newer) {
        ArrayList<TLRPC.Message> ready = collectReady(session, newer);
        if (ready.size() >= PAGE_SIZE || !anyEdgeLeft(session, newer)) {
            emitEdge(session, newer, ready);
            return;
        }
        // Not enough posts for a page yet: keep them buffered and move the watermark by fetching the
        // channels whose frontiers hold the merge back (newest frontier for older pages, oldest for newer).
        restoreToBuffers(session, ready, newer);
        ArrayList<Source> candidates = new ArrayList<>();
        boolean edgeInFlight = false;
        for (Source source : session.sources) {
            Edge edge = newer ? source.newer : source.older;
            if (edge.inFlight) {
                edgeInFlight = true;
            } else if (!edge.exhausted) {
                candidates.add(source);
            }
        }
        if (newer) {
            Collections.sort(candidates, (a, b) -> Integer.compare(a.newer.frontier, b.newer.frontier));
        } else {
            Collections.sort(candidates, (a, b) -> Integer.compare(b.older.frontier, a.older.frontier));
        }
        for (int i = 0; i < candidates.size() && inFlightCount(session) < maxParallel; i++) {
            fetchEdge(session, candidates.get(i), newer);
            edgeInFlight = true;
        }
        if (!edgeInFlight && candidates.isEmpty()) {
            emitEdge(session, newer, collectReady(session, newer));
        }
        // Otherwise wait: pump() runs again whenever a request completes.
    }

    private boolean anyEdgeLeft(Session session, boolean newer) {
        for (Source source : session.sources) {
            if (!(newer ? source.newer : source.older).exhausted) {
                return true;
            }
        }
        return false;
    }

    private void fetchEdge(Session session, Source source, boolean newer) {
        Edge edge = newer ? source.newer : source.older;
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        req.offset_id = edge.offsetId;
        final int requestOffsetId = edge.offsetId;
        if (newer) {
            // The posts right after offset_id. As for the probe, the window also covers offset_id itself
            // and the post before it (filtered out), whether or not the offset post still exists.
            req.add_offset = -FETCH_LIMIT;
            req.limit = FETCH_LIMIT + 1;
        } else {
            req.limit = FETCH_LIMIT;
        }
        final int liveVersion = source.liveVersion;
        edge.inFlight = true;
        edge.reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            edge.inFlight = false;
            if (sessions.get(session.guid) != session) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                putUsersAndChats(res);
                if (newer && requestOffsetId != 0) {
                    saveToCache(source, res.messages, MessagesController.LOAD_FORWARD, requestOffsetId);
                } else if (!newer && requestOffsetId != 0) {
                    // The page holds only posts below the offset (no add_offset), while putMessages treats
                    // max_id as included: pass the id right below it, so the offset post's hole stays open
                    // when it is not cached.
                    saveToCache(source, res.messages, MessagesController.LOAD_BACKWARD, requestOffsetId - 1);
                } else {
                    saveToCache(source, res.messages, MessagesController.LOAD_AROUND_DATE, 0);
                }
                TLRPC.Message outermost = null;
                int count = 0;
                for (TLRPC.Message message : res.messages) {
                    if (!isFromSource(message, source)) {
                        continue;
                    }
                    if (newer ? message.id <= edge.offsetId : (edge.offsetId != 0 && message.id >= edge.offsetId)) {
                        continue;
                    }
                    count++;
                    if (outermost == null || (newer ? message.id > outermost.id : message.id < outermost.id)) {
                        outermost = message;
                    }
                    if (isPost(message, source) && source.seen.add(message.id)) {
                        edge.buffer.add(message);
                    }
                }
                if (newer) {
                    sortOldestFirst(edge.buffer);
                } else {
                    sortNewestFirst(edge.buffer);
                }
                edge.retries = 0;
                onRequestSucceeded();
                if (outermost == null) {
                    edge.exhausted = true;
                } else {
                    edge.offsetId = outermost.id;
                    edge.frontier = outermost.date;
                    // A newer window holds FETCH_LIMIT - 1 new posts when the offset post still exists.
                    if (count < (newer ? FETCH_LIMIT - 1 : FETCH_LIMIT)) {
                        edge.exhausted = true;
                    }
                }
                if (newer && source.liveVersion != liveVersion) {
                    // A post arrived after this response was built: there is more to load.
                    edge.exhausted = false;
                }
            } else if (!scheduleRetry(session, error, ++edge.retries, () -> edge.inFlight = true, () -> edge.inFlight = false)) {
                edge.exhausted = true;
            } else {
                return;
            }
            pump(session);
        }));
    }

    /** Removes and returns every buffered post of the given edge that can be shown now, newest first. */
    private ArrayList<TLRPC.Message> collectReady(Session session, boolean newer) {
        // Older edge: no channel can have unfetched posts newer than the highest older frontier.
        // Newer edge: no channel can have unfetched posts older than the lowest newer frontier.
        int watermark = newer ? Integer.MAX_VALUE : 0;
        for (Source source : session.sources) {
            Edge edge = newer ? source.newer : source.older;
            if (!edge.exhausted) {
                watermark = newer ? Math.min(watermark, edge.frontier) : Math.max(watermark, edge.frontier);
            }
        }
        ArrayList<TLRPC.Message> ready = new ArrayList<>();
        for (Source source : session.sources) {
            Iterator<TLRPC.Message> iterator = (newer ? source.newer : source.older).buffer.iterator();
            while (iterator.hasNext()) {
                TLRPC.Message message = iterator.next();
                if (newer ? message.date <= watermark : message.date >= watermark) {
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

    private void restoreToBuffers(Session session, ArrayList<TLRPC.Message> messages, boolean newer) {
        for (TLRPC.Message message : messages) {
            Source source = findSource(session, MessageObject.getDialogId(message));
            if (source != null) {
                (newer ? source.newer : source.older).buffer.add(message);
            }
        }
        for (Source source : session.sources) {
            if (newer) {
                sortOldestFirst(source.newer.buffer);
            } else {
                sortNewestFirst(source.older.buffer);
            }
        }
    }

    private void emitEdge(Session session, boolean newer, ArrayList<TLRPC.Message> ready) {
        // ready is newest first. Older pages continue downwards from nextOlderId, newer pages upwards.
        int n = ready.size();
        ArrayList<MessageObject> messageObjects = new ArrayList<>(n);
        int firstId = newer ? session.nextNewerId + n - 1 : session.nextOlderId;
        for (int i = 0; i < n; i++) {
            messageObjects.add(toMessageObject(ready.get(i), firstId - i));
        }
        if (newer) {
            session.nextNewerId += n;
            session.loadingNewer = false;
        } else {
            session.nextOlderId -= n;
            session.loadingOlder = false;
        }
        final boolean wasInitial = session.initialPending;
        session.initialPending = false;
        final boolean olderEnd = !hasMore(session, false);
        final boolean newerEnd = !hasMore(session, true);
        final int loadIndex = newer ? session.newerLoadIndex : session.olderLoadIndex;
        AndroidUtilities.runOnUIThread(() -> {
            if (sessions.get(session.guid) != session) {
                return;
            }
            if (newer) {
                // load_type 1: a count different from the page size tells ChatActivity the newest post was reached.
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesDidLoad,
                        0L, newerEnd ? messageObjects.size() + 1 : messageObjects.size(), messageObjects, false, 0, 0, 0, 0, 1, false,
                        session.guid, loadIndex, 0, 0, ChatActivity.MODE_FOLDER_FEED);
            } else {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesDidLoad,
                        0L, messageObjects.size(), messageObjects, false, 0, 0, 0, 0, 2, olderEnd,
                        session.guid, loadIndex, 0, 0, ChatActivity.MODE_FOLDER_FEED);
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedLoaded,
                    session.guid, olderEnd, newer ? LOADED_NEWER : (wasInitial ? LOADED_INITIAL : LOADED_OLDER), newerEnd, 0);
        });
    }

    // --- helpers ---

    private MessageObject toMessageObject(TLRPC.Message message, int syntheticId) {
        // SEARCH_PUBLIC_POSTS makes the cell draw the channel avatar and the "go to post" side button.
        MessageObject messageObject = new MessageObject(currentAccount, message, null, null, null, null, null, true, true, 0, false, false, false, ChatActivity.SEARCH_PUBLIC_POSTS);
        messageObject.isFolderFeedMessage = true;
        message.realId = message.id;
        message.id = syntheticId;
        return messageObject;
    }

    private static boolean isFromSource(TLRPC.Message message, Source source) {
        return message != null && !(message instanceof TLRPC.TL_messageEmpty) && MessageObject.getDialogId(message) == source.dialogId;
    }

    /** A post shown in the feed: from this channel and not a service message. */
    private static boolean isPost(TLRPC.Message message, Source source) {
        return isFromSource(message, source) && !(message instanceof TLRPC.TL_messageService);
    }

    private void putUsersAndChats(TLRPC.messages_Messages res) {
        MessagesController.getInstance(currentAccount).putUsers(res.users, false);
        MessagesController.getInstance(currentAccount).putChats(res.chats, false);
        MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
    }

    /**
     * Schedules a retry of a failed request, unless the error is permanent (e.g. the channel became
     * private). Transient failures are retried for as long as the feed is open, with a growing delay or
     * the wait the server asks for; a FLOOD_WAIT also lowers the parallelism for the rest of the
     * controller's life. Returns false when the request should be given up.
     */
    private boolean scheduleRetry(Session session, TLRPC.TL_error error, int attempt, Runnable markInFlight, Runnable clearInFlight) {
        FileLog.e("FolderFeed: getHistory failed: " + (error != null ? error.code + " " + error.text : "unexpected response"));
        if (error == null || error.code == 400 || error.code == 403 || error.code == 406) {
            return false;
        }
        int delay = Math.min(1000 << Math.min(attempt - 1, 5), 30_000);
        if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
            maxParallel = FLOOD_PARALLEL_REQUESTS;
            try {
                delay = Math.max(delay, Integer.parseInt(error.text.substring("FLOOD_WAIT_".length())) * 1000 + 500);
            } catch (NumberFormatException ignore) {
            }
            floodCalmUntil = SystemClock.elapsedRealtime() + delay + FLOOD_CALM_MS;
        }
        // Keep the slot occupied while waiting, so pump() does not start the same request again.
        markInFlight.run();
        AndroidUtilities.runOnUIThread(() -> {
            clearInFlight.run();
            pump(session);
        }, delay);
        return true;
    }

    /** Raises the parallelism back step by step once some time passed without a FLOOD_WAIT. */
    private void onRequestSucceeded() {
        if (maxParallel < MAX_PARALLEL_REQUESTS && SystemClock.elapsedRealtime() > floodCalmUntil) {
            maxParallel++;
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

    private static void sortOldestFirst(ArrayList<TLRPC.Message> messages) {
        sortNewestFirst(messages);
        Collections.reverse(messages);
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
                    Source source = findSource(session, dialogId);
                    if (source != null) {
                        source.liveVersion++;
                        if (source.newer.offsetId == 0 && (source.aroundDone || session.state == STATE_READY)) {
                            // Nothing was known of the channel: its history starts right before the new posts.
                            int minId = Integer.MAX_VALUE, minDate = Integer.MAX_VALUE;
                            for (MessageObject messageObject : messages) {
                                if (messageObject != null && messageObject.getId() > 0) {
                                    minId = Math.min(minId, messageObject.getId());
                                    minDate = Math.min(minDate, messageObject.messageOwner.date);
                                }
                            }
                            if (minId != Integer.MAX_VALUE) {
                                source.newer.offsetId = minId - 1;
                                source.newer.frontier = minDate;
                            }
                        }
                        if (source.newer.offsetId != 0) {
                            // The new posts arrive through forward paging, like scrolling down to newer posts.
                            source.newer.exhausted = false;
                        }
                    }
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedNewPosts, session.guid, count);
                }
            }
        }
    }

    // endregion
}
