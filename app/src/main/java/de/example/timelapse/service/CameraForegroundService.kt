package de.example.timelapse.service

import android.Manifest
import android.R
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import de.example.timelapse.*
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.camera.PhotoCaptureHelper
import de.example.timelapse.camera.StorageCleanupHelper
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.mqtt.MqttDiscovery
import de.example.timelapse.network.NetworkMonitor
import de.example.timelapse.smb.SmbUploader
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Calendar
import java.util.Locale


/**
 * Since Android 14, a foreground service of type "camera" cannot be
 * started while the app is in the background. This service is started
 * from the foreground and stays alive to run the capture loop.
 */
class CameraForegroundService : Service() {
    companion object {
        const val ACTION_START = "de.example.timelapse.START"
        const val ACTION_STOP = "de.example.timelapse.STOP"
        
        private const val MAX_SINGLE_SLEEP_MS = 5 * 60_000L

        @Volatile
        private var instance: CameraForegroundService? = null

        fun isServiceRunning(): Boolean = instance != null

        fun nudge() {
            instance?.nudgeChannel?.trySend(Unit)
        }

        fun triggerDailyUpload() {
            instance?.triggerDailyUploadInternal()
        }

        /**
         * Standard helper to ensure the service is running, respecting
         * background start restrictions by only attempting it when likely
         * in the foreground.
         */
        fun ensureServiceRunning(context: Context) {
            val s = SettingsManager(context)
            if (!s.timelapseEnabled && s.mqttHost.isBlank()) return
            try {
                val intent = Intent(context, CameraForegroundService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.w("Timelapse", "Failed to start camera service", t)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var hasCameraPermission = false
    private val nudgeChannel = Channel<Unit>(Channel.CONFLATED)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "timelapse_enabled" || key == "capture_interval_minutes" || key == "manual_upload_requested" ||
            key == "time_window_enabled" || key == "window_start_hour" || key == "window_start_minute" ||
            key == "window_end_hour" || key == "window_end_minute" || key == "window_offset_seconds" ||
            key == "smb_upload_enabled" || key == "smb_upload_hour" || key == "smb_upload_minute") {
            nudgeChannel.trySend(Unit)

            if (key == "manual_upload_requested") {
                triggerManualUploadIfNeeded()
            }
            
            // Sync state to MQTT immediately
            if (key == "timelapse_enabled" || key == "time_window_enabled" || key == "window_start_hour" || 
                key == "window_start_minute" || key == "window_end_hour" || key == "window_end_minute" ||
                key == "capture_interval_minutes" || key == "smb_upload_enabled" || key == "smb_upload_hour" ||
                key == "smb_upload_minute" || key == "manual_upload_requested") {
                scope.launch {
                    try {
                        val s = SettingsManager(this@CameraForegroundService)
                        val mqtt = MqttClientManager(this@CameraForegroundService)
                        MqttDiscovery(mqtt, s, this@CameraForegroundService).publishState()
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(10, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(10, notification())
        }
        hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            Log.e("Timelapse", "CAMERA permission not granted - aborting service")
            stopSelf()
            return
        }
        instance = this
        
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val networkMonitor = NetworkMonitor.getInstance(this)
        scope.launch {
            networkMonitor.isOnline.collect { online ->
                if (online) {
                    Log.i("Timelapse", "Network restored: resetting MQTT cooldown and restarting listener")
                    MqttClientManager.resetCooldown()
                    startMqttListenerIfNeeded()
                    triggerManualUploadIfNeeded()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasCameraPermission) {
            WakeLockHolder.release()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                WakeLockHolder.release()
                loopJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startLoopIfNeeded()
                startMqttListenerIfNeeded()
                triggerManualUploadIfNeeded()
                nudgeChannel.trySend(Unit)
            }
        }
        return START_STICKY
    }

    private var mqttJob: Job? = null
    private fun startMqttListenerIfNeeded() {
        val s = SettingsManager(this)
        val activeWindow = TimeWindowUtils.isWithinWindowOrWindowEnd(s)

        if (s.timeWindowEnabled && !activeWindow) {
            // Outside active capture window (off-hours): cancel continuous listener job to avoid Wi-Fi radio wakeups,
            // and perform a single-shot poll for retained command messages.
            if (mqttJob?.isActive == true) {
                mqttJob?.cancel()
                mqttJob = null
                scope.launch {
                    try { MqttClientManager(this@CameraForegroundService).disconnect() } catch (_: Throwable) {}
                }
            }
            scope.launch {
                try { MqttClientManager(this@CameraForegroundService).pollMqttCommandsOnce() } catch (_: Throwable) {}
            }
            return
        }

        if (mqttJob?.isActive == true && MqttClientManager.isConnected()) return
        mqttJob?.cancel()
        mqttJob = scope.launch {
            try {
                val mqtt = MqttClientManager(this@CameraForegroundService)
                mqtt.handleMqttCommands()
            } catch (_: Throwable) {}
        }
    }

    private var manualUploadJob: Job? = null
    private fun triggerManualUploadIfNeeded() {
        val s = SettingsManager(this@CameraForegroundService)
        if (!s.manualUploadRequested) return
        if (manualUploadJob?.isActive == true) return

        manualUploadJob = scope.launch {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val uploadLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timelapse:ManualUpload")
            if (!uploadLock.isHeld) {
                try { uploadLock.acquire(10 * 60_000L) } catch (_: Throwable) {}
            }

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val wifiLock = try {
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Timelapse:ManualUploadWifi")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) { null }

            try {
                val mqtt = MqttClientManager(this@CameraForegroundService)
                mqtt.publish("timelapse/${s.deviceId}/upload/state", "ON")
                MqttDiscovery(mqtt, s, this@CameraForegroundService).publishState()

                val result = withTimeoutOrNull(3 * 60_000L) {
                    SmbUploader(this@CameraForegroundService).uploadPendingPhotos()
                }

                if (result != null) {
                    if (result.uploaded > 0) {
                        mqtt.publish("timelapse/${s.deviceId}/last_upload", Instant.now().toString())
                    }
                    mqtt.publish("timelapse/${s.deviceId}/last_upload_count", result.uploaded.toString())
                    mqtt.publish("timelapse/${s.deviceId}/last_upload_failed", result.failed.toString())
                    if (result.lastError != null) {
                        mqtt.publish("timelapse/${s.deviceId}/last_error", result.lastError)
                    }
                } else {
                    mqtt.publish("timelapse/${s.deviceId}/last_error", "Upload Zeitüberschreitung (SMB/WLAN keine Rückmeldung)")
                }
            } catch (t: Throwable) {
                Log.e("Timelapse", "Manual upload failed", t)
            } finally {
                withContext(NonCancellable) {
                    try {
                        s.manualUploadRequested = false
                        val mqtt = MqttClientManager(this@CameraForegroundService)
                        mqtt.publish("timelapse/${s.deviceId}/upload/state", "OFF")
                        MqttDiscovery(mqtt, s, this@CameraForegroundService).publishState()
                    } catch (_: Throwable) {}
                    if (uploadLock.isHeld) {
                        try { uploadLock.release() } catch (_: Throwable) {}
                    }
                    wifiLock?.let {
                        if (it.isHeld) {
                            try { it.release() } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
    }

    private var dailyUploadJob: Job? = null
    fun triggerDailyUploadInternal() {
        val s = SettingsManager(this@CameraForegroundService)
        if (!s.smbUploadEnabled) return
        if (dailyUploadJob?.isActive == true) return

        dailyUploadJob = scope.launch {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val uploadLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timelapse:DailyUpload")
            if (!uploadLock.isHeld) {
                try { uploadLock.acquire(15 * 60_000L) } catch (_: Throwable) {}
            }

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val wifiLock = try {
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Timelapse:DailyUploadWifi")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) { null }

            try {
                val mqtt = MqttClientManager(this@CameraForegroundService)
                val result = withTimeoutOrNull(10 * 60_000L) {
                    SmbUploader(this@CameraForegroundService).uploadPendingPhotos()
                }

                if (result != null) {
                    mqtt.publish("timelapse/${s.deviceId}/last_upload", Instant.now().toString())
                    mqtt.publish("timelapse/${s.deviceId}/last_upload_count", result.uploaded.toString())
                    mqtt.publish("timelapse/${s.deviceId}/last_upload_failed", result.failed.toString())
                    if (result.lastError != null) {
                        mqtt.publish("timelapse/${s.deviceId}/last_error", result.lastError)
                    }
                    val nowCal = Calendar.getInstance()
                    lastDailyUploadDate = String.format(
                        Locale.US, "%04d-%02d-%02d",
                        nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH) + 1, nowCal.get(Calendar.DAY_OF_MONTH)
                    )
                }
                StorageCleanupHelper.cleanOldEmptyFolders(this@CameraForegroundService)
                MqttDiscovery(mqtt, s, this@CameraForegroundService).publishState()
            } catch (t: Throwable) {
                Log.e("Timelapse", "Daily upload failed", t)
            } finally {
                withContext(NonCancellable) {
                    if (uploadLock.isHeld) try { uploadLock.release() } catch (_: Throwable) {}
                    wifiLock?.let { if (it.isHeld) try { it.release() } catch (_: Throwable) {} }
                }
            }
        }
    }

    private var lastDailyUploadDate = ""

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val serviceLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timelapse:ServiceLoop")
            
            try {
                while (isActive) {
                    val s = SettingsManager(this@CameraForegroundService)
                    
                    if (s.manualUploadRequested) {
                        triggerManualUploadIfNeeded()
                    }

                    if (s.smbUploadEnabled) {
                        val nowCal = Calendar.getInstance()
                        val todayDate = String.format(
                            Locale.US, "%04d-%02d-%02d",
                            nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH) + 1, nowCal.get(Calendar.DAY_OF_MONTH))
                        val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)
                        val currentMinute = nowCal.get(Calendar.MINUTE)
                        if (currentHour == s.smbUploadHour && currentMinute == s.smbUploadMinute && lastDailyUploadDate != todayDate) {
                            triggerDailyUploadInternal()
                        }
                    }

                    if (!s.timelapseEnabled) {
                        AlarmScheduler(this@CameraForegroundService).cancelCapture()
                        WakeLockHolder.release()
                        // Wait indefinitely until nudged via PrefListener
                        nudgeChannel.receive()
                        continue
                    }
                    
                    // Always guarantee that an exact AlarmManager alarm is scheduled in the OS
                    // before going into withTimeoutOrNull, ensuring Doze sleep can wake up CPU.
                    AlarmScheduler(this@CameraForegroundService).scheduleNextCapture()

                    val waitMs = msUntilNextCapture(s)
                    if (waitMs <= 15000L) { // 15s grace period
                        if (waitMs > 0L) {
                            delay(waitMs)
                        }
                        if (!serviceLock.isHeld) serviceLock.acquire(45_000L)
                        try {
                            capture(s)
                        } finally {
                            WakeLockHolder.release()
                            if (serviceLock.isHeld) try { serviceLock.release() } catch (_: Throwable) {}
                        }
                    } else {
                        WakeLockHolder.release()
                        if (serviceLock.isHeld) try { serviceLock.release() } catch (_: Throwable) {}
                        startMqttListenerIfNeeded()
                        
                        withTimeoutOrNull(waitMs) {
                            nudgeChannel.receive()
                        }
                    }
                }
            } finally {
                if (serviceLock.isHeld) try { serviceLock.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun msUntilNextCapture(s: SettingsManager): Long {
        return TimeWindowUtils.msUntilNextCapture(s)
    }

    private suspend fun capture(s: SettingsManager) {
        if (!isWithinWindow(s)) return

        try {
            s.lastCaptureAt = System.currentTimeMillis()
            AlarmScheduler(this).scheduleNextCapture()
        } catch (t: Throwable) {
            Log.e("Timelapse", "failed to schedule next capture", t)
        }
        
        try {
            val captureResult = withTimeoutOrNull(45_000L) {
                val cameras = PhotoCaptureHelper.resolveCameras(this@CameraForegroundService, s)
                if (cameras.isEmpty()) {
                    reportError("Keine passende Kamera gefunden")
                    return@withTimeoutOrNull null
                }
                
                var savedAny = false
                val failures = mutableListOf<String>()
                for ((index, camera) in cameras.withIndex()) {
                    try {
                        if (index > 0) delay(300)
                        val (w, h) = PhotoCaptureHelper.resolveResolution(s, camera.id)
                        PhotoCaptureHelper.captureAndSave(this@CameraForegroundService, camera.id, w, h, s.jpegQuality, PhotoCaptureHelper.cameraLabel(camera))
                        savedAny = true
                    } catch (t: Throwable) {
                        Log.e("Timelapse", "capture failed for camera ${camera.id}", t)
                        failures.add("${camera.id}: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
                if (savedAny) {
                    s.lastCaptureAt = System.currentTimeMillis()
                }
                failures
            }

            if (captureResult == null) {
                s.lastCaptureAt = System.currentTimeMillis()
                reportError("Aufnahme fehlgeschlagen: Zeitüberschreitung / Kamera-Schnittstelle antwortet nicht")
                CameraRepository.clearCache()
                return
            }

            // Consolidate MQTT calls
            try {
                val mqtt = MqttClientManager(this)
                if (captureResult.isNotEmpty()) {
                    mqtt.publish("timelapse/${s.deviceId}/last_error", "Aufnahme fehlgeschlagen: " + captureResult.joinToString("; "))
                } else {
                    mqtt.publish("timelapse/${s.deviceId}/last_error", "")
                }
                MqttDiscovery(mqtt, s, this).publishState()
            } catch (t: Throwable) {
                Log.w("Timelapse", "mqtt state publish failed", t)
            }
        } catch (t: Throwable) {
            Log.e("Timelapse", "capture failed", t)
            reportError("Aufnahme fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun reportError(message: String) {
        scope.launch {
            try {
                val s = SettingsManager(this@CameraForegroundService)
                val mqtt = MqttClientManager(this@CameraForegroundService)
                mqtt.publish("timelapse/${s.deviceId}/last_error", message)
                mqtt.close()
            } catch (_: Throwable) {}
        }
    }

    private fun isWithinWindow(s: SettingsManager): Boolean {
        return TimeWindowUtils.isWithinWindowOrWindowEnd(s)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("camera", "Timelapse", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification = Notification.Builder(this, "camera")
        .setContentTitle("Timelapse läuft")
        .setContentText("Wartet auf nächste Aufnahme …")
        .setSmallIcon(R.drawable.ic_menu_camera)
        .build()

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        loopJob?.cancel()
        scope.launch {
            try { MqttClientManager(this@CameraForegroundService).close() } catch (_: Throwable) {}
            scope.cancel()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
