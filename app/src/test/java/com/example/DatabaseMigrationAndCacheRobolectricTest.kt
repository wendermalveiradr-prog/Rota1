package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.OfferRepository
import com.example.data.RoomGeoCacheStore
import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.AppDatabase
import com.example.data.local.CachedAddressEntity
import com.example.data.local.CachedRouteEntity
import com.example.domain.FieldConfidence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationAndCacheRobolectricTest {

    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun `migration from v1 to v4 preserves existing offers, stats, and enables persistent Room cache`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_v1_v4_${System.currentTimeMillis()}.db"
        context.deleteDatabase(dbName)

        // 1. Create authentic v1 SQLite schema and insert legacy row
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `analyzed_offers` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `source` TEXT NOT NULL,
                            `rawSnippet` TEXT NOT NULL,
                            `offerPriceBrl` REAL NOT NULL,
                            `pickupAddress` TEXT NOT NULL,
                            `pickupNeighborhood` TEXT NOT NULL,
                            `customerAddress` TEXT NOT NULL,
                            `customerNeighborhood` TEXT NOT NULL,
                            `leg1CurrentToPickupKm` REAL NOT NULL,
                            `leg2PickupToCustomerKm` REAL NOT NULL,
                            `leg3CustomerToCenterKm` REAL NOT NULL,
                            `totalKm` REAL NOT NULL,
                            `earningsPerKm` REAL NOT NULL,
                            `targetPerKm` REAL NOT NULL,
                            `decision` TEXT NOT NULL,
                            `decisionReason` TEXT NOT NULL,
                            `neighborhoodAlert` TEXT,
                            `routeSource` TEXT NOT NULL,
                            `usedGpsStart` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `neighborhood_rules` (
                            `name` TEXT NOT NULL,
                            `normalizedKey` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `notes` TEXT NOT NULL,
                            `isCustom` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`name`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `analyzed_offers` (
                            `timestamp`, `source`, `rawSnippet`, `offerPriceBrl`,
                            `pickupAddress`, `pickupNeighborhood`, `customerAddress`, `customerNeighborhood`,
                            `leg1CurrentToPickupKm`, `leg2PickupToCustomerKm`, `leg3CustomerToCenterKm`,
                            `totalKm`, `earningsPerKm`, `targetPerKm`, `decision`, `decisionReason`,
                            `neighborhoodAlert`, `routeSource`, `usedGpsStart`
                        ) VALUES (
                            1700000000000, 'MANUAL', 'legacy', 20.0,
                            'Centro', 'Centro', 'Ibituruna', 'Ibituruna',
                            1.0, 3.0, 3.0, 7.0, 2.86, 1.9, 'GREEN', 'Legacy OK',
                            NULL, 'OSRM_REAL_ROADS', 1
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()

        // 2. Open with Room v4 + MIGRATION_1_2 + MIGRATION_2_3 + MIGRATION_3_4
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        db = migratedDb

        val offersAfterMigration = migratedDb.offerDao().observeAllOffers().first()
        assertEquals(1, offersAfterMigration.size)
        val migratedOffer = offersAfterMigration.first()
        assertEquals("Legacy OK", migratedOffer.decisionReason)
        assertEquals("", migratedOffer.storeName)
        assertEquals(FieldConfidence.UNIDENTIFIED.name, migratedOffer.storeConfidence)
        assertEquals(FieldConfidence.UNIDENTIFIED.name, migratedOffer.pickupNeighborhoodConfidence)

        // 3. Test persistent Room cache with TTL
        val cacheStore = RoomGeoCacheStore(migratedDb.geoCacheDao())
        val now = 1_750_000_000_000L
        cacheStore.putAddress(
            CachedAddressEntity(
                normalizedQuery = "av esteves rodrigues 450 centro",
                originalQuery = "Av. Esteves Rodrigues, 450 - Centro",
                latitude = -16.7282,
                longitude = -43.8617,
                resolvedNeighborhood = "Centro",
                resolutionSource = "GEOCODER",
                createdAt = now,
                expiresAt = now + 60_000L
            )
        )
        assertNotNull(cacheStore.getValidAddress("av esteves rodrigues 450 centro", now + 10_000L))
        assertNull(cacheStore.getValidAddress("av esteves rodrigues 450 centro", now + 120_000L))

        cacheStore.putRoute(
            CachedRouteEntity(
                routeKey = "route_1",
                leg1Km = 1.2,
                leg2Km = 2.4,
                leg3Km = 2.5,
                totalKm = 6.1,
                routeSource = "OSRM_REAL_ROADS",
                createdAt = now,
                expiresAt = now + 60_000L
            )
        )
        assertNotNull(cacheStore.getValidRoute("route_1", now + 1_000L))
        assertNull(cacheStore.getValidRoute("route_1", now + 90_000L))

        assertEquals(1, migratedDb.geoCacheDao().countValidAddresses(now + 10_000L))
        cacheStore.pruneExpired(now + 120_000L)
        assertEquals(0, migratedDb.geoCacheDao().countValidAddresses(now + 120_000L))
        assertEquals(0, migratedDb.geoCacheDao().countValidRoutes(now + 120_000L))

        // 4. Verify old offer pruning works without wiping fresh offers
        val repo = OfferRepository(migratedDb.offerDao(), migratedDb.neighborhoodDao())
        val freshOffer = AnalyzedOfferEntity(
            timestamp = System.currentTimeMillis(),
            source = "MANUAL",
            rawSnippet = "fresh",
            offerPriceBrl = 19.0,
            storeName = "Restaurante Teste",
            storeConfidence = FieldConfidence.HIGH.name,
            pickupAddress = "Centro",
            pickupAddressConfidence = FieldConfidence.HIGH.name,
            pickupNeighborhood = "Centro",
            pickupNeighborhoodConfidence = FieldConfidence.HIGH.name,
            customerAddress = "Ibituruna",
            customerAddressConfidence = FieldConfidence.HIGH.name,
            customerNeighborhood = "Ibituruna",
            customerNeighborhoodConfidence = FieldConfidence.HIGH.name,
            leg1CurrentToPickupKm = 1.0,
            leg2PickupToCustomerKm = 3.0,
            leg3CustomerToCenterKm = 3.0,
            totalKm = 7.0,
            earningsPerKm = 2.71,
            targetPerKm = 1.9,
            decision = "GREEN",
            decisionReason = "Fresh OK",
            routeSource = "OSRM_REAL_ROADS",
            usedGpsStart = true
        )
        repo.saveAnalyzedOffer(freshOffer)
        val remaining = migratedDb.offerDao().observeAllOffers().first()
        assertEquals(1, remaining.size)
        assertEquals("Fresh OK", remaining.first().decisionReason)
        assertEquals("Restaurante Teste", remaining.first().storeName)
        assertEquals(FieldConfidence.HIGH.name, remaining.first().storeConfidence)
    }

    @Test
    fun `migration from v3 to v4 preserves existing stores and history without loss`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_v3_v4_${System.currentTimeMillis()}.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `analyzed_offers` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `source` TEXT NOT NULL,
                            `rawSnippet` TEXT NOT NULL,
                            `offerPriceBrl` REAL NOT NULL,
                            `storeName` TEXT NOT NULL DEFAULT '',
                            `pickupAddress` TEXT NOT NULL,
                            `pickupNeighborhood` TEXT NOT NULL,
                            `customerAddress` TEXT NOT NULL,
                            `customerNeighborhood` TEXT NOT NULL,
                            `leg1CurrentToPickupKm` REAL NOT NULL,
                            `leg2PickupToCustomerKm` REAL NOT NULL,
                            `leg3CustomerToCenterKm` REAL NOT NULL,
                            `totalKm` REAL NOT NULL,
                            `earningsPerKm` REAL NOT NULL,
                            `targetPerKm` REAL NOT NULL,
                            `decision` TEXT NOT NULL,
                            `decisionReason` TEXT NOT NULL,
                            `neighborhoodAlert` TEXT,
                            `routeSource` TEXT NOT NULL,
                            `usedGpsStart` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `neighborhood_rules` (
                            `name` TEXT NOT NULL,
                            `normalizedKey` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `notes` TEXT NOT NULL,
                            `isCustom` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`name`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `cached_addresses` (
                            `normalizedQuery` TEXT NOT NULL,
                            `originalQuery` TEXT NOT NULL,
                            `latitude` REAL NOT NULL,
                            `longitude` REAL NOT NULL,
                            `resolvedNeighborhood` TEXT NOT NULL,
                            `resolutionSource` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `expiresAt` INTEGER NOT NULL,
                            PRIMARY KEY(`normalizedQuery`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_addresses_expiresAt` ON `cached_addresses` (`expiresAt`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `cached_routes` (
                            `routeKey` TEXT NOT NULL,
                            `leg1Km` REAL NOT NULL,
                            `leg2Km` REAL NOT NULL,
                            `leg3Km` REAL NOT NULL,
                            `totalKm` REAL NOT NULL,
                            `routeSource` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `expiresAt` INTEGER NOT NULL,
                            PRIMARY KEY(`routeKey`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_routes_expiresAt` ON `cached_routes` (`expiresAt`)")
                    db.execSQL(
                        """
                        INSERT INTO `analyzed_offers` (
                            `timestamp`, `source`, `rawSnippet`, `offerPriceBrl`, `storeName`,
                            `pickupAddress`, `pickupNeighborhood`, `customerAddress`, `customerNeighborhood`,
                            `leg1CurrentToPickupKm`, `leg2PickupToCustomerKm`, `leg3CustomerToCenterKm`,
                            `totalKm`, `earningsPerKm`, `targetPerKm`, `decision`, `decisionReason`,
                            `neighborhoodAlert`, `routeSource`, `usedGpsStart`
                        ) VALUES (
                            1750000000000, 'OCR_SCREENSHOT', 'snippet', 25.0, 'Bodega do Cerrado',
                            'Rua Tupinambás, 210 - Melo', 'Melo', 'Rua Santa Lúcia, 340 - Maracanã', 'Maracanã',
                            1.2, 3.8, 3.5, 8.5, 2.94, 1.9, 'GREEN', 'V3 Preserved',
                            NULL, 'OSRM_REAL_ROADS', 1
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        db = migratedDb

        val list = migratedDb.offerDao().observeAllOffers().first()
        assertEquals(1, list.size)
        assertEquals("Bodega do Cerrado", list.first().storeName)
        assertEquals("V3 Preserved", list.first().decisionReason)
        assertEquals(FieldConfidence.UNIDENTIFIED.name, list.first().storeConfidence)
    }
}
