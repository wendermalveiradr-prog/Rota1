package com.example.service

import com.example.data.local.AnalyzedOfferEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class ServiceRuntimeState(
    val activeRunId: Long = 0L,
    val isAccessibilityConnected: Boolean = false,
    val isOverlayServiceRunning: Boolean = false,
    val isAnalyzing: Boolean = false,
    val lastDetectedPackage: String = "",
    val foregroundPackage: String = "",
    val isForegroundMobLets: Boolean = false,
    val minimizeNonce: Long = 0L,
    val expandNonce: Long = 0L,
    val lastCaptureMethod: String = "Aguardando oferta",
    val lastDetectionTimestamp: Long? = null,
    val lastOcrFallbackTimestamp: Long? = null,
    val latestOffer: AnalyzedOfferEntity? = null,
    val lastAnalysisError: String? = null,
    val lastStatusMessage: String = "Serviços prontos para monitoramento"
)

object ServiceRuntimeBus {
    private val runIdGenerator = AtomicLong(0L)
    private val _state = MutableStateFlow(ServiceRuntimeState())
    val state: StateFlow<ServiceRuntimeState> = _state.asStateFlow()

    private val _manualOcrTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val manualOcrTrigger: SharedFlow<Unit> = _manualOcrTrigger.asSharedFlow()

    fun nextRunId(): Long = runIdGenerator.incrementAndGet()

    fun isRunCurrent(runId: Long): Boolean = runId >= _state.value.activeRunId

    fun setAccessibilityConnected(connected: Boolean) {
        _state.update {
            it.copy(
                isAccessibilityConnected = connected,
                lastStatusMessage = if (connected) {
                    "Acessibilidade conectada ao leitor Mob Lets"
                } else {
                    "Acessibilidade desconectada"
                }
            )
        }
    }

    fun setOverlayServiceRunning(running: Boolean) {
        _state.update { it.copy(isOverlayServiceRunning = running) }
    }

    fun onForegroundAppChanged(packageName: String, isMobLets: Boolean) {
        if (packageName.isBlank()) return
        _state.update { current ->
            val packageChanged = current.foregroundPackage != packageName
            val modeChanged = current.isForegroundMobLets != isMobLets
            if (!packageChanged && !modeChanged) return@update current

            current.copy(
                foregroundPackage = packageName,
                isForegroundMobLets = isMobLets,
                minimizeNonce = if (!isMobLets) current.minimizeNonce + 1L else current.minimizeNonce,
                expandNonce = if (isMobLets && !current.isForegroundMobLets) {
                    current.expandNonce + 1L
                } else {
                    current.expandNonce
                }
            )
        }
    }

    fun beginRun(runId: Long, message: String, detectedPackage: String = "") {
        _state.update { current ->
            if (runId < current.activeRunId) return@update current
            current.copy(
                activeRunId = runId,
                isAnalyzing = true,
                latestOffer = null,
                lastAnalysisError = null,
                lastDetectedPackage = detectedPackage.ifBlank { current.lastDetectedPackage },
                lastStatusMessage = message
            )
        }
    }

    fun completeRun(runId: Long, offer: AnalyzedOfferEntity, detectedPackage: String = "") {
        _state.update { current ->
            if (runId < current.activeRunId) return@update current
            val shouldExpandForMobLets = current.isForegroundMobLets ||
                detectedPackage.contains("moblets", ignoreCase = true) ||
                detectedPackage.contains("mob.lets", ignoreCase = true) ||
                detectedPackage.contains("mob_lets", ignoreCase = true)
            current.copy(
                activeRunId = runId,
                isAnalyzing = false,
                latestOffer = offer,
                lastAnalysisError = null,
                lastDetectedPackage = detectedPackage.ifBlank { current.lastDetectedPackage },
                lastCaptureMethod = offer.source,
                lastDetectionTimestamp = offer.timestamp,
                lastOcrFallbackTimestamp = if (offer.source.startsWith("OCR")) offer.timestamp else current.lastOcrFallbackTimestamp,
                lastStatusMessage = offer.decisionReason,
                expandNonce = if (shouldExpandForMobLets) current.expandNonce + 1L else current.expandNonce
            )
        }
    }

    fun refineRun(runId: Long, refinedOffer: AnalyzedOfferEntity, detectedPackage: String = "") {
        _state.update { current ->
            if (runId != current.activeRunId) return@update current
            current.copy(
                isAnalyzing = false,
                latestOffer = refinedOffer,
                lastAnalysisError = null,
                lastDetectedPackage = detectedPackage.ifBlank { current.lastDetectedPackage },
                lastStatusMessage = refinedOffer.decisionReason
            )
        }
    }

    fun failRun(runId: Long, errorMessage: String, detectedPackage: String = "") {
        _state.update { current ->
            if (runId < current.activeRunId) return@update current
            current.copy(
                activeRunId = runId,
                isAnalyzing = false,
                latestOffer = null,
                lastAnalysisError = errorMessage,
                lastDetectedPackage = detectedPackage.ifBlank { current.lastDetectedPackage },
                lastStatusMessage = errorMessage
            )
        }
    }

    fun clearLatestOffer() {
        _state.update {
            it.copy(
                isAnalyzing = false,
                latestOffer = null,
                lastAnalysisError = null,
                lastStatusMessage = if (it.isAccessibilityConnected) {
                    "Aguardando nova oferta do Mob Lets..."
                } else {
                    "Serviços prontos para monitoramento"
                }
            )
        }
    }

    fun setAnalyzing(analyzing: Boolean, message: String? = null) {
        _state.update {
            it.copy(
                isAnalyzing = analyzing,
                lastAnalysisError = if (analyzing) null else it.lastAnalysisError,
                lastStatusMessage = message ?: it.lastStatusMessage
            )
        }
    }

    fun onOfferAnalyzed(offer: AnalyzedOfferEntity, detectedPackage: String = "") {
        val runId = nextRunId()
        completeRun(runId, offer, detectedPackage)
    }

    fun updateStatusMessage(message: String, detectedPackage: String? = null) {
        _state.update {
            it.copy(
                lastStatusMessage = message,
                lastDetectedPackage = detectedPackage ?: it.lastDetectedPackage
            )
        }
    }

    fun requestImmediateScreenOcr() {
        _manualOcrTrigger.tryEmit(Unit)
    }

    fun requestExpandOverlay() {
        _state.update { current ->
            current.copy(expandNonce = current.expandNonce + 1L)
        }
    }
}
