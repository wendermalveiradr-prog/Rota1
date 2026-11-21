package com.example.data

import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.NeighborhoodDao
import com.example.data.local.NeighborhoodRuleEntity
import com.example.data.local.NeighborhoodStatus
import com.example.data.local.OfferDao
import com.example.domain.MontesClarosGeoCatalog
import kotlinx.coroutines.flow.Flow

class OfferRepository(
    private val offerDao: OfferDao,
    private val neighborhoodDao: NeighborhoodDao
) {
    val allOffers: Flow<List<AnalyzedOfferEntity>> = offerDao.observeAllOffers()
    val latestOffer: Flow<AnalyzedOfferEntity?> = offerDao.observeLatestOffer()
    val allNeighborhoodRules: Flow<List<NeighborhoodRuleEntity>> = neighborhoodDao.observeAllRules()

    suspend fun ensureSeededNeighborhoods() {
        // Insert any new default neighborhoods from catalog updates without overwriting user-edited rules
        neighborhoodDao.insertIgnoreAll(MontesClarosGeoCatalog.defaultRuleEntities())
    }

    suspend fun getNeighborhoodRulesOnce(): List<NeighborhoodRuleEntity> {
        ensureSeededNeighborhoods()
        return neighborhoodDao.getAllRulesOnce()
    }

    suspend fun saveAnalyzedOffer(offer: AnalyzedOfferEntity): Long {
        val insertedId = offerDao.insertOffer(offer)
        pruneOldOffers()
        return insertedId
    }

    suspend fun updateAnalyzedOffer(offer: AnalyzedOfferEntity) {
        if (offer.id > 0L) {
            offerDao.insertOffer(offer)
        }
    }

    suspend fun pruneOldOffers(
        maxAgeMs: Long = OFFER_RETENTION_MS,
        maxRows: Int = MAX_HISTORY_ROWS
    ) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        offerDao.deleteOffersOlderThan(cutoff)
        offerDao.trimOffersKeepingLatest(maxRows)
    }

    suspend fun deleteOffer(id: Long) =
        offerDao.deleteOfferById(id)

    suspend fun clearHistory() =
        offerDao.clearAllOffers()

    suspend fun updateNeighborhoodStatus(
        name: String,
        status: NeighborhoodStatus,
        notes: String = ""
    ) {
        neighborhoodDao.updateRuleStatus(
            name = name,
            status = status.name,
            notes = notes,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun addCustomNeighborhood(
        name: String,
        status: NeighborhoodStatus,
        notes: String
    ) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        neighborhoodDao.upsertRule(
            NeighborhoodRuleEntity(
                name = trimmed,
                normalizedKey = MontesClarosGeoCatalog.normalize(trimmed),
                status = status.name,
                notes = notes.trim(),
                isCustom = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteCustomNeighborhood(name: String) {
        neighborhoodDao.deleteCustomRule(name)
    }

    companion object {
        private const val OFFER_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        private const val MAX_HISTORY_ROWS = 300
    }
}
