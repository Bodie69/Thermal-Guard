package com.thermalguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.ValueEventListener
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.model.LiveSystem
import com.thermalguard.app.model.TemperatureAlertEvaluator
import com.thermalguard.app.model.TemperatureAlertLevel
import com.thermalguard.app.model.TemperatureAlertState
import com.thermalguard.app.model.ZoneReading
import java.util.Locale
import kotlin.math.max

/**
 * Foreground alarm service for WARNING and CRITICAL temperatures.
 *
 * - WARNING loops warning_alert.wav.
 * - CRITICAL loops critical_alarm.wav.
 * - STOP SOUND silences the current event from the notification.
 * - A later escalation from WARNING to CRITICAL is allowed to sound again.
 * - Returning below the warning threshold clears the notification and mute state.
 * - While active, the service observes Firebase live data so it can switch or stop
 *   accurately even after the app UI is closed.
 * - The level comes from the Raspberry Pi's per-zone alarm state (confirmed,
 *   with hysteresis, held while a sensor is offline) - see TemperatureAlertEvaluator.
 * - Losing the Raspberry Pi heartbeat does NOT clear an active alarm: the room
 *   may still be hot and the Pi may have failed because of it. The alarm stays,
 *   marked "LOST CONTACT", until the Pi reports again or someone stops the sound.
 */
class TemperatureAlertService : Service() {
    private val repo by lazy { ThermalRepository() }
    private var liveListener: ValueEventListener? = null
    private var player: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentState = TemperatureAlertState()
    private var silencedRank = 0
    private var latestSystem: LiveSystem? = null
    private var latestZones: List<ZoneReading> = emptyList()
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val controllerWatchdog = object : Runnable {
        override fun run() {
            val system = latestSystem
            if (system != null && currentState.level != TemperatureAlertLevel.NORMAL) {
                val next = TemperatureAlertEvaluator.evaluate(system, latestZones)
                when {
                    // Heartbeat stopped: keep the alarm, tell the user contact is lost.
                    next.controllerLost -> if (!currentState.controllerLost) {
                        applyState(currentState.copy(controllerLost = true))
                    }
                    next.level == TemperatureAlertLevel.NORMAL -> {
                        clearAndStop()
                        return
                    }
                }
            }
            if (liveListener != null) {
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        silencedRank = alarmPrefs().getInt(KEY_SILENCED_RANK, 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SOUND -> silenceCurrentAlert()
            ACTION_RESUME_SOUND -> resumeCurrentAlert()
            ACTION_APPLY_STATE -> {
                val state = stateFromIntent(intent)
                applyState(state)
            }
            else -> restoreLastStateOrStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyState(state: TemperatureAlertState) {
        if (state.level == TemperatureAlertLevel.NORMAL) {
            clearAndStop()
            return
        }

        currentState = state
        saveState(state)
        ensureLiveObserver()

        val muted = silencedRank >= state.level.rank
        if (muted) {
            stopPlayback()
        } else {
            playFor(state.level)
        }

        startForeground(NOTIFICATION_ID, buildNotification(state, muted))
    }

    private fun silenceCurrentAlert() {
        // This path is also used when MainActivity is opened from a killed process.
        // Load the persisted alert directly so STOP never causes the alarm to start first.
        if (currentState.level == TemperatureAlertLevel.NORMAL) {
            currentState = loadSavedState()
        }
        if (currentState.level == TemperatureAlertLevel.NORMAL) {
            stopPlayback()
            stopSelf()
            return
        }
        silencedRank = max(silencedRank, currentState.level.rank)
        alarmPrefs().edit().putInt(KEY_SILENCED_RANK, silencedRank).apply()
        stopPlayback()
        ensureLiveObserver()
        startForeground(NOTIFICATION_ID, buildNotification(currentState, muted = true))
    }

    private fun resumeCurrentAlert() {
        if (currentState.level == TemperatureAlertLevel.NORMAL) {
            restoreLastStateOrStop()
            return
        }
        silencedRank = 0
        alarmPrefs().edit().putInt(KEY_SILENCED_RANK, 0).apply()
        playFor(currentState.level)
        startForeground(NOTIFICATION_ID, buildNotification(currentState, muted = false))
    }

    private fun ensureLiveObserver() {
        if (liveListener != null) return
        liveListener = repo.observeLive({ system, zones ->
            latestSystem = system
            latestZones = zones
            val next = TemperatureAlertEvaluator.evaluate(system, zones)
            when {
                next.controllerLost -> {
                    // Stale snapshot: never clear an alarm because the Pi went quiet.
                    if (currentState.level != TemperatureAlertLevel.NORMAL && !currentState.controllerLost) {
                        applyState(currentState.copy(controllerLost = true))
                    }
                }
                next.level == TemperatureAlertLevel.NORMAL -> clearAndStop()
                else -> applyState(next)
            }
        }, {
            // Keep the last known safety state for short Firebase interruptions.
            // The local watchdog will stop the temperature alarm after the Pi
            // heartbeat crosses the offline timeout.
            if (currentState.level != TemperatureAlertLevel.NORMAL) {
                startForeground(NOTIFICATION_ID, buildNotification(currentState, silencedRank >= currentState.level.rank))
            }
        })
        watchdogHandler.removeCallbacks(controllerWatchdog)
        watchdogHandler.post(controllerWatchdog)
    }

    private fun playFor(level: TemperatureAlertLevel) {
        val wantedRes = when (level) {
            TemperatureAlertLevel.CRITICAL -> R.raw.critical_alarm
            TemperatureAlertLevel.WARNING -> R.raw.warning_alert
            TemperatureAlertLevel.NORMAL -> return
        }

        val currentRes = alarmPrefs().getInt(KEY_PLAYING_RES, 0)
        val alreadyPlaying = runCatching { player?.isPlaying == true }.getOrDefault(false)
        if (alreadyPlaying && currentRes == wantedRes) return

        stopPlayback()
        requestAudioFocus()

        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                resources.openRawResourceFd(wantedRes).use { afd ->
                    requireNotNull(afd) { "Unable to open alarm resource" }
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        }.getOrNull()

        if (player == null) abandonAudioFocus()
        alarmPrefs().edit().putInt(KEY_PLAYING_RES, if (player != null) wantedRes else 0).apply()
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
        alarmPrefs().edit().putInt(KEY_PLAYING_RES, 0).apply()
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        val manager = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener { _ ->
                // A fire alarm keeps sounding when another app (music, a call) takes
                // audio focus. Before, focus loss stopped it for good while the
                // notification still said "Alarm sound active". Only STOP silences it.
            }
            .build()
        audioFocusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) }
        audioFocusRequest = null
    }

    private fun buildNotification(state: TemperatureAlertState, muted: Boolean): Notification {
        val critical = state.level == TemperatureAlertLevel.CRITICAL
        val channelId = if (critical) CHANNEL_CRITICAL else CHANNEL_WARNING
        val levelText = if (critical) "CRITICAL" else "WARNING"
        val temperatureText = when {
            state.sensorOffline -> "SENSOR OFFLINE"
            else -> state.temperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "temperature pending"
        }
        val zone = state.zoneName.ifBlank { "Monitored area" }
        val title = when {
            state.controllerLost -> "LOST CONTACT • last known $levelText"
            else -> "$levelText TEMPERATURE • $temperatureText"
        }
        val thresholdText = when {
            state.controllerLost ->
                "Thermal Guard stopped reporting. Last report: $levelText in $zone. Check the room."
            state.sensorOffline ->
                "Sensor offline • Thermal Guard is holding the $levelText alarm"
            critical -> "Critical threshold ${formatTemp(state.criticalThresholdC)}"
            else -> "Warning threshold ${formatTemp(state.warningThresholdC)}"
        }
        val soundText = if (muted) {
            "Sound stopped • monitoring continues"
        } else {
            "Alarm sound active • tap notification to open app and stop sound"
        }
        val body = if (state.controllerLost) "$thresholdText • $soundText" else "$zone • $thresholdText • $soundText"

        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("destination", "alerts")
                putExtra("stop_alarm_on_open", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIntent = Intent(this, TemperatureAlertService::class.java).apply {
            action = if (muted) ACTION_RESUME_SOUND else ACTION_STOP_SOUND
        }
        val actionPending = PendingIntent.getService(
            this,
            if (muted) REQUEST_RESUME else REQUEST_STOP,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_thermal_guard)
            .setColor(ContextCompat.getColor(this, if (critical) R.color.tg_red else R.color.tg_amber))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(
                if (muted) R.drawable.ic_siren_on else R.drawable.ic_siren_off,
                if (muted) "RESUME APP SOUND" else "STOP APP SOUND",
                actionPending
            )
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(
                CHANNEL_CRITICAL,
                "Critical temperature alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Persistent critical-temperature alarm with stop control"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(
                CHANNEL_WARNING,
                "Warning temperature alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Persistent warning-temperature alarm with stop control"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        ).forEach(manager::createNotificationChannel)
    }

    private fun loadSavedState(): TemperatureAlertState {
        val prefs = alarmPrefs()
        return TemperatureAlertState(
            level = TemperatureAlertLevel.fromName(prefs.getString(KEY_LEVEL, null)),
            temperatureC = prefs.getString(KEY_TEMPERATURE, null)?.toDoubleOrNull(),
            zoneName = prefs.getString(KEY_ZONE, "").orEmpty(),
            warningThresholdC = prefs.getString(KEY_WARNING, "35.0")?.toDoubleOrNull() ?: 35.0,
            criticalThresholdC = prefs.getString(KEY_CRITICAL, "38.0")?.toDoubleOrNull() ?: 38.0,
            source = "restore"
        )
    }

    private fun restoreLastStateOrStop() {
        val saved = loadSavedState()
        if (saved.level == TemperatureAlertLevel.NORMAL) {
            stopSelf()
            return
        }
        applyState(saved)
    }

    private fun saveState(state: TemperatureAlertState) {
        alarmPrefs().edit()
            .putString(KEY_LEVEL, state.level.name)
            .putString(KEY_TEMPERATURE, state.temperatureC?.toString())
            .putString(KEY_ZONE, state.zoneName)
            .putString(KEY_WARNING, state.warningThresholdC.toString())
            .putString(KEY_CRITICAL, state.criticalThresholdC.toString())
            .apply()
    }

    private fun clearAndStop() {
        stopPlayback()
        watchdogHandler.removeCallbacks(controllerWatchdog)
        liveListener?.let(repo::removeLiveObserver)
        liveListener = null
        latestSystem = null
        latestZones = emptyList()
        currentState = TemperatureAlertState()
        alarmPrefs().edit().clear().apply()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun alarmPrefs() = getSharedPreferences(PREFS_ALARM, Context.MODE_PRIVATE)

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(controllerWatchdog)
        liveListener?.let(repo::removeLiveObserver)
        liveListener = null
        latestSystem = null
        latestZones = emptyList()
        stopPlayback()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_APPLY_STATE = "com.thermalguard.app.APPLY_TEMPERATURE_ALERT"
        private const val ACTION_STOP_SOUND = "com.thermalguard.app.STOP_TEMPERATURE_SOUND"
        private const val ACTION_RESUME_SOUND = "com.thermalguard.app.RESUME_TEMPERATURE_SOUND"

        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_TEMPERATURE = "temperature"
        private const val EXTRA_HAS_TEMPERATURE = "has_temperature"
        private const val EXTRA_ZONE = "zone"
        private const val EXTRA_WARNING = "warning"
        private const val EXTRA_CRITICAL = "critical"
        private const val EXTRA_SOURCE = "source"

        private const val PREFS_ALARM = "thermal_guard_temperature_alarm"
        private const val KEY_LEVEL = "level"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_ZONE = "zone"
        private const val KEY_WARNING = "warning"
        private const val KEY_CRITICAL = "critical"
        private const val KEY_SILENCED_RANK = "silenced_rank"
        private const val KEY_PLAYING_RES = "playing_res"

        private const val CHANNEL_CRITICAL = "thermal_guard_critical_alarm_v96"
        private const val CHANNEL_WARNING = "thermal_guard_warning_alarm_v96"
        private const val NOTIFICATION_ID = 9601
        private const val REQUEST_OPEN = 9602
        private const val REQUEST_STOP = 9603
        private const val REQUEST_RESUME = 9604
        private const val WATCHDOG_INTERVAL_MS = 1_000L

        fun apply(context: Context, state: TemperatureAlertState) {
            if (!notificationsEnabled(context)) {
                clear(context)
                return
            }
            if (state.level == TemperatureAlertLevel.NORMAL) {
                clear(context)
                return
            }
            val intent = Intent(context, TemperatureAlertService::class.java).apply {
                action = ACTION_APPLY_STATE
                putExtra(EXTRA_LEVEL, state.level.name)
                putExtra(EXTRA_HAS_TEMPERATURE, state.temperatureC != null)
                state.temperatureC?.let { putExtra(EXTRA_TEMPERATURE, it) }
                putExtra(EXTRA_ZONE, state.zoneName)
                putExtra(EXTRA_WARNING, state.warningThresholdC)
                putExtra(EXTRA_CRITICAL, state.criticalThresholdC)
                putExtra(EXTRA_SOURCE, state.source)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun silenceIfActive(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_ALARM, Context.MODE_PRIVATE)
            val level = TemperatureAlertLevel.fromName(prefs.getString(KEY_LEVEL, null))
            if (level == TemperatureAlertLevel.NORMAL) return
            // MainActivity is foreground when this is called, so a normal service start
            // is allowed and avoids the 5-second foreground-service requirement when
            // there is no active alarm to restore.
            context.startService(Intent(context, TemperatureAlertService::class.java).apply {
                action = ACTION_STOP_SOUND
            })
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_ALARM, Context.MODE_PRIVATE).edit().clear().apply()
            context.stopService(Intent(context, TemperatureAlertService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        /** True while a WARNING/CRITICAL alarm (sounding or muted) is active. */
        fun isAlarmActive(context: Context): Boolean =
            TemperatureAlertLevel.fromName(
                context.getSharedPreferences(PREFS_ALARM, Context.MODE_PRIVATE).getString(KEY_LEVEL, null)
            ) != TemperatureAlertLevel.NORMAL

        fun notificationsEnabled(context: Context): Boolean =
            context.getSharedPreferences("thermal_guard_ui", Context.MODE_PRIVATE)
                .getBoolean("notifications", true)

        private fun stateFromIntent(intent: Intent): TemperatureAlertState {
            val hasTemperature = intent.getBooleanExtra(EXTRA_HAS_TEMPERATURE, false)
            return TemperatureAlertState(
                level = TemperatureAlertLevel.fromName(intent.getStringExtra(EXTRA_LEVEL)),
                temperatureC = if (hasTemperature) intent.getDoubleExtra(EXTRA_TEMPERATURE, 0.0) else null,
                zoneName = intent.getStringExtra(EXTRA_ZONE).orEmpty(),
                warningThresholdC = intent.getDoubleExtra(EXTRA_WARNING, 35.0),
                criticalThresholdC = intent.getDoubleExtra(EXTRA_CRITICAL, 38.0),
                source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
            )
        }

        private fun formatTemp(value: Double): String = String.format(Locale.US, "%.1f °C", value)
    }
}
