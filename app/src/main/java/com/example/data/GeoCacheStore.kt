package com.example.data

import com.example.data.local.CachedAddressEntity
import com.example.data.local.CachedRouteEntity
import com.example.data.local.GeoCacheDao
import java.util.concurrent.ConcurrentHashMap

interface GeoCacheStore {
    suspend fun getValidAddress(normalizedQuery: String, now: Long = System.currentTimeMillis()): CachedAddressEntity?
    suspend fun getStaleAddress(normalizedQuery: String): CachedAddressEntity?
    suspend fun putAddress(entity: CachedAddressEntity)
    suspend fun getValidRoute(routeKey: String, now: Long = System.currentTimeMillis()): CachedRouteEntity?
    suspend fun getStaleRoute(routeKey: String): CachedRouteEntity?
    suspend fun putRoute(entity: CachedRouteEntity)
    suspend fun pruneExpired(now: Long = System.currentTimeMillis())
    suspend fun clearAll()
}

class RoomGeoCacheStore(
    private val dao: GeoCacheDao,
    private val maxAddressEntries: Int = 500,
    private val maxRouteEntries: Int = 500
) : GeoCacheStore {

    private val memoryAddressCache = ConcurrentHashMap<String, CachedAddressEntity>()
    private val memoryRouteCache = ConcurrentHashMap<String, CachedRouteEntity>()

    override suspend fun getValidAddress(normalizedQuery: String, now: Long): CachedAddressEntity? {
        val memHit = memoryAddressCache[normalizedQuery]
        if (memHit != null) {
            if (memHit.expiresAt > now) return memHit
            memoryAddressCache.remove(normalizedQuery)
        }
        val dbHit = dao.getAddress(normalizedQuery) ?: return null
        return if (dbHit.expiresAt > now) {
            memoryAddressCache[normalizedQuery] = dbHit
            dbHit
        } else {
            null
        }
    }

    override suspend fun getStaleAddress(normalizedQuery: String): CachedAddressEntity? {
        return memoryAddressCache[normalizedQuery] ?: dao.getAddress(normalizedQuery)
    }

    override suspend fun putAddress(entity: CachedAddressEntity) {
        memoryAddressCache[entity.normalizedQuery] = entity
        if (memoryAddressCache.size > maxAddressEntries) {
            memoryAddressCache.clear()
            memoryAddressCache[entity.normalizedQuery] = entity
        }
        dao.upsertAddress(entity)
    }

    override suspend fun getValidRoute(routeKey: String, now: Long): CachedRouteEntity? {
        val memHit = memoryRouteCache[routeKey]
        if (memHit != null) {
            if (memHit.expiresAt > now) return memHit
            memoryRouteCache.remove(routeKey)
        }
        val dbHit = dao.getRoute(routeKey) ?: return null
        return if (dbHit.expiresAt > now) {
            memoryRouteCache[routeKey] = dbHit
            dbHit
        } else {
            null
        }
    }

    override suspend fun getStaleRoute(routeKey: String): CachedRouteEntity? {
        return memoryRouteCache[routeKey] ?: dao.getRoute(routeKey)
    }

    override suspend fun putRoute(entity: CachedRouteEntity) {
        memoryRouteCache[entity.routeKey] = entity
        if (memoryRouteCache.size > maxRouteEntries) {
            memoryRouteCache.clear()
            memoryRouteCache[entity.routeKey] = entity
        }
        dao.upsertRoute(entity)
    }

    override suspend fun pruneExpired(now: Long) {
        memoryAddressCache.entries.removeIf { it.value.expiresAt <= now }
        memoryRouteCache.entries.removeIf { it.value.expiresAt <= now }
        dao.deleteExpiredAddresses(now)
        dao.deleteExpiredRoutes(now)
        dao.trimAddressesKeepingLatest(maxAddressEntries)
        dao.trimRoutesKeepingLatest(maxRouteEntries)
    }

    override suspend fun clearAll() {
        memoryAddressCache.clear()
        memoryRouteCache.clear()
        dao.clearAllAddresses()
        dao.clearAllRoutes()
    }
}

class InMemoryGeoCacheStore : GeoCacheStore {
    private val addresses = ConcurrentHashMap<String, CachedAddressEntity>()
    private val routes = ConcurrentHashMap<String, CachedRouteEntity>()

    override suspend fun getValidAddress(normalizedQuery: String, now: Long): CachedAddressEntity? =
        addresses[normalizedQuery]?.takeIf { it.expiresAt > now }

    override suspend fun getStaleAddress(normalizedQuery: String): CachedAddressEntity? =
        addresses[normalizedQuery]

    override suspend fun putAddress(entity: CachedAddressEntity) {
        addresses[entity.normalizedQuery] = entity
    }

    override suspend fun getValidRoute(routeKey: String, now: Long): CachedRouteEntity? =
        routes[routeKey]?.takeIf { it.expiresAt > now }

    override suspend fun getStaleRoute(routeKey: String): CachedRouteEntity? =
        routes[routeKey]

    override suspend fun putRoute(entity: CachedRouteEntity) {
        routes[entity.routeKey] = entity
    }

    override suspend fun pruneExpired(now: Long) {
        addresses.entries.removeIf { it.value.expiresAt <= now }
        routes.entries.removeIf { it.value.expiresAt <= now }
    }

    override suspend fun clearAll() {
        addresses.clear()
        routes.clear()
    }
}
