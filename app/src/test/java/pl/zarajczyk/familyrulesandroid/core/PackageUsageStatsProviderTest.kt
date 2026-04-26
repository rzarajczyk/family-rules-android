package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageUsageStatsProviderTest {

    private val startOfDay = 1_700_000_000_000L
    private val now = startOfDay + 12L * 60L * 60L * 1_000L // midday

    private fun resume(t: Long, pkg: String, cls: String = "$pkg.Main") =
        UsageEventTuple(t, pkg, cls, UsageEvents.Event.ACTIVITY_RESUMED)

    private fun pause(t: Long, pkg: String, cls: String = "$pkg.Main") =
        UsageEventTuple(t, pkg, cls, UsageEvents.Event.ACTIVITY_PAUSED)

    private fun other(t: Long, pkg: String, type: Int) =
        UsageEventTuple(t, pkg, "$pkg.Other", type)

    @Test
    fun `empty input returns empty map`() {
        val result = computeTodayPackageUsage(emptyList(), startOfDay, now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single session today is exact RESUME-PAUSE`() {
        val r = startOfDay + 1_000
        val p = startOfDay + 11_000
        val result = computeTodayPackageUsage(
            listOf(resume(r, "a"), pause(p, "a")),
            startOfDay,
            now,
        )
        assertEquals(mapOf("a" to 10_000L), result)
    }

    @Test
    fun `multiple sessions today same package are summed`() {
        val events = listOf(
            resume(startOfDay + 1_000, "a"),
            pause(startOfDay + 6_000, "a"),
            resume(startOfDay + 10_000, "a"),
            pause(startOfDay + 13_000, "a"),
        )
        val result = computeTodayPackageUsage(events, startOfDay, now)
        assertEquals(mapOf("a" to 8_000L), result)
    }

    @Test
    fun `cross-midnight paused today is clipped to today portion`() {
        val r = startOfDay - 60L * 60L * 1_000L     // 1h before midnight
        val p = startOfDay + 30L * 60L * 1_000L     // 30m after midnight
        val result = computeTodayPackageUsage(
            listOf(resume(r, "a"), pause(p, "a")),
            startOfDay,
            now,
        )
        assertEquals(mapOf("a" to 30L * 60L * 1_000L), result)
    }

    @Test
    fun `cross-midnight still open returns now minus startOfDay`() {
        val r = startOfDay - 2L * 60L * 60L * 1_000L
        val result = computeTodayPackageUsage(
            listOf(resume(r, "a")),
            startOfDay,
            now,
        )
        assertEquals(mapOf("a" to (now - startOfDay)), result)
    }

    @Test
    fun `opened today still open returns now minus RESUME`() {
        val r = startOfDay + 10L * 60L * 1_000L
        val nowLocal = startOfDay + 60L * 60L * 1_000L
        val result = computeTodayPackageUsage(
            listOf(resume(r, "a")),
            startOfDay,
            nowLocal,
        )
        assertEquals(mapOf("a" to 50L * 60L * 1_000L), result)
    }

    @Test
    fun `duplicate RESUME for same class then PAUSE counts from first RESUME`() {
        val r1 = startOfDay + 1_000
        val r2 = startOfDay + 5_000
        val p = startOfDay + 11_000
        val result = computeTodayPackageUsage(
            listOf(resume(r1, "a"), resume(r2, "a"), pause(p, "a")),
            startOfDay,
            now,
        )
        assertEquals(mapOf("a" to 10_000L), result)
    }

    @Test
    fun `two packages interleaved have independent totals`() {
        val events = listOf(
            resume(startOfDay + 1_000, "a"),
            resume(startOfDay + 3_000, "b"),
            pause(startOfDay + 6_000, "a"),
            pause(startOfDay + 9_000, "b"),
        )
        val result = computeTodayPackageUsage(events, startOfDay, now)
        assertEquals(2, result.size)
        assertEquals(5_000L, result["a"])
        assertEquals(6_000L, result["b"])
    }

    @Test
    fun `STOPPED and FOREGROUND_SERVICE events are ignored`() {
        val events = listOf(
            resume(startOfDay + 1_000, "a"),
            other(startOfDay + 2_000, "a", UsageEvents.Event.ACTIVITY_STOPPED),
            other(startOfDay + 3_000, "a", UsageEvents.Event.FOREGROUND_SERVICE_START),
            other(startOfDay + 4_000, "a", UsageEvents.Event.FOREGROUND_SERVICE_STOP),
            pause(startOfDay + 11_000, "a"),
        )
        val result = computeTodayPackageUsage(events, startOfDay, now)
        assertEquals(mapOf("a" to 10_000L), result)
    }

    @Test
    fun `zero-duration session is filtered out`() {
        val t = startOfDay + 1_000
        val result = computeTodayPackageUsage(
            listOf(resume(t, "a"), pause(t, "a")),
            startOfDay,
            now,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `same-package handoff across two activities is continuous`() {
        // A.RESUME @ t0, B.RESUME @ t1, A.PAUSE @ t2, B.PAUSE @ t3 — total = t3 - t0
        val t0 = startOfDay + 1_000
        val t1 = startOfDay + 4_000
        val t2 = startOfDay + 7_000
        val t3 = startOfDay + 12_000
        val events = listOf(
            UsageEventTuple(t0, "a", "a.A", UsageEvents.Event.ACTIVITY_RESUMED),
            UsageEventTuple(t1, "a", "a.B", UsageEvents.Event.ACTIVITY_RESUMED),
            UsageEventTuple(t2, "a", "a.A", UsageEvents.Event.ACTIVITY_PAUSED),
            UsageEventTuple(t3, "a", "a.B", UsageEvents.Event.ACTIVITY_PAUSED),
        )
        val result = computeTodayPackageUsage(events, startOfDay, now)
        assertEquals(mapOf("a" to (t3 - t0)), result)
        // sanity: the post-handoff tail (t3 - t2) is included, not lost
        assertFalse((t3 - t0) == (t2 - t0))
        assertNull(result["a.A"])
    }
}
