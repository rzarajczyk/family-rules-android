package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Provides today's per-package usage time using the OS-aggregated
 * `UsageStatsManager.queryAndAggregateUsageStats(...)` API.
 *
 * This replaces the previous event-based accumulator (`PackageUsageCalculator`'s
 * `todayPackageUsage` map), which suffered from "lost time" bugs whenever the
 * full event stream was re-processed (screen-off resets, service restart):
 *  - per-package summation overlapped intervals across long-running foreground
 *    services and over-counted them at the expense of the actual foreground app;
 *  - the dedup of consecutive STARTING events discarded the start of any session
 *    that had a foreground service kick in mid-way.
 *
 * `queryAndAggregateUsageStats` returns the same daily totals that Digital
 * Wellbeing and FamilyLink show, so we no longer maintain or replay any state.
 *
 * Result is cached for [CACHE_TTL_MS] to keep the binder-call cost negligible
 * even when the UI re-reads the value frequently during recomposition.
 */
class PackageUsageStatsProvider(context: Context) {

    companion object {
        private const val TAG = "PackageUsageStatsProvider"
        private const val CACHE_TTL_MS = 1_000L
    }

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Volatile
    private var cachedResult: Map<String, Long> = emptyMap()

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * Returns map of `packageName` -> total time in foreground today (ms),
     * computed from the OS' aggregated UsageStats. Only entries with non-zero
     * foreground time are included.
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
            val stats = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
            stats.mapNotNull { (pkg, usage) ->
                val ms = usage.totalTimeInForeground
                if (ms > 0L) pkg to ms else null
            }.toMap()
        } catch (t: Throwable) {
            Log.w(TAG, "queryAndAggregateUsageStats failed: ${t.message}", t)
            cachedResult // serve last-known good on transient failure
        }

        cachedResult = result
        cachedAt = now
        return result
    }
}
