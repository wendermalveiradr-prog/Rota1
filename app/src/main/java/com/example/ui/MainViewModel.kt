package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RotaInteligenteApp
import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.NeighborhoodRuleEntity
import com.example.data.local.NeighborhoodStatus
import com.example.data.local.OfferCaptureSource
import com.example.data.preferences.DriverSettings
import com.example.domain.FieldConfidence
import com.example.domain.OfferAnalysisCoordinator
import com.example.domain.OfferAnalysisOutcome
import com.example.domain.OfferParser
import com.example.service.MobLetsAccessibilityService
import com.example.service.ServiceRuntimeBus
import com.example.service.ServiceRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PermissionSnapshot(
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val locationGranted: Boolean = false,
    val notificationsGranted: Boolean = true
) {
    val allCoreGranted: Boolean
        get() = accessibilityEnabled && overlayGranted && locationGranted && notificationsGranted
}

data class DashboardUiState(
    val runtime: ServiceRuntimeState = ServiceRuntimeState(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val latestOffer: AnalyzedOfferEntity? = null,
    val offerHistory: List<AnalyzedOfferEntity> = emptyList(),
    val neighborhoodRules: List<NeighborhoodRuleEntity> = emptyList(),
    val settings: DriverSettings = DriverSettings(),
    val isManualProcessing: Boolean = false,
    val feedbackBanner: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RotaInteligenteApp
    private val container = app.container

    private val permissionsFlow = MutableStateFlow(readPermissions())
    private val manualProcessingFlow = MutableStateFlow(false)
    private val feedbackFlow = MutableStateFlow<String?>(null)

    private val baseDataFlow = combine(
        ServiceRuntimeBus.state,
        permissionsFlow,
        container.offerRepository.latestOffer,
        container.offerRepository.allOffers,
        container.offerRepository.allNeighborhoodRules
    ) { runtime, permissions, latestOfferFromDb, history, rules ->
        // Never show a stale previous offer while a new run is analyzing or failed address resolution
        val effectiveLatest = when {
            runtime.isAnalyzing -> null
            !runtime.lastAnalysisError.isNullOrBlank() -> null
            runtime.latestOffer != null -> runtime.latestOffer
            else -> latestOfferFromDb
        }
        Triple(
            Pair(runtime, permissions),
            Pair(effectiveLatest, history),
            rules
        )
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        baseDataFlow,
        container.preferencesRepository.settingsFlow,
        manualProcessingFlow,
        feedbackFlow
    ) { base, settings, isManualProcessing, feedback ->
        DashboardUiState(
            runtime = base.first.first,
            permissions = base.first.second,
            latestOffer = base.second.first,
            offerHistory = base.second.second.take(20),
            neighborhoodRules = base.third,
            settings = settings,
            isManualProcessing = isManualProcessing,
            feedbackBanner = feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(permissions = readPermissions())
    )

    fun refreshPermissions() {
        val snap = readPermissions()
        permissionsFlow.value = snap
        if (snap.accessibilityEnabled != ServiceRuntimeBus.state.value.isAccessibilityConnected) {
            ServiceRuntimeBus.setAccessibilityConnected(snap.accessibilityEnabled)
        }
    }

    fun clearFeedback() {
        feedbackFlow.value = null
    }

    fun analyzeManualOffer(
        priceInput: String,
        pickupAddress: String,
        customerAddress: String,
        storeName: String = ""
    ) {
        val sanitizedPrice = priceInput
            .replace("R$", "", ignoreCase = true)
            .replace(" ", "")
            .replace(",", ".")
            .toDoubleOrNull()

        if (sanitizedPrice == null || sanitizedPrice <= 0.0) {
            feedbackFlow.value = "Informe um valor válido em reais (ex: 15,50)."
            return
        }
        if (pickupAddress.isBlank() || customerAddress.isBlank()) {
            feedbackFlow.value = "Preencha o endereço da loja de retirada e do cliente."
            return
        }

        viewModelScope.launch {
            manualProcessingFlow.value = true
            try {
                when (
                    val outcome = container.analysisCoordinator.analyzeStructuredOffer(
                        priceBrl = sanitizedPrice,
                        pickupAddress = pickupAddress.trim(),
                        customerAddress = customerAddress.trim(),
                        source = OfferCaptureSource.MANUAL,
                        rawSnippet = "Loja: ${storeName.trim().ifBlank { OfferParser.UNIDENTIFIED_LABEL }} | Valor: R$ $priceInput | Retirada: $pickupAddress | Entrega: $customerAddress",
                        storeName = storeName.trim(),
                        storeConfidence = if (storeName.trim().isNotBlank()) FieldConfidence.HIGH else FieldConfidence.UNIDENTIFIED,
                        pickupAddressConfidence = FieldConfidence.HIGH,
                        customerAddressConfidence = FieldConfidence.HIGH
                    )
                ) {
                    is OfferAnalysisOutcome.Success -> {
                        feedbackFlow.value = outcome.offer.decisionReason
                    }
                    is OfferAnalysisOutcome.Unresolved -> {
                        feedbackFlow.value = outcome.reason
                    }
                    is OfferAnalysisOutcome.Error -> {
                        feedbackFlow.value = outcome.reason
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feedbackFlow.value = "Erro ao calcular rota: ${e.message ?: "verifique os endereços"}"
            } finally {
                manualProcessingFlow.value = false
            }
        }
    }

    fun analyzeScreenshotUri(uri: Uri) {
        viewModelScope.launch {
            manualProcessingFlow.value = true
            try {
                when (val outcome = container.analysisCoordinator.analyzeFromOcrImageUri(uri)) {
                    is OfferAnalysisOutcome.Success -> {
                        feedbackFlow.value = "OCR concluído: ${outcome.offer.decisionReason}"
                    }
                    is OfferAnalysisOutcome.Unresolved -> {
                        feedbackFlow.value = outcome.reason
                    }
                    is OfferAnalysisOutcome.Error -> {
                        feedbackFlow.value = outcome.reason
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                manualProcessingFlow.value = false
            }
        }
    }

    fun toggleFloatingOverlay() {
        val permissions = readPermissions()
        permissionsFlow.value = permissions
        if (!permissions.overlayGranted) {
            feedbackFlow.value = "Conceda a permissão 'Sobrepor a outros apps' antes de iniciar o painel flutuante."
            return
        }
        if (ServiceRuntimeBus.state.value.isOverlayServiceRunning) {
            OfferAnalysisCoordinator.stopOverlay(app)
            feedbackFlow.value = "Janela flutuante encerrada."
        } else {
            OfferAnalysisCoordinator.startOrUpdateOverlay(app, forceEnable = true)
            feedbackFlow.value = "Janela flutuante iniciada sobre outros aplicativos."
        }
    }

    fun deleteOffer(id: Long) {
        viewModelScope.launch {
            if (ServiceRuntimeBus.state.value.latestOffer?.id == id) {
                ServiceRuntimeBus.clearLatestOffer()
            }
            container.offerRepository.deleteOffer(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            ServiceRuntimeBus.clearLatestOffer()
            container.offerRepository.clearHistory()
            feedbackFlow.value = "Histórico de ofertas limpo."
        }
    }

    fun updateNeighborhoodStatus(name: String, status: NeighborhoodStatus, notes: String = "") {
        viewModelScope.launch {
            container.offerRepository.updateNeighborhoodStatus(name, status, notes)
        }
    }

    fun addCustomNeighborhood(name: String, status: NeighborhoodStatus, notes: String) {
        viewModelScope.launch {
            container.offerRepository.addCustomNeighborhood(name, status, notes)
            feedbackFlow.value = "Bairro '$name' salvo nas regras."
        }
    }

    fun deleteCustomNeighborhood(name: String) {
        viewModelScope.launch {
            container.offerRepository.deleteCustomNeighborhood(name)
        }
    }

    fun updateMinTargetPerKm(value: Double) {
        viewModelScope.launch {
            container.preferencesRepository.setMinTargetPerKm(value)
        }
    }

    fun setOcrFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setOcrFallbackEnabled(enabled)
        }
    }

    fun setUseOsrmRoadRouting(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setUseOsrmRoadRouting(enabled)
        }
    }

    fun setAutoShowOverlayOnOffer(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setAutoShowOverlayOnOffer(enabled)
        }
    }

    fun setStrictMobLetsPackageFilter(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setStrictMobLetsPackageFilter(enabled)
        }
    }

    private fun readPermissions(): PermissionSnapshot {
        val overlayGranted = Settings.canDrawOverlays(app)
        val locationGranted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return PermissionSnapshot(
            accessibilityEnabled = isAccessibilityServiceEnabled(),
            overlayGranted = overlayGranted,
            locationGranted = locationGranted,
            notificationsGranted = notificationsGranted
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(app, MobLetsAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentString = splitter.next()
            val component = ComponentName.unflattenFromString(componentString)
            if (component == expectedComponent) return true
        }
        return false
    }
}
