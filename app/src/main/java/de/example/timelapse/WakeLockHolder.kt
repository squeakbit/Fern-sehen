package de.example.timelapse

import android.content.Context
import android.os.PowerManager

/**
 * Bridges the CPU-awake gap between [AlarmReceiver] firing and the service
 * it starts actually finishing its work. The timeout passed to [acquire] is
 * only a safety-net ceiling (in case something hangs, e.g. a stuck network
 * call) - the normal path is for the service to call [release] itself the
 * moment its work completes, so the device isn't kept awake any longer than
 * actually necessary.
 */
object WakeLockHolder {
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context, timeoutMs: Long) {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timelapse:AlarmWakeLock").apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.let {
            try {
                it.acquire(timeoutMs)
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun release() {
        wakeLock?.let {
            try {
                while (it.isHeld) {
                    it.release()
                }
            } catch (_: Throwable) {}
        }
    }
}
