package pl.zarajczyk.familyrulesandroid.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import pl.zarajczyk.familyrulesandroid.utils.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "LocationTracker"
private const val LOCATION_TIMEOUT_MS = 30_000L

class LocationTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    private var lastReportedLat: Double? = null

    @Volatile
    private var lastReportedLng: Double? = null

    @Volatile
    private var lastCachedLat: Double? = null

    @Volatile
    private var lastCachedLng: Double? = null

    @Volatile
    private var lastCachedTimestamp: Long = 0L

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) {
            Logger.w(TAG, "No location permission granted")
            return null
        }

        val lastKnown = getLastKnownLocation()
        if (lastKnown != null) {
            Logger.d(TAG, "Using last known location: ${lastKnown.first}, ${lastKnown.second}")
            lastCachedLat = lastKnown.first
            lastCachedLng = lastKnown.second
            lastCachedTimestamp = System.currentTimeMillis()
            return lastKnown
        }

        val fresh = requestFreshLocation()
        if (fresh != null) {
            Logger.d(TAG, "Got fresh location: ${fresh.first}, ${fresh.second}")
            lastCachedLat = fresh.first
            lastCachedLng = fresh.second
            lastCachedTimestamp = System.currentTimeMillis()
        }
        return fresh
    }

    fun hasMoved(lat: Double, lng: Double, thresholdMeters: Double = 250.0): Boolean {
        val prevLat = lastReportedLat
        val prevLng = lastReportedLng
        if (prevLat == null || prevLng == null) {
            Logger.d(TAG, "No previous position, reporting as moved")
            return true
        }
        val distance = haversineMeters(prevLat, prevLng, lat, lng)
        val moved = distance > thresholdMeters
        Logger.d(TAG, "Distance from last report: ${distance.toInt()}m (threshold: ${thresholdMeters.toInt()}m, moved: $moved)")
        return moved
    }

    fun markReported(lat: Double, lng: Double) {
        lastReportedLat = lat
        lastReportedLng = lng
        Logger.d(TAG, "Marked position as reported: $lat, $lng")
    }

    fun getLastCachedLocation(): Pair<Double, Double>? {
        val lat = lastCachedLat
        val lng = lastCachedLng
        return if (lat != null && lng != null) Pair(lat, lng) else null
    }

    fun getLastCachedTimestamp(): Long = lastCachedTimestamp

    private suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        return suspendCoroutine { continuation ->
            try {
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    fusedClient.lastLocation
                        .addOnSuccessListener { location ->
                            if (location != null) {
                                continuation.resume(Pair(location.latitude, location.longitude))
                            } else {
                                continuation.resume(null)
                            }
                        }
                        .addOnFailureListener {
                            Logger.e(TAG, "Failed to get last known location", it)
                            continuation.resume(null)
                        }
                } else {
                    continuation.resume(null)
                }
            } catch (e: SecurityException) {
                Logger.e(TAG, "Security exception getting last known location", e)
                continuation.resume(null)
            }
        }
    }

    private suspend fun requestFreshLocation(): Pair<Double, Double>? {
        return suspendCoroutine { continuation ->
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                0L
            ).apply {
                setDurationMillis(LOCATION_TIMEOUT_MS)
                setMaxUpdates(1)
            }.build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    fusedClient.removeLocationUpdates(this)
                    val location = result.lastLocation
                    if (location != null) {
                        continuation.resume(Pair(location.latitude, location.longitude))
                    } else {
                        Logger.w(TAG, "Fresh location request returned null")
                        continuation.resume(null)
                    }
                }
            }

            try {
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    fusedClient.requestLocationUpdates(
                        locationRequest,
                        callback,
                        Looper.getMainLooper()
                    )
                } else {
                    continuation.resume(null)
                }
            } catch (e: SecurityException) {
                Logger.e(TAG, "Security exception requesting fresh location", e)
                continuation.resume(null)
            }
        }
    }

    companion object {
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}
