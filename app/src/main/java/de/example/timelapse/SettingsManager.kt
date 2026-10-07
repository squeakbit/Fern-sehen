package de.example.timelapse

import android.content.Context
import android.provider.Settings
import java.util.UUID

class SettingsManager(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets by lazy { SecureSecrets.getInstance(context) }
    private fun getId(): String {
        val current = p.getString("device_id", null)
        if (current != null) return current
        val id = UUID.randomUUID().toString()
        p.edit().putString("device_id", id).apply()
        return id
    }
    var deviceId: String
        get() = getId()
        set(v) { p.edit().putString("device_id", v).apply() }
    var deviceName: String
        get() = p.getString("device_name", "Fern-Sehen") ?: "Fern-Sehen"
        set(v) = p.edit().putString("device_name", v).apply()
    var timelapseEnabled: Boolean
        get() = p.getBoolean("timelapse_enabled", false)
        set(v) = p.edit().putBoolean("timelapse_enabled", v).apply()
    var captureIntervalMinutes: Int
        get() = p.getInt("capture_interval_minutes", 10)
        set(v) = p.edit().putInt("capture_interval_minutes", v.coerceIn(1, 1440)).apply()
    var lastPreviewCameraId: String
        get() = p.getString("last_preview_camera_id", "") ?: ""
        set(v) = p.edit().putString("last_preview_camera_id", v).apply()
    fun getPinnedGhostPhotoId(cameraLabel: String): Long = p.getLong("pinned_ghost_$cameraLabel", -1L)
    fun setPinnedGhostPhotoId(cameraLabel: String, id: Long) = p.edit().putLong("pinned_ghost_$cameraLabel", id).apply()
    var showGrid: Boolean
        get() = p.getBoolean("show_grid", true)
        set(v) = p.edit().putBoolean("show_grid", v).apply()
    var showGhost: Boolean
        get() = p.getBoolean("show_ghost", false)
        set(v) = p.edit().putBoolean("show_ghost", v).apply()
    var ghostMode: Int
        get() = p.getInt("ghost_mode", 0)
        set(v) = p.edit().putInt("ghost_mode", v).apply()
    var ghostOpacity: Float
        get() = p.getFloat("ghost_opacity", 0.4f)
        set(v) = p.edit().putFloat("ghost_opacity", v).apply()
    var ghostOscillationEnabled: Boolean
        get() = p.getBoolean("ghost_osc_enabled", false)
        set(v) = p.edit().putBoolean("ghost_osc_enabled", v).apply()
    var ghostOscillationMin: Float
        get() = p.getFloat("ghost_osc_min", 0.2f)
        set(v) = p.edit().putFloat("ghost_osc_min", v).apply()
    var ghostOscillationMax: Float
        get() = p.getFloat("ghost_osc_max", 0.8f)
        set(v) = p.edit().putFloat("ghost_osc_max", v).apply()
    /**
     * Freely chosen set of camera IDs to capture with on every scheduled
     * cycle (in no particular guaranteed order - see
     * [de.example.timelapse.camera.PhotoCaptureHelper.resolveCameras] for
     * how this is intersected with the cameras actually present on the
     * device). Any combination is allowed: one camera, a handful, or all of
     * them.
     */
    var selectedCameraIds: Set<String>
        get() {
            val stored = p.getStringSet("selected_camera_ids", null)
            if (stored != null) return HashSet(stored)
            return emptySet()
        }
        set(v) = p.edit().putStringSet("selected_camera_ids", HashSet(v)).apply()
    /**
     * Optional per-camera resolution override, keyed by camera ID. Returns
     * null if no override is set for this camera, meaning [cameraWidth]/
     * [cameraHeight] (the global default) should be used instead.
     */
    fun cameraResolutionOverride(cameraId: String): Pair<Int, Int>? {
        val w = p.getInt("camera_res_${cameraId}_w", -1)
        val h = p.getInt("camera_res_${cameraId}_h", -1)
        return if (w > 0 && h > 0) w to h else null
    }
    fun setCameraResolutionOverride(cameraId: String, width: Int, height: Int) {
        p.edit().putInt("camera_res_${cameraId}_w", width).putInt("camera_res_${cameraId}_h", height).apply()
    }
    fun clearCameraResolutionOverride(cameraId: String) {
        p.edit().remove("camera_res_${cameraId}_w").remove("camera_res_${cameraId}_h").apply()
    }
    /** Default resolution used for any selected camera without its own [cameraResolutionOverride]. */
    var cameraWidth: Int
        get() = p.getInt("camera_width", 1920)
        set(v) = p.edit().putInt("camera_width", v).apply()
    var cameraHeight: Int
        get() = p.getInt("camera_height", 1080)
        set(v) = p.edit().putInt("camera_height", v).apply()
    var jpegQuality: Int
        get() = p.getInt("jpeg_quality", 90)
        set(v) = p.edit().putInt("jpeg_quality", v.coerceIn(1, 100)).apply()
    var focusMode: Int
        get() = p.getInt("focus_mode", 0)
        set(v) {
            p.edit().putInt("focus_mode", v).apply()
            clearSavedFocusDistances()
            lastFocusWindowStartMs = 0L
        }
    fun getSavedFocusDistance(cameraId: String): Float? {
        if (!p.contains("saved_focus_dist_$cameraId")) return null
        val v = p.getFloat("saved_focus_dist_$cameraId", -1f)
        return if (v >= 0f) v else null
    }
    fun setSavedFocusDistance(cameraId: String, distance: Float) {
        p.edit().putFloat("saved_focus_dist_$cameraId", distance.coerceAtLeast(0f)).apply()
    }
    fun clearSavedFocusDistances() {
        val editor = p.edit()
        val keys = HashSet(p.all.keys)
        for (k in keys) {
            if (k.startsWith("saved_focus_dist_")) {
                editor.remove(k)
            }
        }
        editor.apply()
    }
    var lastFocusWindowStartMs: Long
        get() = p.getLong("last_focus_window_start_ms", 0L)
        set(v) = p.edit().putLong("last_focus_window_start_ms", v).apply()
    var smbUploadEnabled: Boolean
        get() = p.getBoolean("smb_upload_enabled", true)
        set(v) = p.edit().putBoolean("smb_upload_enabled", v).apply()
    var smbUploadHour: Int
        get() = p.getInt("smb_upload_hour", 3)
        set(v) = p.edit().putInt("smb_upload_hour", v.coerceIn(0, 23)).apply()
    var smbUploadMinute: Int
        get() = p.getInt("smb_upload_minute", 0)
        set(v) = p.edit().putInt("smb_upload_minute", v.coerceIn(0, 59)).apply()
    var deleteAfterUpload: Boolean
        get() = p.getBoolean("delete_after_upload", false)
        set(v) = p.edit().putBoolean("delete_after_upload", v).apply()
    var mqttHost: String
        get() = p.getString("mqtt_host", "") ?: ""
        set(v) = p.edit().putString("mqtt_host", v).apply()
    var mqttPort: Int
        get() = p.getInt("mqtt_port", 1883)
        set(v) = p.edit().putInt("mqtt_port", v.coerceIn(1, 65535)).apply()
    var mqttTls: Boolean
        get() = p.getBoolean("mqtt_tls", false)
        set(v) = p.edit().putBoolean("mqtt_tls", v).apply()
    var mqttUsername: String
        get() {
            val sec = secrets.mqttUsername
            if (sec.isNotBlank()) return sec
            val legacy = p.getString("mqtt_username", "") ?: ""
            if (legacy.isNotBlank()) {
                secrets.mqttUsername = legacy
                p.edit().remove("mqtt_username").apply()
            }
            return legacy
        }
        set(v) {
            secrets.mqttUsername = v
            p.edit().remove("mqtt_username").apply()
        }
    var mqttPassword: String
        get() {
            val sec = secrets.mqttPassword
            if (sec.isNotBlank()) return sec
            val legacy = p.getString("mqtt_password", "") ?: ""
            if (legacy.isNotBlank()) {
                secrets.mqttPassword = legacy
                p.edit().remove("mqtt_password").apply()
            }
            return legacy
        }
        set(v) {
            secrets.mqttPassword = v
            p.edit().remove("mqtt_password").apply()
        }
    var smbHost: String
        get() = p.getString("smb_host", "") ?: ""
        set(v) = p.edit().putString("smb_host", v).apply()
    var smbShare: String
        get() = p.getString("smb_share", "") ?: ""
        set(v) = p.edit().putString("smb_share", v).apply()
    var smbUsername: String
        get() {
            val sec = secrets.smbUsername
            if (sec.isNotBlank()) return sec
            val legacy = p.getString("smb_username", "") ?: ""
            if (legacy.isNotBlank()) {
                secrets.smbUsername = legacy
                p.edit().remove("smb_username").apply()
            }
            return legacy
        }
        set(v) {
            secrets.smbUsername = v
            p.edit().remove("smb_username").apply()
        }
    var smbPassword: String
        get() {
            val sec = secrets.smbPassword
            if (sec.isNotBlank()) return sec
            val legacy = p.getString("smb_password", "") ?: ""
            if (legacy.isNotBlank()) {
                secrets.smbPassword = legacy
                p.edit().remove("smb_password").apply()
            }
            return legacy
        }
        set(v) {
            secrets.smbPassword = v
            p.edit().remove("smb_password").apply()
        }
    var smbDomain: String
        get() = p.getString("smb_domain", "") ?: ""
        set(v) = p.edit().putString("smb_domain", v).apply()
    var smbRemoteDirectory: String
        get() = p.getString("smb_remote_directory", "Timelapse") ?: "Timelapse"
        set(v) = p.edit().putString("smb_remote_directory", v).apply()
    var timeWindowEnabled: Boolean
        get() = p.getBoolean("time_window_enabled", false)
        set(v) = p.edit().putBoolean("time_window_enabled", v).apply()
    var windowStartHour: Int
        get() = p.getInt("window_start_hour", 18)
        set(v) = p.edit().putInt("window_start_hour", v.coerceIn(0, 23)).apply()
    var windowStartMinute: Int
        get() = p.getInt("window_start_minute", 0)
        set(v) = p.edit().putInt("window_start_minute", v.coerceIn(0, 59)).apply()
    var windowEndHour: Int
        get() = p.getInt("window_end_hour", 6)
        set(v) = p.edit().putInt("window_end_hour", v.coerceIn(0, 23)).apply()
    var windowEndMinute: Int
        get() = p.getInt("window_end_minute", 0)
        set(v) = p.edit().putInt("window_end_minute", v.coerceIn(0, 59)).apply()
    var windowOffsetSeconds: Int
        get() = p.getInt("window_offset_seconds", 10)
        set(v) = p.edit().putInt("window_offset_seconds", v.coerceIn(0, 300)).apply()
    /** Wall-clock time (epoch ms) of the last capture attempt (success or failure), used by
     *  CameraForegroundService's internal loop to know when the next one is due, and also
     *  shown in the UI as a general "still alive" indicator (there is no separate heartbeat). */
    var lastCaptureAt: Long
        get() = p.getLong("last_capture_at", 0L)
        set(v) = p.edit().putLong("last_capture_at", v).apply()
    var manualUploadRequested: Boolean
        get() = p.getBoolean("manual_upload_requested", false)
        set(v) = p.edit().putBoolean("manual_upload_requested", v).apply()
    val mqttClientId: String
        get() = "timelapse-$deviceId"
}