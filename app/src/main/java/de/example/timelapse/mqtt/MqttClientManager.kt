package de.example.timelapse.mqtt

import android.content.Context
import android.util.Log
import de.example.timelapse.*
import de.example.timelapse.network.NetworkMonitor
import de.example.timelapse.service.CameraForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties

class MqttClientManager(private val context: Context) {
    private val settings = SettingsManager(context)
    private val networkMonitor = NetworkMonitor.getInstance(context)

    companion object {
        private var sharedClient: MqttAsyncClient? = null
        private val mutex = Mutex()

        @Volatile
        private var lastConnectFailureTime: Long = 0L
        private const val CONNECT_COOLDOWN_MS = 30_000L

        fun resetCooldown() {
            lastConnectFailureTime = 0L
        }
    }

    suspend fun publish(topic: String, payload: String, retained: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val host = settings.mqttHost
        if (host.isBlank()) return@withContext false
        try {
            val c = getConnectedClient()
            if (c.isConnected) {
                val msg = MqttMessage(payload.toByteArray()).apply { qos = 1; isRetained = retained }
                c.publish(topic, msg).waitForCompletion(5000)
                return@withContext true
            }
        } catch (t: Throwable) {
            Log.w("Timelapse", "MQTT publish failed for $topic: ${t.message}")
        }
        return@withContext false
    }

    suspend fun connectAndDiscover() = withContext(Dispatchers.IO) {
        try {
            getConnectedClient()
            MqttDiscovery(this@MqttClientManager, settings, context).publishAll()
        } catch (t: Throwable) {
            Log.w("Timelapse", "MQTT connectAndDiscover failed: ${t.message}")
        }
    }

    suspend fun handleMqttCommands() = withContext(Dispatchers.IO) {
        val host = settings.mqttHost
        if (host.isBlank()) return@withContext
        val c = try { getConnectedClient() } catch (_: Throwable) { return@withContext }

        try {
            MqttDiscovery(this@MqttClientManager, settings, context).publishAll()

            val base = "timelapse/${settings.deviceId}"
            val topics = arrayOf(
                "$base/upload/set",
                "$base/enabled/set",
                "$base/time_window/set",
                "$base/window_start/set",
                "$base/window_end/set",
                "$base/capture_interval/set",
                "$base/smb_upload/set",
                "$base/smb_upload_time/set"
            )

            c.setCallback(object : MqttCallback {
                override fun disconnected(dr: MqttDisconnectResponse?) {
                    Log.d("Timelapse", "MQTT client disconnected: ${dr?.reasonString}")
                }
                override fun mqttErrorOccurred(ex: MqttException?) {}
                override fun messageArrived(t: String?, msg: MqttMessage?) {
                    val payload = msg?.toString() ?: ""
                    if (payload.isBlank()) return

                    var changed = false
                    when (t) {
                        "$base/upload/set" -> if (payload == "ON" || payload == "PRESS") { settings.manualUploadRequested = true; changed = true }
                        "$base/enabled/set" -> {
                            val on = (payload == "ON")
                            if (on != settings.timelapseEnabled) {
                                if (on) settings.lastCaptureAt = 0L
                                settings.timelapseEnabled = on
                                AlarmScheduler(context).scheduleAll()
                                CameraForegroundService.nudge()
                                changed = true
                            }
                        }
                        "$base/time_window/set" -> {
                            val on = (payload == "ON")
                            if (on != settings.timeWindowEnabled) {
                                settings.timeWindowEnabled = on
                                AlarmScheduler(context).scheduleAll()
                                CameraForegroundService.nudge()
                                changed = true
                            }
                        }
                        "$base/window_start/set" -> {
                            val parts = payload.split(":")
                            if (parts.size == 2) {
                                settings.windowStartHour = parts[0].toIntOrNull() ?: settings.windowStartHour
                                settings.windowStartMinute = parts[1].toIntOrNull() ?: settings.windowStartMinute
                                AlarmScheduler(context).scheduleNextCapture()
                                CameraForegroundService.nudge()
                                changed = true
                            }
                        }
                        "$base/window_end/set" -> {
                            val parts = payload.split(":")
                            if (parts.size == 2) {
                                settings.windowEndHour = parts[0].toIntOrNull() ?: settings.windowEndHour
                                settings.windowEndMinute = parts[1].toIntOrNull() ?: settings.windowEndMinute
                                AlarmScheduler(context).scheduleNextCapture()
                                CameraForegroundService.nudge()
                                changed = true
                            }
                        }
                        "$base/capture_interval/set" -> {
                            val minutes = payload.toIntOrNull()
                            if (minutes != null && minutes > 0) {
                                settings.captureIntervalMinutes = minutes
                                AlarmScheduler(context).scheduleAll()
                                CameraForegroundService.nudge()
                                changed = true
                            }
                        }
                        "$base/smb_upload/set" -> {
                            val on = (payload == "ON")
                            if (on != settings.smbUploadEnabled) {
                                settings.smbUploadEnabled = on
                                AlarmScheduler(context).scheduleAll()
                                CameraForegroundService.nudge()
                                changed = true
                            }
                        }
                        "$base/smb_upload_time/set" -> {
                            val parts = payload.split(":")
                            if (parts.size == 2) {
                                val h = parts[0].toIntOrNull()
                                val m = parts[1].toIntOrNull()
                                if (h != null && m != null && h in 0..23 && m in 0..59) {
                                    settings.smbUploadHour = h
                                    settings.smbUploadMinute = m
                                    AlarmScheduler(context).scheduleUpload()
                                    CameraForegroundService.nudge()
                                    changed = true
                                }
                            }
                        }
                    }

                    if (changed) {
                        val emptyMsg = MqttMessage("".toByteArray()).apply { qos = 1; isRetained = true }
                        try { c.publish(t, emptyMsg) } catch (_: Throwable) {}
                    }
                }
                override fun deliveryComplete(token: IMqttToken?) {}
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i("Timelapse", "MQTT connectComplete (reconnect=$reconnect)")
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val ts = arrayOf(
                                "$base/upload/set",
                                "$base/enabled/set",
                                "$base/time_window/set",
                                "$base/window_start/set",
                                "$base/window_end/set",
                                "$base/capture_interval/set",
                                "$base/smb_upload/set",
                                "$base/smb_upload_time/set"
                            )
                            c.subscribe(ts, IntArray(ts.size) { 1 })
                        } catch (t: Throwable) {
                            Log.w("Timelapse", "MQTT re-subscribe failed: ${t.message}")
                        }
                    }
                }
                override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
            })

            c.subscribe(topics, IntArray(topics.size) { 1 }).waitForCompletion(5000)
        } catch (t: Throwable) {
            Log.w("Timelapse", "handleMqttCommands failed: ${t.message}")
        }
    }

    private suspend fun getConnectedClient(): MqttAsyncClient {
        val host = settings.mqttHost
        if (host.isBlank()) throw Exception("No MQTT host")

        if (!networkMonitor.isCurrentlyOnline()) {
            throw Exception("Network offline")
        }

        sharedClient?.let { if (it.isConnected) return it }

        val now = System.currentTimeMillis()
        if (now - lastConnectFailureTime < CONNECT_COOLDOWN_MS) {
            throw Exception("MQTT connect on cooldown after recent failure")
        }

        return mutex.withLock {
            sharedClient?.let { if (it.isConnected) return it }

            if (!networkMonitor.isCurrentlyOnline()) {
                throw Exception("Network offline")
            }

            if (System.currentTimeMillis() - lastConnectFailureTime < CONNECT_COOLDOWN_MS) {
                throw Exception("MQTT connect on cooldown after recent failure")
            }

            try {
                sharedClient?.let {
                    if (!it.isConnected) {
                        try { it.close() } catch (_: Throwable) {}
                    }
                }
                val uri = (if (settings.mqttTls) "ssl" else "tcp") + "://$host:${settings.mqttPort}"
                val c = MqttAsyncClient(uri, settings.mqttClientId, null)
                val o = MqttConnectionOptions().apply {
                    isCleanStart = false
                    isAutomaticReconnect = true
                    keepAliveInterval = 600
                    val secrets = SecureSecrets.getInstance(context)
                    userName = secrets.mqttUsername.ifBlank { settings.mqttUsername }
                    password = secrets.mqttPassword.ifBlank { settings.mqttPassword }.toByteArray()
                }
                c.connect(o).waitForCompletion(15_000)
                sharedClient = c
                lastConnectFailureTime = 0L
                c
            } catch (t: Throwable) {
                lastConnectFailureTime = System.currentTimeMillis()
                Log.w("Timelapse", "MQTT connection failed: ${t.message}")
                throw t
            }
        }
    }

    fun close() {
        // Kept open for app lifecycle reuse
    }
}
