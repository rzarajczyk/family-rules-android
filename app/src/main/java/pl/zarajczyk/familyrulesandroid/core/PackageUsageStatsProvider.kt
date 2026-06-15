package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import pl.zarajczyk.familyrulesandroid.utils.Logger
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Provides today's per-package foreground time by replaying
 * `UsageEvents` from `UsageStatsManager.queryEvents(...)` and clipping every
 * session to today's local-day window.
 *
 * Why event replay rather than `queryAndAggregateUsageStats(...)`: the
 * aggregator does not clip `totalTimeInForeground` to the query window — it
 * returns the full daily-bucket total of every bucket that overlaps the
 * range, and the OS rotates buckets lazily, so a single bucket can carry
 * several days of accumulated time. That produced wildly inflated numbers
 * after restart.
 *
 * Session durations are intersected with [buildScreenOnIntervals] so stale
 * activity bookkeeping cannot accrue time while the display is off.
 *
 * Total screen time uses the same replay path via [computeTodayScreenTime]
 * so daily totals stay consistent with per-app attribution.
 *
 * Results are cached for [CACHE_TTL_MS] to keep the binder-call cost negligible
 * even when the UI re-reads values frequently during recomposition.
 */
class PackageUsageStatsProvider(context: Context) {

    companion object {
        private const val TAG = "PackageUsageStatsProvider"
        private const val CACHE_TTL_MS = 1_000L
        private const val LOOKBACK_MS = 24L * 60L * 60L * 1_000L
    }

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Volatile
    private var cachedPackageUsage: Map<String, Long> = emptyMap()

    @Volatile
    private var cachedScreenTimeMs: Long = 0L

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * Returns map of `packageName` -> total foreground time today (ms),
     * derived from `UsageEvents` and clipped to the local-day window.
     */
    fun getTodayPackageUsage(): Map<String, Long> {
        refreshIfStale()
        return cachedPackageUsage
    }

    /** Returns total screen-on time today (ms), derived from the same event replay. */
    fun getTodayScreenTime(): Long {
        refreshIfStale()
        return cachedScreenTimeMs
    }

    private fun refreshIfStale(now: Long = System.currentTimeMillis()) {
        if (now - cachedAt < CACHE_TTL_MS) {
            return
        }

        val startOfDay = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant()
            .toEpochMilli()

        try {
            val events = readEvents(startOfDay - LOOKBACK_MS, now)
            cachedPackageUsage = computeTodayPackageUsage(events, startOfDay, now)
            cachedScreenTimeMs = computeTodayScreenTime(events, startOfDay, now)
        } catch (t: Throwable) {
            Logger.w(TAG, "queryEvents failed: ${t.message}", t)
            // serve last-known good on transient failure
        }

        cachedAt = now
    }

    private fun readEvents(start: Long, end: Long): List<UsageEventTuple> {
        val out = mutableListOf<UsageEventTuple>()
        val iter = usageStatsManager.queryEvents(start, end) ?: return out
        val event = UsageEvents.Event()
        while (iter.hasNextEvent()) {
            iter.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    out.add(
                        UsageEventTuple(
                            timestamp = event.timeStamp,
                            packageName = event.packageName ?: continue,
                            className = event.className ?: "",
                            eventType = event.eventType,
                        )
                    )
                }
                UsageEvents.Event.SCREEN_INTERACTIVE,
                DEVICE_SHUTDOWN -> {
                    out.add(
                        UsageEventTuple(
                            timestamp = event.timeStamp,
                            packageName = "",
                            className = "",
                            eventType = event.eventType,
                        )
                    )
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                DEVICE_STARTUP -> {
                    out.add(
                        UsageEventTuple(
                            timestamp = event.timeStamp,
                            packageName = "",
                            className = "",
                            eventType = event.eventType,
                        )
                    )
                }
            }
        }
        return out
    }
}

private const val DEVICE_SHUTDOWN = 26 // UsageEvents.Event.DEVICE_SHUTDOWN (API 28+)
private const val DEVICE_STARTUP = 27 // UsageEvents.Event.DEVICE_STARTUP (API 28+)
// Sessions that were already open more than this long before midnight, with no RESUME
// today, are treated as stale OS bookkeeping rather than continuous cross-midnight use.
private const val MAX_CROSS_MIDNIGHT_OPEN_MS = 3L * 60L * 60L * 1_000L

internal data class UsageEventTuple(
    val timestamp: Long,
    val packageName: String,
    val className: String,
    val eventType: Int,
)

/**
 * Pure computation: walks events per package while tracking the set of
 * currently-resumed `className` values. The package is "in foreground" iff
 * the set is non-empty; a session opens on the 0->1 transition and closes
 * on the 1->0 transition. `ACTIVITY_STOPPED` is treated as a fallback close
 * signal only when that class is still active. Durations are intersected with
 * screen-on intervals so stale sessions cannot accrue overnight hours while
 * the display is off.
 */
internal fun computeTodayPackageUsage(
    events: List<UsageEventTuple>,
    startOfDay: Long,
    now: Long,
): Map<String, Long> {
    val screenIntervals = buildScreenOnIntervals(events, startOfDay, now)

    val packageNames = events
        .filter {
            it.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                it.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                it.eventType == UsageEvents.Event.ACTIVITY_STOPPED
        }
        .map { it.packageName }
        .distinct()

    val byPackage = events
        .flatMap { event ->
            if (event.eventType.isGlobalUsageBoundary()) {
                packageNames.map { packageName ->
                    event.copy(packageName = packageName)
                }
            } else {
                listOf(event)
            }
        }
        .filter {
            it.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                it.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                it.eventType == UsageEvents.Event.ACTIVITY_STOPPED ||
                it.eventType.isGlobalUsageBoundary()
        }
        .sortedBy { it.timestamp }
        .groupBy { it.packageName }

    val result = mutableMapOf<String, Long>()
    for ((pkg, evs) in byPackage) {
        val active = mutableSetOf<String>()
        var openSince: Long? = null
        var total = 0L

        fun addSessionDuration(rangeStart: Long, rangeEnd: Long) {
            total += overlapWithScreenOn(rangeStart, rangeEnd, screenIntervals, startOfDay, now)
        }

        for (e in evs) {
            when (e.eventType) {
                DEVICE_SHUTDOWN, UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    val start = openSince
                    if (start != null) {
                        addSessionDuration(start, e.timestamp)
                        openSince = null
                    }
                    active.clear()
                }
                DEVICE_STARTUP -> {
                    openSince = null
                    active.clear()
                }
            }
            if (e.eventType.isGlobalUsageBoundary()) continue

            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val wasEmpty = active.isEmpty()
                active.add(e.className)
                when {
                    wasEmpty -> openSince = e.timestamp
                    openSince != null && openSince < startOfDay && e.timestamp >= startOfDay ->
                        // Stale pre-midnight bookkeeping; treat today's RESUME as a fresh session.
                        openSince = e.timestamp
                }
            } else {
                val removed = active.remove(e.className)
                if (!removed) {
                    continue
                }
                if (active.isEmpty()) {
                    val start = openSince
                    if (start != null) {
                        addSessionDuration(start, e.timestamp)
                        openSince = null
                    }
                }
            }
        }

        val stillOpen = openSince
        if (stillOpen != null) {
            val hasResumeToday = evs.any {
                it.eventType == UsageEvents.Event.ACTIVITY_RESUMED && it.timestamp >= startOfDay
            }
            val stalePhantomCarry = stillOpen < startOfDay &&
                !hasResumeToday &&
                startOfDay - stillOpen > MAX_CROSS_MIDNIGHT_OPEN_MS
            if (!stalePhantomCarry) {
                addSessionDuration(stillOpen, now)
            }
        }
        if (total > 0L) result[pkg] = total
    }
    return result
}

internal fun computeTodayScreenTime(
    events: List<UsageEventTuple>,
    startOfDay: Long,
    now: Long,
): Long = buildScreenOnIntervals(events, startOfDay, now).sumOf { (start, end) -> end - start }

internal fun buildScreenOnIntervals(
    events: List<UsageEventTuple>,
    startOfDay: Long,
    now: Long,
): List<Pair<Long, Long>> {
    val toggles = events
        .filter {
            it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE ||
                it.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                it.eventType == DEVICE_SHUTDOWN ||
                it.eventType == DEVICE_STARTUP
        }
        .sortedBy { it.timestamp }
        .map { e ->
            e.timestamp to (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE)
        }
        .fold(mutableListOf<Pair<Long, Boolean>>()) { acc, toggle ->
            if (acc.isEmpty() || acc.last().second != toggle.second) {
                acc.add(toggle)
            } else {
                acc[acc.lastIndex] = toggle
            }
            acc
        }

    var isOn = false
    var lastInteractiveBeforeDay: Long? = null
    for ((ts, on) in toggles) {
        if (ts >= startOfDay) break
        if (on) lastInteractiveBeforeDay = ts
        isOn = on
    }

    val hasInteractiveToday = toggles.any { (ts, on) -> ts >= startOfDay && on }
    if (isOn &&
        !hasInteractiveToday &&
        lastInteractiveBeforeDay != null &&
        startOfDay - lastInteractiveBeforeDay > MAX_CROSS_MIDNIGHT_OPEN_MS
    ) {
        isOn = false
    }

    var openAt: Long? = if (isOn) startOfDay else null
    val intervals = mutableListOf<Pair<Long, Long>>()
    var sawInteractiveToday = false

    for ((ts, on) in toggles) {
        if (ts < startOfDay) continue
        val t = minOf(ts, now)
        if (on) {
            sawInteractiveToday = true
            when {
                openAt == null -> openAt = t
                openAt == startOfDay &&
                    lastInteractiveBeforeDay != null &&
                    lastInteractiveBeforeDay < startOfDay &&
                    t > startOfDay ->
                    // Stale pre-midnight carry clipped to startOfDay; fresh interactive today.
                    openAt = t
            }
            isOn = true
        } else if (isOn) {
            openAt?.let { start ->
                val phantomMidnightCarry = start == startOfDay &&
                    !sawInteractiveToday &&
                    lastInteractiveBeforeDay != null &&
                    lastInteractiveBeforeDay < startOfDay &&
                    startOfDay - lastInteractiveBeforeDay > MAX_CROSS_MIDNIGHT_OPEN_MS
                if (!phantomMidnightCarry && t > start) {
                    intervals.add(start to t)
                }
            }
            openAt = null
            isOn = false
        }
    }
    if (openAt != null && now > openAt) {
        intervals.add(openAt to now)
    }
    return intervals
}

internal fun overlapWithScreenOn(
    rangeStart: Long,
    rangeEnd: Long,
    screenIntervals: List<Pair<Long, Long>>,
    startOfDay: Long,
    now: Long,
): Long {
    val s = maxOf(rangeStart, startOfDay)
    val e = minOf(rangeEnd, now)
    if (e <= s) return 0L
    if (screenIntervals.isEmpty()) return e - s
    return screenIntervals.sumOf { (iStart, iEnd) ->
        val os = maxOf(iStart, s)
        val oe = minOf(iEnd, e)
        if (oe > os) oe - os else 0L
    }
}

private fun Int.isGlobalUsageBoundary(): Boolean =
    this == DEVICE_SHUTDOWN ||
        this == DEVICE_STARTUP ||
        this == UsageEvents.Event.SCREEN_NON_INTERACTIVE
