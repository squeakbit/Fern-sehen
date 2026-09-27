package de.example.timelapse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.service.DataSyncService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WakeLockHolder.acquire(context, 5 * 60_000L)
        try {
            val scheduler = AlarmScheduler(context)
            when (intent.action) {
                AlarmScheduler.UPLOAD -> {
                    scheduler.scheduleUpload()
                    val uploadIntent = Intent(context, DataSyncService::class.java).setAction(DataSyncService.ACTION_UPLOAD)
                    try {
                        ContextCompat.startForegroundService(context, uploadIntent)
                    } catch (t: Throwable) {
                        Log.w("Timelapse", "Failed to start upload sync service", t)
                    }
                }
                AlarmScheduler.CAPTURE -> {
                    // Intentional Wecker-App wake up call: Brief activity launch with minimum brightness (0.01f)
                    // ensures camera HAL hardware and process state wake up on aggressive OEM Android versions (Android 9 to 16+).
                    val wakeupIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("EXTRA_ALARM_CAPTURE", true)
                    }
                    try {
                        context.startActivity(wakeupIntent)
                    } catch (t: Throwable) {
                        Log.w("Timelapse", "Activity wakeup call restricted or failed", t)
                    }

                    try {
                        CameraForegroundService.ensureServiceRunning(context)
                    } catch (_: Throwable) {
                    }

                    if (CameraForegroundService.isServiceRunning()) {
                        CameraForegroundService.nudge()
                    }
                }
            }
        } finally {
            WakeLockHolder.release()
        }
    }
}
