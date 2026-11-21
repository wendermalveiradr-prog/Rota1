package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AnalyzedOfferEntity::class,
        NeighborhoodRuleEntity::class,
        CachedAddressEntity::class,
        CachedRouteEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offerDao(): OfferDao
    abstract fun neighborhoodDao(): NeighborhoodDao
    abstract fun geoCacheDao(): GeoCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_addresses_expiresAt` ON `cached_addresses` (`expiresAt`)"
                )

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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_routes_expiresAt` ON `cached_routes` (`expiresAt`)"
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `analyzed_offers` ADD COLUMN `storeName` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `analyzed_offers` ADD COLUMN `storeConfidence` TEXT NOT NULL DEFAULT 'UNIDENTIFIED'"
                )
                db.execSQL(
                    "ALTER TABLE `analyzed_offers` ADD COLUMN `pickupAddressConfidence` TEXT NOT NULL DEFAULT 'UNIDENTIFIED'"
                )
                db.execSQL(
                    "ALTER TABLE `analyzed_offers` ADD COLUMN `pickupNeighborhoodConfidence` TEXT NOT NULL DEFAULT 'UNIDENTIFIED'"
                )
                db.execSQL(
                    "ALTER TABLE `analyzed_offers` ADD COLUMN `customerAddressConfidence` TEXT NOT NULL DEFAULT 'UNIDENTIFIED'"
                )
                db.execSQL(
                    "ALTER TABLE `analyzed_offers` ADD COLUMN `customerNeighborhoodConfidence` TEXT NOT NULL DEFAULT 'UNIDENTIFIED'"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rota_inteligente_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
