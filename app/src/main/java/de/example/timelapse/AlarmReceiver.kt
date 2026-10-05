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
        // Acquire short WakeLock to bridge the gap until CameraForegroundService or DataSyncService
        // receives the intent and takes over.
        WakeLockHolder.acquire(context, 30_000L)
        
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
