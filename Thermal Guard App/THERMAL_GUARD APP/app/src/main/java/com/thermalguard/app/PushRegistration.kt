package com.thermalguard.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessaging
import com.thermalguard.app.data.ThermalRepository

/**
 * Registers this phone for Thermal Guard alarm pushes.
 *
 * The Raspberry Pi sends each push only to the FCM tokens stored under
 * /thermal_guard/users/{uid}/fcm_tokens of users who are active admin/staff at
 * that moment. FCM topics (used before) cannot be revoked from the server, so
 * a removed or deactivated staff member's phone kept receiving alarms.
 *
 * - enable(): save this phone's token (only when it changed, or daily, so the
 *   profile listener reacting to our own write cannot loop), then leave the old
 *   topics. If saving fails the old topic subscription is left untouched.
 * - disable(): remove the token from Firebase, leave the topics and delete the
 *   FCM token itself, so even a copy left in the database stops working.
 */
object PushRegistration {
    private const val PREFS = "thermal_guard_push"
    private const val KEY_TOKEN = "saved_token"
    private const val KEY_SAVED_AT = "saved_at"
    private const val REFRESH_MS = 24L * 60 * 60 * 1000
    /** Offline, Firebase never confirms the delete; sign-out must not wait forever. */
    private const val DISABLE_TIMEOUT_MS = 5_000L
    private val LEGACY_TOPICS = listOf("thermal_guard_alerts", "thermal_guard_system")

    fun enable(context: Context, repo: ThermalRepository) {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                save(context, repo, token)
            }
        }
    }

    /** Also called from ThermalMessagingService.onNewToken. */
    fun save(context: Context, repo: ThermalRepository, token: String, force: Boolean = false) {
        if (token.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = prefs.getString(KEY_TOKEN, null) == token &&
            System.currentTimeMillis() - prefs.getLong(KEY_SAVED_AT, 0L) < REFRESH_MS
        if (fresh && !force) return
        repo.saveFcmToken(token) { ok, _ ->
            if (!ok) return@saveFcmToken          // keep any old topic subscription as a fallback
            prefs.edit().putString(KEY_TOKEN, token).putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
            runCatching { LEGACY_TOPICS.forEach { FirebaseMessaging.getInstance().unsubscribeFromTopic(it) } }
        }
    }

    fun disable(context: Context, repo: ThermalRepository, done: () -> Unit = {}) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null)
        runCatching { LEGACY_TOPICS.forEach { FirebaseMessaging.getInstance().unsubscribeFromTopic(it) } }

        val main = Handler(Looper.getMainLooper())
        var reported = false
        fun report() {
            if (reported) return
            reported = true
            main.post(done)
        }
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            prefs.edit().clear().apply()
            runCatching {
                FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { report() }
            }.onFailure { report() }
        }
        // Timed out waiting for Firebase: still drop the token (FCM-side), then carry on.
        main.postDelayed({ finish(); report() }, DISABLE_TIMEOUT_MS)

        // Must run while still signed in: the rules only let the owner remove it.
        if (token != null && repo.uid() != null) {
            repo.deleteFcmToken(token) { _, _ -> finish() }
        } else {
            finish()
        }
    }
}
