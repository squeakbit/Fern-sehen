package de.example.timelapse

import java.util.Calendar

data class TimeWindowInfo(
    val isInside: Boolean,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val nextWindowStartMs: Long
)

object TimeWindowUtils {

    fun getTimeWindowInfo(s: SettingsManager, nowMs: Long = System.currentTimeMillis()): TimeWindowInfo {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val startHour = s.windowStartHour
        val startMinute = s.windowStartMinute
        val endHour = s.windowEndHour
        val endMinute = s.windowEndMinute

        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute
        val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

        if (startMinutes == endMinutes) {
            // 24-hour window
            return TimeWindowInfo(
                isInside = true,
                windowStartMs = nowMs,
                windowEndMs = nowMs + 86_400_000L,
                nextWindowStartMs = nowMs
            )
        }

        val isSameDay = startMinutes < endMinutes

        if (isSameDay) {
            if (nowMinutes in startMinutes until endMinutes) {
                val startCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val nextStartCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                return TimeWindowInfo(
                    isInside = true,
                    windowStartMs = startCal.timeInMillis,
                    windowEndMs = endCal.timeInMillis,
                    nextWindowStartMs = nextStartCal.timeInMillis
                )
            } else if (nowMinutes < startMinutes) {
                val startCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val prevEndCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val prevStartCal = (prevEndCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return TimeWindowInfo(
                    isInside = false,
                    windowStartMs = prevStartCal.timeInMillis,
                    windowEndMs = prevEndCal.timeInMillis,
                    nextWindowStartMs = startCal.timeInMillis
                )
            } else { // nowMinutes >= endMinutes
                val endCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val nextStartCal = if (startCal.timeInMillis <= nowMs) {
                    (startCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                } else startCal

                return TimeWindowInfo(
                    isInside = false,
                    windowStartMs = startCal.timeInMillis,
                    windowEndMs = endCal.timeInMillis,
                    nextWindowStartMs = nextStartCal.timeInMillis
                )
            }
        } else {
            // Overnight window (e.g. 18:00 to 02:00)
            if (nowMinutes >= startMinutes) { // e.g. 20:00
                val startCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val nextStartCal = (startCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                return TimeWindowInfo(
                    isInside = true,
                    windowStartMs = startCal.timeInMillis,
                    windowEndMs = endCal.timeInMillis,
                    nextWindowStartMs = nextStartCal.timeInMillis
                )
            } else if (nowMinutes < endMinutes) { // e.g. 01:30
                val endCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startCal = (endCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val nextStartCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return TimeWindowInfo(
                    isInside = true,
                    windowStartMs = startCal.timeInMillis,
                    windowEndMs = endCal.timeInMillis,
                    nextWindowStartMs = nextStartCal.timeInMillis
                )
            } else { // nowMinutes is between endMinutes and startMinutes (e.g. 10:00)
                val prevEndCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val prevStartCal = (prevEndCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val nextStartCal = (nowCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return TimeWindowInfo(
                    isInside = false,
                    windowStartMs = prevStartCal.timeInMillis,
                    windowEndMs = prevEndCal.timeInMillis,
                    nextWindowStartMs = nextStartCal.timeInMillis
                )
            }
        }
    }

    fun msUntilNextCapture(s: SettingsManager, nowMs: Long = System.currentTimeMillis()): Long {
        if (!s.timeWindowEnabled) {
            val elapsed = nowMs - s.lastCaptureAt
            val intervalMs = s.captureIntervalMinutes * 60_000L
            return (intervalMs - elapsed).coerceAtLeast(0L)
        }

        val info = getTimeWindowInfo(s, nowMs)
        val offsetMs = s.windowOffsetSeconds * 1000L
        val effectiveStartMs = info.windowStartMs + offsetMs
        val effectiveEndMs = info.windowEndMs - offsetMs

        if (nowMs in effectiveStartMs until effectiveEndMs) {
            val intervalMs = s.captureIntervalMinutes * 60_000L
            val nextIntervalTarget = if (s.lastCaptureAt < effectiveStartMs) nowMs else s.lastCaptureAt + intervalMs
            val targetMs = minOf(nextIntervalTarget, effectiveEndMs)
            return (targetMs - nowMs).coerceAtLeast(0L)
        } else if (nowMs < effectiveStartMs) {
            // Before effective window start (e.g. lights ramping up)
            return (effectiveStartMs - nowMs).coerceAtLeast(0L)
        } else {
            // At or after effective window end (e.g. windowEnd - offset)
            val recentlyEnded = (nowMs - effectiveEndMs) in 0L..60_000L
            val capturedRecently = (nowMs - s.lastCaptureAt) < 60_000L
            if (recentlyEnded && !capturedRecently) {
                return 0L
            }
            return (info.nextWindowStartMs + offsetMs - nowMs).coerceAtLeast(0L)
        }
    }

    fun isWithinWindowOrWindowEnd(s: SettingsManager, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!s.timeWindowEnabled) return true
        val info = getTimeWindowInfo(s, nowMs)
        val offsetMs = s.windowOffsetSeconds * 1000L
        val effectiveStartMs = info.windowStartMs + offsetMs
        val effectiveEndMs = info.windowEndMs - offsetMs

        if (nowMs in effectiveStartMs until effectiveEndMs) return true

        val recentlyEnded = (nowMs - effectiveEndMs) in 0L..60_000L
        val capturedRecently = (nowMs - s.lastCaptureAt) < 60_000L
        return recentlyEnded && !capturedRecently
    }
}
