package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
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
        if (pkg.isBlank() || pkg in transientSystemPackages) return

        val isMobLetsPackage = isMobLetsPackageName(pkg)

        // Track foreground app changes before ignoring our own app so opening Rota Inteligente IA
        // or switching away from Mob Lets immediately minimizes the floating overlay pill.
        if (pkg == packageName) {
            // pkg, isTargetApp = false)
            return
        }

        if (pkg == "com.android.settings") {
            // pkg, isTargetApp = false)
            return
        }

        // pkg, isTargetApp = isMobLetsPackage)

        if (activeEventJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastEventScanMs < EVENT_SCAN_INTERVAL_MS) return
        lastEventScanMs = now

        val app = application as? RotaInteligenteApp ?: return
        val coordinator = app.container.analysisCoordinator
        val prefs = app.container.preferencesRepository

        // Capture node text synchronously before node snapshots go stale
        val rawCaptured = collectWindowTextSafely()
        val extractedText = if (rawCaptured.contains("Detalhes da entrega", ignoreCase = true) || rawCaptured.contains("1º parada", ignoreCase = true) || rawCaptured.contains("1ª parada", ignoreCase = true)) {
            rawCaptured.lines().filterNot { l ->
                val low = l.lowercase()
                low.contains("disp. (") || low.contains("pend. (") || low.contains("prog. (") ||
                low.trim() == "mapa" || low.trim() == "livre" || low.trim() == "entregas" || low.trim() == "desconectar"
            }.joinToString("\n")
        } else {
            rawCaptured
        }
        if (extractedText.contains("Nenhuma entrega no momento", ignoreCase = true)) return
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

        val parsedFromAccessibility = OfferParser.parse(
            rawText = extractedText,
            requireDeliveryKeywords = !isMobLetsPackage
        )

        if (parsedFromAccessibility != null) {
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

            // New distinct offer detected -> cancel only pending accessibility parsing job and start instant analysis
            lastActiveOfferSignature = signature
            // Manual apenas
            activeEventJob?.cancel()
            activeEventJob = serviceScope.launch {
                try {
                    val settings = prefs.getSettingsOnce()
                    if (settings.strictMobLetsPackageFilter && !isMobLetsPackage) {
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

        // Screen has non-offer content: reset lastActiveOfferSignature only if user left the offer screen stably
        if (extractedText.length >= 40) {
            if (lastNonOfferScreenStartMs == 0L) {
                lastNonOfferScreenStartMs = now
            } else if (lastActiveOfferSignature.isNotBlank() &&
                (now - lastNonOfferScreenStartMs) >= OFFER_LEAVE_CONFIRMATION_MS
            ) {
                lastActiveOfferSignature = ""
            }
        }

        if (sameVisualScreen && lastActiveOfferSignature.isNotBlank()) return
        lastScreenFingerprint = fingerprint

        if (now - lastOcrScanMs < OCR_SCAN_INTERVAL_MS || isOcrInFlight.get()) return

        serviceScope.launch {
            try {
                val settings = prefs.getSettingsOnce()
                if (settings.strictMobLetsPackageFilter && !isMobLetsPackage) return@launch
                if (settings.ocrFallbackEnabled && (isMobLetsPackage || extractedText.length < 35)) {
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
        val rootText = try {
            val root = rootInActiveWindow
            val text = collectNodeText(root)
            try { root?.recycle() } catch (_: Exception) {}
            text
        } catch (_: Exception) {
            ""
        }
        return pkg.contains("moblets", ignoreCase = true) ||
            pkg.contains("mob.lets", ignoreCase = true) ||
            pkg.contains("mob_lets", ignoreCase = true) || pkg.contains("ubercab.driver", ignoreCase = true) || pkg.contains("taxis99", ignoreCase = true) || pkg.contains("didiglobal.driver", ignoreCase = true) ||
            pkg.contains("delivery", ignoreCase = true) ||
            rootText.contains("mob", ignoreCase = true) ||
            rootText.contains("corrida", ignoreCase = true) ||
            rootText.contains("entrega", ignoreCase = true)
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
        if (node == null || depth > 24 || builder.length > 4000) return builder.toString()

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
        private const val EVENT_SCAN_INTERVAL_MS = 300L
        private const val OCR_SCAN_INTERVAL_MS = 3500L
        private const val OFFER_LEAVE_CONFIRMATION_MS = 2500L
    }
}
