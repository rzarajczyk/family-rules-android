package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object UsageEventLogReplay {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lineRegex = Regex(
        """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+([A-Z_]+)\s+\((\d+)\)\s+([^/]+)/\s*(.*)$"""
    )

    fun parse(vararg lines: String): List<UsageEventTuple> =
        lines.mapNotNull { parseLine(it) }

    private fun parseLine(line: String): UsageEventTuple? {
        val match = lineRegex.matchEntire(line.trim()) ?: return null
        val timestamp = LocalDateTime.parse(match.groupValues[1], timestampFormatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val eventType = match.groupValues[3].toInt()
        val packageName = match.groupValues[4]
        val className = match.groupValues[5]

        return when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventTuple(
                timestamp = timestamp,
                packageName = packageName,
                className = className,
                eventType = eventType,
            )

            else -> null
        }
    }
}
