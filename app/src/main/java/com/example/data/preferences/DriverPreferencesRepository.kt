package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "driver_settings")

data class DriverSettings(
    val minTargetPerKm: Double = 1.90,
    val ocrFallbackEnabled: Boolean = true,
    val useOsrmRoadRouting: Boolean = true,
    val autoShowOverlayOnOffer: Boolean = true,
    val strictMobLetsPackageFilter: Boolean = true,
    val filter99Enabled: Boolean = false,
    val filterUberEnabled: Boolean = false,
    val cautionReturnThresholdKm: Double = 6.5,
    val countReturnEnabled: Boolean = false
)

class DriverPreferencesRepository(private val context: Context) {

    private object Keys {
        val MIN_TARGET_PER_KM = doublePreferencesKey("min_target_per_km")
        val OCR_FALLBACK_ENABLED = booleanPreferencesKey("ocr_fallback_enabled")
        val USE_OSRM_ROAD_ROUTING = booleanPreferencesKey("use_osrm_road_routing")
        val AUTO_SHOW_OVERLAY = booleanPreferencesKey("auto_show_overlay")
        val STRICT_MOBLETS_FILTER = booleanPreferencesKey("strict_moblets_filter")
        val FILTER_99_ENABLED = booleanPreferencesKey("filter_99_enabled")
        val FILTER_UBER_ENABLED = booleanPreferencesKey("filter_uber_enabled")
        val CAUTION_RETURN_KM = doublePreferencesKey("caution_return_km")
        val COUNT_RETURN_ENABLED = booleanPreferencesKey("count_return_enabled")
    }

    val settingsFlow: Flow<DriverSettings> = context.dataStore.data.map { prefs ->
        DriverSettings(
            minTargetPerKm = prefs[Keys.MIN_TARGET_PER_KM] ?: 1.90,
            ocrFallbackEnabled = prefs[Keys.OCR_FALLBACK_ENABLED] ?: true,
            useOsrmRoadRouting = prefs[Keys.USE_OSRM_ROAD_ROUTING] ?: true,
            autoShowOverlayOnOffer = prefs[Keys.AUTO_SHOW_OVERLAY] ?: true,
            strictMobLetsPackageFilter = prefs[Keys.STRICT_MOBLETS_FILTER] ?: true,
            filter99Enabled = prefs[Keys.FILTER_99_ENABLED] ?: false,
            filterUberEnabled = prefs[Keys.FILTER_UBER_ENABLED] ?: false,
            cautionReturnThresholdKm = prefs[Keys.CAUTION_RETURN_KM] ?: 6.5,
            countReturnEnabled = prefs[Keys.COUNT_RETURN_ENABLED] ?: false
        )
    }

    suspend fun getSettingsOnce(): DriverSettings = settingsFlow.first()

    suspend fun setMinTargetPerKm(value: Double) {
        context.dataStore.edit { it[Keys.MIN_TARGET_PER_KM] = value.coerceIn(0.50, 10.0) }
    }

    suspend fun setOcrFallbackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OCR_FALLBACK_ENABLED] = enabled }
    }

    suspend fun setUseOsrmRoadRouting(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_OSRM_ROAD_ROUTING] = enabled }
    }

    suspend fun setAutoShowOverlayOnOffer(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SHOW_OVERLAY] = enabled }
    }

    suspend fun setStrictMobLetsPackageFilter(enabled: Boolean) {
        context.dataStore.edit { it[Keys.STRICT_MOBLETS_FILTER] = enabled }
    }

    suspend fun setFilter99Enabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FILTER_99_ENABLED] = enabled }
    }

    suspend fun setFilterUberEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FILTER_UBER_ENABLED] = enabled }
    }

    suspend fun setCautionReturnThresholdKm(km: Double) {
        context.dataStore.edit { it[Keys.CAUTION_RETURN_KM] = km.coerceIn(2.0, 20.0) }
    }

    suspend fun setCountReturnEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COUNT_RETURN_ENABLED] = enabled }
    }
}
