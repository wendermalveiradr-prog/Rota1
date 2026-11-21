package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfferDao {
    @Query("SELECT * FROM analyzed_offers ORDER BY timestamp DESC")
    fun observeAllOffers(): Flow<List<AnalyzedOfferEntity>>

    @Query("SELECT * FROM analyzed_offers ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestOffer(): Flow<AnalyzedOfferEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffer(offer: AnalyzedOfferEntity): Long

    @Query("DELETE FROM analyzed_offers WHERE id = :id")
    suspend fun deleteOfferById(id: Long)

    @Query("DELETE FROM analyzed_offers")
    suspend fun clearAllOffers()

    @Query("DELETE FROM analyzed_offers WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOffersOlderThan(cutoffTimestamp: Long): Int

    @Query(
        "DELETE FROM analyzed_offers WHERE id NOT IN " +
            "(SELECT id FROM analyzed_offers ORDER BY timestamp DESC LIMIT :maxRows)"
    )
    suspend fun trimOffersKeepingLatest(maxRows: Int): Int
}

@Dao
interface NeighborhoodDao {
    @Query("SELECT * FROM neighborhood_rules ORDER BY CASE status WHEN 'AVOIDED' THEN 0 WHEN 'PREFERRED' THEN 1 ELSE 2 END, name ASC")
    fun observeAllRules(): Flow<List<NeighborhoodRuleEntity>>

    @Query("SELECT * FROM neighborhood_rules")
    suspend fun getAllRulesOnce(): List<NeighborhoodRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: NeighborhoodRuleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rules: List<NeighborhoodRuleEntity>)

    @Query("UPDATE neighborhood_rules SET status = :status, notes = :notes, updatedAt = :updatedAt WHERE name = :name")
    suspend fun updateRuleStatus(name: String, status: String, notes: String, updatedAt: Long)

    @Query("DELETE FROM neighborhood_rules WHERE name = :name AND isCustom = 1")
    suspend fun deleteCustomRule(name: String)
}

@Dao
interface GeoCacheDao {
    @Query("SELECT * FROM cached_addresses WHERE normalizedQuery = :normalizedQuery LIMIT 1")
    suspend fun getAddress(normalizedQuery: String): CachedAddressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAddress(entity: CachedAddressEntity)

    @Query("DELETE FROM cached_addresses WHERE expiresAt <= :now")
    suspend fun deleteExpiredAddresses(now: Long): Int

    @Query("SELECT COUNT(*) FROM cached_addresses WHERE expiresAt > :now")
    suspend fun countValidAddresses(now: Long): Int

    @Query(
        "DELETE FROM cached_addresses WHERE normalizedQuery NOT IN " +
            "(SELECT normalizedQuery FROM cached_addresses ORDER BY createdAt DESC LIMIT :maxRows)"
    )
    suspend fun trimAddressesKeepingLatest(maxRows: Int): Int

    @Query("DELETE FROM cached_addresses")
    suspend fun clearAllAddresses()

    @Query("SELECT * FROM cached_routes WHERE routeKey = :routeKey LIMIT 1")
    suspend fun getRoute(routeKey: String): CachedRouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(entity: CachedRouteEntity)

    @Query("DELETE FROM cached_routes WHERE expiresAt <= :now")
    suspend fun deleteExpiredRoutes(now: Long): Int

    @Query("SELECT COUNT(*) FROM cached_routes WHERE expiresAt > :now")
    suspend fun countValidRoutes(now: Long): Int

    @Query(
        "DELETE FROM cached_routes WHERE routeKey NOT IN " +
            "(SELECT routeKey FROM cached_routes ORDER BY createdAt DESC LIMIT :maxRows)"
    )
    suspend fun trimRoutesKeepingLatest(maxRows: Int): Int

    @Query("DELETE FROM cached_routes")
    suspend fun clearAllRoutes()
}
