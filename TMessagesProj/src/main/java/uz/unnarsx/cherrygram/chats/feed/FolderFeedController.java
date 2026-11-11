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
import java.util.Iterator;
import java.util.List;

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

    /** Posts a page waits for before it is shown (fewer only at the end of history). */
    private static final int PAGE_SIZE = 20;
    /** Posts shown at most in one page; the rest stays buffered for the next one. */
    private static final int MAX_PAGE_SIZE = 60;
    /** Posts requested per channel when paging (a page usually takes posts from several channels). */
    private static final int FETCH_LIMIT = 20;
    /** Opening: posts fetched per channel at or after / before the opening point. */
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

    /** A request slot: at most one getHistory request in flight (or waiting to be retried). */
    private static class Slot {
        boolean inFlight;
        int reqId;
        int retries;
    }

    /** One edge (older or newer) of a channel's loaded range of history. */
    private static class Edge extends Slot {
        /** Real id of the outermost fetched message on this edge (service messages included); 0 = none yet. */
        int offsetId;
        /** Bound for the date of every post on this edge that is not fetched yet: upper bound for the older
         *  edge, lower bound for the newer edge. */
        int frontier;
        boolean exhausted;
        /** Fetched posts not shown yet, ordered from the loaded range outwards: the older edge keeps them
         *  newest first, the newer edge oldest first. */
        final ArrayList<TLRPC.Message> buffer = new ArrayList<>();
    }

    private static class Source {
        final long dialogId;
        final TLRPC.InputPeer peer;
        final Edge older = new Edge();
        final Edge newer = new Edge();
        /** The opening request (window around the read pointer or around the opening point). */
        final Slot opening = new Slot();
        /** Real ids of every post handed to a buffer, so the two edges never yield the same post twice. */
        final HashSet<Integer> seen = new HashSet<>();
        /** Read pointer and unread count when the feed was opened (posts with id > readMax are unread). */
        int readMax;
        int unreadCount;
        /** Opening: date of the channel's first unread message, 0 if it was not found. */
        int firstUnreadDate;
        /** Opening: the window around the read pointer (with the first unread message), reused as the
         *  channel's starting window when it covers the opening point. */
        ArrayList<TLRPC.Message> probeWindow;
        boolean probeOlderEnd;
        boolean probeNewerEnd;
        int probeLiveVersion;
        boolean probeDone;
        boolean aroundDone;
        /** Bumped when the channel gets a new post, so a newer-edge response built before it does not
         *  mark the edge as exhausted for good. */
        int liveVersion;
        /** Opening, for the open stats: the local cache was asked for this channel, had posts of it,
         *  and gave a usable window. */
        boolean cacheAsked;
        boolean cacheHadPosts;
        boolean fromCache;
        /** Why the last cached window was not used (CACHE_MISS_*), for the open stats. */
        int cacheMiss;

        Source(long dialogId, TLRPC.InputPeer peer) {
            this.dialogId = dialogId;
            this.peer = peer;
        }

        boolean hasUnread() {
            return unreadCount > 0 && readMax > 0;
        }

        Edge edge(boolean newer) {
            return newer ? this.newer : older;
        }
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

    /** Synthetic ids grow outwards from here: older posts count down, newer posts count up. */
    private static final int ID_BASE = 1_000_000_000;

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
        int nextOlderId = ID_BASE;
        int nextNewerId = ID_BASE + 1;
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
        TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(source.dialogId);
        int lastDate = dialog != null && dialog.last_message_date > 0 ? dialog.last_message_date : Integer.MAX_VALUE;
        source.older.offsetId = 0;
        source.older.frontier = lastDate;
        source.newer.exhausted = true;
        setNewerStart(source, null);
    }

    /** Where newer posts continue: after the given message, or after the dialog's top message. */
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
        int fromCache = 0, nothingCached = 0, noEarlier = 0, noLater = 0;
        for (Source source : session.sources) {
            if (source.fromCache) {
                fromCache++;
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
        session.openStats = LocaleController.formatString(R.string.CG_FolderFeed_OpenStats, seconds, fromCache,
                session.sources.size(), nothingCached, noEarlier, noLater, session.requestsSent, session.floodWaits, session.floodWaitSeconds);
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
        for (Source source : session.sources) {
            Edge edge = source.edge(newer);
            if (!edge.exhausted || !edge.buffer.isEmpty()) {
                return true;
            }
        }
        return false;
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

    /** What a contiguous window of a channel's history holds relative to a pivot (a message id, or else a date). */
    private static class WindowScan {
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        int minId = Integer.MAX_VALUE;
        int maxId;
        /** Messages before / from the pivot on. */
        int olderCount;
        int newerCount;
        /** The first message after the pivot id (only with an id pivot). */
        TLRPC.Message firstAfterPivot;
    }

    private static WindowScan scanWindow(List<TLRPC.Message> messages, Source source, int pivotId, int pivotDate) {
        WindowScan scan = new WindowScan();
        for (TLRPC.Message message : messages) {
            if (!isFromSource(message, source)) {
                continue;
            }
            scan.messages.add(message);
            scan.minId = Math.min(scan.minId, message.id);
            scan.maxId = Math.max(scan.maxId, message.id);
            boolean newer = pivotId != 0 ? message.id >= pivotId : message.date >= pivotDate;
            if (newer) {
                scan.newerCount++;
            } else {
                scan.olderCount++;
            }
            if (pivotId != 0 && message.id > pivotId && (scan.firstAfterPivot == null || message.id < scan.firstAfterPivot.id)) {
                scan.firstAfterPivot = message;
            }
        }
        return scan;
    }

    /**
     * Opening, before any request: finds each unread channel's first unread post in the local database,
     * with the posts around its read pointer. Channels whose cached history covers it need no request.
     */
    private void readProbeCache(Session session) {
        ArrayList<FolderFeedCache.Request> requests = new ArrayList<>();
        for (Source source : session.sources) {
            if (!source.probeDone) {
                requests.add(new FolderFeedCache.Request(source.dialogId, source.readMax, 0, AROUND_OLDER, AROUND_NEWER));
            }
        }
        readCache(session, requests, CACHE_KIND_PROBE, (source, window, liveVersion) -> {
            WindowScan scan = scanWindow(window, source, source.readMax, 0);
            // Usable when it starts at or before the read pointer (so its first unread message really is
            // the first one) and contains it.
            if (scan.minId > source.readMax) {
                source.cacheMiss = CACHE_MISS_NO_EARLIER;
                return false;
            }
            if (scan.firstAfterPivot == null) {
                source.cacheMiss = CACHE_MISS_NO_LATER;
                return false;
            }
            // Neither end is taken from the database: it cannot tell where history begins (one request
            // when scrolling back there), and for channels without live updates both the cache and the
            // dialog's top message can be stale, so a network page confirms the newest end.
            setProbeResult(source, scan, false, false, liveVersion);
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
                requests.add(new FolderFeedCache.Request(source.dialogId, 0, session.anchorDate, AROUND_OLDER, AROUND_NEWER));
            }
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);
        readCache(session, requests, CACHE_KIND_AROUND, (source, window, liveVersion) -> {
            WindowScan scan = scanWindow(window, source, 0, session.anchorDate);
            TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
            boolean atTop = dialog != null && scan.maxId >= dialog.top_message;
            // Usable when it spans the opening point: posts on both sides of it, or nothing newer exists.
            if (scan.olderCount == 0) {
                source.cacheMiss = CACHE_MISS_NO_EARLIER;
                return false;
            }
            if (scan.newerCount == 0 && !atTop) {
                source.cacheMiss = CACHE_MISS_NO_LATER;
                return false;
            }
            // As for the probe window, a network page confirms the newest end.
            applyWindow(session, source, scan.messages, false, false);
            source.aroundDone = true;
            return true;
        });
    }

    private interface CachedWindowHandler {
        /** Returns true when the window was used. */
        boolean onWindow(Source source, ArrayList<TLRPC.Message> window, int liveVersionAtRead);
    }

    private static final int CACHE_MISS_NO_EARLIER = 1;
    private static final int CACHE_MISS_NO_LATER = 2;

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
                ArrayList<TLRPC.Message> window = windows.get(source.dialogId);
                if (hasCacheResult(source, kind) || window == null) {
                    continue;
                }
                source.cacheAsked = true;
                if (window.isEmpty()) {
                    continue;
                }
                source.cacheHadPosts = true;
                if (handler.onWindow(source, window, versions[i])) {
                    source.fromCache = true;
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
            requests.add(new FolderFeedCache.Request(source.dialogId, 0, Integer.MAX_VALUE, FETCH_LIMIT, 0));
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);
        readCache(session, requests, CACHE_KIND_LATEST, (source, window, liveVersion) -> {
            WindowScan scan = scanWindow(window, source, 0, Integer.MAX_VALUE);
            TLRPC.Dialog dialog = controller.dialogs_dict.get(source.dialogId);
            if (dialog == null || scan.maxId != dialog.top_message || !source.older.buffer.isEmpty() || source.older.offsetId != 0) {
                return false;
            }
            TLRPC.Message oldest = null;
            for (TLRPC.Message message : scan.messages) {
                if (oldest == null || message.id < oldest.id) {
                    oldest = message;
                }
                if (isPost(message, source) && source.seen.add(message.id)) {
                    source.older.buffer.add(message);
                }
            }
            sortNewestFirst(source.older.buffer);
            // Older posts continue from the network, below the cached ones.
            source.older.offsetId = oldest.id;
            source.older.frontier = oldest.date;
            return true;
        });
    }

    private static void setProbeResult(Source source, WindowScan scan, boolean olderEnd, boolean newerEnd, int liveVersion) {
        // Any unread message, service ones included: the feed only needs a time at or before the
        // channel's first unread post.
        source.firstUnreadDate = scan.firstAfterPivot != null ? scan.firstAfterPivot.date : 0;
        source.probeWindow = scan.messages;
        source.probeOlderEnd = olderEnd;
        source.probeNewerEnd = newerEnd;
        source.probeLiveVersion = liveVersion;
        source.probeDone = true;
    }

    /** Saves a page fetched for a channel into the local database, like a channel's own pages. */
    private void saveToCache(Source source, ArrayList<TLRPC.Message> page, int loadType, int maxId) {
        FolderFeedCache.write(currentAccount, source.dialogId, page, loadType, maxId, getReadMax(currentAccount, source.dialogId));
    }

    /** The posts around the read pointer: the channel's first unread one, and usually its starting window. */
    private void probe(Session session, Source source) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        // AROUND_NEWER from the read post on (it may be gone), and AROUND_OLDER before it.
        req.offset_id = source.readMax;
        req.add_offset = -AROUND_NEWER;
        req.limit = AROUND_NEWER + AROUND_OLDER;
        final int liveVersion = source.liveVersion;
        sendHistory(session, source.opening, req, res -> {
            saveToCache(source, res.messages, MessagesController.LOAD_AROUND_DATE, 0);
            WindowScan scan = scanWindow(res.messages, source, source.readMax, 0);
            setProbeResult(source, scan, scan.olderCount < AROUND_OLDER, scan.newerCount < AROUND_NEWER, liveVersion);
        }, () -> source.probeDone = true);
    }

    /** The posts around the opening point. */
    private void loadAround(Session session, Source source) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        // AROUND_NEWER at or after the opening point, AROUND_OLDER before it.
        req.offset_date = session.anchorDate;
        req.add_offset = -AROUND_NEWER;
        req.limit = AROUND_NEWER + AROUND_OLDER;
        final int liveVersion = source.liveVersion;
        sendHistory(session, source.opening, req, res -> {
            saveToCache(source, res.messages, MessagesController.LOAD_AROUND_DATE, 0);
            WindowScan scan = scanWindow(res.messages, source, 0, session.anchorDate);
            applyWindow(session, source, scan.messages, scan.olderCount < AROUND_OLDER, scan.newerCount < AROUND_NEWER && source.liveVersion == liveVersion);
            source.aroundDone = true;
        }, () -> {
            // The channel cannot be read (e.g. it became private): leave it out of the feed.
            source.older.exhausted = true;
            source.newer.exhausted = true;
            setNewerStart(source, null);
            source.aroundDone = true;
        });
    }

    /**
     * Decides, once the opening point is known, which channels need a request around it: a channel whose
     * window around its read pointer already covers that point reuses it, and a channel whose last
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
                initAtDialogTop(source);
                source.aroundDone = true;
            }
        }
    }

    /**
     * Sets a channel's starting state from a contiguous window of its history (messages of the channel
     * only): posts from the opening point on go to the newer edge, earlier ones to the older edge.
     */
    private void applyWindow(Session session, Source source, List<TLRPC.Message> messages, boolean olderExhausted, boolean newerExhausted) {
        TLRPC.Message oldestOlder = null, newestNewer = null, newest = null;
        for (TLRPC.Message message : messages) {
            if (newest == null || message.id > newest.id) newest = message;
            boolean newer = message.date >= session.anchorDate;
            if (newer) {
                if (newestNewer == null || message.id > newestNewer.id) newestNewer = message;
            } else {
                if (oldestOlder == null || message.id < oldestOlder.id) oldestOlder = message;
            }
            if (isPost(message, source) && source.seen.add(message.id)) {
                source.edge(newer).buffer.add(message);
            }
        }
        sortNewestFirst(source.older.buffer);
        sortOldestFirst(source.newer.buffer);
        if (oldestOlder != null) {
            source.older.offsetId = oldestOlder.id;
            source.older.frontier = oldestOlder.date;
        }
        source.older.exhausted = olderExhausted || oldestOlder == null;
        // With nothing at or after the opening point yet, newer posts, once they arrive, follow the newest one.
        setNewerStart(source, newestNewer != null ? newestNewer : newest);
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
            messageObjects.add(toMessageObject(message, ID_BASE + n - 1 - i));
            long dialogId = MessageObject.getDialogId(message);
            Source source = session.sourcesById.get(dialogId);
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
        ArrayList<TLRPC.Message> ready = collectReady(session, newer);
        if (ready.size() >= PAGE_SIZE || !anyEdgeLeft(session, newer)) {
            emitEdge(session, newer, capPage(session, ready, newer));
            return;
        }
        // Not enough posts for a page yet: keep them buffered and move the watermark by fetching more.
        // Channels go in frontier order (newest frontier first for older pages, oldest first for newer
        // ones), so the ones holding the merge back are fetched first; the parallelism limit lets a few
        // more be prefetched alongside.
        restoreToBuffers(session, ready, newer);
        ArrayList<Source> candidates = new ArrayList<>();
        for (Source source : session.sources) {
            Edge edge = source.edge(newer);
            if (!edge.inFlight && !edge.exhausted) {
                candidates.add(source);
            }
        }
        if (newer) {
            Collections.sort(candidates, (a, b) -> Integer.compare(a.newer.frontier, b.newer.frontier));
        } else {
            Collections.sort(candidates, (a, b) -> Integer.compare(b.older.frontier, a.older.frontier));
        }
        int inFlight = inFlightCount(session);
        for (int i = 0; i < candidates.size() && inFlight < maxParallel && !isFloodPaused(session); i++, inFlight++) {
            fetchEdge(session, candidates.get(i), newer);
        }
        // pump() runs again whenever a request completes.
    }

    /** Keeps at most MAX_PAGE_SIZE posts, those next to the loaded range; the rest goes back to the buffers. */
    private ArrayList<TLRPC.Message> capPage(Session session, ArrayList<TLRPC.Message> ready, boolean newer) {
        if (ready.size() <= MAX_PAGE_SIZE) {
            return ready;
        }
        // ready is newest first: an older page continues from its newest posts, a newer page from its oldest.
        int from = newer ? ready.size() - MAX_PAGE_SIZE : 0;
        ArrayList<TLRPC.Message> page = new ArrayList<>(ready.subList(from, from + MAX_PAGE_SIZE));
        ArrayList<TLRPC.Message> rest = new ArrayList<>(ready);
        rest.subList(from, from + MAX_PAGE_SIZE).clear();
        restoreToBuffers(session, rest, newer);
        return page;
    }

    private static boolean anyEdgeLeft(Session session, boolean newer) {
        for (Source source : session.sources) {
            if (!source.edge(newer).exhausted) {
                return true;
            }
        }
        return false;
    }

    private void fetchEdge(Session session, Source source, boolean newer) {
        Edge edge = source.edge(newer);
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = source.peer;
        req.offset_id = edge.offsetId;
        final int requestOffsetId = edge.offsetId;
        if (newer) {
            // The posts right after offset_id. The window also covers offset_id itself and the post before
            // it (filtered out), whether or not the offset post still exists.
            req.add_offset = -FETCH_LIMIT;
            req.limit = FETCH_LIMIT + 1;
        } else {
            req.limit = FETCH_LIMIT;
        }
        final int liveVersion = source.liveVersion;
        // An older page from the top of a channel set up from the dialogs list around an opening point:
        // if that entry was stale, the page can hold posts from the opening point on, which belong below.
        final boolean splitAtAnchor = !newer && requestOffsetId == 0 && session.anchorDate > 0;
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
            TLRPC.Message outermost = null;
            int count = 0;
            boolean movedBelow = false;
            for (TLRPC.Message message : res.messages) {
                if (!isFromSource(message, source)) {
                    continue;
                }
                if (newer ? message.id <= requestOffsetId : (requestOffsetId != 0 && message.id >= requestOffsetId)) {
                    continue;
                }
                count++;
                if (outermost == null || (newer ? message.id > outermost.id : message.id < outermost.id)) {
                    outermost = message;
                }
                if (!isPost(message, source) || !source.seen.add(message.id)) {
                    continue;
                }
                if (splitAtAnchor && message.date >= session.anchorDate) {
                    source.newer.buffer.add(message);
                    movedBelow = true;
                } else {
                    edge.buffer.add(message);
                }
            }
            if (movedBelow) {
                sortOldestFirst(source.newer.buffer);
            }
            if (newer) {
                sortOldestFirst(edge.buffer);
            } else {
                sortNewestFirst(edge.buffer);
            }
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
        slot.reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            slot.inFlight = false;
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

    /** Removes and returns every buffered post of the given edge that can be shown now, newest first. */
    private static ArrayList<TLRPC.Message> collectReady(Session session, boolean newer) {
        // Older edge: no channel can have unfetched posts newer than the highest older frontier.
        // Newer edge: no channel can have unfetched posts older than the lowest newer frontier.
        int watermark = newer ? Integer.MAX_VALUE : 0;
        for (Source source : session.sources) {
            Edge edge = source.edge(newer);
            if (!edge.exhausted) {
                watermark = newer ? Math.min(watermark, edge.frontier) : Math.max(watermark, edge.frontier);
            }
        }
        ArrayList<TLRPC.Message> ready = new ArrayList<>();
        for (Source source : session.sources) {
            Iterator<TLRPC.Message> iterator = source.edge(newer).buffer.iterator();
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

    private static void restoreToBuffers(Session session, ArrayList<TLRPC.Message> messages, boolean newer) {
        for (TLRPC.Message message : messages) {
            Source source = session.sourcesById.get(MessageObject.getDialogId(message));
            if (source != null) {
                source.edge(newer).buffer.add(message);
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
        int loadIndex;
        if (newer) {
            session.nextNewerId += n;
            session.loadingNewer = false;
            loadIndex = session.newerLoadIndex;
        } else {
            session.nextOlderId -= n;
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
                source.liveVersion++;
                if (source.newer.offsetId == 0 && (source.aroundDone || session.state == STATE_READY)) {
                    // Nothing was known of the channel: its history starts right before the new posts.
                    source.newer.offsetId = minId - 1;
                    source.newer.frontier = minDate;
                }
                if (source.newer.offsetId != 0) {
                    // The new posts arrive through forward paging, like scrolling down to newer posts.
                    source.newer.exhausted = false;
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.cgFolderFeedNewPosts, session.guid);
            }
        }
    }

    // endregion
}
