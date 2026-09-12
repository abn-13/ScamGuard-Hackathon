package com.scamguard.spike.registration

import android.content.Context

/**
 * Whether this device has completed registration against the backend, and if so, which
 * `user_id` to send with every `/check-message` call. Backed by SharedPreferences -- this
 * is a single-user-per-device spike, not a multi-account app, so a flat key/value store is
 * enough (no need for a full local DB just to remember one id).
 */
object UserSession {
    private const val PREFS_NAME = "scamguard_session"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_GUARDIAN_PHONE_NUMBER = "guardian_phone_number"
    private const val NO_USER = -1L

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
     * (not re-fetched from the backend) so [com.scamguard.spike.notifications.GuardianAlerter]
     * can send an SMS from a background Worker with no network round trip. Only ever set
     * from RegistrationActivity today; if a second family member is ever addable later,
     * this single-value cache is the first thing that needs to become a list.
     */
    fun getGuardianPhoneNumber(context: Context): String? =
        prefs(context).getString(KEY_GUARDIAN_PHONE_NUMBER, null)

    fun setGuardianPhoneNumber(context: Context, phoneNumber: String) {
        prefs(context).edit().putString(KEY_GUARDIAN_PHONE_NUMBER, phoneNumber).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
