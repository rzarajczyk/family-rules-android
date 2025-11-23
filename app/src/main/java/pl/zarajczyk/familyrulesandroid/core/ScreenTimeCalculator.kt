package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE
import android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE
import android.util.Log
import pl.zarajczyk.familyrulesandroid.core.ScreenTimeCalculator.ScreenState.TURNING_OFF
import pl.zarajczyk.familyrulesandroid.core.ScreenTimeCalculator.ScreenState.TURNING_ON

class ScreenTimeCalculator : SystemEventProcessor {
    fun getTodayScreenTime(): Long {
        return todayScreenTime
    }

    @Volatile
    private var todayScreenTime: Long = 0L

    @Volatile
    private var isScreenOn: Boolean = false

    enum class ScreenState { TURNING_ON, TURNING_OFF }
    data class ScreenEvent(val state: ScreenState, val timestamp: Long)

    override fun reset() {
        todayScreenTime = 0L
    }

    override fun processEventBatch(events: List<Event>, start: Long, end: Long) {
        var screenEvents = events.toScreenEvents()

        if (screenEvents.isEmpty()) {
            if (isScreenOn)
                todayScreenTime += end - start
            return
        }

        isScreenOn = screenEvents.last().state == TURNING_ON

        if (screenEvents.first().state != TURNING_ON) {
            screenEvents = listOf(ScreenEvent(TURNING_ON, start)) + screenEvents
        }
        if (screenEvents.last().state != TURNING_OFF) {
            screenEvents = screenEvents + ScreenEvent(TURNING_OFF, end)
        }
        val batchScreenOnTime = screenEvents
            .chunked(2)
            .sumOf { chunk ->
                chunk.last().timestamp - chunk.first().timestamp
            }

        todayScreenTime += batchScreenOnTime
    }

    private fun List<Event>.toScreenEvents(): List<ScreenEvent> =
        this.mapNotNull {
            when (it.eventType) {
                SCREEN_INTERACTIVE -> ScreenEvent(TURNING_ON, it.timestamp)
                SCREEN_NON_INTERACTIVE -> ScreenEvent(TURNING_OFF, it.timestamp)
                else -> null
            }
        }.fold(mutableListOf()) { acc, event ->
            if (acc.isEmpty() || acc.last().state != event.state) {
                acc.add(event)
            } else {
                acc[acc.lastIndex] = event
            }
            acc
        }
}