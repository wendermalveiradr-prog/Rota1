package com.example

import android.app.Application
import com.example.data.GeoCacheStore
import com.example.data.OfferRepository
import com.example.data.RoomGeoCacheStore
import com.example.data.local.AppDatabase
import com.example.data.preferences.DriverPreferencesRepository
import com.example.domain.LocationHelper
import com.example.domain.OcrFallbackProcessor
import com.example.domain.OfferAnalysisCoordinator
import com.example.domain.RouteCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    private val database = AppDatabase.getInstance(application)
    val geoCacheStore: GeoCacheStore = RoomGeoCacheStore(database.geoCacheDao())
    val offerRepository = OfferRepository(
        offerDao = database.offerDao(),
        neighborhoodDao = database.neighborhoodDao()
    )
    val preferencesRepository = DriverPreferencesRepository(application)
    val locationHelper = LocationHelper(application)
    val routeCalculator = RouteCalculator(
        context = application,
        cacheStore = geoCacheStore
    )
    val ocrProcessor = OcrFallbackProcessor()
    val analysisCoordinator = OfferAnalysisCoordinator(
        context = application,
        offerRepository = offerRepository,
        preferencesRepository = preferencesRepository,
        locationHelper = locationHelper,
        routeCalculator = routeCalculator,
        ocrProcessor = ocrProcessor
    )
}

class RotaInteligenteApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            container.offerRepository.ensureSeededNeighborhoods()
            container.analysisCoordinator.warmUpCaches()
            container.offerRepository.pruneOldOffers()
            container.geoCacheStore.pruneExpired()
        }
    }
}
