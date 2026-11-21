package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TrafficLightDecision {
    GREEN,
    YELLOW,
    RED
}

enum class NeighborhoodStatus {
    PREFERRED,
    NEUTRAL,
    AVOIDED
}

enum class OfferCaptureSource {
    ACCESSIBILITY,
    OCR_SCREENSHOT,
    OCR_IMAGE,
    MANUAL
}

@Entity(tableName = "analyzed_offers")
data class AnalyzedOfferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val rawSnippet: String,
    val offerPriceBrl: Double,
    val storeName: String = "",
    val storeConfidence: String = "UNIDENTIFIED",
    val pickupAddress: String,
    val pickupAddressConfidence: String = "UNIDENTIFIED",
    val pickupNeighborhood: String,
    val pickupNeighborhoodConfidence: String = "UNIDENTIFIED",
    val customerAddress: String,
    val customerAddressConfidence: String = "UNIDENTIFIED",
    val customerNeighborhood: String,
    val customerNeighborhoodConfidence: String = "UNIDENTIFIED",
    val leg1CurrentToPickupKm: Double,
    val leg2PickupToCustomerKm: Double,
    val leg3CustomerToCenterKm: Double,
    val totalKm: Double,
    val earningsPerKm: Double,
    val targetPerKm: Double,
    val decision: String,
    val decisionReason: String,
    val neighborhoodAlert: String? = null,
    val routeSource: String,
    val usedGpsStart: Boolean
)

@Entity(tableName = "neighborhood_rules")
data class NeighborhoodRuleEntity(
    @PrimaryKey val name: String,
    val normalizedKey: String,
    val status: String,
    val notes: String = "",
    val isCustom: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cached_addresses",
    indices = [Index(value = ["expiresAt"])]
)
data class CachedAddressEntity(
    @PrimaryKey val normalizedQuery: String,
    val originalQuery: String,
    val latitude: Double,
    val longitude: Double,
    val resolvedNeighborhood: String,
    val resolutionSource: String,
    val createdAt: Long,
    val expiresAt: Long
)

@Entity(
    tableName = "cached_routes",
    indices = [Index(value = ["expiresAt"])]
)
data class CachedRouteEntity(
    @PrimaryKey val routeKey: String,
    val leg1Km: Double,
    val leg2Km: Double,
    val leg3Km: Double,
    val totalKm: Double,
    val routeSource: String,
    val createdAt: Long,
    val expiresAt: Long
)
