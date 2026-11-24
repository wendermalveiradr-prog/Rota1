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
    val overlayStoppedByUser: Boolean = false,
    val isAnalyzing: Boolean = false,
    val lastDetectedPackage: String = "",
    val foregroundPackage: String = "",
    val isForegroundTargetApp: Boolean = false,
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

    fun isTargetDeliveryPackage(pkg: String, screenText: String = ""): Boolean {
        val p = pkg.lowercase()
        val t = screenText.lowercase()
        return p.contains("moblets") ||
            p.contains("mob.lets") ||
            p.contains("mob_lets") ||
            p.contains("mobilete") ||
            p.contains("delivery") ||
            p.contains("entregador") ||
            t.contains("mob lets") ||
            t.contains("moblets") ||
            t.contains("mobilete")
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _state.update {
            it.copy(
                isAccessibilityConnected = connected,
                lastStatusMessage = if (connected) {
                    "Acessibilidade conectada (Mob Lets / Mobilete)"
                } else {
                    "Acessibilidade desconectada"
                }
            )
        }
    }

    fun setOverlayServiceRunning(running: Boolean) {
        _state.update {
            it.copy(
                isOverlayServiceRunning = running,
                overlayStoppedByUser = if (running) false else it.overlayStoppedByUser
            )
        }
    }

    fun markOverlayStoppedByUser() {
        _state.update {
            it.copy(
                isOverlayServiceRunning = false,
                overlayStoppedByUser = true,
                isForegroundTargetApp = false
            )
        }
    }

    fun allowOverlayAutoStart() {
        _state.update { it.copy(overlayStoppedByUser = false) }
    }

    fun onForegroundAppChanged(packageName: String, isTargetApp: Boolean) {
        if (packageName.isBlank()) return
        _state.update { current ->
            val packageChanged = current.foregroundPackage != packageName
            val modeChanged = current.isForegroundTargetApp != isTargetApp
            if (!packageChanged && !modeChanged) return@update current

            current.copy(
                foregroundPackage = packageName,
                isForegroundTargetApp = isTargetApp,
                minimizeNonce = if (!isTargetApp) current.minimizeNonce + 1L else current.minimizeNonce,
                expandNonce = if (isTargetApp) current.expandNonce + 1L else current.expandNonce
            )
        }
    }

    fun beginRun(runId: Long, message: String, detectedPackage: String = "") {
        _state.update { current ->
            if (runId < current.activeRunId) return@update current
            current.copy(
                activeRunId = runId,
                isAnalyzing = true,
                lastAnalysisError = null,
                lastDetectedPackage = detectedPackage.ifBlank { current.lastDetectedPackage },
                lastStatusMessage = message
            )
        }
    }

    fun completeRun(runId: Long, offer: AnalyzedOfferEntity, detectedPackage: String = "") {
        _state.update { current ->
            if (runId < current.activeRunId) return@update current
            val shouldExpand = current.isForegroundTargetApp ||
                isTargetDeliveryPackage(detectedPackage, offer.rawSnippet)
            current.copy(
                activeRunId = runId,
                isAnalyzing = false,
                latestOffer = offer,
                lastAnalysisError = null,
                lastDetectedPackage = detectedPackage.ifBlank { current.lastDetectedPackage },
                lastCaptureMethod = offer.source,
                lastDetectionTimestamp = offer.timestamp,
                lastOcrFallbackTimestamp = if (offer.source.startsWith("OCR")) {
                    offer.timestamp
                } else {
                    current.lastOcrFallbackTimestamp
                },
                lastStatusMessage = offer.decisionReason,
                expandNonce = if (shouldExpand) current.expandNonce + 1L else current.expandNonce
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

    fun updateStatusMessage(message: String, detectedPackage: String? = null) {
        _state.update {
            it.copy(
                lastStatusMessage = message,
                lastDetectedPackage = detectedPackage ?: it.lastDetectedPackage
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
                    "Aguardando nova oferta..."
                } else {
                    "Serviços prontos para monitoramento"
                }
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
