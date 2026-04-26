package pl.zarajczyk.familyrulesandroid.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object UsageEventsDumper {
    private const val TAG = "UsageEventsDumper"
    private const val MAX_EVENTS = 10_000
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun dumpForDay(context: Context, date: LocalDate): String {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val zone = ZoneId.systemDefault()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val systemEvents = usm.queryEvents(start, end)
                ?: return "(no events available)\n"

            val sb = StringBuilder()
            val event = UsageEvents.Event()
            var count = 0
            while (systemEvents.hasNextEvent() && count < MAX_EVENTS) {
                systemEvents.getNextEvent(event)
                val time = Instant.ofEpochMilli(event.timeStamp)
                    .atZone(zone)
                    .format(timestampFormat)
                val typeName = eventTypeName(event.eventType)
                val pkg = event.packageName ?: "-"
                val cls = event.className?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                sb.appendLine("$time  $typeName (${event.eventType})  $pkg/$cls")
                count++
            }
            if (count == 0) "(no events recorded for this day)\n"
            else sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump usage events for $date", e)
            "(error reading events: ${e.message})\n"
        }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
        UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
        UsageEvents.Event.SHORTCUT_INVOCATION -> "SHORTCUT_INVOCATION"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        UsageEvents.Event.FOREGROUND_SERVICE_START -> "FOREGROUND_SERVICE_START"
        UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "FOREGROUND_SERVICE_STOP"
        UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "STANDBY_BUCKET_CHANGED"
        9 -> "CHOOSER_ACTION"
        10 -> "NOTIFICATION_SEEN"
        12 -> "NOTIFICATION_INTERRUPTION"
        14 -> "SLICE_PINNED_PRIV"
        26 -> "DEVICE_SHUTDOWN"
        27 -> "DEVICE_STARTUP"
        30 -> "USER_UNLOCKED"
        31 -> "USER_STOPPED"
        else -> "EVENT_$type"
    }
}
