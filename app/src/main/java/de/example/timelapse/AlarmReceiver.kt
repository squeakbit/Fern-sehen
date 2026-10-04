package de.example.timelapse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.service.DataSyncService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Acquire WakeLock to bridge the gap until CameraForegroundService or DataSyncService
        // completes its work and explicitly calls WakeLockHolder.release().
        WakeLockHolder.acquire(context, 3 * 60_000L)
        
        val scheduler = AlarmScheduler(context)
        when (intent.action) {
            AlarmScheduler.UPLOAD -> {
                scheduler.scheduleUpload()
                if (CameraForegroundService.isServiceRunning()) {
                    CameraForegroundService.triggerDailyUpload()
                } else {
                    val uploadIntent = Intent(context, DataSyncService::class.java).setAction(DataSyncService.ACTION_UPLOAD)
                    try {
                        ContextCompat.startForegroundService(context, uploadIntent)
                    } catch (t: Throwable) {
                        Log.w("Timelapse", "Failed to start upload sync service", t)
                    }
                }
            }
            AlarmScheduler.CAPTURE -> {
                // On older Android versions (API < 29, e.g. Android 9), launching the wakeup activity
                // with minimum brightness (0.01f) is necessary to wake up OEM camera HAL hardware.
                // On modern Android versions (API 29+, Android 10-16+), foreground services handle camera access
                // natively in the background, so we skip activity launch to prevent display panel power draw.
                if (Build.VERSION.SDK_INT < 29) {
                    val wakeupIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("EXTRA_ALARM_CAPTURE", true)
                    }
                    try {
                        context.startActivity(wakeupIntent)
                    } catch (e: Throwable) {
                        Log.w("Timelapse", "Activity wakeup call restricted or failed", e)
                    }
                }

                if (CameraForegroundService.isServiceRunning()) {
                    CameraForegroundService.nudge()
                } else {
                    try {
                        CameraForegroundService.ensureServiceRunning(context)
                        CameraForegroundService.nudge()
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
