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
import android.util.LongSparseArray;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import uz.unnarsx.cherrygram.chats.feed.FolderFeedMerge.Edge;
import uz.unnarsx.cherrygram.chats.feed.FolderFeedMerge.Slot;
import uz.unnarsx.cherrygram.core.configs.CherrygramAppearanceConfig;
import uz.unnarsx.cherrygram.core.configs.CherrygramChatsConfig;

/**
 * Loads the posts of every broadcast channel in a chat folder and merges them into a single
 * time-ordered stream that {@link ChatActivity} renders in {@link ChatActivity#MODE_FOLDER_FEED}.
 * <p>
 * Telegram has no server method returning the history of a folder, so each channel's history is
 * paged separately with {@code messages.getHistory} and merged on the client. Like a channel, the feed
 * opens at a saved position or else at the oldest unread post across its channels: the posts around
 * each unread channel's read pointer give its first unread post, and every channel is then loaded
 * around the time of the oldest one. From there the feed pages in both directions. Pages come from the
 * local message database when it covers them (see {@link FolderFeedCache}) and from the network
 * otherwise.
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

    // Page sizes and request limits: see FolderFeedMerge (PAGE_SIZE, MAX_PAGE_SIZE, FETCH_LIMIT, AROUND_*).
    /**
     * getHistory requests in flight at once. Telegram has no multi-chat history method, but parallel
     * requests share MTProto containers, so a few in flight cost little; after a FLOOD_WAIT the
     * controller falls back to a gentle limit and recovers once the server stops asking to wait.
     */
    private static final int MAX_PARALLEL_REQUESTS = 8;
    private static final int FLOOD_PARALLEL_REQUESTS = 2;
    private static final long FLOOD_CALM_MS = 30_000;
    /** Backstop for folder changes that are not announced by a notification the cache listens to. */
    private static final long CHANNELS_CACHE_MS = 5_000;

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
    private boolean observingNewMessages;
    private int maxParallel = MAX_PARALLEL_REQUESTS;
    /** After a FLOOD_WAIT the parallelism stays low until this time (elapsedRealtime), then recovers step by step. */
    private long floodCalmUntil;
    /** No new request is started before this time (elapsedRealtime): the server asked to wait. */
    private long floodPauseUntil;
    /** Loading the rest of the chat list (main list / archive) for the feed. */
    private boolean wantMainDialogs;
    private boolean wantArchivedDialogs;

    private FolderFeedController(int account) {
        currentAccount = account;
        // Folder membership changes: drop the cached channel lists (all callers are on the UI thread).
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogFiltersUpdated);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogsNeedReload);
    }

    // region Folder helpers

    public static boolean isFeatureEnabled() {
        return CherrygramChatsConfig.INSTANCE.getFolderChannelsFeed();
    }

    private static class CachedChannels {
        final List<TLRPC.Chat> channels;
        final int dialogsCount;
        final long time;

        CachedChannels(List<TLRPC.Chat> channels, int dialogsCount, long time) {
            this.channels = channels;
            this.dialogsCount = dialogsCount;
            this.time = time;
        }
    }

    /** Channel lists by folder id. The dialogs list and its rows ask often; computing one walks all dialogs. */
    private final SparseArray<CachedChannels> channelsCache = new SparseArray<>();

    /** Broadcast channels (not groups) that belong to the folder, in dialogs-list order. Read-only. */
    public static List<TLRPC.Chat> getFeedChannels(int account, @Nullable MessagesController.DialogFilter filter) {
        if (filter == null || filter.isDefault()) {
            return Collections.emptyList();
        }
        FolderFeedController controller = getInstance(account);
        int dialogsCount = MessagesController.getInstance(account).getAllDialogs().size();
        long now = SystemClock.elapsedRealtime();
        CachedChannels cached = controller.channelsCache.get(filter.id);
        if (cached != null && cached.dialogsCount == dialogsCount && now - cached.time < CHANNELS_CACHE_MS) {
            return cached.channels;
        }
        List<TLRPC.Chat> channels = Collections.unmodifiableList(computeFeedChannels(account, filter));
        controller.channelsCache.put(filter.id, new CachedChannels(channels, dialogsCount, now));
        return channels;
    }

    private static ArrayList<TLRPC.Chat> computeFeedChannels(int account, MessagesController.DialogFilter filter) {
        ArrayList<TLRPC.Chat> result = new ArrayList<>();
        AccountInstance accountInstance = AccountInstance.getInstance(account);
        MessagesController controller = accountInstance.getMessagesController();
        HashSet<Long> added = new HashSet<>();
        for (TLRPC.Dialog dialog : new ArrayList<>(controller.getAllDialogs())) {
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
     * The channel's read pointer as known locally. Reading updates dialogs_read_inbox_max (and the
     * unread count) right away, while dialog.read_inbox_max_id catches up later, so both are needed.
     */
    public static int getReadMax(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
        Integer localReadMax = controller.dialogs_read_inbox_max.get(dialogId);
        return Math.max(dialog != null ? dialog.read_inbox_max_id : 0, localReadMax != null ? localReadMax : 0);
    }

    @Nullable
    public static MessagesController.DialogFilter findFilter(int account, int filterId) {
        return MessagesController.getInstance(account).dialogFiltersById.get(filterId);
    }

    // endregion

    // region Sessions

    /**
     * The merge and paging decisions live in {@link FolderFeedMerge} (plain Java, unit-tested on the JVM);
     * this is how it reads a {@link TLRPC.Message}.
     */
    static final FolderFeedMerge.Posts<TLRPC.Message> TL_POSTS = new FolderFeedMerge.Posts<TLRPC.Message>() {
        @Override
        public int id(TLRPC.Message message) {
            return message.id;
        }

        @Override
        public int date(TLRPC.Message message) {
            return message.date;
        }

        @Override
        public long dialogId(TLRPC.Message message) {
            return MessageObject.getDialogId(message);
        }

        @Override
        public boolean isEmpty(TLRPC.Message message) {
            return message instanceof TLRPC.TL_messageEmpty;
        }

        @Override
        public boolean isService(TLRPC.Message message) {
            return message instanceof TLRPC.TL_messageService;
        }
    };

    private static final FolderFeedMerge<TLRPC.Message> MERGE = new FolderFeedMerge<>(TL_POSTS);

    /** A channel of the feed: its paging state ({@link FolderFeedMerge.Channel}) plus what the requests need. */
    private static class Source extends FolderFeedMerge.Channel<TLRPC.Message> {
        final TLRPC.InputPeer peer;
        /** The opening request (window around the read pointer or around the opening point). */
        final Slot opening = new Slot();
        /** Opening, for the open stats: the local cache was asked for this channel, had posts of it,
         *  and gave a usable window. */
        boolean cacheAsked;
        boolean cacheHadPosts;
        boolean fromCache;
        boolean fromMemory;
        /** Why the last cached window was not used (CACHE_MISS_*), for the open stats. */
        int cacheMiss;

        Source(long dialogId, TLRPC.InputPeer peer) {
            super(dialogId);
            this.peer = peer;
        }
    }

    /** The channel's top as the dialogs list knows it now. */
    private FolderFeedMerge.DialogTop dialogTop(long dialogId) {
        TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId);
        return dialog != null ? new FolderFeedMerge.DialogTop(true, dialog.top_message, dialog.last_message_date) : FolderFeedMerge.DialogTop.UNKNOWN;
    }

    private static final int STATE_PROBING = 0;
    private static final int STATE_AROUND = 1;
    private static final int STATE_READY = 2;
    /** Waiting for the chat list to be complete before the folder's channels are known. */
    private static final int STATE_WAITING_FOR_DIALOGS = 3;
    /** A feed opens with the channels known by then even if the chat list is still loading. */
    private static final long DIALOGS_WAIT_MS = 15_000;

    private static final int CACHE_NOT_READ = 0;
    private static final int CACHE_READING = 1;
    private static final int CACHE_DONE = 2;

    /** Direction reported with {@link NotificationCenter#cgFolderFeedLoaded}. */
    public static final int LOADED_INITIAL = 0;
    public static final int LOADED_OLDER = 1;
    public static final int LOADED_NEWER = 2;

    private static class Session {
        final int guid;
        int filterId;
        boolean atLatest;
        int restoreDate;
        long dialogsWaitStarted;
        final ArrayList<Source> sources = new ArrayList<>();
        final LongSparseArray<Source> sourcesById = new LongSparseArray<>();
        int state;
        /** Date the feed opens at: the oldest first-unread post across the channels, or a saved position. */
        int anchorDate;
        /** Saved position to restore (channel and real id of the post), or 0 to open at the first unread. */
        long restoreDialogId;
        int restoreMessageId;
        /** Synthetic ids of the shown posts (see {@link FolderFeedMerge.SyntheticIds}). */
        final FolderFeedMerge.SyntheticIds ids = new FolderFeedMerge.SyntheticIds();
        boolean initialPending = true;
        int initialLoadIndex;
        boolean loadingOlder;
        int olderLoadIndex;
        boolean loadingNewer;
        int newerLoadIndex;
        int progressPosted = -1;
        /** Local database reads before the network. */
        int probeCacheState = CACHE_NOT_READ;
        int aroundCacheState = CACHE_NOT_READ;
        /** Opening at the newest posts: the channels' newest cached posts are read first. */
        int latestCacheState = CACHE_DONE;
        /** How the opening went, for {@link #getOpenStats(int)}. */
        final long startTime = SystemClock.elapsedRealtime();
        int requestsSent;
        int responses;
        long responseTimeTotal;
        int maxInFlight;
        boolean resumeScheduled;
        int floodWaits;
        int floodWaitSeconds;
        String openStats;

        Session(int guid) {
            this.guid = guid;
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
        Session session = new Session(guid);
        session.initialLoadIndex = loadIndex;
        session.filterId = filterId;
        session.atLatest = atLatest;
        session.restoreDialogId = restoreDialogId;
        session.restoreMessageId = restoreMessageId;
        session.restoreDate = restoreDate;
        sessions.put(guid, session);
        updateObservingNewMessages();
        MessagesController.DialogFilter filter = findFilter(currentAccount, filterId);
        if (!areAllDialogsLoaded(currentAccount, filter)) {
            // The chat list is loaded in pages (archived chats separately): the folder's channels are only
            // all known once it is complete.
            session.state = STATE_WAITING_FOR_DIALOGS;
            session.dialogsWaitStarted = SystemClock.elapsedRealtime();
            requestAllDialogs(filter);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedProgress, guid, -1, 0);
            AndroidUtilities.runOnUIThread(() -> beginWhenDialogsLoaded(session), DIALOGS_WAIT_MS);
            return;
        }
        begin(session);
    }

    /** Starts a session that waited for the chat list, once it is complete (or after DIALOGS_WAIT_MS anyway). */
    private void beginWhenDialogsLoaded(Session session) {
        if (sessions.get(session.guid) != session || session.state != STATE_WAITING_FOR_DIALOGS) {
            return;
        }
        boolean timedOut = SystemClock.elapsedRealtime() - session.dialogsWaitStarted >= DIALOGS_WAIT_MS;
        if (timedOut || areAllDialogsLoaded(currentAccount, findFilter(currentAccount, session.filterId))) {
            begin(session);
        }
    }

    private void begin(Session session) {
        if (SystemClock.elapsedRealtime() > floodCalmUntil) {
            // An earlier FLOOD_WAIT is long over: no reason to open this feed slowly.
            maxParallel = MAX_PARALLEL_REQUESTS;
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);
        boolean anyUnread = false;
        for (TLRPC.Chat chat : getFeedChannels(currentAccount, findFilter(currentAccount, session.filterId))) {
            long dialogId = -chat.id;
            TLRPC.InputPeer peer = controller.getInputPeer(dialogId);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                continue;
            }
            Source source = new Source(dialogId, peer);
            TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog != null) {
                source.readMax = getReadMax(currentAccount, dialogId);
                source.unreadCount = dialog.unread_count;
            }
            anyUnread |= source.hasUnread();
            session.sources.add(source);
            session.sourcesById.put(dialogId, source);
        }
        if (!session.atLatest && session.restoreDate > 0 && session.sourcesById.get(session.restoreDialogId) != null) {
            session.anchorDate = session.restoreDate;
            session.state = STATE_AROUND;
            prepareAround(session);
        } else if (session.atLatest || !anyUnread) {
            session.restoreDialogId = 0;
            startAtLatest(session);
        } else {
            session.restoreDialogId = 0;
            session.state = STATE_PROBING;
            for (Source source : session.sources) {
                source.probeDone = !source.hasUnread();
            }
        }
        pump(session);
    }

    /** Whether the chat list is complete for the folder: the main list, and the archive if the folder includes it. */
    public static boolean areAllDialogsLoaded(int account, @Nullable MessagesController.DialogFilter filter) {
        MessagesController controller = MessagesController.getInstance(account);
        return isFolderLoaded(controller, 0) && (filter == null || (filter.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) != 0 || isFolderLoaded(controller, 1));
    }

    private static boolean isFolderLoaded(MessagesController controller, int folderId) {
        return controller.isDialogsEndReached(folderId) && controller.isServerDialogsEndReached(folderId);
    }

    /**
     * Loads the rest of the chat list page by page (from the local cache, then the server), and the archive
     * when the folder includes archived chats, so the feed and its entry points see every channel of the
     * folder. The dialogs list does the same when it is scrolled to the end; each loaded page triggers the
     * next one here.
     */
    public void requestAllDialogs(@Nullable MessagesController.DialogFilter filter) {
        if (filter == null || filter.isDefault() || !isFeatureEnabled()) {
            return;
        }
        wantMainDialogs = true;
        if ((filter.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) == 0) {
            wantArchivedDialogs = true;
        }
        continueLoadingDialogs();
    }

    private void continueLoadingDialogs() {
        MessagesController controller = MessagesController.getInstance(currentAccount);
        if (wantMainDialogs && !loadMoreDialogs(controller, 0)) {
            wantMainDialogs = false;
        }
        if (wantArchivedDialogs && !loadMoreDialogs(controller, 1)) {
            wantArchivedDialogs = false;
        }
    }

    /** Requests the next page of the folder's chat list; returns false once it is complete. */
    private static boolean loadMoreDialogs(MessagesController controller, int folderId) {
        if (isFolderLoaded(controller, folderId)) {
            return false;
        }
        if (!controller.isLoadingDialogs(folderId)) {
            controller.loadDialogs(folderId, -1, 100, !controller.isDialogsEndReached(folderId));
        }
        return true;
    }

    /** Opens at the newest posts: only the older edge loads, the newer edge is at the live end. */
    private void startAtLatest(Session session) {
        session.state = STATE_READY;
        session.latestCacheState = CACHE_NOT_READ;
        for (Source source : session.sources) {
            initAtDialogTop(source);
        }
        session.loadingOlder = true;
        session.olderLoadIndex = session.initialLoadIndex;
    }

    /**
     * Places the channel at the top of its history as known from the dialogs list: nothing newer to load,
     * the older edge starts from the newest post.
     */
    private void initAtDialogTop(Source source) {
        MERGE.initAtDialogTop(source, dialogTop(source.dialogId));
    }

    public void stop(int guid) {
        Session session = sessions.get(guid);
        if (session == null) {
            return;
        }
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
        for (Source source : session.sources) {
            for (Slot slot : new Slot[]{source.opening, source.older, source.newer}) {
                if (slot.inFlight) {
                    connectionsManager.cancelRequest(slot.reqId, false);
                }
            }
        }
        sessions.remove(guid);
        updateObservingNewMessages();
    }

    private void recordOpenStats(Session session) {
        float seconds = (SystemClock.elapsedRealtime() - session.startTime) / 1000f;
        int fromCache = 0, fromMemory = 0, nothingCached = 0, noEarlier = 0, noLater = 0;
        for (Source source : session.sources) {
            if (source.fromCache) {
                fromCache++;
                if (source.fromMemory) {
                    fromMemory++;
                }
            } else if (source.cacheAsked) {
                if (!source.cacheHadPosts) {
                    nothingCached++;
                } else if (source.cacheMiss == CACHE_MISS_NO_EARLIER) {
                    noEarlier++;
                } else {
                    noLater++;
                }
            }
        }
        float averageRequest = session.responses > 0 ? session.responseTimeTotal / 1000f / session.responses : 0;
        session.openStats = LocaleController.formatString(R.string.CG_FolderFeed_OpenStats, seconds, fromCache,
                session.sources.size(), fromMemory, nothingCached, noEarlier, noLater, session.requestsSent, averageRequest,
                session.maxInFlight, session.floodWaits, session.floodWaitSeconds);
        FileLog.d("FolderFeed: " + session.openStats);
    }

    /**
     * How the feed shown by the ChatActivity with the given classGuid opened (time, channels read from
     * the local cache, network requests, flood waits), or null while it is still opening.
     */
    @Nullable
    public String getOpenStats(int guid) {
        Session session = sessions.get(guid);
        return session != null ? session.openStats : null;
    }

    /** Whether the channel is part of the feed shown by the ChatActivity with the given classGuid. */
    public boolean hasChannel(int guid, long dialogId) {
        Session session = sessions.get(guid);
        return session != null && session.sourcesById.get(dialogId) != null;
    }

    /**
     * Unread posts across the feed's channels as counted by the server (loaded or not), muted channels
     * included, like a channel's own "next unread" counter.
     */
    public int getUnreadCount(int guid) {
        Session session = sessions.get(guid);
        if (session == null) {
            return 0;
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);
        int count = 0;
        for (Source source : session.sources) {
            TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
            if (dialog != null && dialog.unread_count > 0) {
                count += dialog.unread_count;
            }
        }
        return count;
    }

    /**
     * The newest message id (service messages included) the feed knows for the channel when nothing
     * newer is left to load or show, else 0: reading the channel up to it reads nothing the feed has not
     * shown. For a channel set up from the dialogs list (opened at the newest posts) it is the dialog's
     * top message.
     */
    public int getNewestFetchedId(int guid, long dialogId) {
        Session session = sessions.get(guid);
        Source source = session != null ? session.sourcesById.get(dialogId) : null;
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

    private static boolean hasMore(Session session, boolean newer) {
        return FolderFeedMerge.hasMore(session.sources, newer);
    }

    private static int inFlightCount(Session session) {
        int count = 0;
        for (Source source : session.sources) {
            if (source.opening.inFlight) count++;
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
            if (session.probeCacheState != CACHE_DONE) {
                if (session.probeCacheState == CACHE_NOT_READ) {
                    readProbeCache(session);
                }
                return;
            }
            if (!runOpeningRequests(session, false)) {
                return;
            }
            int anchor = FolderFeedMerge.anchorFromProbes(session.sources);
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
            if (session.aroundCacheState != CACHE_DONE) {
                if (session.aroundCacheState == CACHE_NOT_READ) {
                    readAroundCache(session);
                }
                return;
            }
            if (!runOpeningRequests(session, true)) {
                return;
            }
            session.state = STATE_READY;
            emitInitialAround(session);
            return;
        }
        if (session.latestCacheState != CACHE_DONE) {
            if (session.latestCacheState == CACHE_NOT_READ) {
                readLatestCache(session);
            }
            return;
        }
        if (session.loadingOlder) {
            processEdge(session, false);
        }
        if (session.loadingNewer) {
            processEdge(session, true);
        }
    }

    /**
     * Starts the opening requests still needed (windows around the read pointers, or around the opening
     * point) within the parallelism limit. Returns true once every channel has its window.
     */
    private boolean runOpeningRequests(Session session, boolean around) {
        boolean allDone = true;
        int inFlight = inFlightCount(session);
        for (Source source : session.sources) {
            if (around ? source.aroundDone : source.probeDone) {
                continue;
            }
            allDone = false;
            if (!source.opening.inFlight && inFlight < maxParallel && !isFloodPaused(session)) {
                if (around) {
                    loadAround(session, source);
                } else {
                    probe(session, source);
                }
                inFlight++;
            }
        }
        if (!allDone) {
            postProgress(session);
        }
        return allDone;
    }

    // --- opening: find the opening point, then load every channel around it ---

    /**
     * Opening, before any request: finds each unread channel's first unread post in the local database,
     * with the posts around its read pointer. Channels whose cached history covers it need no request.
     */
    private void readProbeCache(Session session) {
        ArrayList<FolderFeedCache.Request> requests = new ArrayList<>();
        for (Source source : session.sources) {
            if (!source.probeDone) {
                requests.add(new FolderFeedCache.Request(source.dialogId, source.readMax, 0, FolderFeedMerge.AROUND_OLDER, FolderFeedMerge.AROUND_NEWER));
            }
        }
        readCache(session, requests, CACHE_KIND_PROBE, (source, window, liveVersion) -> {
            FolderFeedMerge.WindowScan<TLRPC.Message> scan = MERGE.scanWindow(window, source, source.readMax, 0);
            // Usable when it starts at or before the read pointer (so its first unread post really is
            // the first one) and contains it.
            int verdict = FolderFeedMerge.probeCacheVerdict(scan, source.readMax);
            if (verdict != FolderFeedMerge.CACHE_USABLE) {
                source.cacheMiss = verdict;
                return false;
            }
            // Neither end is taken from the database: it cannot tell where history begins (one request
            // when scrolling back there), and for channels without live updates both the cache and the
            // dialog's top message can be stale, so a network page confirms the newest end.
            MERGE.setProbeResult(source, scan, false, false, liveVersion);
            return true;
        });
    }

    /**
     * Once the opening point is known: reads from the local database the posts around it of every channel
     * still without a starting window. Channels whose cached history covers it need no request.
     */
    private void readAroundCache(Session session) {
        ArrayList<FolderFeedCache.Request> requests = new ArrayList<>();
        for (Source source : session.sources) {
            if (!source.aroundDone) {
                requests.add(new FolderFeedCache.Request(source.dialogId, 0, session.anchorDate, FolderFeedMerge.AROUND_OLDER, FolderFeedMerge.AROUND_NEWER));
            }
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);
        readCache(session, requests, CACHE_KIND_AROUND, (source, window, liveVersion) -> {
            FolderFeedMerge.WindowScan<TLRPC.Message> scan = MERGE.scanWindow(window, source, 0, session.anchorDate);
            TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
            boolean atTop = dialog != null && scan.maxId >= dialog.top_message;
            // Usable when it spans the opening point: posts on both sides of it, or nothing newer exists.
            int verdict = FolderFeedMerge.aroundCacheVerdict(scan, atTop);
            if (verdict != FolderFeedMerge.CACHE_USABLE) {
                source.cacheMiss = verdict;
                return false;
            }
            // As for the probe window, a network page confirms the newest end.
            MERGE.applyWindow(source, scan.messages, session.anchorDate, false, false, dialogTop(source.dialogId));
            source.aroundDone = true;
            return true;
        });
    }

    private interface CachedWindowHandler {
        /** Returns true when the window was used. */
        boolean onWindow(Source source, ArrayList<TLRPC.Message> window, int liveVersionAtRead);
    }

    private static final int CACHE_MISS_NO_EARLIER = FolderFeedMerge.CACHE_MISS_NO_EARLIER;

    private static final int CACHE_KIND_PROBE = 0;
    private static final int CACHE_KIND_AROUND = 1;
    private static final int CACHE_KIND_LATEST = 2;

    private static void setCacheState(Session session, int kind, int state) {
        if (kind == CACHE_KIND_PROBE) {
            session.probeCacheState = state;
        } else if (kind == CACHE_KIND_AROUND) {
            session.aroundCacheState = state;
        } else {
            session.latestCacheState = state;
        }
    }

    /** Whether the channel already has what this kind of cache read would give it. */
    private static boolean hasCacheResult(Source source, int kind) {
        return kind == CACHE_KIND_PROBE ? source.probeDone : kind == CACHE_KIND_AROUND && source.aroundDone;
    }

    private void readCache(Session session, ArrayList<FolderFeedCache.Request> requests, int kind, CachedWindowHandler handler) {
        setCacheState(session, kind, CACHE_READING);
        Runnable done = () -> {
            setCacheState(session, kind, CACHE_DONE);
            pump(session);
        };
        if (requests.isEmpty()) {
            done.run();
            return;
        }
        final int[] versions = new int[session.sources.size()];
        for (int i = 0; i < versions.length; i++) {
            versions[i] = session.sources.get(i).liveVersion;
        }
        FolderFeedCache.read(currentAccount, requests, windows -> {
            if (sessions.get(session.guid) != session) {
                return;
            }
            for (int i = 0; i < session.sources.size(); i++) {
                Source source = session.sources.get(i);
                if (hasCacheResult(source, kind)) {
                    continue;
                }
                // What this app session already loaded first, then the database.
                ArrayList<TLRPC.Message> memoryWindow = windows.memory.get(source.dialogId);
                ArrayList<TLRPC.Message> databaseWindow = windows.database.get(source.dialogId);
                if (memoryWindow == null && databaseWindow == null) {
                    continue;
                }
                source.cacheAsked = true;
                for (ArrayList<TLRPC.Message> window : new ArrayList[]{memoryWindow, databaseWindow}) {
                    if (window == null || window.isEmpty()) {
                        continue;
                    }
                    source.cacheHadPosts = true;
                    if (handler.onWindow(source, window, versions[i])) {
                        source.fromCache = true;
                        source.fromMemory = window == memoryWindow;
                        break;
                    }
                }
            }
            done.run();
        });
    }

    /**
     * Opening at the newest posts: seeds each channel's older edge with its newest cached posts, when they
     * reach the channel's top message as known from the dialogs list (the same top the network path
     * starts from). Only channels without such a stretch in the cache are fetched for the first pages.
     */
    private void readLatestCache(Session session) {
        ArrayList<FolderFeedCache.Request> requests = new ArrayList<>();
        for (Source source : session.sources) {
            requests.add(new FolderFeedCache.Request(source.dialogId, 0, Integer.MAX_VALUE, FolderFeedMerge.FETCH_LIMIT, 0));
        }
        readCache(session, requests, CACHE_KIND_LATEST, (source, window, liveVersion) -> MERGE.seedLatest(source, window, dialogTop(source.dialogId)));
    }

    /** Saves a page fetched for a channel into the local database, like a channel's own pages. */
    private void saveToCache(Source source, ArrayList<TLRPC.Message> page, int loadType, int maxId) {
        FolderFeedCache.write(currentAccount, source.dialogId, page, loadType, maxId, getReadMax(currentAccount, source.dialogId));
    }

    /** The posts around the read pointer: the channel's first unread one, and usually its starting window. */
    private void probe(Session session, Source source) {
        TLRPC.TL_messages_getHistory req = historyRequest(source, FolderFeedMerge.probeQuery(source));
        final int liveVersion = source.liveVersion;
        sendHistory(session, source.opening, req, res -> {
            saveToCache(source, res.messages, MessagesController.LOAD_AROUND_DATE, 0);
            MERGE.onProbePage(source, res.messages, liveVersion);
        }, () -> source.probeDone = true);
    }

    /** The posts around the opening point. */
    private void loadAround(Session session, Source source) {
        TLRPC.TL_messages_getHistory req = historyRequest(source, FolderFeedMerge.aroundQuery(session.anchorDate));
        final int liveVersion = source.liveVersion;
        sendHistory(session, source.opening, req, res -> {
            saveToCache(source, res.messages, MessagesController.LOAD_AROUND_DATE, 0);
            MERGE.onAroundPage(source, res.messages, session.anchorDate, liveVersion, dialogTop(source.dialogId));
        }, () -> {
            // The channel cannot be read (e.g. it became private): leave it out of the feed.
            MERGE.onAroundGaveUp(source, dialogTop(source.dialogId));
        });
    }

    /**
     * Decides, once the opening point is known, which channels need a request around it: a channel whose
     * window around its read pointer already covers that point reuses it, and a channel whose last
     * message is older has nothing there, so its posts load only when the feed is scrolled back to them.
     */
    private void prepareAround(Session session) {
        for (Source source : session.sources) {
            MERGE.prepareAround(source, session.anchorDate, dialogTop(source.dialogId));
        }
    }

    private static TLRPC.TL_messages_getHistory historyRequest(Source source, FolderFeedMerge.Query query) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        req.offset_id = query.offsetId;
        req.offset_date = query.offsetDate;
        req.add_offset = query.addOffset;
        req.limit = query.limit;
        return req;
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

    private void emitInitialAround(Session session) {
        FolderFeedMerge.Initial<TLRPC.Message> initial = MERGE.takeInitial(session.sources, session.restoreDialogId, session.restoreMessageId, session.anchorDate);
        // Newest first; ids grow outwards from ID_BASE: this page takes ID_BASE..ID_BASE+n-1.
        int n = initial.messages.size();
        int topId = session.ids.initial(n);
        ArrayList<MessageObject> messageObjects = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            messageObjects.add(toMessageObject(initial.messages.get(i), topId - i));
        }
        int firstUnreadId = session.restoreDialogId == 0 && initial.firstUnreadIndex >= 0 ? topId - initial.firstUnreadIndex : 0;
        int restoreId = initial.restoreIndex >= 0 ? topId - initial.restoreIndex : 0;
        session.initialPending = false;
        postPage(session, messageObjects, LOADED_INITIAL, session.initialLoadIndex, firstUnreadId, restoreId,
                !hasMore(session, false), !hasMore(session, true));
    }

    /**
     * Posts a page to ChatActivity: messagesDidLoad, then cgFolderFeedLoaded(guid, olderEnd, direction,
     * newerEnd, restoreMessageId). Always asynchronous, like regular history loads (pages are requested
     * from scroll callbacks). An end flag holds only if it held when the page was built (a later page
     * of the other direction may still be queued) and still holds when it is posted (a new post may have
     * reopened the newer edge in between).
     */
    private void postPage(Session session, ArrayList<MessageObject> messageObjects, int direction, int loadIndex, int firstUnreadId, int restoreId,
                          boolean olderEndWhenBuilt, boolean newerEndWhenBuilt) {
        AndroidUtilities.runOnUIThread(() -> {
            if (sessions.get(session.guid) != session) {
                return;
            }
            boolean olderEnd = olderEndWhenBuilt && !hasMore(session, false);
            boolean newerEnd = newerEndWhenBuilt && !hasMore(session, true);
            NotificationCenter notificationCenter = NotificationCenter.getInstance(currentAccount);
            int size = messageObjects.size();
            if (direction == LOADED_NEWER) {
                // load_type 1: a count different from the page size tells ChatActivity the newest post was reached.
                notificationCenter.postNotificationName(NotificationCenter.messagesDidLoad,
                        0L, newerEnd ? size + 1 : size, messageObjects, false, 0, 0, 0, 0, 1, false,
                        session.guid, loadIndex, 0, 0, ChatActivity.MODE_FOLDER_FEED);
            } else {
                // first_unread_id makes ChatActivity open at that post with the "Unread messages" divider;
                // a non-zero last_message_id that matches no post tells it newer posts remain to be loaded.
                notificationCenter.postNotificationName(NotificationCenter.messagesDidLoad,
                        0L, size, messageObjects, false, firstUnreadId, newerEnd ? 0 : Integer.MIN_VALUE, 0, 0, 2, olderEnd,
                        session.guid, loadIndex, 0, 0, ChatActivity.MODE_FOLDER_FEED);
            }
            if (direction == LOADED_INITIAL) {
                recordOpenStats(session);
            }
            // A restored position is applied by ChatActivity before the first layout (args[4]).
            notificationCenter.postNotificationName(NotificationCenter.cgFolderFeedLoaded, session.guid, olderEnd, direction, newerEnd, restoreId);
        });
    }

    // --- paging on either edge: merge by date with per-channel frontiers ---

    private void processEdge(Session session, boolean newer) {
        ArrayList<TLRPC.Message> page = MERGE.takePage(session.sources, newer);
        if (page != null) {
            emitEdge(session, newer, page);
            return;
        }
        // Not enough posts for a page yet: they stay buffered, and fetching more moves the watermark.
        // Channels go in frontier order, so the ones holding the merge back are fetched first; the
        // parallelism limit lets a few more be prefetched alongside.
        ArrayList<Source> candidates = FolderFeedMerge.fetchCandidates(session.sources, newer);
        int inFlight = inFlightCount(session);
        for (int i = 0; i < candidates.size() && inFlight < maxParallel && !isFloodPaused(session); i++, inFlight++) {
            fetchEdge(session, candidates.get(i), newer);
        }
        // pump() runs again whenever a request completes.
    }

    private void fetchEdge(Session session, Source source, boolean newer) {
        Edge<TLRPC.Message> edge = source.edge(newer);
        FolderFeedMerge.Query query = FolderFeedMerge.edgeQuery(source, newer);
        TLRPC.TL_messages_getHistory req = historyRequest(source, query);
        final int requestOffsetId = query.offsetId;
        final int liveVersion = source.liveVersion;
        final int anchorDate = session.anchorDate;
        sendHistory(session, edge, req, res -> {
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
            MERGE.onEdgePage(source, newer, requestOffsetId, res.messages, anchorDate, liveVersion);
        }, () -> edge.exhausted = true);
    }

    private interface HistoryHandler {
        void onHistory(TLRPC.messages_Messages res);
    }

    /**
     * Sends a getHistory request in the given slot. On a response, stores users/chats and runs
     * {@code onResponse}; on a transient error, retries later; on a permanent one, runs {@code onGiveUp}.
     * Then drives the session on.
     */
    private void sendHistory(Session session, Slot slot, TLRPC.TL_messages_getHistory req, HistoryHandler onResponse, Runnable onGiveUp) {
        slot.inFlight = true;
        session.requestsSent++;
        session.maxInFlight = Math.max(session.maxInFlight, inFlightCount(session));
        final long sentAt = SystemClock.elapsedRealtime();
        slot.reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            slot.inFlight = false;
            session.responses++;
            session.responseTimeTotal += SystemClock.elapsedRealtime() - sentAt;
            if (sessions.get(session.guid) != session) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                putUsersAndChats(res);
                slot.retries = 0;
                onRequestSucceeded();
                onResponse.onHistory(res);
            } else if (scheduleRetry(session, error, slot)) {
                return;
            } else {
                onGiveUp.run();
            }
            pump(session);
        }));
    }

    private void emitEdge(Session session, boolean newer, ArrayList<TLRPC.Message> ready) {
        // ready is newest first. Older pages continue the ids downwards, newer pages upwards.
        int n = ready.size();
        ArrayList<MessageObject> messageObjects = new ArrayList<>(n);
        int firstId = session.ids.page(n, newer);
        for (int i = 0; i < n; i++) {
            messageObjects.add(toMessageObject(ready.get(i), firstId - i));
        }
        int loadIndex;
        if (newer) {
            session.loadingNewer = false;
            loadIndex = session.newerLoadIndex;
        } else {
            session.loadingOlder = false;
            loadIndex = session.olderLoadIndex;
        }
        int direction = newer ? LOADED_NEWER : (session.initialPending ? LOADED_INITIAL : LOADED_OLDER);
        session.initialPending = false;
        postPage(session, messageObjects, direction, loadIndex, 0, 0, !hasMore(session, false), !hasMore(session, true));
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

    private void putUsersAndChats(TLRPC.messages_Messages res) {
        MessagesController.getInstance(currentAccount).putUsers(res.users, false);
        MessagesController.getInstance(currentAccount).putChats(res.chats, false);
        MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
    }

    /**
     * Schedules a retry of a failed request, unless the error is permanent (e.g. the channel became
     * private). Transient failures are retried for as long as the feed is open, with a growing delay or
     * the wait the server asks for; a FLOOD_WAIT also lowers the parallelism until the server has been
     * calm for a while (see {@link #onRequestSucceeded()}). Returns false when the request should be
     * given up.
     */
    private boolean scheduleRetry(Session session, TLRPC.TL_error error, Slot slot) {
        FileLog.e("FolderFeed: getHistory failed: " + (error != null ? error.code + " " + error.text : "unexpected response"));
        if (error == null || error.code == 400 || error.code == 403 || error.code == 406) {
            return false;
        }
        int attempt = ++slot.retries;
        int delay = Math.min(1000 << Math.min(attempt - 1, 5), 30_000);
        if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
            maxParallel = FLOOD_PARALLEL_REQUESTS;
            try {
                int seconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length()));
                delay = Math.max(delay, seconds * 1000 + 500);
                session.floodWaits++;
                session.floodWaitSeconds = Math.max(session.floodWaitSeconds, seconds);
            } catch (NumberFormatException ignore) {
            }
            long now = SystemClock.elapsedRealtime();
            // Every request would get the same answer meanwhile: pause them all, not just this one.
            floodPauseUntil = Math.max(floodPauseUntil, now + delay);
            floodCalmUntil = now + delay + FLOOD_CALM_MS;
        }
        // Keep the slot occupied while waiting, so pump() does not start the same request again.
        slot.inFlight = true;
        AndroidUtilities.runOnUIThread(() -> {
            slot.inFlight = false;
            pump(session);
        }, delay);
        return true;
    }

    /** Whether requests are paused after a FLOOD_WAIT; if so, the session is driven on when the pause ends. */
    private boolean isFloodPaused(Session session) {
        long wait = floodPauseUntil - SystemClock.elapsedRealtime();
        if (wait <= 0) {
            return false;
        }
        if (!session.resumeScheduled) {
            session.resumeScheduled = true;
            AndroidUtilities.runOnUIThread(() -> {
                session.resumeScheduled = false;
                pump(session);
            }, wait);
        }
        return true;
    }

    /** Raises the parallelism back step by step once some time passed without a FLOOD_WAIT. */
    private void onRequestSucceeded() {
        if (maxParallel < MAX_PARALLEL_REQUESTS && SystemClock.elapsedRealtime() > floodCalmUntil) {
            maxParallel++;
        }
    }

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

    /**
     * Returns the list with every merged-feed message replaced by a copy that carries its real id,
     * for code that sends message ids to the server (e.g. forwarding). Other messages are kept.
     */
    public static ArrayList<MessageObject> withRealIds(ArrayList<MessageObject> messages) {
        if (!hasFeedMessages(messages)) {
            return messages;
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (MessageObject messageObject : messages) {
            TLRPC.Message copy = messageObject != null && messageObject.isFolderFeedMessage ? FolderFeedCache.copyOf(messageObject.messageOwner) : null;
            result.add(copy != null ? new MessageObject(messageObject.currentAccount, copy, false, false) : messageObject);
        }
        return result;
    }

    // endregion

    // region Notifications

    private void updateObservingNewMessages() {
        boolean need = sessions.size() > 0;
        if (need == observingNewMessages) {
            return;
        }
        observingNewMessages = need;
        if (need) {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        } else {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogFiltersUpdated || id == NotificationCenter.dialogsNeedReload) {
            channelsCache.clear();
            if (wantMainDialogs || wantArchivedDialogs) {
                // A page of the chat list was loaded: request the next one.
                AndroidUtilities.runOnUIThread(this::continueLoadingDialogs);
            }
            for (int i = 0; i < sessions.size(); i++) {
                Session session = sessions.valueAt(i);
                if (session.state == STATE_WAITING_FOR_DIALOGS) {
                    AndroidUtilities.runOnUIThread(() -> beginWhenDialogsLoaded(session));
                }
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            int minId = Integer.MAX_VALUE, minDate = Integer.MAX_VALUE;
            boolean anyPost = false;
            for (MessageObject messageObject : messages) {
                if (messageObject == null || messageObject.getId() <= 0) {
                    continue;
                }
                minId = Math.min(minId, messageObject.getId());
                minDate = Math.min(minDate, messageObject.messageOwner.date);
                anyPost |= !messageObject.isSponsored() && messageObject.messageOwner.action == null;
            }
            if (!anyPost) {
                return;
            }
            for (int i = 0; i < sessions.size(); i++) {
                Session session = sessions.valueAt(i);
                Source source = session.sourcesById.get(dialogId);
                if (source == null) {
                    continue;
                }
                FolderFeedMerge.onNewPosts(source, minId, minDate, source.aroundDone || session.state == STATE_READY);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedNewPosts, session.guid);
            }
        }
    }

    // endregion
}
