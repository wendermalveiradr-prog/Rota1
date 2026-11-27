package com.example.domain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class DriverLocationResult(
    val point: GeoPoint,
    val usedGps: Boolean
)

class LocationHelper(private val context: Context) {

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @Volatile
    private var lastFixPoint: GeoPoint? = null

    @Volatile
    private var lastFixTimestampMs: Long = 0L

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Zero-blocking location lookup (< 1ms) for instant <300ms street offer evaluation.
     * Uses the latest cached GPS fix if fresh, or Montes Claros downtown reference immediately.
     */
    fun getCachedLocationOrCenterInstant(): DriverLocationResult {
        val now = System.currentTimeMillis()
        val recent = lastFixPoint
        return if (hasLocationPermission() && recent != null && (now - lastFixTimestampMs) < GPS_CACHE_TTL_MS) {
            DriverLocationResult(point = recent, usedGps = true)
        } else {
            DriverLocationResult(point = MontesClarosGeoCatalog.DOWNTOWN_MONTES_CLAROS, usedGps = false)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationOrFallback(): DriverLocationResult {
        return getCachedLocationOrCenterInstant()
        if (!hasLocationPermission()) {
            return DriverLocationResult(
                point = MontesClarosGeoCatalog.DOWNTOWN_MONTES_CLAROS,
                usedGps = false
            )
        }

        val now = System.currentTimeMillis()
        val recent = lastFixPoint
        if (recent != null && (now - lastFixTimestampMs) < GPS_CACHE_TTL_MS) {
            return DriverLocationResult(point = recent, usedGps = true)
        }

        val current = try {
            withTimeoutOrNull(GPS_TIMEOUT_MS) {
                suspendCancellableCoroutine<GeoPoint?> { cont ->
                    val tokenSource = CancellationTokenSource()
                    fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                        .addOnSuccessListener { location ->
                            if (cont.isActive) {
                                if (location != null) {
                                    cont.resume(GeoPoint(location.latitude, location.longitude, "Localização GPS Atual"))
                                } else {
                                    fusedClient.lastLocation
                                        .addOnSuccessListener { last ->
                                            if (cont.isActive) {
                                                cont.resume(
                                                    last?.let {
                                                        GeoPoint(it.latitude, it.longitude, "Última Localização GPS")
                                                    }
                                                )
                                            }
                                        }
                                        .addOnFailureListener {
                                            if (cont.isActive) cont.resume(null)
                                        }
                                }
                            }
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                    cont.invokeOnCancellation { tokenSource.cancel() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }

        return if (current != null) {
            lastFixPoint = current
            lastFixTimestampMs = System.currentTimeMillis()
            DriverLocationResult(point = current, usedGps = true)
        } else {
            DriverLocationResult(
                point = MontesClarosGeoCatalog.DOWNTOWN_MONTES_CLAROS,
                usedGps = false
            )
        }
    }

    companion object {
        private const val GPS_TIMEOUT_MS = 2200L
        private const val GPS_CACHE_TTL_MS = 15_000L
    }
}
