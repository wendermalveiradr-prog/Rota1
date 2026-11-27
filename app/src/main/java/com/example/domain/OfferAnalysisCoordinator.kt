package com.example.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.data.OfferRepository
import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.NeighborhoodRuleEntity
import com.example.data.local.OfferCaptureSource
import com.example.data.preferences.DriverPreferencesRepository
import com.example.data.preferences.DriverSettings
import com.example.service.FloatingOverlayService
import com.example.service.ServiceRuntimeBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class OfferAnalysisOutcome {
    data class Success(val offer: AnalyzedOfferEntity) : OfferAnalysisOutcome()
    data class Unresolved(val reason: String) : OfferAnalysisOutcome()
    data class Error(val reason: String) : OfferAnalysisOutcome()
}

class OfferAnalysisCoordinator(
    private val context: Context,
    private val offerRepository: OfferRepository,
    private val preferencesRepository: DriverPreferencesRepository,
    private val locationHelper: LocationHelper,
    private val routeCalculator: RouteCalculator,
    private val ocrProcessor: OcrFallbackProcessor
) {
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchMutex = Mutex()
    private var activeRunId: Long = 0L
    private var activeSignature: String? = null
    private var activeDeferred: Deferred<OfferAnalysisOutcome>? = null
    private var activeRefinementJob: Job? = null

    @Volatile
    private var cachedSettings: DriverSettings? = null

    @Volatile
    private var cachedRules: List<NeighborhoodRuleEntity>? = null

    suspend fun analyzeStructuredOffer(
        priceBrl: Double,
        pickupAddress: String,
        customerAddress: String,
        source: OfferCaptureSource,
        rawSnippet: String = "",
        detectedPackage: String = "",
        storeName: String = "",
        storeConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
        pickupAddressConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
        pickupNeighborhoodHint: String = "",
        customerAddressConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
        customerNeighborhoodHint: String = "",
        pickupDistanceHintKm: Double? = null,
        screenTotalKmHint: Double? = null
    ): OfferAnalysisOutcome {
        val cleanStoreName = storeName.trim()
        val cleanPickup = pickupAddress.trim()
        val cleanCustomer = customerAddress.trim()
        val signature = buildOfferSignature(priceBrl, cleanStoreName, cleanPickup, cleanCustomer)

        val (runId, deferredToAwait) = dispatchMutex.withLock {
            val currentDeferred = activeDeferred
            if (currentDeferred != null && currentDeferred.isActive && activeSignature == signature) {
                return@withLock activeRunId to currentDeferred
            }

            // Cancel previous stale run & background OSRM job cleanly when a distinct offer arrives
            currentDeferred?.cancel(CancellationException("Nova oferta recebida"))
            activeRefinementJob?.cancel(CancellationException("Nova oferta recebida"))

            val nextRunId = ServiceRuntimeBus.nextRunId()
            ServiceRuntimeBus.beginRun(
                runId = nextRunId,
                message = "Calculando semáforo instantâneo (Montes Claros)...",
                detectedPackage = detectedPackage
            )

            val newDeferred = coordinatorScope.async {
                executeOfferAnalysis(
                    runId = nextRunId,
                    priceBrl = priceBrl,
                    storeName = cleanStoreName,
                    storeConfidence = storeConfidence,
                    pickupAddress = cleanPickup,
                    pickupAddressConfidence = pickupAddressConfidence,
                    pickupNeighborhoodHint = pickupNeighborhoodHint.trim(),
                    customerAddress = cleanCustomer,
                    customerAddressConfidence = customerAddressConfidence,
                    customerNeighborhoodHint = customerNeighborhoodHint.trim(),
                    pickupDistanceHintKm = pickupDistanceHintKm,
                    screenTotalKmHint = screenTotalKmHint,
                    source = source,
                    rawSnippet = rawSnippet,
                    detectedPackage = detectedPackage
                )
            }

            activeRunId = nextRunId
            activeSignature = signature
            activeDeferred = newDeferred

            newDeferred.invokeOnCompletion {
                coordinatorScope.launch {
                    dispatchMutex.withLock {
                        if (activeRunId == nextRunId && activeDeferred === newDeferred) {
                            activeDeferred = null
                            activeSignature = null
                        }
                    }
                }
            }

            nextRunId to newDeferred
        }

        return try {
            deferredToAwait.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Falha inesperada na análise: ${e.message ?: "erro interno"}"
            ServiceRuntimeBus.failRun(runId, msg, detectedPackage)
            OfferAnalysisOutcome.Error(msg)
        }
    }

    private suspend fun executeOfferAnalysis(
        runId: Long,
        priceBrl: Double,
        storeName: String,
        storeConfidence: FieldConfidence,
        pickupAddress: String,
        pickupAddressConfidence: FieldConfidence,
        pickupNeighborhoodHint: String,
        customerAddress: String,
        customerAddressConfidence: FieldConfidence,
        customerNeighborhoodHint: String,
        pickupDistanceHintKm: Double?,
        screenTotalKmHint: Double?,
        source: OfferCaptureSource,
        rawSnippet: String,
        detectedPackage: String
    ): OfferAnalysisOutcome {
        return try {
            val settings = cachedSettings ?: preferencesRepository.getSettingsOnce().also {
                cachedSettings = it
            }
            val rules = cachedRules ?: offerRepository.getNeighborhoodRulesOnce().also {
                cachedRules = it
            }

            if (!ServiceRuntimeBus.isRunCurrent(runId)) {
                throw CancellationException("Execução substituída por oferta mais recente")
            }

            val effectiveSnippet = rawSnippet.ifBlank {
                "Loja: ${storeName.ifBlank { OfferParser.UNIDENTIFIED_LABEL }} | Valor: R$ $priceBrl | Retirada: $pickupAddress | Entrega: $customerAddress"
            }

            // STAGE 1: Instant < 300ms offline calculation (screen numbers + MOC offline catalog)
            val instantLocation = locationHelper.getCachedLocationOrCenterInstant()
            val instantRoute = routeCalculator.calculateInstantOfflineRoute(
                currentLocation = instantLocation.point,
                pickupAddress = pickupAddress,
                customerAddress = customerAddress,
                pickupDistanceHintKm = pickupDistanceHintKm,
                screenTotalKmHint = screenTotalKmHint,
                countReturn = rawSnippet.contains("com retorno", ignoreCase = true)
            )

            if (instantRoute != null) {
                val instantEvaluated = OfferDecisionEngine.evaluateOffer(
                    source = source,
                    rawSnippet = effectiveSnippet,
                    offerPriceBrl = priceBrl,
                    storeName = storeName,
                    storeConfidence = storeConfidence,
                    pickupAddress = pickupAddress,
                    pickupAddressConfidence = pickupAddressConfidence,
                    pickupNeighborhoodHint = pickupNeighborhoodHint,
                    customerAddress = customerAddress,
                    customerAddressConfidence = customerAddressConfidence,
                    customerNeighborhoodHint = customerNeighborhoodHint,
                    route = instantRoute,
                    usedGpsStart = instantLocation.usedGps,
                    neighborhoodRules = rules,
                    settings = settings
                )

                // Exibe resultado instantaneo (< 5ms) na janela flutuante e grava no historico sem travar
                ServiceRuntimeBus.completeRun(runId, instantEvaluated, detectedPackage)
                coordinatorScope.launch {
                    try {
                        offerRepository.saveAnalyzedOffer(instantEvaluated)
                    } catch (_: Exception) {
                    }
                }

                // STAGE 2: Non-blocking background refinement via fresh GPS + Geocoder + OSRM
                if (false) scheduleBackgroundOsrmRefinement(
                    runId = runId,
                    savedOfferId = 0L,
                    originalTimestamp = instantEvaluated.timestamp,
                    priceBrl = priceBrl,
                    storeName = storeName,
                    storeConfidence = storeConfidence,
                    pickupAddress = pickupAddress,
                    pickupAddressConfidence = pickupAddressConfidence,
                    pickupNeighborhoodHint = pickupNeighborhoodHint,
                    customerAddress = customerAddress,
                    customerAddressConfidence = customerAddressConfidence,
                    customerNeighborhoodHint = customerNeighborhoodHint,
                    source = source,
                    rawSnippet = effectiveSnippet,
                    detectedPackage = detectedPackage,
                    initialSettings = settings
                )

                return OfferAnalysisOutcome.Success(instantEvaluated)
            }

            // Fallback path when neither catalog nor screen km provided instant offline coordinates:
            val freshSettings = preferencesRepository.getSettingsOnce().also { cachedSettings = it }
            val freshRules = offerRepository.getNeighborhoodRulesOnce().also { cachedRules = it }
            val driverLocation = locationHelper.getCurrentLocationOrFallback()

            if (!ServiceRuntimeBus.isRunCurrent(runId)) {
                throw CancellationException("Execução substituída por oferta mais recente")
            }

            when (
                val routeResolution = routeCalculator.calculateCompleteRoute(
                    currentLocation = driverLocation.point,
                    pickupAddress = pickupAddress,
                    customerAddress = customerAddress,
                    useOsrmRoadRouting = false,
                    countReturn = rawSnippet.contains("com retorno", ignoreCase = true)
                )
            ) {
                is RouteResolutionResult.UnresolvedAddress -> {
                    ServiceRuntimeBus.failRun(
                        runId = runId,
                        errorMessage = routeResolution.reason,
                        detectedPackage = detectedPackage
                    )
                    if (false) {
                        startOrUpdateOverlay(context)
                    }
                    OfferAnalysisOutcome.Unresolved(routeResolution.reason)
                }

                is RouteResolutionResult.Resolved -> {
                    if (!ServiceRuntimeBus.isRunCurrent(runId)) {
                        throw CancellationException("Execução substituída por oferta mais recente")
                    }

                    val evaluated = OfferDecisionEngine.evaluateOffer(
                        source = source,
                        rawSnippet = effectiveSnippet,
                        offerPriceBrl = priceBrl,
                        storeName = storeName,
                        storeConfidence = storeConfidence,
                        pickupAddress = pickupAddress,
                        pickupAddressConfidence = pickupAddressConfidence,
                        pickupNeighborhoodHint = pickupNeighborhoodHint,
                        customerAddress = customerAddress,
                        customerAddressConfidence = customerAddressConfidence,
                        customerNeighborhoodHint = customerNeighborhoodHint,
                        route = routeResolution.route,
                        usedGpsStart = driverLocation.usedGps,
                        neighborhoodRules = freshRules,
                        settings = freshSettings
                    )

                    val insertedId = offerRepository.saveAnalyzedOffer(evaluated)
                    val savedOffer = evaluated.copy(id = insertedId)
                    ServiceRuntimeBus.completeRun(runId, savedOffer, detectedPackage)

                    if (false) {
                        startOrUpdateOverlay(context)
                    }
                    OfferAnalysisOutcome.Success(savedOffer)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Erro ao calcular rota: ${e.message ?: "verifique a conexão"}"
            ServiceRuntimeBus.failRun(runId, errorMsg, detectedPackage)
            OfferAnalysisOutcome.Error(errorMsg)
        }
    }

    private fun scheduleBackgroundOsrmRefinement(
        runId: Long,
        savedOfferId: Long,
        originalTimestamp: Long,
        priceBrl: Double,
        storeName: String,
        storeConfidence: FieldConfidence,
        pickupAddress: String,
        pickupAddressConfidence: FieldConfidence,
        pickupNeighborhoodHint: String,
        customerAddress: String,
        customerAddressConfidence: FieldConfidence,
        customerNeighborhoodHint: String,
        source: OfferCaptureSource,
        rawSnippet: String,
        detectedPackage: String,
        initialSettings: DriverSettings
    ) {
        activeRefinementJob = coordinatorScope.launch {
            try {
                // Refresh rules & preferences asynchronously for next run too
                val freshSettings = preferencesRepository.getSettingsOnce().also { cachedSettings = it }
                val freshRules = offerRepository.getNeighborhoodRulesOnce().also { cachedRules = it }
                if (!ServiceRuntimeBus.isRunCurrent(runId)) return@launch

                val driverLocation = locationHelper.getCurrentLocationOrFallback()
                if (!ServiceRuntimeBus.isRunCurrent(runId)) return@launch

                val routeResolution = routeCalculator.calculateCompleteRoute(
                    currentLocation = driverLocation.point,
                    pickupAddress = pickupAddress,
                    customerAddress = customerAddress,
                    useOsrmRoadRouting = false,
                    countReturn = rawSnippet.contains("com retorno", ignoreCase = true)
                )

                if (!ServiceRuntimeBus.isRunCurrent(runId)) return@launch
                if (routeResolution is RouteResolutionResult.Resolved) {
                    val refined = OfferDecisionEngine.evaluateOffer(
                        source = source,
                        rawSnippet = rawSnippet,
                        offerPriceBrl = priceBrl,
                        storeName = storeName,
                        storeConfidence = storeConfidence,
                        pickupAddress = pickupAddress,
                        pickupAddressConfidence = pickupAddressConfidence,
                        pickupNeighborhoodHint = pickupNeighborhoodHint,
                        customerAddress = customerAddress,
                        customerAddressConfidence = customerAddressConfidence,
                        customerNeighborhoodHint = customerNeighborhoodHint,
                        route = routeResolution.route,
                        usedGpsStart = driverLocation.usedGps,
                        neighborhoodRules = freshRules,
                        settings = freshSettings
                    ).copy(
                        id = savedOfferId,
                        timestamp = originalTimestamp
                    )

                    offerRepository.updateAnalyzedOffer(refined)
                    ServiceRuntimeBus.refineRun(runId, refined, detectedPackage)

                    if (false) {
                        startOrUpdateOverlay(context)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep Stage 1 instant offline evaluation intact when offline or OSRM times out
            }
        }
    }

    suspend fun analyzeFromOcrImageUri(uri: Uri): OfferAnalysisOutcome {
        val runId = dispatchMutex.withLock {
            activeDeferred?.cancel(CancellationException("Nova leitura OCR solicitada"))
            activeRefinementJob?.cancel(CancellationException("Nova leitura OCR solicitada"))
            activeDeferred = null
            activeSignature = null
            val nextId = ServiceRuntimeBus.nextRunId()
            activeRunId = nextId
            ServiceRuntimeBus.beginRun(nextId, "Executando OCR (ML Kit) na imagem selecionada...")
            nextId
        }

        return try {
            val extractedText = ocrProcessor.extractTextFromUri(context, uri)
            if (!ServiceRuntimeBus.isRunCurrent(runId)) {
                return OfferAnalysisOutcome.Error("Leitura OCR descartada porque uma nova oferta chegou.")
            }

            val parsed = OfferParser.parse(extractedText, requireDeliveryKeywords = false)
            if (parsed == null) {
                val msg = "Não foi possível identificar valor (R$) e endereços válidos na imagem."
                ServiceRuntimeBus.failRun(runId, msg)
                OfferAnalysisOutcome.Error(msg)
            } else {
                analyzeStructuredOffer(
                    priceBrl = parsed.offerPriceBrl,
                    pickupAddress = parsed.pickupAddress,
                    customerAddress = parsed.customerAddress,
                    source = OfferCaptureSource.OCR_IMAGE,
                    rawSnippet = parsed.rawSnippet,
                    storeName = parsed.storeName,
                    storeConfidence = parsed.storeConfidence,
                    pickupAddressConfidence = parsed.pickupAddressConfidence,
                    pickupNeighborhoodHint = parsed.pickupNeighborhoodHint,
                    customerAddressConfidence = parsed.customerAddressConfidence,
                    customerNeighborhoodHint = parsed.customerNeighborhoodHint,
                    pickupDistanceHintKm = parsed.pickupDistanceKm,
                    screenTotalKmHint = parsed.screenTotalKm
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Falha no OCR: ${e.message ?: "erro ao ler imagem"}"
            ServiceRuntimeBus.failRun(runId, msg)
            OfferAnalysisOutcome.Error(msg)
        }
    }

    private fun buildOfferSignature(
        priceBrl: Double,
        storeName: String,
        pickupAddress: String,
        customerAddress: String
    ): String {
        val normStore = MontesClarosGeoCatalog.normalize(storeName)
        val normPickup = MontesClarosGeoCatalog.normalizeAddressCacheKey(pickupAddress)
        val normCustomer = MontesClarosGeoCatalog.normalizeAddressCacheKey(customerAddress)
        val roundedPrice = Math.round(priceBrl * 100.0)
        return "$roundedPrice|$normStore|$normPickup|$normCustomer"
    }

    companion object {
        @Volatile var isOverlayManuallyDisabled = false
                @Volatile var cachedRules: List<com.example.data.local.NeighborhoodRuleEntity>? = null

        fun startOrUpdateOverlay(context: Context, forceEnable: Boolean = false) {
            if (forceEnable) isOverlayManuallyDisabled = false
            if (isOverlayManuallyDisabled || !Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_SHOW_OR_UPDATE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stopOverlay(context: Context) {
            isOverlayManuallyDisabled = true
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
