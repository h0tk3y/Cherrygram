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
import android.text.TextUtils;
import android.util.LongSparseArray;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * The merged feed's use of the local message database ({@code messages_v2} / {@code messages_holes}),
 * the same cache regular channels use: every page the feed fetches is saved like a channel's page, and
 * when the feed opens, each channel's posts around the opening point are read from it first.
 * <p>
 * The cache keeps one invariant: stored messages without a recorded hole between them are contiguous
 * history. Reads only return windows without a hole inside; writes only save windows that touch or lie
 * below the channel's cached messages (whose gaps the database already tracks), never a window floating
 * above them, where the gap to the cached messages would not be recorded.
 */
final class FolderFeedCache {

    private FolderFeedCache() {
    }

    /** Messages around a pivot: a message id (posts from it on are "newer") or, if 0, a date. */
    static final class Request {
        final long dialogId;
        final int pivotId;
        final int pivotDate;
        final int olderLimit;
        final int newerLimit;

        Request(long dialogId, int pivotId, int pivotDate, int olderLimit, int newerLimit) {
            this.dialogId = dialogId;
            this.pivotId = pivotId;
            this.pivotDate = pivotDate;
            this.olderLimit = olderLimit;
            this.newerLimit = newerLimit;
        }
    }

    /** Windows read for a set of requests, by channel: from this app session's memory and from the database. */
    static final class Windows {
        final LongSparseArray<ArrayList<TLRPC.Message>> memory = new LongSparseArray<>();
        LongSparseArray<ArrayList<TLRPC.Message>> database = new LongSparseArray<>();
    }

    /**
     * Reads the requested windows: from the memory of this app session (on the calling UI thread) and from
     * the database (on the storage thread). Each window is contiguous history, newest first, possibly empty;
     * its messages are fresh copies the caller may change. Users and chats the database windows mention are
     * loaded too. Delivered on the UI thread.
     */
    static void read(int account, ArrayList<Request> requests, Utilities.Callback<Windows> done) {
        Windows windows = new Windows();
        for (Request request : requests) {
            ArrayList<TLRPC.Message> window = readMemoryWindow(account, request);
            if (window != null) {
                windows.memory.put(request.dialogId, window);
            }
        }
        readDatabase(account, requests, database -> {
            windows.database = database;
            done.run(windows);
        });
    }

    private static void readDatabase(int account, ArrayList<Request> requests, Utilities.Callback<LongSparseArray<ArrayList<TLRPC.Message>>> done) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            LongSparseArray<ArrayList<TLRPC.Message>> result = new LongSparseArray<>();
            ArrayList<TLRPC.User> users = new ArrayList<>();
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            try {
                SQLiteDatabase database = storage.getDatabase();
                long selfId = UserConfig.getInstance(account).getClientUserId();
                ArrayList<Long> usersToLoad = new ArrayList<>();
                ArrayList<Long> chatsToLoad = new ArrayList<>();
                for (Request request : requests) {
                    ArrayList<TLRPC.Message> window = readWindow(database, selfId, request);
                    result.put(request.dialogId, window);
                    for (TLRPC.Message message : window) {
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    }
                }
                if (!usersToLoad.isEmpty()) {
                    storage.getUsersInternal(usersToLoad, users);
                }
                if (!chatsToLoad.isEmpty()) {
                    storage.getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(users, true);
                MessagesController.getInstance(account).putChats(chats, true);
                done.run(result);
            });
        });
    }

    private static final String SELECT = "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, m.media, m.ttl, m.mention, m.imp, m.forwards, m.replies_data, m.custom_params FROM messages_v2 as m WHERE m.uid = %d AND m.mid > 0 AND ";

    private static ArrayList<TLRPC.Message> readWindow(SQLiteDatabase database, long selfId, Request request) throws Exception {
        ArrayList<TLRPC.Message> newer = new ArrayList<>();
        ArrayList<TLRPC.Message> older = new ArrayList<>();
        String newerCondition = request.pivotId != 0 ? "m.mid >= " + request.pivotId : "m.date >= " + request.pivotDate;
        String olderCondition = request.pivotId != 0 ? "m.mid < " + request.pivotId : "m.date < " + request.pivotDate;
        readRows(database, selfId, request.dialogId, String.format(Locale.US, SELECT, request.dialogId) + newerCondition + " ORDER BY m.mid ASC LIMIT " + request.newerLimit, newer);
        readRows(database, selfId, request.dialogId, String.format(Locale.US, SELECT, request.dialogId) + olderCondition + " ORDER BY m.mid DESC LIMIT " + request.olderLimit, older);
        ArrayList<TLRPC.Message> window = new ArrayList<>(newer.size() + older.size());
        window.addAll(newer);
        window.addAll(older);
        if (window.isEmpty()) {
            return window;
        }
        // The window must not span a hole: keep the contiguous part around the pivot.
        int pivot = FolderFeedCacheCore.holePivot(TL_IDS, newer, older, request.pivotId);
        int minId = Integer.MAX_VALUE, maxId = 0;
        for (TLRPC.Message message : window) {
            minId = Math.min(minId, message.id);
            maxId = Math.max(maxId, message.id);
        }
        ArrayList<int[]> holes = new ArrayList<>();
        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT start, end FROM messages_holes WHERE uid = %d AND end > %d AND start < %d", request.dialogId, minId, maxId));
        try {
            while (cursor.next()) {
                holes.add(new int[]{cursor.intValue(0), cursor.intValue(1)});
            }
        } finally {
            cursor.dispose();
        }
        return FolderFeedCacheCore.trimToHoles(TL_IDS, window, pivot, holes);
    }

    /** Same row decoding as MessagesStorage.getMessagesInternal, for the columns selected above. */
    private static void readRows(SQLiteDatabase database, long selfId, long dialogId, String query, ArrayList<TLRPC.Message> out) throws Exception {
        SQLiteCursor cursor = database.queryFinalized(query);
        try {
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message == null) {
                    data.reuse();
                    continue;
                }
                message.send_state = cursor.intValue(2);
                message.id = (int) cursor.longValue(3);
                if (message.id > 0 && message.send_state != 0 && message.send_state != 3) {
                    message.send_state = 0;
                }
                message.readAttachPath(data, selfId);
                data.reuse();
                MessageObject.setUnreadFlags(message, cursor.intValue(0));
                message.date = cursor.intValue(4);
                message.dialog_id = dialogId;
                if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
                    message.views = cursor.intValue(5);
                    message.forwards = cursor.intValue(9);
                }
                NativeByteBuffer repliesData = cursor.byteBufferValue(10);
                if (repliesData != null) {
                    TLRPC.MessageReplies replies = TLRPC.MessageReplies.TLdeserialize(repliesData, repliesData.readInt32(false), false);
                    if (replies != null) {
                        message.replies = replies;
                    }
                    repliesData.reuse();
                }
                if (message.ttl == 0) {
                    message.ttl = cursor.intValue(6);
                }
                if (cursor.intValue(7) != 0) {
                    message.mentioned = true;
                }
                int flags = cursor.intValue(8);
                if ((flags & 1) != 0) {
                    message.stickerVerified = 0;
                } else if ((flags & 2) != 0) {
                    message.stickerVerified = 2;
                }
                NativeByteBuffer customParams = cursor.byteBufferValue(11);
                if (customParams != null) {
                    MessageCustomParamsHelper.readLocalParams(message, customParams);
                    customParams.reuse();
                }
                out.add(message);
            }
        } finally {
            cursor.dispose();
        }
    }

    /**
     * Saves a contiguous page of a channel's history fetched by the feed, like MessagesController saves a
     * channel's page: {@code loadType} and {@code maxId} are those of {@link MessagesStorage#putMessages}
     * (LOAD_BACKWARD with the newest id the page may cover, LOAD_FORWARD with the request's offset id, or
     * LOAD_AROUND_DATE for a window).
     * Copies are saved, since the feed later changes the ids of the messages it shows.
     */
    static void write(int account, long dialogId, ArrayList<TLRPC.Message> page, int loadType, int maxId, int readMax) {
        ArrayList<TLRPC.Message> copies = new ArrayList<>(page.size());
        for (TLRPC.Message message : page) {
            if (message == null || message instanceof TLRPC.TL_messageEmpty || MessageObject.getDialogId(message) != dialogId) {
                continue;
            }
            TLRPC.Message copy = copyOf(message);
            if (copy == null) {
                return;
            }
            if (copy.action instanceof TLRPC.TL_messageActionChatMigrateTo || copy.action instanceof TLRPC.TL_messageActionChannelCreate) {
                copy.unread = false;
                copy.media_unread = false;
            } else {
                // Outgoing posts are written as read: the feed does not track the outbox pointer.
                copy.unread = !copy.out && copy.id > readMax;
            }
            copies.add(copy);
        }
        // An empty page is never saved: for a backward page, putMessages would mark the channel's history
        // as fully loaded, which is only right when the offset sits at the bottom of the cached history.
        if (copies.isEmpty()) {
            return;
        }
        Collections.sort(copies, (a, b) -> Integer.compare(b.id, a.id));
        // Kept in memory whatever the database accepts below: a backward page continues the stretch that
        // holds its offset post (maxId + 1); other pages overlap the stretch they continue.
        remember(account, dialogId, copies, loadType == MessagesController.LOAD_BACKWARD ? maxId + 1 : 0);
        if (loadType == MessagesController.LOAD_FORWARD && (maxId == 0 || copies.get(0).id < maxId)) {
            // A forward page needs its offset (closing holes from 0 would wipe every gap below it), and
            // must reach past it.
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                if (!prepareWrite(storage.getDatabase(), dialogId, copies, loadType, maxId)) {
                    return;
                }
                TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
                res.messages = copies;
                // Queued after this runnable on the same storage thread, so it sees the prepared holes.
                storage.putMessages(res, dialogId, loadType, maxId, false, 0, 0);
                int pageMin = copies.get(copies.size() - 1).id;
                int pageMax = copies.get(0).id;
                // Queued after putMessages: the read flags above were computed on the UI thread, and a read
                // (processPendingRead) may have been stored in between and been overwritten. Re-apply the
                // read pointer to the saved page only; the dialog's unread count is not touched (the read
                // that moved the pointer already updated it).
                storage.getStorageQueue().postRunnable(() -> {
                    Integer readPointer = MessagesController.getInstance(account).dialogs_read_inbox_max.get(dialogId);
                    if (readPointer == null || readPointer <= 0) {
                        return;
                    }
                    try {
                        storage.getDatabase().executeFast(String.format(Locale.US,
                                "UPDATE messages_v2 SET read_state = read_state | 1 WHERE uid = %d AND mid >= %d AND mid <= %d AND mid <= %d AND read_state IN(0,2) AND out = 0",
                                dialogId, pageMin, pageMax, readPointer)).stepThis().dispose();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Checks that saving the page keeps the cache's invariant, and records the "everything below the
     * cached messages is unknown" hole if the channel has none yet (as getMessagesInternal would).
     */
    private static boolean prepareWrite(SQLiteDatabase database, long dialogId, ArrayList<TLRPC.Message> page, int loadType, int maxId) throws Exception {
        int cachedMin = 0, cachedMax = 0;
        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT min(mid), max(mid) FROM messages_v2 WHERE uid = %d AND mid > 0", dialogId));
        try {
            if (cursor.next()) {
                cachedMin = cursor.intValue(0);
                cachedMax = cursor.intValue(1);
            }
        } finally {
            cursor.dispose();
        }
        if (cachedMin == 0) {
            // Nothing cached for the channel (not even its last message): the gaps around the page could
            // not be recorded.
            return false;
        }
        int pageMin = page.get(page.size() - 1).id;
        if (loadType == MessagesController.LOAD_FORWARD && maxId != 0) {
            pageMin = Math.min(pageMin, maxId);
        }
        if (pageMin > cachedMax) {
            // Above everything cached: the gap between them is not recorded anywhere.
            return false;
        }
        cursor = database.queryFinalized(String.format(Locale.US, "SELECT start FROM messages_holes WHERE uid = %d AND start IN (0, 1)", dialogId));
        boolean hasBottomHole;
        try {
            hasBottomHole = cursor.next();
        } finally {
            cursor.dispose();
        }
        if (!hasBottomHole) {
            SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages_holes VALUES(?, ?, ?)");
            try {
                state.requery();
                state.bindLong(1, dialogId);
                state.bindInteger(2, 0);
                state.bindInteger(3, cachedMin);
                state.step();
            } finally {
                state.dispose();
            }
        }
        return true;
    }

    // region Memory of this app session

    /** {@link TLRPC.Message} for {@link FolderFeedCacheCore}: messages are copied with {@link #copyOf}. */
    static final FolderFeedCacheCore.Ids<TLRPC.Message> TL_IDS = new FolderFeedCacheCore.Ids<TLRPC.Message>() {
        @Override
        public int id(TLRPC.Message message) {
            return message.id;
        }

        @Override
        public int date(TLRPC.Message message) {
            return message.date;
        }

        @Override
        public TLRPC.Message copy(TLRPC.Message message) {
            return copyOf(message);
        }
    };

    /**
     * Pages the feed fetched in this app session, per channel, as contiguous stretches of history keyed by
     * message id (see {@link FolderFeedCacheCore.Stretches}). It does not depend on what the database
     * accepts (see prepareWrite), so reopening a feed in the same session finds every channel it loaded
     * before. UI thread only.
     */
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<FolderFeedCacheCore.Stretches<TLRPC.Message>>[] memory = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];

    private static FolderFeedCacheCore.Stretches<TLRPC.Message> stretches(int account, long dialogId) {
        if (memory[account] == null) {
            memory[account] = new LongSparseArray<>();
        }
        FolderFeedCacheCore.Stretches<TLRPC.Message> stretches = memory[account].get(dialogId);
        if (stretches == null) {
            stretches = new FolderFeedCacheCore.Stretches<>(TL_IDS);
            memory[account].put(dialogId, stretches);
        }
        return stretches;
    }

    /** Adds a contiguous page (newest first); {@code connectsTo} is a message id it continues, or 0. */
    private static void remember(int account, long dialogId, ArrayList<TLRPC.Message> page, int connectsTo) {
        stretches(account, dialogId).remember(page, connectsTo, SystemClock.elapsedRealtime());
    }

    /** The memory's window for a request, like the database's: around the pivot within one stretch. */
    @Nullable
    private static ArrayList<TLRPC.Message> readMemoryWindow(int account, Request request) {
        if (memory[account] == null) {
            return null;
        }
        FolderFeedCacheCore.Stretches<TLRPC.Message> stretches = memory[account].get(request.dialogId);
        if (stretches == null) {
            return null;
        }
        return stretches.window(request.pivotId, request.pivotDate, request.olderLimit, request.newerLimit, SystemClock.elapsedRealtime());
    }

    // endregion

    /** A deep copy of the message carrying its real id (the feed gives shown messages synthetic ids). */
    static TLRPC.Message copyOf(TLRPC.Message message) {
        SerializedData data = new SerializedData(message.getObjectSize());
        message.serializeToStream(data);
        SerializedData input = new SerializedData(data.toByteArray());
        TLRPC.Message copy = TLRPC.Message.TLdeserialize(input, input.readInt32(false), false);
        data.cleanup();
        input.cleanup();
        if (copy != null) {
            int realId = message.realId != 0 ? message.realId : message.id;
            copy.id = realId;
            copy.realId = 0;
            copy.dialog_id = message.dialog_id;
        }
        return copy;
    }
}
