package pl.zarajczyk.familyrulesandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTrackerTest {

    @Test
    fun haversine_samePoint_returnsZero() {
        val distance = LocationTracker.haversineMeters(52.2297, 21.0122, 52.2297, 21.0122)
        assertEquals(0.0, distance, 0.01)
    }

    @Test
    fun haversine_knownDistance_warsawToKrakow() {
        // Warsaw (52.2297, 21.0122) to Krakow (50.0647, 19.9450) ~252km
        val distance = LocationTracker.haversineMeters(52.2297, 21.0122, 50.0647, 19.9450)
        assertTrue("Distance should be ~252km but was ${distance.toInt()}m", distance in 240_000.0..265_000.0)
    }

    @Test
    fun haversine_shortDistance_250mThreshold() {
        // Two points ~250m apart (roughly 0.0022 degrees latitude at Warsaw)
        val lat1 = 52.2297
        val lon1 = 21.0122
        val lat2 = 52.2319 // ~0.0022 degrees north
        val lon2 = 21.0122
        val distance = LocationTracker.haversineMeters(lat1, lon1, lat2, lon2)
        assertTrue("Distance should be ~250m but was ${distance.toInt()}m", distance in 200.0..300.0)
    }

    @Test
    fun haversine_veryShortDistance_10mApart() {
        // Two points ~10m apart
        val lat1 = 52.2297
        val lon1 = 21.0122
        val lat2 = 52.22979 // ~0.00009 degrees ~10m
        val lon2 = 21.0122
        val distance = LocationTracker.haversineMeters(lat1, lon1, lat2, lon2)
        assertTrue("Distance should be ~10m but was ${distance.toInt()}m", distance in 1.0..20.0)
    }

    @Test
    fun haversine_crossingPoles() {
        // North pole to south pole should be ~20000km
        val distance = LocationTracker.haversineMeters(90.0, 0.0, -90.0, 0.0)
        assertEquals(20_000_000.0, distance, 100_000.0)
    }

    @Test
    fun haversine_antiMeridian() {
        // Points near the date line
        val distance = LocationTracker.haversineMeters(0.0, 179.0, 0.0, -179.0)
        assertEquals(222_000.0, distance, 5_000.0) // ~222km at equator
    }
}
