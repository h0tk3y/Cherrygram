/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Message filtering is disabled: no message is ever blocked and no spoiler entities are added,
 * so callers get exactly the stock Telegram entities/text. The exclusion list used by
 * MessageFiltersPreferencesEntry is still persisted so that screen keeps working.
 */

package uz.unnarsx.cherrygram.chats.filters

import android.app.Activity
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import uz.unnarsx.cherrygram.camera.CameraXStubSupport

object MessagesFilterHelper {

    /** SharedPreferences key of the excluded chats list (passed back into get/saveArrayList). */
    val excludedList: String = "CP_MessageFiltersExcludedChats"

    init {
        // Earliest stub touched at startup: make sure camera code falls back to the stock camera.
        CameraXStubSupport.ensureCameraXDisabled()
    }

    private val preferences: SharedPreferences?
        get() = try {
            ApplicationLoader.applicationContext?.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }

    fun shouldBlockMessage(messageObject: MessageObject?): Boolean {
        return false
    }

    /** Stock entities of the message (never null). */
    fun addSpoilerEntities(messageObject: MessageObject?): ArrayList<TLRPC.MessageEntity> {
        return messageObject?.messageOwner?.entities ?: ArrayList()
    }

    /** Returns [entities] unchanged. */
    fun addSpoilerEntities(
        messageObject: MessageObject?,
        entities: ArrayList<TLRPC.MessageEntity>?,
        text: CharSequence?
    ): ArrayList<TLRPC.MessageEntity>? {
        return entities
    }

    /** Returns [text] unchanged. */
    fun spoilerText(messageObject: MessageObject?, text: String?): String? {
        return text
    }

    fun getArrayList(key: String?): ArrayList<String> {
        val prefs = preferences ?: return ArrayList()
        val set = try {
            prefs.getStringSet(key ?: excludedList, null)
        } catch (e: Exception) {
            null
        }
        return if (set == null) ArrayList() else ArrayList(set)
    }

    fun saveArrayList(list: ArrayList<String>?, key: String?) {
        val prefs = preferences ?: return
        prefs.edit().putStringSet(key ?: excludedList, HashSet(list ?: ArrayList())).apply()
    }

    fun getExcludedChatsCount(): Int {
        return getArrayList(excludedList).size
    }
}
