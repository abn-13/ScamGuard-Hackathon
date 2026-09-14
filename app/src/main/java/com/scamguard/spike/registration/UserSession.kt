package com.scamguard.spike.registration

import android.content.Context
import android.content.Intent

/**
 * Whether this device has completed registration against the backend, and if so, which
 * `user_id` to send with every `/check-message` call. Backed by SharedPreferences -- this
 * is a single-user-per-device spike, not a multi-account app, so a flat key/value store is
 * enough (no need for a full local DB just to remember one id).
 */
object UserSession {
    private const val PREFS_NAME = "scamguard_session"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_GUARDIAN_ID = "guardian_id"
    private const val KEY_GUARDIAN_USERNAME = "guardian_username"
    private const val KEY_GUARDIAN_PHONE_NUMBER = "guardian_phone_number"
    private const val NO_USER = -1L
    private const val NO_GUARDIAN = -1L

    fun getUserId(context: Context): Long? {
        val id = prefs(context).getLong(KEY_USER_ID, NO_USER)
        return if (id == NO_USER) null else id
    }

    fun setUserId(context: Context, userId: Long) {
        prefs(context).edit().putLong(KEY_USER_ID, userId).apply()
    }

    fun isRegistered(context: Context): Boolean = getUserId(context) != null

    /**
     * The one optional family member collected at registration, if any -- cached locally
     * (not re-fetched from the backend, there's no GET endpoint for it) so
     * [com.scamguard.spike.notifications.GuardianAlerter] can send an SMS from a background
     * Worker with no network round trip, and so EditContactActivity has something to
     * pre-fill/know which id to PATCH-equivalent-POST to. Only ever set from
     * RegistrationActivity/EditContactActivity today; if a second family member is ever
     * addable later, this single-value cache is the first thing that needs to become a list.
     */
    fun getGuardianPhoneNumber(context: Context): String? =
        prefs(context).getString(KEY_GUARDIAN_PHONE_NUMBER, null)

    fun getGuardianUsername(context: Context): String? =
        prefs(context).getString(KEY_GUARDIAN_USERNAME, null)

    fun getGuardianId(context: Context): Long? {
        val id = prefs(context).getLong(KEY_GUARDIAN_ID, NO_GUARDIAN)
        return if (id == NO_GUARDIAN) null else id
    }

    /** Set together -- there's no case where only one of these three is known. */
    fun setGuardian(context: Context, id: Long, username: String, phoneNumber: String) {
        prefs(context).edit()
            .putLong(KEY_GUARDIAN_ID, id)
            .putString(KEY_GUARDIAN_USERNAME, username)
            .putString(KEY_GUARDIAN_PHONE_NUMBER, phoneNumber)
            .apply()
    }

    /** Wipes everything -- user id, guardian id/username/phone. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Self-heals from a locally-cached user_id the backend no longer recognizes -- the
     * backend's SQLite has no persistent volume, so any redeploy/restart resets it and
     * orphans every device's saved id (this is the same failure `allowBackup=false` above
     * guards against for the reinstall case, but that fix can't help when the *backend*,
     * not the device, is what reset). Clears the stale session and bounces straight to
     * RegistrationActivity so the app recovers on its own instead of getting stuck showing
     * "Check failed" forever. NEW_TASK + CLEAR_TASK so this works both from a foreground
     * Activity and from a background Worker's applicationContext, and so there's no stale
     * screen left on the back stack to navigate back into.
     */
    fun recoverFromMissingUser(context: Context) {
        clear(context)
        val intent = Intent(context, RegistrationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
