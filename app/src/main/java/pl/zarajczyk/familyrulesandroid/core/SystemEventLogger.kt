package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Logs all system events for debugging purposes.
 * Similar to PackageUsageCalculator and ScreenTimeCalculator.
 */
class SystemEventLogger : SystemEventProcessor {
    
    @Volatile
    private var todayEvents = ConcurrentHashMap<String, MutableList<EventLogEntry>>()
    
    data class EventLogEntry(
        val timestamp: Long,
        val eventType: Int,
        val eventTypeName: String
    )
    
    /**
     * Get all logged events for a specific package
     */
    fun getEventsForPackage(packageName: String): List<EventLogEntry> {
        return todayEvents[packageName]?.toList() ?: emptyList()
    }
    
    /**
     * Get all logged events for all packages
     */
    fun getAllEvents(): Map<String, List<EventLogEntry>> {
        return todayEvents.mapValues { it.value.toList() }
    }
    
    override fun reset() {
        todayEvents.clear()
    }
    
    override fun processEventBatch(events: List<Event>, start: Long, end: Long) {
        events.forEach { event ->
            val eventTypeName = getEventTypeName(event.eventType)
            val logEntry = EventLogEntry(
                timestamp = event.timestamp,
                eventType = event.eventType,
                eventTypeName = eventTypeName
            )
            
            todayEvents.getOrPut(event.packageName) { mutableListOf() }.add(logEntry)
        }
    }
    
    /**
     * Format events as copyable text
     */
    fun formatEventsAsText(packageName: String): String {
        val events = getEventsForPackage(packageName)
        if (events.isEmpty()) {
            return "No events recorded for $packageName today"
        }
        
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val builder = StringBuilder()
        builder.append("System Events for: $packageName\n")
        builder.append("Total Events: ${events.size}\n")
        builder.append("=" .repeat(50))
        builder.append("\n\n")
        
        events.forEach { entry ->
            val time = dateFormat.format(Date(entry.timestamp))
            builder.append("$time - ${entry.eventTypeName} (${entry.eventType})\n")
        }
        
        return builder.toString()
    }
    
    /**
     * Convert event type code to human-readable name
     */
    private fun getEventTypeName(eventType: Int): String {
        return when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
            UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
            UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
            UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
            UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
            UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
            UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
            UsageEvents.Event.FOREGROUND_SERVICE_START -> "FOREGROUND_SERVICE_START"
            UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "FOREGROUND_SERVICE_STOP"
            UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "STANDBY_BUCKET_CHANGED"
            UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
            UsageEvents.Event.SHORTCUT_INVOCATION -> "SHORTCUT_INVOCATION"
            9 -> "CHOOSER_ACTION" // API 28+
            10 -> "NOTIFICATION_SEEN" // API 28+
            12 -> "NOTIFICATION_INTERRUPTION" // API 23+
            14 -> "SLICE_PINNED_PRIV" // API 28+
            // Note: 15 conflicts with SCREEN_INTERACTIVE, using direct constant above
            26 -> "DEVICE_SHUTDOWN" // API 28+
            27 -> "DEVICE_STARTUP" // API 28+
            30 -> "USER_UNLOCKED" // API 31+
            31 -> "USER_STOPPED" // API 31+
            else -> "UNKNOWN_EVENT_$eventType"
        }
    }
}
