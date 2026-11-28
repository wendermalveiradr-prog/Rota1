package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.RotaInteligenteApp
import com.example.data.local.OfferCaptureSource
import com.example.domain.MontesClarosGeoCatalog
import com.example.domain.OfferParser
import com.example.domain.RecentOfferPriceCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MobLetsAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isOcrInFlight = AtomicBoolean(false)

    @Volatile
    private var activeEventJob: Job? = null

    @Volatile
    private var lastEventScanMs = 0L
    private var cachedSettingsMem: com.example.data.preferences.DriverSettings? = null
    private var cachedSettingsAtMs = 0L

    @Volatile
    private var lastOcrScanMs = 0L

    @Volatile
    private var lastActiveOfferSignature = ""

    @Volatile
    private var lastOfferScreenVisibleMs = 0L

    @Volatile
    private var lastScreenFingerprint = ""

    @Volatile
    private var lastNonOfferScreenStartMs = 0L

    // Transient OS overlays / keyboards that should NOT trigger auto-minimize while inside Mob Lets
    private val transientSystemPackages = setOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )

    private val timerNoiseRegex = Regex("""\b\d{1,2}\s?(?:s|seg|min)\b|\b\d{1,2}:\d{2}\b""", RegexOption.IGNORE_CASE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceRuntimeBus.setAccessibilityConnected(true)

        serviceScope.launch {
            ServiceRuntimeBus.manualOcrTrigger.collectLatest {
                triggerOcrFallbackScan(
                    detectedPackage = ServiceRuntimeBus.state.value.lastDetectedPackage.ifBlank { "ocr.manual" },
                    requireKeywords = false
                )
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName || pkg in transientSystemPackages) return
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val nowCheck = System.currentTimeMillis()
        if (pkg != packageName && false) return
        val isMobLetsPackage = isMobLetsPackageName(pkg)

        // Track foreground app changes before ignoring our own app so opening Rota Inteligente IA
        // or switching away from Mob Lets immediately minimizes the floating overlay pill.
        if (pkg == packageName) {
            // pkg, isTargetApp = false)
            return
        }

        if (pkg == packageName || pkg == "com.android.settings" || pkg.startsWith("com.android.systemui")) {
            return
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && lastActiveOfferSignature.isNotBlank()) {
            val nowFast = System.currentTimeMillis()
            if (nowFast - lastOfferScreenVisibleMs < 60L) return
        }

        // pkg, isTargetApp = isMobLetsPackage)

                val now = System.currentTimeMillis()
        if (now - lastEventScanMs < EVENT_SCAN_INTERVAL_MS) return
        lastEventScanMs = now

        val eventStartedAt = SystemClock.elapsedRealtime()
        val app = application as? RotaInteligenteApp ?: return
        val coordinator = app.container.analysisCoordinator
        val prefs = app.container.preferencesRepository

        // Capture node text synchronously before node snapshots go stale
        val captureStartedAt = SystemClock.elapsedRealtime()
        val extractedText = collectWindowTextSafely()
        if (extractedText.length < 15) return
        val lowerQuick = extractedText.lowercase()
        if (!lowerQuick.contains("parada") && !lowerQuick.contains("retirada") && !lowerQuick.contains("aceitar") && !lowerQuick.contains("coleta")) return
        val captureMs = SystemClock.elapsedRealtime() - captureStartedAt
        val fingerprint = normalizeScreenFingerprint(pkg, extractedText)
        val sameVisualScreen = fingerprint.isNotBlank() && fingerprint == lastScreenFingerprint

        // Warm 2-minute memory price/km cache whenever an offer screen with R$ is visible,
        // even if customer addresses aren't visible until driver opens "Detalhes da entrega".
        if (isMobLetsPackage) {
            OfferParser.extractOfferScreenMetrics(extractedText)?.let { metrics ->
                RecentOfferPriceCache.remember(
                    priceBrl = metrics.priceBrl,
                    screenTotalKm = metrics.screenTotalKm,
                    storeNameHint = metrics.storeNameHint,
                    nowMs = now
                )
            }
        }

        val parseStartedAt = SystemClock.elapsedRealtime()
        val parsedFromAccessibility = OfferParser.parse(
            rawText = extractedText,
            requireDeliveryKeywords = !isMobLetsPackage
        )

        val parseMs = SystemClock.elapsedRealtime() - parseStartedAt
        if (parsedFromAccessibility != null) {
            val eventToDispatchMs = SystemClock.elapsedRealtime() - eventStartedAt
            Log.d("RotaPerf", "captura_tela=${captureMs}ms interpretacao=${parseMs}ms evento_ate_despacho=${eventToDispatchMs}ms texto=${extractedText.length}chars")
            lastOfferScreenVisibleMs = now
            lastNonOfferScreenStartMs = 0L
            lastScreenFingerprint = fingerprint

            val signature = buildSignature(
                parsedFromAccessibility.offerPriceBrl,
                parsedFromAccessibility.storeName,
                parsedFromAccessibility.pickupAddress,
                parsedFromAccessibility.customerAddress
            )

            // Same offer still displayed on screen (e.g., countdown tick or scroll redraw) -> do NOT re-analyze
            if (signature == lastActiveOfferSignature) {
                return
            }

            // New distinct offer detected -> expande o card automaticamente
            ServiceRuntimeBus.onForegroundAppChanged(pkg, isTargetApp = true)
            lastActiveOfferSignature = signature
            activeEventJob?.cancel()
            activeEventJob = serviceScope.launch {
                try {
                    val settings = cachedSettingsMem?.takeIf { System.currentTimeMillis() - cachedSettingsAtMs < 5000L } ?: prefs.getSettingsOnce().also { cachedSettingsMem = it; cachedSettingsAtMs = System.currentTimeMillis() }
                    if (!isPackageAllowedBySettings(pkg, settings)) {
                        return@launch
                    }
                    coordinator.analyzeStructuredOffer(
                        priceBrl = parsedFromAccessibility.offerPriceBrl,
                        pickupAddress = parsedFromAccessibility.pickupAddress,
                        customerAddress = parsedFromAccessibility.customerAddress,
                        source = OfferCaptureSource.ACCESSIBILITY,
                        rawSnippet = parsedFromAccessibility.rawSnippet,
                        detectedPackage = pkg,
                        storeName = parsedFromAccessibility.storeName,
                        storeConfidence = parsedFromAccessibility.storeConfidence,
                        pickupAddressConfidence = parsedFromAccessibility.pickupAddressConfidence,
                        pickupNeighborhoodHint = parsedFromAccessibility.pickupNeighborhoodHint,
                        customerAddressConfidence = parsedFromAccessibility.customerAddressConfidence,
                        customerNeighborhoodHint = parsedFromAccessibility.customerNeighborhoodHint,
                        pickupDistanceHintKm = parsedFromAccessibility.pickupDistanceKm,
                        screenTotalKmHint = parsedFromAccessibility.screenTotalKm
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServiceRuntimeBus.updateStatusMessage(
                        "Erro na leitura de tela: ${e.message ?: "tentando novamente"}",
                        pkg
                    )
                }
            }
            return
        }

        // Saiu da tela de oferta (voltou ao mapa Livre) -> limpa cache e minimiza na bolinha ROTA IA
        if (lastActiveOfferSignature.isNotBlank()) {
            lastActiveOfferSignature = ""
            RecentOfferPriceCache.clear()
            ServiceRuntimeBus.clearCurrentOfferCache()
            ServiceRuntimeBus.onForegroundAppChanged(pkg, isTargetApp = false)
        }

        if (sameVisualScreen) return
        lastScreenFingerprint = fingerprint

        if (now - lastOcrScanMs < OCR_SCAN_INTERVAL_MS || isOcrInFlight.get()) return

        serviceScope.launch {
            try {
                val settings = cachedSettingsMem?.takeIf { System.currentTimeMillis() - cachedSettingsAtMs < 5000L } ?: prefs.getSettingsOnce().also { cachedSettingsMem = it; cachedSettingsAtMs = System.currentTimeMillis() }
                if (!isPackageAllowedBySettings(pkg, settings)) return@launch
                if (false && settings.ocrFallbackEnabled) {
                    lastOcrScanMs = System.currentTimeMillis()
                    triggerOcrFallbackScan(
                        detectedPackage = pkg,
                        requireKeywords = !isMobLetsPackage
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun isMobLetsPackageName(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l == "br.com.letsexpress.taxi.taximachine" || l.contains("letsexpress") || l.contains("taximachine") || l.contains("moblets")
    }

    private fun is99PackageName(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l == "com.app99.driver" || l == "com.taxis99" || l.contains("app99") || l.contains("taxis99") || l.contains("didi")
    }

    private fun isUberPackageName(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l == "com.ubercab.driver" || l == "com.ubercab" || l.contains("ubercab")
    }

    private fun isPackageAllowedBySettings(pkg: String, settings: com.example.data.preferences.DriverSettings): Boolean {
        val isMob = isMobLetsPackageName(pkg)
        val is99 = is99PackageName(pkg)
        val isUber = isUberPackageName(pkg)
        if (!settings.strictMobLetsPackageFilter && !settings.filter99Enabled && !settings.filterUberEnabled) {
            return true
        }
        return (settings.strictMobLetsPackageFilter && isMob) ||
            (settings.filter99Enabled && is99) ||
            (settings.filterUberEnabled && isUber)
    }

    private fun triggerOcrFallbackScan(detectedPackage: String, requireKeywords: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ServiceRuntimeBus.updateStatusMessage(
                "Fallback OCR automático requer Android 11+ (use o leitor de print no painel).",
                detectedPackage
            )
            return
        }

        if (!isOcrInFlight.compareAndSet(false, true)) return

        val app = application as? RotaInteligenteApp ?: run {
            isOcrInFlight.set(false)
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var softwareBitmap: Bitmap? = null
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val hwBitmap = try {
                                Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            } finally {
                                hardwareBuffer.close()
                            }

                            if (hwBitmap == null) {
                                isOcrInFlight.set(false)
                                return
                            }
                            softwareBitmap = try {
                                hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                hwBitmap.recycle()
                            }
                        } catch (_: Exception) {
                            softwareBitmap?.recycle()
                            isOcrInFlight.set(false)
                            return
                        }

                        val capturedBitmap = softwareBitmap
                        if (capturedBitmap == null) {
                            isOcrInFlight.set(false)
                            return
                        }

                        serviceScope.launch {
                            try {
                                val ocrText = try {
                                    app.container.ocrProcessor.extractTextFromBitmap(capturedBitmap)
                                } finally {
                                    if (!capturedBitmap.isRecycled) {
                                        capturedBitmap.recycle()
                                    }
                                }

                                OfferParser.extractOfferScreenMetrics(ocrText)?.let { metrics ->
                                    RecentOfferPriceCache.remember(
                                        priceBrl = metrics.priceBrl,
                                        screenTotalKm = metrics.screenTotalKm,
                                        storeNameHint = metrics.storeNameHint
                                    )
                                }

                                val parsed = OfferParser.parse(
                                    rawText = ocrText,
                                    requireDeliveryKeywords = requireKeywords
                                ) ?: return@launch

                                val signature = buildSignature(
                                    parsed.offerPriceBrl,
                                    parsed.storeName,
                                    parsed.pickupAddress,
                                    parsed.customerAddress
                                )
                                if (signature == lastActiveOfferSignature) return@launch

                                lastActiveOfferSignature = signature
                                lastOfferScreenVisibleMs = System.currentTimeMillis()
                                lastNonOfferScreenStartMs = 0L

                                app.container.analysisCoordinator.analyzeStructuredOffer(
                                    priceBrl = parsed.offerPriceBrl,
                                    pickupAddress = parsed.pickupAddress,
                                    customerAddress = parsed.customerAddress,
                                    source = OfferCaptureSource.OCR_SCREENSHOT,
                                    rawSnippet = parsed.rawSnippet,
                                    detectedPackage = detectedPackage,
                                    storeName = parsed.storeName,
                                    storeConfidence = parsed.storeConfidence,
                                    pickupAddressConfidence = parsed.pickupAddressConfidence,
                                    pickupNeighborhoodHint = parsed.pickupNeighborhoodHint,
                                    customerAddressConfidence = parsed.customerAddressConfidence,
                                    customerNeighborhoodHint = parsed.customerNeighborhoodHint,
                                    pickupDistanceHintKm = parsed.pickupDistanceKm,
                                    screenTotalKmHint = parsed.screenTotalKm
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            } finally {
                                isOcrInFlight.set(false)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        isOcrInFlight.set(false)
                        ServiceRuntimeBus.updateStatusMessage(
                            "Captura OCR indisponível neste quadro (código $errorCode)",
                            detectedPackage
                        )
                    }
                }
            )
        } catch (_: Exception) {
            isOcrInFlight.set(false)
        }
    }

    private fun normalizeScreenFingerprint(pkg: String, text: String): String {
        if (text.isBlank()) return ""
        val withoutTimers = text.replace(timerNoiseRegex, " ")
        return "$pkg|${MontesClarosGeoCatalog.normalize(withoutTimers).take(300)}"
    }

    private fun buildSignature(
        priceBrl: Double,
        storeName: String,
        pickup: String,
        customer: String
    ): String {
        val normStore = MontesClarosGeoCatalog.normalize(storeName)
        val normP = MontesClarosGeoCatalog.normalizeAddressCacheKey(pickup)
        val normC = MontesClarosGeoCatalog.normalizeAddressCacheKey(customer)
        return "${Math.round(priceBrl * 100.0)}|$normStore|$normP|$normC"
    }

    private fun collectWindowTextSafely(): String {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return ""

        return try {
            collectNodeText(root)
        } finally {
            try {
                @Suppress("DEPRECATION")
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun collectNodeText(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        builder: StringBuilder = StringBuilder()
    ): String {
        if (node == null || depth > 20 || builder.length > 2200) return builder.toString()
        if (!node.isVisibleToUser) return builder.toString()
        val nodeClass = node.className?.toString().orEmpty()
        if (nodeClass.contains("MapView", ignoreCase = true) || nodeClass.contains("WebView", ignoreCase = true) || nodeClass.contains("GLSurfaceView", ignoreCase = true)) return builder.toString()

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            builder.append(text).append('\n')
        } else {
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrBlank()) {
                builder.append(desc).append('\n')
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectNodeText(child, depth + 1, builder)
            } finally {
                try {
                    @Suppress("DEPRECATION")
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return builder.toString()
    }

    override fun onInterrupt() {
        activeEventJob?.cancel()
        isOcrInFlight.set(false)
        ServiceRuntimeBus.setAccessibilityConnected(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeEventJob?.cancel()
        isOcrInFlight.set(false)
        serviceScope.cancel()
        ServiceRuntimeBus.setAccessibilityConnected(false)
    }

    companion object {
        private const val EVENT_SCAN_INTERVAL_MS = 60L
        private const val OCR_SCAN_INTERVAL_MS = 2000L
        private const val OFFER_LEAVE_CONFIRMATION_MS = 2500L
    }
}
