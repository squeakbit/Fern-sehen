package de.example.timelapse.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.StorageCleanupHelper
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.smb.SmbUploader
import java.time.Instant
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for handling SMB uploads efficiently in the background.
 * WorkManager automatically respects Doze mode, App Standby Buckets (Android 9+),
 * and network constraints to batch execution when the device is connected to Wi-Fi.
 */
class SmbUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settings = SettingsManager(applicationContext)
        if (!settings.smbUploadEnabled) {
            return Result.success()
        }

        Log.i("TimelapseWorker", "Starting background SMB upload worker")
        val mqtt = MqttClientManager(applicationContext)
        return try {
            val result = SmbUploader(applicationContext).uploadPendingPhotos()
            mqtt.publish("timelapse/${settings.deviceId}/last_upload_count", result.uploaded.toString())
            mqtt.publish("timelapse/${settings.deviceId}/last_upload_failed", result.failed.toString())
            if (result.lastError != null) {
                mqtt.publish("timelapse/${settings.deviceId}/last_error", result.lastError)
            }
            mqtt.publish("timelapse/${settings.deviceId}/last_upload", Instant.now().toString())
            
            StorageCleanupHelper.cleanOldEmptyFolders(applicationContext)
            
            if (result.failed > 0 && result.uploaded == 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (t: Throwable) {
            Log.e("TimelapseWorker", "Background upload failed", t)
            Result.retry()
        } finally {
            try { mqtt.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        const val WORK_NAME = "timelapse_daily_smb_upload"

        /**
         * Schedules periodic daily SMB upload using WorkManager with network constraints.
         */
        fun schedule(context: Context) {
            val settings = SettingsManager(context)
            if (!settings.smbUploadEnabled) {
                cancel(context)
                return
            }

            // Calculate initial delay until configured hour/minute
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, settings.smbUploadHour)
                set(Calendar.MINUTE, settings.smbUploadMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val initialDelayMs = target.timeInMillis - now.timeInMillis

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val uploadWorkRequest = PeriodicWorkRequestBuilder<SmbUploadWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                uploadWorkRequest
            )
            Log.i("TimelapseWorker", "Scheduled daily SMB upload worker with initial delay ${initialDelayMs / 1000}s")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i("TimelapseWorker", "Cancelled SMB upload worker")
        }
    }
}
