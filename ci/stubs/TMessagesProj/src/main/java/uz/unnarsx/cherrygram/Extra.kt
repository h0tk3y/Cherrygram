/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Provides the API app id/hash (substituted by ci/apply-stubs.sh from the TG_APP_ID /
 * TG_APP_HASH environment variables, falling back to the upstream Telegram defaults) and inert
 * values for the integrity-check / donate-list constants that the rest of the code reads.
 */

package uz.unnarsx.cherrygram

import android.app.Activity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import uz.unnarsx.cherrygram.core.helpers.CGResourcesHelper

object Extra {

    /** Replaced by ci/apply-stubs.sh; anything that is not a positive integer falls back. */
    private const val APP_ID_RAW = "@TG_APP_ID@"

    /** Replaced by ci/apply-stubs.sh; anything that is not 32 hex chars falls back. */
    private const val APP_HASH_RAW = "@TG_APP_HASH@"

    /** Upstream Telegram defaults (see org.telegram.messenger.BuildVars). */
    private const val DEFAULT_APP_ID = 4
    private const val DEFAULT_APP_HASH = "014b35b6184100b085b0d0572f9b5103"

    fun getAppID(): Int {
        val id = APP_ID_RAW.trim().toIntOrNull()
        return if (id != null && id > 0) id else DEFAULT_APP_ID
    }

    fun getAppHash(): String {
        val hash = APP_HASH_RAW.trim()
        return if (Regex("^[0-9a-fA-F]{32}$").matches(hash)) hash else DEFAULT_APP_HASH
    }

    /** Read by BuildVars.getSmsHash() (Java static field access, hence const). Upstream release value. */
    const val SMS_HASH = "oLeq9AcOZkT"

    /*
     * Integrity-check name fragments (KotlinFragmentsManager / SamsungDatastore). They are joined
     * and compared against file names, folder names, drawer item titles and normalized [a-z0-9]
     * strings. An empty value would match everything (String.contains("") == true) and make the
     * app restart itself, so each value contains a '/' and upper-case letters, which can never
     * occur in a file name or in a normalized string, and never equals a real drawer item.
     */
    val XName_Arr = arrayOf("CI/STUB/X1")
    val XName_ArrTwo = arrayOf("CI/STUB/X2")
    val Name_Arr = arrayOf("CI/STUB/N1")
    val Name_Arr2 = arrayOf("CI/STUB/N2")
    val Name_Arr3 = arrayOf("CI/STUB/N3")
    val Name_ArrTwo = arrayOf("CI/STUB/N4")
    val Name_ArrTwo1 = arrayOf("CI/STUB/T1")
    val Name_ArrTwo2 = arrayOf("CI/STUB/T2")
    val Name_ArrTwo3 = arrayOf("CI/STUB/T3")
    val Name_ArrTwo4 = arrayOf("CI/STUB/T4")
    val Name_ArrTwo5 = arrayOf("CI/STUB/T5")
    val Name_ArrTwo6 = arrayOf("CI/STUB/T6")

    /* Only used by integrity checks that are currently disabled in KotlinFragmentsManager. */
    val HASH_ARRAY = arrayOf("CI/STUB/H")
    val APP_ARRAY = arrayOf("CI/STUB/A")
    val SMS_ARRAY = arrayOf("CI/STUB/S")

    /*
     * Base64-encoded parts consumed by DonatesManager.decodeBase64Array(). File names are local
     * cache files; URLs point at a reserved ".invalid" host so the download fails fast and the
     * code falls back to its (empty) local cache.
     */
    val FILE_NAME_HASH = arrayOf("Y2dfY2lfc3R1Yl9kb25hdGVzLnR4dA==")
    val GITLAB_RAW_URL_HASH = arrayOf("aHR0cHM6Ly9jaGVycnlncmFtLWNpLXN0dWIuaW52YWxpZC9kb25hdGVzLnR4dA==")
    val FILE_NAME_MARKETPLACE_HASH = arrayOf("Y2dfY2lfc3R1Yl9tYXJrZXRwbGFjZS50eHQ=")
    val GITLAB_RAW_URL_MARKETPLACE_HASH = arrayOf("aHR0cHM6Ly9jaGVycnlncmFtLWNpLXN0dWIuaW52YWxpZC9tYXJrZXRwbGFjZS50eHQ=")
    val FILE_NAME_BLOCKED_HASH = arrayOf("Y2dfY2lfc3R1Yl9ibG9ja2VkLnR4dA==")
    val GITLAB_RAW_URL_BLOCKED_HASH = arrayOf("aHR0cHM6Ly9jaGVycnlncmFtLWNpLXN0dWIuaW52YWxpZC9ibG9ja2VkLnR4dA==")
    val FILE_NAME_BADGE_COLORS_HASH = arrayOf("Y2dfY2lfc3R1Yl9iYWRnZV9jb2xvcnMudHh0")
    val GITLAB_RAW_URL_BADGE_COLORS_HASH = arrayOf("aHR0cHM6Ly9jaGVycnlncmFtLWNpLXN0dWIuaW52YWxpZC9iYWRnZV9jb2xvcnMudHh0")
    val FILE_NAME_TON_RATE_HASH = arrayOf("Y2dfY2lfc3R1Yl90b25fcmF0ZS5qc29u")

    /** Marker string used by CherrygramAppearanceConfig / CherrygramMessagesConfig. */
    val VERIF_LOG = arrayOf("Y2dfY2lfc3R1Yl92ZXJpZmllZA==")

    /** Not a t.me link, so MessageLoader rejects it ("Invalid link format") without any request. */
    val TG_BLOCKED_URL = arrayOf("aHR0cHM6Ly9jaGVycnlncmFtLWNpLXN0dWIuaW52YWxpZC8w")

    /** "DC: n, name, location" line shown under the ID row of a profile. */
    fun getProfileDC(fragment: BaseFragment?, user: TLRPC.User?, chat: TLRPC.Chat?): StringBuilder {
        val dcId = when {
            user?.photo != null -> user.photo.dc_id
            chat?.photo != null -> chat.photo.dc_id
            else -> 0
        }
        val sb = StringBuilder()
        sb.append("DC: ")
        if (dcId > 0) {
            sb.append(dcId)
            sb.append(", ")
            sb.append(CGResourcesHelper.getDCName(dcId))
            sb.append(", ")
            sb.append(CGResourcesHelper.getDCGeo(dcId))
        } else {
            sb.append(LocaleController.getString(R.string.NumberUnknown))
        }
        return sb
    }

    /** Suffix appended after "Mobile" in ProfileActivity; no country lookup in the stub. */
    fun getCountryByPhone(fragment: BaseFragment?, user: TLRPC.User?, hidden: Boolean): String {
        return ""
    }

    /** Full subtitle for the phone row in MessagesAndProfilesPreferencesEntry. */
    fun getCountryByPhone(fragment: BaseFragment?, user: TLRPC.User?): String {
        return LocaleController.getString(R.string.PhoneMobile)
    }

    /** No-op in the stub. */
    fun addBirthdayToCalendar(activity: Activity?, userId: Long) {
    }

    /** Callers ignore an empty/null value (no hint is shown). */
    fun getRegistrationDate(fragment: BaseFragment?, userId: Long, chatId: Long): CharSequence? {
        return null
    }
}
