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
 * Computed from the same `UsageEvents` stream that Digital Wellbeing and
 * FamilyLink internally consume; values typically match FamilyLink within a
 * few seconds, but exact parity is not guaranteed.
 *
 * To seed cross-midnight sessions correctly the query window starts 24h
 * before today's local midnight; sessions whose `RESUME` predates that point
 * will be undercounted starting at `startOfDay`.
 *
 * Result is cached for [CACHE_TTL_MS] to keep the binder-call cost negligible
 * even when the UI re-reads the value frequently during recomposition.
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
    private var cachedResult: Map<String, Long> = emptyMap()

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * Returns map of `packageName` -> total foreground time today (ms),
     * derived from `UsageEvents` and clipped to the local-day window.
     */
    fun getTodayPackageUsage(): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_TTL_MS) {
            return cachedResult
        }

        val startOfDay = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant()
            .toEpochMilli()

        val result = try {
            val events = readEvents(startOfDay - LOOKBACK_MS, now)
            computeTodayPackageUsage(events, startOfDay, now)
        } catch (t: Throwable) {
            Logger.w(TAG, "queryEvents failed: ${t.message}", t)
            cachedResult // serve last-known good on transient failure
        }

        cachedResult = result
        cachedAt = now
        return result
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
                DEVICE_SHUTDOWN -> {
                    // No packageName for shutdown events; use empty string as sentinel.
                    out.add(
                        UsageEventTuple(
                            timestamp = event.timeStamp,
                            packageName = "",
                            className = "",
                            eventType = DEVICE_SHUTDOWN,
                        )
                    )
                }
            }
        }
        return out
    }
}

private const val DEVICE_SHUTDOWN = 26 // UsageEvents.Event.DEVICE_SHUTDOWN (API 28+)

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
 * signal only when that class is still active, which preserves the preferred
 * `PAUSED` boundary while fixing streams that omit it entirely. All session
 * boundaries are clipped to
 * `[startOfDay, now]`.
 */
internal fun computeTodayPackageUsage(
    events: List<UsageEventTuple>,
    startOfDay: Long,
    now: Long,
): Map<String, Long> {
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
            // DEVICE_SHUTDOWN is a global event, but we must use it as a signal for all apps
            if (event.eventType == DEVICE_SHUTDOWN) {
                // Replicate the shutdown into every known package stream.
                // className is not read by the DEVICE_SHUTDOWN branch in the loop below.
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
                it.eventType == DEVICE_SHUTDOWN
        }
        .sortedBy { it.timestamp }
        .groupBy { it.packageName }

    val result = mutableMapOf<String, Long>()
    for ((pkg, evs) in byPackage) {
        val active = mutableSetOf<String>()
        var openSince: Long? = null
        var total = 0L

        for (e in evs) {
            if (e.eventType == DEVICE_SHUTDOWN) {
                val start = openSince
                if (start != null) {
                    val s = maxOf(start, startOfDay)
                    if (e.timestamp > s) total += e.timestamp - s
                    openSince = null
                }
                active.clear()
                continue
            }

            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val wasEmpty = active.isEmpty()
                active.add(e.className)
                if (wasEmpty) openSince = e.timestamp
            } else {
                val removed = active.remove(e.className)
                if (!removed) {
                    continue
                }
                if (active.isEmpty()) {
                    val start = openSince
                    if (start != null) {
                        val s = maxOf(start, startOfDay)
                        if (e.timestamp > s) total += e.timestamp - s
                        openSince = null
                    }
                }
            }
        }

        val stillOpen = openSince
        if (stillOpen != null) {
            val s = maxOf(stillOpen, startOfDay)
            if (now > s) total += now - s
        }
        if (total > 0L) result[pkg] = total
    }
    return result
}
