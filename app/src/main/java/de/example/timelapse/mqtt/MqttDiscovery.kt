package de.example.timelapse.mqtt
import android.content.Context
import android.os.BatteryManager
import android.util.Log
import de.example.timelapse.SettingsManager
import de.example.timelapse.data.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

class MqttDiscovery(private val mqtt: MqttClientManager, private val s: SettingsManager, private val context: Context) {
    private val base = "timelapse/${s.deviceId}"

    private fun device() = JSONObject().apply {
        put("identifiers", JSONArray().put(s.deviceId))
        put("name", s.deviceName)
        put("manufacturer", "Fern-Sehen")
        put("model", "Camera")
    }

    suspend fun publishAll() {
        Log.i("Timelapse", "MqttDiscovery: starting publishAll for ${s.deviceId}")
        sensor("battery", "Akku", "$base/battery", "mdi:battery", "%")
        sensor("photos_pending", "Fotos ausstehend", "$base/photos_pending", "mdi:image-multiple-outline", null)
        sensor("last_photo", "Letztes Foto", "$base/last_photo", "mdi:camera-timer", "timestamp")
        sensor("last_upload", "Letzter Upload", "$base/last_upload", "mdi:cloud-upload-outline", "timestamp")
        sensor("last_upload_failed", "Letzter Upload Fehler", "$base/last_upload_failed", "mdi:alert-circle-outline", null)
        sensor("last_error", "Letzter Fehler", "$base/last_error", "mdi:alert", null)

        // Main Enable Switch
        config("switch", "enabled", JSONObject().apply {
            put("name", "${s.deviceName} Aktiv")
            put("unique_id", "${s.deviceId}_enabled")
            put("command_topic", "$base/enabled/set")
            put("state_topic", "$base/enabled/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("retain", true)
            put("optimistic", false)
            put("icon", "mdi:camera-timer")
            put("device", device())
        })

        // Time Window Switch
        config("switch", "time_window", JSONObject().apply {
            put("name", "${s.deviceName} Zeitfenster")
            put("unique_id", "${s.deviceId}_time_window")
            put("command_topic", "$base/time_window/set")
            put("state_topic", "$base/time_window/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("retain", true)
            put("optimistic", false)
            put("icon", "mdi:timetable")
            put("device", device())
        })

        // Window Start Time
        config("text", "window_start", JSONObject().apply {
            put("name", "${s.deviceName} Startzeit")
            put("unique_id", "${s.deviceId}_window_start")
            put("command_topic", "$base/window_start/set")
            put("state_topic", "$base/window_start/state")
            put("pattern", "^[0-2][0-9]:[0-5][0-9]$")
            put("mode", "text")
            put("icon", "mdi:clock-start")
            put("device", device())
        })

        // Window End Time
        config("text", "window_end", JSONObject().apply {
            put("name", "${s.deviceName} Endzeit")
            put("unique_id", "${s.deviceId}_window_end")
            put("command_topic", "$base/window_end/set")
            put("state_topic", "$base/window_end/state")
            put("pattern", "^[0-2][0-9]:[0-5][0-9]$")
            put("mode", "text")
            put("icon", "mdi:clock-end")
            put("device", device())
        })

        // Capture Interval (Number / Text input)
        config("text", "capture_interval", JSONObject().apply {
            put("name", "${s.deviceName} Intervall (Minuten)")
            put("unique_id", "${s.deviceId}_capture_interval")
            put("command_topic", "$base/capture_interval/set")
            put("state_topic", "$base/capture_interval/state")
            put("pattern", "^[0-9]+$")
            put("mode", "text")
            put("icon", "mdi:update")
            put("device", device())
        })
        
        // Remove legacy switch entity in HA
        mqtt.publish("homeassistant/switch/${s.deviceId}_manual_upload/config", "", true)

        // Manual Upload Trigger Button
        config("button", "manual_upload", JSONObject().apply {
            put("name", "${s.deviceName} Manueller Upload")
            put("unique_id", "${s.deviceId}_manual_upload")
            put("command_topic", "$base/upload/set")
            put("payload_press", "ON")
            put("icon", "mdi:cloud-upload")
            put("device", device())
        })

        // Upload Active Binary Sensor
        config("binary_sensor", "upload_active", JSONObject().apply {
            put("name", "${s.deviceName} Upload Aktiv")
            put("unique_id", "${s.deviceId}_upload_active")
            put("state_topic", "$base/upload/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("icon", "mdi:cloud-upload-outline")
            put("device", device())
        })

        // SMB Auto-Upload Switch
        config("switch", "smb_upload", JSONObject().apply {
            put("name", "${s.deviceName} SMB Auto-Upload")
            put("unique_id", "${s.deviceId}_smb_upload")
            put("command_topic", "$base/smb_upload/set")
            put("state_topic", "$base/smb_upload/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("retain", true)
            put("optimistic", false)
            put("icon", "mdi:folder-sync")
            put("device", device())
        })

        // SMB Upload Time
        config("text", "smb_upload_time", JSONObject().apply {
            put("name", "${s.deviceName} SMB Uploadzeit")
            put("unique_id", "${s.deviceId}_smb_upload_time")
            put("command_topic", "$base/smb_upload_time/set")
            put("state_topic", "$base/smb_upload_time/state")
            put("pattern", "^[0-2][0-9]:[0-5][0-9]$")
            put("mode", "text")
            put("icon", "mdi:clock-outline")
            put("device", device())
        })
        
        publishState()
        Log.i("Timelapse", "MqttDiscovery: finished publishAll for ${s.deviceId}")
    }

    /**
     * Publishes the current sensor values (retained) so entities show real
     * data right away instead of "unbekannt" until the next scheduled
     * capture or upload.
     */
    suspend fun publishState() {
        val dao = AppDatabase.getInstance(context).photoDao()
        val pendingCount = try { dao.getPendingCount().toString() } catch (_: Throwable) { "0" }
        val lastPhoto = try { dao.getLastPhoto()?.let { Instant.ofEpochMilli(it.capturedAt).toString() } } catch (_: Throwable) { null }
        val bat = try { getBattery().toString() } catch (_: Throwable) { "100" }

        safePublish("$base/battery", bat)
        safePublish("$base/photos_pending", pendingCount)
        safePublish("$base/enabled/state", if (s.timelapseEnabled) "ON" else "OFF")
        safePublish("$base/time_window/state", if (s.timeWindowEnabled) "ON" else "OFF")
        safePublish("$base/window_start/state", String.format(Locale.US, "%02d:%02d", s.windowStartHour, s.windowStartMinute))
        safePublish("$base/window_end/state", String.format(Locale.US, "%02d:%02d", s.windowEndHour, s.windowEndMinute))
        safePublish("$base/capture_interval/state", s.captureIntervalMinutes.toString())
        safePublish("$base/upload/state", if (s.manualUploadRequested) "ON" else "OFF")
        safePublish("$base/smb_upload/state", if (s.smbUploadEnabled) "ON" else "OFF")
        safePublish("$base/smb_upload_time/state", String.format(Locale.US, "%02d:%02d", s.smbUploadHour, s.smbUploadMinute))
        if (lastPhoto != null) {
            safePublish("$base/last_photo", lastPhoto)
        }
    }

    private suspend fun safePublish(topic: String, payload: String): Boolean {
        return try {
            mqtt.publish(topic, payload)
        } catch (_: Throwable) {
            false
        }
    }

    private fun getBattery(): Int =
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private suspend fun sensor(id: String, name: String, state: String, icon: String, unit: String?) {
        config("sensor", id, JSONObject().apply {
            put("name", "${s.deviceName} $name")
            put("state_topic", state)
            put("icon", icon)
            if (unit == "timestamp") put("device_class", "timestamp")
            else if (unit != null) {
                put("unit_of_measurement", unit)
                if (id == "battery") put("device_class", "battery")
                put("state_class", "measurement")
            }
        })
    }

    private suspend fun config(type: String, id: String, json: JSONObject) {
        if (!json.has("unique_id")) json.put("unique_id", "${s.deviceId}_$id")
        if (!json.has("device")) json.put("device", device())
        val topic = "homeassistant/$type/${s.deviceId}_$id/config"
        Log.d("Timelapse", "MqttDiscovery: publishing config to $topic")
        mqtt.publish(topic, json.toString(), true)
    }
}
