package pl.zarajczyk.familyrulesandroid.core

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PackageUsageStatsProviderTest {

    companion object {
        private const val DEVICE_SHUTDOWN = 26
        private const val DEVICE_STARTUP = 27
    }

    private val startOfDay = 1_700_000_000_000L
    private val now = startOfDay + 12L * 60L * 60L * 1_000L // midday

    private fun resume(t: Long, pkg: String, cls: String = "$pkg.Main") =
        UsageEventTuple(t, pkg, cls, UsageEvents.Event.ACTIVITY_RESUMED)

    private fun pause(t: Long, pkg: String, cls: String = "$pkg.Main") =
        UsageEventTuple(t, pkg, cls, UsageEvents.Event.ACTIVITY_PAUSED)

    private fun stop(t: Long, pkg: String, cls: String = "$pkg.Main") =
        UsageEventTuple(t, pkg, cls, UsageEvents.Event.ACTIVITY_STOPPED)

    private fun other(t: Long, pkg: String, type: Int) =
        UsageEventTuple(t, pkg, "$pkg.Other", type)

    private fun shutdown(t: Long) =
        UsageEventTuple(t, "", "", DEVICE_SHUTDOWN)

    private fun startup(t: Long) =
        UsageEventTuple(t, "", "", DEVICE_STARTUP)

    private fun screenOff(t: Long) =
        UsageEventTuple(t, "", "", UsageEvents.Event.SCREEN_NON_INTERACTIVE)

    private fun ts(dateTime: String): Long =
        LocalDateTime.parse(dateTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun startOfDay(date: String): Long =
        LocalDate.parse(date)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

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
    fun `STOPPED for different class and FOREGROUND_SERVICE events are ignored`() {
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
    fun `RESUME then STOPPED for same class closes session`() {
        val r = startOfDay + 1_000
        val s = startOfDay + 11_000
        val result = computeTodayPackageUsage(
            listOf(resume(r, "a"), stop(s, "a")),
            startOfDay,
            now,
        )
        assertEquals(mapOf("a" to 10_000L), result)
    }

    @Test
    fun `PAUSE then delayed STOPPED does not double close session`() {
        val r = startOfDay + 1_000
        val p = startOfDay + 11_000
        val s = startOfDay + 13_000
        val result = computeTodayPackageUsage(
            listOf(resume(r, "a"), pause(p, "a"), stop(s, "a")),
            startOfDay,
            now,
        )
        assertEquals(mapOf("a" to 10_000L), result)
    }

    @Test
    fun `cross-midnight stopped today is clipped to today portion`() {
        val r = startOfDay - 60L * 60L * 1_000L
        val s = startOfDay + 30L * 60L * 1_000L
        val result = computeTodayPackageUsage(
            listOf(resume(r, "a"), stop(s, "a")),
            startOfDay,
            now,
        )
        assertEquals(mapOf("a" to 30L * 60L * 1_000L), result)
    }

    @Test
    fun `camera regression with later unrelated events stays closed`() {
        val events = listOf(
            stop(startOfDay + 500, "irrelevant", "irrelevant.Main"),
            resume(startOfDay + 1_000, "camera", "camera.Main"),
            stop(startOfDay + 4_000, "camera", "camera.Main"),
            resume(startOfDay + 10_000, "chat", "chat.Main"),
            pause(startOfDay + 12_000, "chat", "chat.Main"),
        )
        val result = computeTodayPackageUsage(events, startOfDay, now)
        assertEquals(3_000L, result["camera"])
        assertEquals(2_000L, result["chat"])
    }

    @Test
    fun `replay real camera log excerpts does not keep camera open overnight`() {
        val events = UsageEventLogReplay.parse(
            "2026-05-06 16:23:47.652  ACTIVITY_RESUMED (1)  com.sec.android.app.camera/ com.sec.android.app.camera.Camera",
            "2026-05-06 16:23:49.016  ACTIVITY_STOPPED (23)  com.sec.android.app.camera/ com.sec.android.app.camera.Camera",
            "2026-05-06 20:33:16.981  ACTIVITY_RESUMED (1)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 20:33:20.339  ACTIVITY_PAUSED (2)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 20:33:20.479  ACTIVITY_STOPPED (23)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 20:33:49.633  ACTIVITY_RESUMED (1)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 20:33:50.852  ACTIVITY_PAUSED (2)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 20:33:51.526  ACTIVITY_STOPPED (23)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 21:49:11.628  ACTIVITY_RESUMED (1)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 21:49:13.779  ACTIVITY_PAUSED (2)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-06 21:49:13.910  ACTIVITY_STOPPED (23)  com.whatsapp/ com.whatsapp.Conversation",
            "2026-05-07 11:48:48.204  ACTIVITY_RESUMED (1)  com.sec.android.app.launcher/ com.android.launcher3.uioverrides.QuickstepLauncher",
            "2026-05-07 11:48:51.172  ACTIVITY_PAUSED (2)  com.sec.android.app.launcher/ com.android.launcher3.uioverrides.QuickstepLauncher",
            "2026-05-07 11:48:58.543  ACTIVITY_RESUMED (1)  com.android.chrome/ org.chromium.chrome.browser.ChromeTabbedActivity",
            "2026-05-07 11:49:10.555  ACTIVITY_PAUSED (2)  com.android.chrome/ org.chromium.chrome.browser.ChromeTabbedActivity",
            "2026-05-07 11:49:33.654  ACTIVITY_RESUMED (1)  com.sec.android.app.camera/ com.sec.android.app.camera.Camera",
            "2026-05-07 11:49:36.482  ACTIVITY_PAUSED (2)  com.sec.android.app.camera/ com.sec.android.app.camera.Camera",
            "2026-05-07 11:49:37.095  ACTIVITY_STOPPED (23)  com.sec.android.app.camera/ com.sec.android.app.camera.Camera",
        )

        val result = computeTodayPackageUsage(
            events = events,
            startOfDay = startOfDay("2026-05-07"),
            now = ts("2026-05-07T12:05:04.665"),
        )

        assertEquals(2_828L, result["com.sec.android.app.camera"])
        assertEquals(12_012L, result["com.android.chrome"])
        assertEquals(2_968L, result["com.sec.android.app.launcher"])
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

    @Test
    fun `same-package handoff remains continuous with STOPPED noise`() {
        val t0 = startOfDay + 1_000
        val t1 = startOfDay + 4_000
        val t2 = startOfDay + 7_000
        val t3 = startOfDay + 12_000
        val t4 = startOfDay + 15_000
        val events = listOf(
            resume(t0, "a", "a.A"),
            resume(t1, "a", "a.B"),
            stop(t2, "a", "a.A"),
            pause(t3, "a", "a.B"),
            stop(t4, "a", "a.B"),
        )
        val result = computeTodayPackageUsage(events, startOfDay, now)
        assertEquals(mapOf("a" to (t3 - t0)), result)
    }

    @Test
    fun `shutdown closes open session and later resume starts fresh session`() {
        val events = listOf(
            resume(startOfDay + 1_000, "a"),
            shutdown(startOfDay + 6_000),
            resume(startOfDay + 10_000, "a"),
            pause(startOfDay + 13_000, "a"),
        )

        val result = computeTodayPackageUsage(events, startOfDay, now)

        assertEquals(mapOf("a" to 8_000L), result)
    }

    @Test
    fun `multiple shutdowns close each open session independently`() {
        val events = listOf(
            resume(startOfDay + 1_000, "a"),
            shutdown(startOfDay + 4_000),
            resume(startOfDay + 6_000, "a"),
            shutdown(startOfDay + 9_000),
            resume(startOfDay + 11_000, "a"),
            pause(startOfDay + 13_000, "a"),
        )

        val result = computeTodayPackageUsage(events, startOfDay, now)

        assertEquals(mapOf("a" to 8_000L), result)
    }

    @Test
    fun `shutdown before first event today does not suppress later sessions`() {
        val events = listOf(
            shutdown(startOfDay - 1_000),
            resume(startOfDay + 1_000, "a"),
            pause(startOfDay + 6_000, "a"),
        )

        val result = computeTodayPackageUsage(events, startOfDay, now)

        assertEquals(mapOf("a" to 5_000L), result)
    }

    @Test
    fun `SCREEN_NON_INTERACTIVE closes stale youtube session from previous evening`() {
        val day = startOfDay("2026-06-10")
        val eveningResume = day - 4L * 60L * 60L * 1_000L
        val eveningScreenOff = day - 2L * 60L * 60L * 1_000L
        val resumeToday = ts("2026-06-10T10:33:51")
        val now = ts("2026-06-10T10:34:09")
        val youtube = "com.google.android.youtube"
        val main = "com.google.android.apps.youtube.app.watchwhile.MainActivity"

        val withoutScreenOff = computeTodayPackageUsage(
            events = listOf(
                resume(eveningResume, youtube, main),
                resume(resumeToday, youtube, main),
            ),
            startOfDay = day,
            now = now,
        )
        assertTrue(withoutScreenOff[youtube]!! >= 10L * 60L * 60L * 1_000L)

        val withScreenOff = computeTodayPackageUsage(
            events = listOf(
                resume(eveningResume, youtube, main),
                screenOff(eveningScreenOff),
                resume(resumeToday, youtube, main),
            ),
            startOfDay = day,
            now = now,
        )
        assertEquals(now - resumeToday, withScreenOff[youtube]!!)
    }

    @Test
    fun `DEVICE_STARTUP discards stale overnight session without counting sleep as today usage`() {
        val day = startOfDay("2026-06-10")
        val yesterdayResume = day - 2L * 60L * 60L * 1_000L
        val boot = ts("2026-06-10T10:33:37")
        val resumeToday = ts("2026-06-10T10:33:51")
        val now = ts("2026-06-10T10:34:09")
        val youtube = "com.google.android.youtube"
        val main = "com.google.android.apps.youtube.app.watchwhile.MainActivity"

        val withoutStartup = computeTodayPackageUsage(
            events = listOf(
                resume(yesterdayResume, youtube, main),
                resume(resumeToday, youtube, main),
            ),
            startOfDay = day,
            now = now,
        )
        val inflated = withoutStartup[youtube]!!
        assertTrue(
            "stale session without STARTUP should inflate to nearly time-since-midnight",
            inflated >= 10L * 60L * 60L * 1_000L,
        )

        val withStartup = computeTodayPackageUsage(
            events = listOf(
                resume(yesterdayResume, youtube, main),
                startup(boot),
                resume(resumeToday, youtube, main),
            ),
            startOfDay = day,
            now = now,
        )

        assertEquals(now - resumeToday, withStartup[youtube]!!)
    }

    @Test
    fun `shutdown before startup still counts active usage up to shutdown`() {
        val day = startOfDay("2026-06-10")
        val resumeAt = day + 10L * 60L * 1_000L
        val shutdownAt = day + 40L * 60L * 1_000L
        val startupAt = day + 41L * 60L * 1_000L
        val resumeAfterBoot = day + 45L * 60L * 1_000L
        val now = day + 50L * 60L * 1_000L

        val result = computeTodayPackageUsage(
            events = listOf(
                resume(resumeAt, "a"),
                shutdown(shutdownAt),
                startup(startupAt),
                resume(resumeAfterBoot, "a"),
            ),
            startOfDay = day,
            now = now,
        )

        assertEquals(35L * 60L * 1_000L, result["a"])
    }

    @Test
    fun `cross-midnight session is clipped to shutdown before reboot session`() {
        val events = listOf(
            resume(startOfDay - 60L * 60L * 1_000L, "a"),
            shutdown(startOfDay + 15L * 60L * 1_000L),
            resume(startOfDay + 20L * 60L * 1_000L, "a"),
            pause(startOfDay + 25L * 60L * 1_000L, "a"),
        )

        val result = computeTodayPackageUsage(events, startOfDay, now)

        assertEquals(mapOf("a" to 20L * 60L * 1_000L), result)
    }
}
