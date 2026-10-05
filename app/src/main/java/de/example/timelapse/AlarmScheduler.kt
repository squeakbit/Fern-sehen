package de.example.timelapse

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import de.example.timelapse.worker.SmbUploadWorker
import java.util.Calendar

/**
 * Manages exact scheduling using Android's [AlarmManager.setAlarmClock] API.
 * The app is intentionally designed with alarm-clock level wake-ups so that
 * deep Doze mode and aggressive OEM power saving (Android 9 to 16+) do NOT
 * suspend or delay periodic timelapse captures or daily uploads.
 */
class AlarmScheduler(private val c: Context) {
    companion object {
        const val UPLOAD = "de.example.timelapse.UPLOAD"
        const val CAPTURE = "de.example.timelapse.CAPTURE"
        private const val RU = 1002
        private const val RC = 1003
    }
    private val am = c.getSystemService(AlarmManager::class.java)

    fun scheduleAll() {
        val s = SettingsManager(c)
        if (s.smbUploadEnabled) scheduleUpload() else cancelUpload()
        if (s.timelapseEnabled) scheduleNextCapture() else cancelCapture()
    }

    /**
     * Daily SMB upload scheduled at fixed time via [AlarmManager.setAlarmClock]
     * to guarantee exact execution even out of deep sleep, with WorkManager as secondary fallback.
     */
    fun scheduleUpload() {
        val s = SettingsManager(c)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.smbUploadHour)
            set(Calendar.MINUTE, s.smbUploadMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        scheduleAlarmClock(UPLOAD, RU, cal.timeInMillis)
        SmbUploadWorker.schedule(c)
    }

    fun cancelUpload() {
        SmbUploadWorker.cancel(c)
        am.cancel(pending(UPLOAD, RU))
    }

    fun cancelCapture() {
        am.cancel(pending(CAPTURE, RC))
    }

    /**
     * Schedules the next capture alarm.
     * - On modern Android (API 31+ / Android 12–16+) with exact alarm permissions,
     *   uses [setExactAndAllowWhileIdle] for periodic captures (>= 1m) to prevent status bar alarm icon and system broadcast churn.
     * - On older Android versions (API < 31, e.g. Android 9),
     *   continues using [setAlarmClock] to guarantee unthrottled execution from deep Doze sleep.
     */
    fun scheduleNextCapture() {
        val s = SettingsManager(c)
        val waitMs = TimeWindowUtils.msUntilNextCapture(s)
        val at = System.currentTimeMillis() + waitMs + 500L
        val useExactWhileIdle = Build.VERSION.SDK_INT >= 31 && s.captureIntervalMinutes >= 1 && am.canScheduleExactAlarms()
        if (useExactWhileIdle) {
            scheduleExactAllowWhileIdle(CAPTURE, RC, at)
        } else {
            scheduleAlarmClock(CAPTURE, RC, at)
        }
    }

    private fun scheduleExactAllowWhileIdle(action: String, request: Int, at: Long) {
        val pi = pending(action, request)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            Log.w("Timelapse", "SecurityException setting exact alarm, using fallback", e)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (t: Throwable) {
                Log.e("Timelapse", "Failed to schedule fallback alarm", t)
            }
        }
    }

    private fun scheduleAlarmClock(action: String, request: Int, at: Long) {
        val pi = pending(action, request)
        val canExact = if (Build.VERSION.SDK_INT >= 31) {
            am.canScheduleExactAlarms()
        } else {
            true
        }

        try {
            if (canExact) {
                // Pass Activity PendingIntent for showIntent so AlarmManagerService and OEM
                // power management (e.g. Huawei EMUI in Doze) validate the AlarmClockInfo properly.
                val showIntent = PendingIntent.getActivity(
                    c,
                    0,
                    Intent(c, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val info = AlarmManager.AlarmClockInfo(at, showIntent)
                am.setAlarmClock(info, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w("Timelapse", "SecurityException setting alarm clock, using fallback", e)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (t: Throwable) {
                Log.e("Timelapse", "Failed to schedule fallback alarm", t)
            }
        }
    }

    private fun pending(action: String, request: Int): PendingIntent {
        val intent = Intent(c, AlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            c,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
