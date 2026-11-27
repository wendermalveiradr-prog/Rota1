package com.example.domain

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.example.data.GeoCacheStore
import com.example.data.InMemoryGeoCacheStore
import com.example.data.local.CachedAddressEntity
import com.example.data.local.CachedRouteEntity
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@JsonClass(generateAdapter = true)
data class OsrmRouteResponse(
    val code: String,
    val routes: List<OsrmRoute> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OsrmRoute(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val legs: List<OsrmLeg> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OsrmLeg(
    val distance: Double = 0.0,
    val duration: Double = 0.0
)

interface OsrmRoutingApi {
    @GET("route/v1/driving/{coordinates}")
    suspend fun getDrivingRoute(
        @Path("coordinates", encoded = true) coordinates: String,
        @Query("overview") overview: String = "false",
        @Query("steps") steps: String = "false",
        @Query("alternatives") alternatives: String = "false",
        @Query("annotations") annotations: String = "false"
    ): OsrmRouteResponse
}

@JsonClass(generateAdapter = true)
data class NominatimAddress(
    val suburb: String?,
    val neighbourhood: String?,
    val city: String?,
    val town: String?
)

@JsonClass(generateAdapter = true)
data class NominatimResult(
    val lat: String?,
    val lon: String?,
    val display_name: String?,
    val address: NominatimAddress?
)

interface NominatimApi {
    @GET("search")
    suspend fun searchAddress(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("viewbox") viewbox: String = "-44.00,-16.60,-43.75,-16.86",
        @Query("bounded") bounded: Int = 1,
        @Query("limit") limit: Int = 1,
        @retrofit2.http.Header("User-Agent") userAgent: String = "RotaInteligenteIA-Android"
    ): List<NominatimResult>
}

data class RouteCalculationResult(
    val leg1CurrentToPickupKm: Double,
    val leg2PickupToCustomerKm: Double,
    val leg3CustomerToCenterKm: Double,
    val totalKm: Double,
    val routeSource: String,
    val fromCache: Boolean = false,
    val pickupPoint: GeoPoint? = null,
    val customerPoint: GeoPoint? = null
)

sealed class RouteResolutionResult {
    data class Resolved(val route: RouteCalculationResult) : RouteResolutionResult()
    data class UnresolvedAddress(
        val missingPickup: Boolean,
        val missingCustomer: Boolean,
        val pickupAddress: String,
        val customerAddress: String,
        val reason: String
    ) : RouteResolutionResult()
}

class RouteCalculator(
    private val context: Context,
    private val cacheStore: GeoCacheStore = InMemoryGeoCacheStore(),
    private val osrmApi: OsrmRoutingApi = createDefaultOsrmApi(),
    private val nominatimApi: NominatimApi = createDefaultNominatimApi(),
    private val geocoderOverride: (suspend (String) -> GeoPoint?)? = null
) {

    // Dedicated supervisor scope so cancelling one caller does not abort a deduplicated fetch for others
    private val sharedFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightAddresses = ConcurrentHashMap<String, CompletableDeferred<GeoPoint?>>()
    private val inFlightRoutes = ConcurrentHashMap<String, CompletableDeferred<RouteCalculationResult>>()

    /**
     * Instant offline route calculation (< 10ms, zero network/IPC blocking) using numbers read from
     * the Mob Lets screen and Montes Claros offline neighborhood catalog coordinates.
     * Real OSRM refinement runs asynchronously in background right after display.
     */
    fun calculateInstantOfflineRoute(
        currentLocation: GeoPoint,
        pickupAddress: String,
        customerAddress: String,
        pickupDistanceHintKm: Double? = null,
        screenTotalKmHint: Double? = null,
        countReturn: Boolean = false
    ): RouteCalculationResult? {
        val centerPoint = MontesClarosGeoCatalog.DOWNTOWN_MONTES_CLAROS
        val validStart = if (isValidCoordinate(currentLocation) &&
            MontesClarosGeoCatalog.isWithinMontesClarosBounds(currentLocation.latitude, currentLocation.longitude)
        ) {
            currentLocation
        } else {
            centerPoint
        }

        val pickupPoint = MontesClarosGeoCatalog.resolveCoordinatesFromCatalog(pickupAddress)
        val customerPoint = MontesClarosGeoCatalog.resolveCoordinatesFromCatalog(customerAddress)
        val urbanFactor = 1.32

        val hasScreenKm = screenTotalKmHint != null && screenTotalKmHint.isFinite() && screenTotalKmHint > 0.2
        val hasPickupDist = pickupDistanceHintKm != null && pickupDistanceHintKm.isFinite() && pickupDistanceHintKm > 0.02

        val l1 = if (hasPickupDist) {
            round2(pickupDistanceHintKm!!)
        } else if (pickupPoint != null) {
            round2((haversineKm(validStart, pickupPoint) * urbanFactor).coerceAtLeast(0.30))
        } else {
            1.20
        }

        val catalogL2 = if (pickupPoint != null && customerPoint != null) {
            round2((haversineKm(pickupPoint, customerPoint) * urbanFactor).coerceAtLeast(0.50))
        } else {
            3.50
        }

        val l2 = if (hasScreenKm) {
            round2(screenTotalKmHint!!)
        } else {
            catalogL2
        }

        val l3 = if (countReturn) {
            val destPoint = customerPoint ?: centerPoint
            val returnTarget = pickupPoint ?: centerPoint
            round2((haversineKm(destPoint, returnTarget) * urbanFactor).coerceAtLeast(0.50))
        } else {
            0.0
        }

        val total = round2(l1 + l2 + l3)
        return RouteCalculationResult(
            leg1CurrentToPickupKm = l1,
            leg2PickupToCustomerKm = l2,
            leg3CustomerToCenterKm = l3,
            totalKm = total,
            routeSource = ROUTE_SOURCE_INSTANT_OFFLINE,
            fromCache = false,
            pickupPoint = pickupPoint,
            customerPoint = customerPoint
        )
    }

    suspend fun calculateCompleteRoute(
        currentLocation: GeoPoint,
        pickupAddress: String,
        customerAddress: String,
        useOsrmRoadRouting: Boolean,
        countReturn: Boolean = false
    ): RouteResolutionResult = withContext(Dispatchers.IO) {
        val (pickupPoint, customerPoint) = coroutineScope {
            val pickupDeferred = async { resolveAddressPoint(pickupAddress) }
            val customerDeferred = async { resolveAddressPoint(customerAddress) }
            pickupDeferred.await() to customerDeferred.await()
        }

        val centerPoint = MontesClarosGeoCatalog.DOWNTOWN_MONTES_CLAROS
        val validationError = validateFourRoutePoints(
            currentLocation = currentLocation,
            pickupPoint = pickupPoint,
            customerPoint = customerPoint,
            centerPoint = centerPoint,
            pickupAddress = pickupAddress,
            customerAddress = customerAddress
        )
        if (validationError != null) {
            return@withContext validationError
        }

        val validPickup = pickupPoint!!
        val validCustomer = customerPoint!!

        val routeKey = buildRouteKey(
            currentLocation = currentLocation,
            pickupPoint = validPickup,
            customerPoint = validCustomer,
            centerPoint = centerPoint,
            useOsrm = useOsrmRoadRouting,
            countReturn = countReturn
        )

        val now = System.currentTimeMillis()
        cacheStore.getValidRoute(routeKey, now)?.let { cached ->
            return@withContext RouteResolutionResult.Resolved(
                RouteCalculationResult(
                    leg1CurrentToPickupKm = cached.leg1Km,
                    leg2PickupToCustomerKm = cached.leg2Km,
                    leg3CustomerToCenterKm = cached.leg3Km,
                    totalKm = cached.totalKm,
                    routeSource = cached.routeSource,
                    fromCache = true,
                    pickupPoint = validPickup,
                    customerPoint = validCustomer
                )
            )
        }

        val route = computeOrAwaitRoute(
            routeKey = routeKey,
            currentLocation = currentLocation,
            pickupPoint = validPickup,
            customerPoint = validCustomer,
            centerPoint = centerPoint,
            useOsrmRoadRouting = useOsrmRoadRouting,
            countReturn = countReturn
        ).copy(
            pickupPoint = validPickup,
            customerPoint = validCustomer
        )

        RouteResolutionResult.Resolved(route)
    }

    private fun validateFourRoutePoints(
        currentLocation: GeoPoint,
        pickupPoint: GeoPoint?,
        customerPoint: GeoPoint?,
        centerPoint: GeoPoint,
        pickupAddress: String,
        customerAddress: String
    ): RouteResolutionResult.UnresolvedAddress? {
        val missingPickup = pickupPoint == null || !isValidCoordinate(pickupPoint) ||
            !MontesClarosGeoCatalog.isWithinMontesClarosBounds(pickupPoint.latitude, pickupPoint.longitude)
        val missingCustomer = customerPoint == null || !isValidCoordinate(customerPoint) ||
            !MontesClarosGeoCatalog.isWithinMontesClarosBounds(customerPoint.latitude, customerPoint.longitude)

        if (missingPickup || missingCustomer) {
            val missingDesc = when {
                missingPickup && missingCustomer ->
                    "retirada (${pickupAddress.ifBlank { "não informado" }}) e cliente (${customerAddress.ifBlank { "não informado" }})"
                missingPickup ->
                    "retirada (${pickupAddress.ifBlank { "não informado" }})"
                else ->
                    "cliente (${customerAddress.ifBlank { "não informado" }})"
            }
            return RouteResolutionResult.UnresolvedAddress(
                missingPickup = missingPickup,
                missingCustomer = missingCustomer,
                pickupAddress = pickupAddress,
                customerAddress = customerAddress,
                reason = "Endereço não localizado em Montes Claros: $missingDesc. Cálculo bloqueado para evitar km fictício."
            )
        }

        if (!isValidCoordinate(currentLocation) || !isValidCoordinate(centerPoint) ||
            !MontesClarosGeoCatalog.isWithinMontesClarosBounds(centerPoint.latitude, centerPoint.longitude)
        ) {
            return RouteResolutionResult.UnresolvedAddress(
                missingPickup = false,
                missingCustomer = false,
                pickupAddress = pickupAddress,
                customerAddress = customerAddress,
                reason = "Ponto de partida (GPS) ou referência do Centro inválido. Cálculo bloqueado."
            )
        }

        val distanceFromCenterKm = haversineKm(currentLocation, centerPoint)
        if (!distanceFromCenterKm.isFinite() || distanceFromCenterKm > MAX_START_RADIUS_FROM_MOC_KM) {
            return RouteResolutionResult.UnresolvedAddress(
                missingPickup = false,
                missingCustomer = false,
                pickupAddress = pickupAddress,
                customerAddress = customerAddress,
                reason = "Localização GPS atual fora da área operacional de Montes Claros. Verifique o GPS."
            )
        }

        return null
    }

    private fun isValidCoordinate(point: GeoPoint): Boolean {
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) return false
        if (point.latitude == 0.0 && point.longitude == 0.0) return false
        return point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
    }

    private suspend fun computeOrAwaitRoute(
        routeKey: String,
        currentLocation: GeoPoint,
        pickupPoint: GeoPoint,
        customerPoint: GeoPoint,
        centerPoint: GeoPoint,
        useOsrmRoadRouting: Boolean,
        countReturn: Boolean
    ): RouteCalculationResult {
        inFlightRoutes[routeKey]?.let { existing ->
            return existing.await()
        }

        val created = CompletableDeferred<RouteCalculationResult>()
        val raceWinner = inFlightRoutes.putIfAbsent(routeKey, created)
        if (raceWinner != null) {
            return raceWinner.await()
        }

        sharedFetchScope.launch {
            try {
                val computed = executeRouteCalculation(
                    routeKey = routeKey,
                    currentLocation = currentLocation,
                    pickupPoint = pickupPoint,
                    customerPoint = customerPoint,
                    centerPoint = centerPoint,
                    useOsrmRoadRouting = useOsrmRoadRouting,
                    countReturn = countReturn
                )
                created.complete(computed)
            } catch (e: Throwable) {
                created.completeExceptionally(e)
            } finally {
                inFlightRoutes.remove(routeKey, created)
            }
        }

        return created.await()
    }

    private suspend fun executeRouteCalculation(
        routeKey: String,
        currentLocation: GeoPoint,
        pickupPoint: GeoPoint,
        customerPoint: GeoPoint,
        centerPoint: GeoPoint,
        useOsrmRoadRouting: Boolean,
        countReturn: Boolean
    ): RouteCalculationResult {
        if (useOsrmRoadRouting) {
            val osrmResult = fetchOsrmRoute(currentLocation, pickupPoint, customerPoint, centerPoint, countReturn)
            if (osrmResult != null) {
                val now = System.currentTimeMillis()
                cacheStore.putRoute(
                    CachedRouteEntity(
                        routeKey = routeKey,
                        leg1Km = osrmResult.leg1CurrentToPickupKm,
                        leg2Km = osrmResult.leg2PickupToCustomerKm,
                        leg3Km = osrmResult.leg3CustomerToCenterKm,
                        totalKm = osrmResult.totalKm,
                        routeSource = osrmResult.routeSource,
                        createdAt = now,
                        expiresAt = now + OSRM_ROUTE_TTL_MS
                    )
                )
                return osrmResult
            }

            // If OSRM is temporarily unreachable, prefer stale cached real-road route over Haversine
            val staleRealRoad = cacheStore.getStaleRoute(routeKey)
                ?.takeIf { it.routeSource == ROUTE_SOURCE_OSRM }
            if (staleRealRoad != null) {
                return RouteCalculationResult(
                    leg1CurrentToPickupKm = staleRealRoad.leg1Km,
                    leg2PickupToCustomerKm = staleRealRoad.leg2Km,
                    leg3CustomerToCenterKm = staleRealRoad.leg3Km,
                    totalKm = staleRealRoad.totalKm,
                    routeSource = staleRealRoad.routeSource,
                    fromCache = true
                )
            }
        }

        val urbanFactor = 1.32
        val l1 = round2((haversineKm(currentLocation, pickupPoint) * urbanFactor).coerceAtLeast(0.20))
        val l2 = round2((haversineKm(pickupPoint, customerPoint) * urbanFactor).coerceAtLeast(0.30))
        val l3 = if (countReturn) {
            round2((haversineKm(customerPoint, currentLocation) * urbanFactor).coerceAtLeast(0.20))
        } else {
            0.0
        }
        val total = round2(l1 + l2 + l3)

        val fallbackResult = RouteCalculationResult(
            leg1CurrentToPickupKm = l1,
            leg2PickupToCustomerKm = l2,
            leg3CustomerToCenterKm = l3,
            totalKm = total,
            routeSource = ROUTE_SOURCE_URBAN_FALLBACK,
            fromCache = false
        )

        val now = System.currentTimeMillis()
        cacheStore.putRoute(
            CachedRouteEntity(
                routeKey = routeKey,
                leg1Km = l1,
                leg2Km = l2,
                leg3Km = l3,
                totalKm = total,
                routeSource = ROUTE_SOURCE_URBAN_FALLBACK,
                createdAt = now,
                expiresAt = now + FALLBACK_ROUTE_TTL_MS
            )
        )
        return fallbackResult
    }

    private suspend fun fetchOsrmRoute(
        currentLocation: GeoPoint,
        pickupPoint: GeoPoint,
        customerPoint: GeoPoint,
        centerPoint: GeoPoint,
        countReturn: Boolean
    ): RouteCalculationResult? {
        val points = if (countReturn) {
            listOf(currentLocation, pickupPoint, customerPoint, currentLocation)
        } else {
            listOf(currentLocation, pickupPoint, customerPoint)
        }
        val coordPath = points.joinToString(";") { "${it.longitude},${it.latitude}" }
        val requiredLegs = if (countReturn) 3 else 2

        return try {
            val response = withTimeoutOrNull(OSRM_REQUEST_TIMEOUT_MS) {
                osrmApi.getDrivingRoute(
                    coordinates = coordPath,
                    overview = "false",
                    steps = "false",
                    alternatives = "false",
                    annotations = "false"
                )
            } ?: return null

            val legs = response.routes.firstOrNull()?.legs
            if (response.code.equals("Ok", ignoreCase = true) && legs != null && legs.size >= requiredLegs) {
                val raw1 = legs[0].distance
                val raw2 = legs[1].distance
                val raw3 = if (countReturn) legs[2].distance else 0.0
                if (!raw1.isFinite() || !raw2.isFinite() || !raw3.isFinite()) return null
                if (raw1 < 0.0 || raw2 < 0.0 || raw3 < 0.0) return null

                val l1 = round2((raw1 / 1000.0).coerceAtLeast(0.15))
                val l2 = round2((raw2 / 1000.0).coerceAtLeast(0.15))
                val l3 = if (countReturn) round2((raw3 / 1000.0).coerceAtLeast(0.15)) else 0.0
                val total = round2(l1 + l2 + l3)
                if (total > MAX_VALID_URBAN_ROUTE_KM) return null

                RouteCalculationResult(
                    leg1CurrentToPickupKm = l1,
                    leg2PickupToCustomerKm = l2,
                    leg3CustomerToCenterKm = l3,
                    totalKm = total,
                    routeSource = ROUTE_SOURCE_OSRM,
                    fromCache = false
                )
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveAddressPoint(rawAddress: String): GeoPoint? {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) return null

        val cacheKey = MontesClarosGeoCatalog.normalizeAddressCacheKey(trimmed)
        if (cacheKey.isBlank()) return null

        val now = System.currentTimeMillis()
        cacheStore.getValidAddress(cacheKey, now)?.let { cached ->
            val cachedNeighborhood = cached.resolvedNeighborhood
                .takeIf { it.isNotBlank() && !MontesClarosGeoCatalog.isUnidentified(it) }
            return GeoPoint(
                latitude = cached.latitude,
                longitude = cached.longitude,
                label = cachedNeighborhood ?: trimmed,
                geocoderMetadata = if (cached.resolutionSource == ADDRESS_SOURCE_GEOCODER && cachedNeighborhood != null) {
                    GeocoderPlaceMetadata(subLocality = cachedNeighborhood)
                } else {
                    null
                }
            )
        }

        inFlightAddresses[cacheKey]?.let { existing ->
            return existing.await()
        }

        val created = CompletableDeferred<GeoPoint?>()
        val raceWinner = inFlightAddresses.putIfAbsent(cacheKey, created)
        if (raceWinner != null) {
            return raceWinner.await()
        }

        sharedFetchScope.launch {
            try {
                val resolved = executeAddressResolution(trimmed, cacheKey)
                created.complete(resolved)
            } catch (e: Throwable) {
                created.completeExceptionally(e)
            } finally {
                inFlightAddresses.remove(cacheKey, created)
            }
        }

        return created.await()
    }

    private suspend fun executeAddressResolution(
        rawAddress: String,
        cacheKey: String
    ): GeoPoint? {
        val catalogPoint = MontesClarosGeoCatalog.resolveCoordinatesFromCatalog(rawAddress)
        val hasStreetDetail = hasStreetOrNumberDetail(rawAddress)

        // Prioritize real Geocoder for street/number precision or unknown addresses
        val geocoded = if (hasStreetDetail || catalogPoint == null) {
            geocodeInMontesClaros(rawAddress)
        } else {
            null
        }

        val now = System.currentTimeMillis()
        if (geocoded != null && MontesClarosGeoCatalog.isWithinMontesClarosBounds(geocoded.latitude, geocoded.longitude)) {
            val neighborhoodMatch = MontesClarosGeoCatalog.identifyNeighborhood(
                address = rawAddress,
                point = geocoded,
                rules = emptyList()
            )
            val resolvedLabel = when {
                !MontesClarosGeoCatalog.isUnidentified(neighborhoodMatch.name) -> neighborhoodMatch.name
                catalogPoint != null -> catalogPoint.label
                else -> neighborhoodMatch.name
            }

            cacheStore.putAddress(
                CachedAddressEntity(
                    normalizedQuery = cacheKey,
                    originalQuery = rawAddress,
                    latitude = geocoded.latitude,
                    longitude = geocoded.longitude,
                    resolvedNeighborhood = resolvedLabel,
                    resolutionSource = ADDRESS_SOURCE_GEOCODER,
                    createdAt = now,
                    expiresAt = now + GEOCODER_ADDRESS_TTL_MS
                )
            )
            return geocoded
        }

        // Fallback to Nominatim API (OpenStreetMap) restricted to Montes Claros
        val nominatimPoint = fetchNominatimPoint(rawAddress)
        if (nominatimPoint != null && MontesClarosGeoCatalog.isWithinMontesClarosBounds(nominatimPoint.latitude, nominatimPoint.longitude)) {
            val neighborhoodMatch = MontesClarosGeoCatalog.identifyNeighborhood(
                address = rawAddress,
                point = nominatimPoint,
                rules = emptyList()
            )
            cacheStore.putAddress(
                CachedAddressEntity(
                    normalizedQuery = cacheKey,
                    originalQuery = rawAddress,
                    latitude = nominatimPoint.latitude,
                    longitude = nominatimPoint.longitude,
                    resolvedNeighborhood = neighborhoodMatch.name,
                    resolutionSource = ADDRESS_SOURCE_NOMINATIM,
                    createdAt = now,
                    expiresAt = now + GEOCODER_ADDRESS_TTL_MS
                )
            )
            return nominatimPoint
        }

        if (catalogPoint != null) {
            cacheStore.putAddress(
                CachedAddressEntity(
                    normalizedQuery = cacheKey,
                    originalQuery = rawAddress,
                    latitude = catalogPoint.latitude,
                    longitude = catalogPoint.longitude,
                    resolvedNeighborhood = catalogPoint.label,
                    resolutionSource = ADDRESS_SOURCE_CATALOG,
                    createdAt = now,
                    expiresAt = now + CATALOG_ADDRESS_TTL_MS
                )
            )
            return catalogPoint
        }

        // Offline resilience: reuse stale Geocoder entry only if it was genuinely resolved in Montes Claros
        val stale = cacheStore.getStaleAddress(cacheKey)
        if (stale != null && MontesClarosGeoCatalog.isWithinMontesClarosBounds(stale.latitude, stale.longitude)) {
            val staleNeighborhood = stale.resolvedNeighborhood
                .takeIf { it.isNotBlank() && !MontesClarosGeoCatalog.isUnidentified(it) }
            return GeoPoint(
                latitude = stale.latitude,
                longitude = stale.longitude,
                label = staleNeighborhood ?: rawAddress,
                geocoderMetadata = staleNeighborhood?.let { GeocoderPlaceMetadata(subLocality = it) }
            )
        }

        return null
    }

    private suspend fun geocodeInMontesClaros(rawAddress: String): GeoPoint? {
        geocoderOverride?.let { override ->
            return try {
                withTimeoutOrNull(GEOCODER_TIMEOUT_MS) { override(rawAddress) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        return null // 100% offline instantaneo
        val query = if (rawAddress.contains("montes claros", ignoreCase = true)) {
            rawAddress
        } else {
            "$rawAddress, Montes Claros, MG, Brazil"
        }

        return try {
            withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                val geocoder = Geocoder(context, Locale("pt", "BR"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocationName(
                            query,
                            1,
                            MontesClarosGeoCatalog.MIN_LAT,
                            MontesClarosGeoCatalog.MIN_LON,
                            MontesClarosGeoCatalog.MAX_LAT,
                            MontesClarosGeoCatalog.MAX_LON
                        ) { addresses ->
                            val addr = addresses.firstOrNull()
                            if (cont.isActive) {
                                cont.resume(addr?.toValidatedGeoPoint(rawAddress))
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocationName(
                        query,
                        1,
                        MontesClarosGeoCatalog.MIN_LAT,
                        MontesClarosGeoCatalog.MIN_LON,
                        MontesClarosGeoCatalog.MAX_LAT,
                        MontesClarosGeoCatalog.MAX_LON
                    )
                    list?.firstOrNull()?.toValidatedGeoPoint(rawAddress)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchNominatimPoint(rawAddress: String): GeoPoint? {
        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(2500L) {
                    val query = if (rawAddress.contains("montes claros", ignoreCase = true)) {
                        rawAddress
                    } else {
                        "$rawAddress, Montes Claros, MG, Brazil"
                    }
                    val results = nominatimApi.searchAddress(query = query)
                    val first = results.firstOrNull() ?: return@withTimeoutOrNull null
                    val lat = first.lat?.toDoubleOrNull() ?: return@withTimeoutOrNull null
                    val lon = first.lon?.toDoubleOrNull() ?: return@withTimeoutOrNull null
                    if (!MontesClarosGeoCatalog.isWithinMontesClarosBounds(lat, lon)) {
                        return@withTimeoutOrNull null
                    }
                    val suburb = first.address?.suburb?.trim()?.takeIf { it.isNotBlank() }
                        ?: first.address?.neighbourhood?.trim()?.takeIf { it.isNotBlank() }
                    val metadata = GeocoderPlaceMetadata(
                        subLocality = suburb,
                        addressLine = first.display_name
                    )
                    GeoPoint(
                        latitude = lat,
                        longitude = lon,
                        label = suburb ?: first.display_name ?: rawAddress,
                        geocoderMetadata = metadata
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun Address.toValidatedGeoPoint(rawAddress: String): GeoPoint? {
        if (!MontesClarosGeoCatalog.isWithinMontesClarosBounds(latitude, longitude)) {
            return null
        }
        val metadata = GeocoderPlaceMetadata(
            subLocality = subLocality?.trim()?.takeIf { it.isNotBlank() },
            thoroughfare = thoroughfare?.trim()?.takeIf { it.isNotBlank() },
            featureName = featureName?.trim()?.takeIf { it.isNotBlank() },
            addressLine = getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() },
            locality = locality?.trim()?.takeIf { it.isNotBlank() }
        )
        return GeoPoint(
            latitude = latitude,
            longitude = longitude,
            label = metadata.subLocality ?: rawAddress,
            geocoderMetadata = metadata
        )
    }

    private fun hasStreetOrNumberDetail(address: String): Boolean {
        val norm = MontesClarosGeoCatalog.normalize(address)
        return Regex("\\b(rua|r|av|avenida|travessa|tv|praca|alameda|rodovia|estrada)\\b").containsMatchIn(norm) ||
            Regex("\\b\\d{1,5}\\b").containsMatchIn(norm)
    }

    private fun buildRouteKey(
        currentLocation: GeoPoint,
        pickupPoint: GeoPoint,
        customerPoint: GeoPoint,
        centerPoint: GeoPoint,
        useOsrm: Boolean,
        countReturn: Boolean
    ): String {
        val mode = if (useOsrm) "OSRM" else "URBAN"
        val retTag = if (countReturn) "RET1" else "RET0"
        return listOf(currentLocation, pickupPoint, customerPoint, centerPoint)
            .joinToString("|") { "${round4(it.latitude)},${round4(it.longitude)}" } + "|$mode|$retTag"
    }

    private fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earthRadiusKm * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
    private fun round4(value: Double): Double = Math.round(value * 10000.0) / 10000.0

    companion object {
        const val ROUTE_SOURCE_OSRM = "OSRM_REAL_ROADS"
        const val ROUTE_SOURCE_URBAN_FALLBACK = "ESTIMATED_URBAN"
        const val ROUTE_SOURCE_INSTANT_OFFLINE = "INSTANT_OFFLINE_SCREEN"
        const val ADDRESS_SOURCE_GEOCODER = "GEOCODER"
        const val ADDRESS_SOURCE_CATALOG = "CATALOG"
        const val ADDRESS_SOURCE_NOMINATIM = "NOMINATIM"

        private const val GEOCODER_TIMEOUT_MS = 1800L
        private const val OSRM_REQUEST_TIMEOUT_MS = 3000L
        private const val GEOCODER_ADDRESS_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private const val CATALOG_ADDRESS_TTL_MS = 6L * 60L * 60L * 1000L
        private const val OSRM_ROUTE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val FALLBACK_ROUTE_TTL_MS = 15L * 60L * 1000L
        private const val MAX_START_RADIUS_FROM_MOC_KM = 120.0
        private const val MAX_VALID_URBAN_ROUTE_KM = 250.0

        fun createDefaultOsrmApi(): OsrmRoutingApi {
            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .callTimeout(3500, TimeUnit.MILLISECONDS)
                .connectionPool(ConnectionPool(4, 3, TimeUnit.MINUTES))
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()

            return Retrofit.Builder()
                .baseUrl("https://router.project-osrm.org/")
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(OsrmRoutingApi::class.java)
        }

        fun createDefaultNominatimApi(): NominatimApi {
            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .callTimeout(3500, TimeUnit.MILLISECONDS)
                .connectionPool(ConnectionPool(4, 3, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build()

            return Retrofit.Builder()
                .baseUrl("https://nominatim.openstreetmap.org/")
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(NominatimApi::class.java)
        }
    }
}
