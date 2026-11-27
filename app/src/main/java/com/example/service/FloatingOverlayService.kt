package com.example.service

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.RotaInteligenteApp
import com.example.data.local.OfferCaptureSource
import com.example.data.local.TrafficLightDecision
import com.example.data.preferences.DriverPreferencesRepository
import com.example.data.preferences.DriverSettings
import com.example.domain.MontesClarosGeoCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.action.STOP_OVERLAY"
        const val ACTION_SHOW_OR_UPDATE = "com.example.action.SHOW_OR_UPDATE"
        private const val CHANNEL_ID = "floating_overlay_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefsRepo by lazy { DriverPreferencesRepository(applicationContext) }
    private var currentSettings = DriverSettings()
    private var windowManager: WindowManager? = null
    private var overlayRoot: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isExpanded = true
    private var isStopping = false
    private var lastHandledMinimizeNonce = 0L
    private var lastHandledExpandNonce = 0L

    private lateinit var minimizedPillContainer: LinearLayout
    private lateinit var minimizedDotView: View
    private lateinit var minimizedLabelView: TextView

    private lateinit var expandedCardContainer: LinearLayout
    private lateinit var headerContainer: LinearLayout
    private lateinit var decisionDotView: View
    private lateinit var decisionTitleView: TextView
    private lateinit var rateMetricView: TextView
    private lateinit var expandToggleView: TextView
    private lateinit var detailsContainer: LinearLayout
    private lateinit var offerSummaryView: TextView
    private lateinit var routeBreakdownView: TextView
    private lateinit var alertBannerView: TextView
    private lateinit var footerRow: LinearLayout
    private lateinit var contarVoltaBtn: TextView
    private lateinit var metaButton: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        ServiceRuntimeBus.setOverlayServiceRunning(true)

        if (Settings.canDrawOverlays(this)) {
            attachOverlayWindow()
        }

        serviceScope.launch {
            prefsRepo.settingsFlow.collectLatest { settings ->
                if (isStopping) return@collectLatest
                currentSettings = settings
                if (::contarVoltaBtn.isInitialized) {
                    contarVoltaBtn.text = if (settings.countReturnEnabled) "🔄 Volta: ON" else "🔄 Volta: OFF"
                }
                if (::metaButton.isInitialized) {
                    metaButton.text = String.format(Locale("pt", "BR"), "Meta: R$ %.2f", settings.minTargetPerKm)
                }
                renderState(ServiceRuntimeBus.state.value)
            }
        }

        serviceScope.launch {
            ServiceRuntimeBus.state.collectLatest { runtime ->
                if (isStopping) return@collectLatest
                renderState(runtime)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP && com.example.domain.OfferAnalysisCoordinator.isOverlayManuallyDisabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlayNow()
                return START_NOT_STICKY
            }
            ACTION_SHOW_OR_UPDATE -> {
                if (isStopping || ServiceRuntimeBus.state.value.overlayStoppedByUser) {
                    stopOverlayNow()
                    return START_NOT_STICKY
                }
                if (overlayRoot == null && Settings.canDrawOverlays(this)) {
                    attachOverlayWindow()
                }
                renderState(ServiceRuntimeBus.state.value)
            }
        }
        return START_STICKY
    }

    private fun stopOverlayNow() {
        if (isStopping) return
        isStopping = true
        ServiceRuntimeBus.markOverlayStoppedByUser()
        detachOverlayWindow()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun detachOverlayWindow() {
        val view = overlayRoot ?: return
        overlayRoot = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
    }

    private fun attachOverlayWindow() {
        if (overlayRoot != null || isStopping) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val initialRuntime = ServiceRuntimeBus.state.value
        isExpanded = initialRuntime.isForegroundTargetApp
        lastHandledMinimizeNonce = initialRuntime.minimizeNonce
        lastHandledExpandNonce = initialRuntime.expandNonce

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            if (isExpanded) dp(318) else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(110)
        }
        layoutParams = params

        val root = buildOverlayView()
        overlayRoot = root
        applyExpandedState(isExpanded, updateWindowLayout = false)
        try {
            windowManager?.addView(root, params)
        } catch (_: Exception) {
            overlayRoot = null
        }
    }

    private fun buildOverlayView(): LinearLayout {
        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#F50B1220"))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.parseColor("#334155"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(14).toFloat()
        }

        minimizedPillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(34)
            setPadding(dp(4), dp(2), dp(6), dp(2))
        }

        minimizedDotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = circleDrawable(Color.parseColor("#38BDF8"))
        }

        minimizedLabelView = TextView(this).apply {
            text = "ROTA IA"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

        minimizedPillContainer.addView(minimizedDotView)
        minimizedPillContainer.addView(minimizedLabelView)
        attachDragBehavior(minimizedPillContainer) {
            applyExpandedState(expanded = true, updateWindowLayout = true)
        }

        expandedCardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        headerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        decisionDotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                marginEnd = dp(8)
            }
            background = circleDrawable(Color.parseColor("#38BDF8"))
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        decisionTitleView = TextView(this).apply {
            text = "ROTA INTELIGENTE IA"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        rateMetricView = TextView(this).apply {
            text = "Aguardando oferta..."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        titleCol.addView(decisionTitleView)
        titleCol.addView(rateMetricView)

        expandToggleView = TextView(this).apply {
            text = "–"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                applyExpandedState(expanded = !isExpanded, updateWindowLayout = true)
        }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { stopOverlayNow() }
        }

        headerContainer.addView(decisionDotView)
        headerContainer.addView(titleCol)
        headerContainer.addView(expandToggleView)
        headerContainer.addView(closeBtn)
        attachDragBehavior(headerContainer, onTap = null)

        detailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }

        offerSummaryView = TextView(this).apply {
            text = "Abra o app de entregas para leitura automática em Montes Claros."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            setLineSpacing(dp(2).toFloat(), 1f)
        }

        routeBreakdownView = TextView(this).apply {
            text = "Rota: Atual → Loja → Cliente"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }

        alertBannerView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#FECACA"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7F1D1D"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }

        detailsContainer.addView(offerSummaryView)
        detailsContainer.addView(routeBreakdownView)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }

        contarVoltaBtn = TextView(this).apply {
            text = if (currentSettings.countReturnEnabled) "🔄 Volta: ON" else "🔄 Volta: OFF"
            setTextColor(Color.parseColor("#BAE6FD"))
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(5), dp(3), dp(5), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C4A6E"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener {
                val nextState = !currentSettings.countReturnEnabled
                serviceScope.launch(Dispatchers.IO) {
                    prefsRepo.setCountReturnEnabled(nextState)
                    reanalyzeLatestOffer()
                }
            }
        }

        metaButton = TextView(this).apply {
            text = String.format(Locale("pt", "BR"), "Meta: R$ %.2f", currentSettings.minTargetPerKm)
            setTextColor(Color.parseColor("#34D399"))
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(5), dp(3), dp(5), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#064E3B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(3)
            }
            setOnClickListener { showGoalEditDialog() }
        }

        controlsRow.addView(contarVoltaBtn)
        controlsRow.addView(metaButton)
        listOf("1.50", "1.90", "2.50").forEach { preset ->
            controlsRow.addView(createGoalPresetButton(preset))
        }

        detailsContainer.addView(controlsRow)
        detailsContainer.addView(alertBannerView)

        footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }

        val ocrBtn = TextView(this).apply {
            text = "OCR Tela"
            setTextColor(Color.parseColor("#BAE6FD"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C4A6E"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { ServiceRuntimeBus.requestImmediateScreenOcr() }
        }

        val openAppBtn = TextView(this).apply {
            text = "Abrir Painel"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                applyExpandedState(expanded = false, updateWindowLayout = true)
                val launch = Intent(this@FloatingOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(launch)
            }
        }

        footerRow.addView(ocrBtn)
        footerRow.addView(openAppBtn)

        expandedCardContainer.addView(headerContainer)
        expandedCardContainer.addView(detailsContainer)
        expandedCardContainer.addView(footerRow)

        root.addView(minimizedPillContainer)
        root.addView(expandedCardContainer)
        return root
    }

    private fun attachDragBehavior(target: View, onTap: (() -> Unit)?) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        target.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val rootView = overlayRoot ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    lp.x = (initialX + dx).coerceAtLeast(0)
                    lp.y = (initialY + dy).coerceAtLeast(0)
                    try {
                        windowManager?.updateViewLayout(rootView, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    moved || onTap != null
                }
                else -> false
            }
        }
    }

    private fun applyExpandedState(expanded: Boolean, updateWindowLayout: Boolean) {
        isExpanded = expanded
        if (!::minimizedPillContainer.isInitialized || !::expandedCardContainer.isInitialized) return

        minimizedPillContainer.visibility = if (expanded) View.GONE else View.VISIBLE
        expandedCardContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        detailsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        footerRow.visibility = if (expanded) View.VISIBLE else View.GONE
        expandToggleView.text = if (expanded) "–" else "+"

        val rootView = overlayRoot
        val rootBg = rootView?.background as? GradientDrawable
        if (expanded) {
            rootView?.setPadding(dp(12), dp(10), dp(12), dp(10))
            rootBg?.cornerRadius = dp(18).toFloat()
        } else {
            rootView?.setPadding(dp(12), dp(6), dp(12), dp(6))
            rootBg?.cornerRadius = dp(24).toFloat()
        }

        if (updateWindowLayout) {
            val lp = layoutParams
            if (lp != null && rootView != null) {
                val targetWidth = if (expanded) dp(318) else WindowManager.LayoutParams.WRAP_CONTENT
                if (lp.width != targetWidth) {
                    lp.width = targetWidth
                    try {
                        windowManager?.updateViewLayout(rootView, lp)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun renderState(runtime: ServiceRuntimeState) {
        if (overlayRoot == null || isStopping) return

        if (runtime.minimizeNonce != lastHandledMinimizeNonce) {
            lastHandledMinimizeNonce = runtime.minimizeNonce
            if (!runtime.isForegroundTargetApp) {
                applyExpandedState(expanded = false, updateWindowLayout = true)
            }
        }

        if (runtime.expandNonce != lastHandledExpandNonce) {
            lastHandledExpandNonce = runtime.expandNonce
            val allowExpand = runtime.isForegroundTargetApp ||
                ServiceRuntimeBus.isTargetDeliveryPackage(
                    runtime.foregroundPackage.ifBlank { runtime.lastDetectedPackage }
                )
            if (allowExpand) {
                applyExpandedState(expanded = true, updateWindowLayout = true)
            }
        }

        if (runtime.isAnalyzing) {
            (overlayRoot?.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#38BDF8"))
            decisionDotView.background = circleDrawable(Color.parseColor("#38BDF8"))
            minimizedDotView.background = circleDrawable(Color.parseColor("#38BDF8"))
            minimizedLabelView.text = "ROTA IA"
            decisionTitleView.text = "CALCULANDO ROTA..."
            decisionTitleView.setTextColor(Color.parseColor("#38BDF8"))
            rateMetricView.text = "Lendo oferta e bairros de Montes Claros"
            offerSummaryView.text = runtime.lastStatusMessage
            routeBreakdownView.text = "Etapas: Atual → Loja → Cliente"
            alertBannerView.visibility = View.GONE
            return
        }

        if (!runtime.lastAnalysisError.isNullOrBlank()) {
            (overlayRoot?.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#F59E0B"))
            decisionDotView.background = circleDrawable(Color.parseColor("#F59E0B"))
            minimizedDotView.background = circleDrawable(Color.parseColor("#F59E0B"))
            minimizedLabelView.text = "ROTA IA"
            decisionTitleView.text = "VERIFICAR DADOS DA OFERTA"
            decisionTitleView.setTextColor(Color.parseColor("#F59E0B"))
            rateMetricView.text = "Sem valor ou endereço completo na tela"
            offerSummaryView.text = runtime.lastAnalysisError
            routeBreakdownView.text = "Toque em OCR Tela ou abra o Painel"
            alertBannerView.visibility = View.GONE
            return
        }

        val offer = runtime.latestOffer
        if (offer == null) {
            (overlayRoot?.background as? GradientDrawable)?.setStroke(dp(1), Color.parseColor("#334155"))
            decisionDotView.background = circleDrawable(Color.parseColor("#38BDF8"))
            minimizedDotView.background = circleDrawable(Color.parseColor("#38BDF8"))
            minimizedLabelView.text = "ROTA IA"
            decisionTitleView.text = "MONITORANDO ENTREGAS"
            decisionTitleView.setTextColor(Color.WHITE)
            rateMetricView.text = String.format(Locale("pt", "BR"), "Meta: R$ %.2f/km", currentSettings.minTargetPerKm)
            offerSummaryView.text = runtime.lastStatusMessage
            routeBreakdownView.text = "Rota: Atual → Loja → Cliente"
            alertBannerView.visibility = View.GONE
            return
        }

        val (dotColor, emojiLabel, borderHex) = when (offer.decision) {
            TrafficLightDecision.GREEN.name -> Triple(Color.parseColor("#10B981"), "🟢 COMPENSA", "#10B981")
            TrafficLightDecision.YELLOW.name -> Triple(Color.parseColor("#F59E0B"), "🟡 ATENÇÃO", "#F59E0B")
            else -> Triple(Color.parseColor("#EF4444"), "🔴 NÃO COMPENSA", "#EF4444")
        }

        (overlayRoot?.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor(borderHex))
        decisionDotView.background = circleDrawable(dotColor)
        minimizedDotView.background = circleDrawable(dotColor)
        minimizedLabelView.text = "ROTA IA"

        val ptBr = Locale("pt", "BR")
        decisionTitleView.text = String.format(
            ptBr,
            "R$ %.2f • %.2f km • R$ %.2f/km",
            offer.offerPriceBrl,
            offer.totalKm,
            offer.earningsPerKm
        )
        decisionTitleView.setTextColor(dotColor)
        rateMetricView.text = String.format(ptBr, "%s • Meta R$ %.2f/km", emojiLabel, currentSettings.minTargetPerKm)

        val storeText = offer.storeName.ifBlank { "Não informada" }
        val pickupNb = if (MontesClarosGeoCatalog.isUnidentified(offer.pickupNeighborhood)) {
            "Montes Claros"
        } else {
            offer.pickupNeighborhood
        }
        val pickupAddrClean = offer.pickupAddress.ifBlank { "Endereço não informado" }

        val customerNb = if (MontesClarosGeoCatalog.isUnidentified(offer.customerNeighborhood)) {
            "Montes Claros"
        } else {
            offer.customerNeighborhood
        }
        val customerAddrClean = offer.customerAddress.ifBlank { "Endereço não informado" }

        offerSummaryView.text = buildString {
            appendLine("Loja: $storeText")
            appendLine("Retirada ($pickupNb): $pickupAddrClean")
            append("Entrega ($customerNb): $customerAddrClean")
        }

        routeBreakdownView.text = String.format(
            ptBr,
            "Loja %.2f km • Corrida %.2f km • Volta %.2f km",
            offer.leg1CurrentToPickupKm,
            offer.leg2PickupToCustomerKm,
            offer.leg3CustomerToCenterKm
        )

        if (!offer.neighborhoodAlert.isNullOrBlank()) {
            alertBannerView.visibility = View.VISIBLE
            alertBannerView.text = "⚠ ${offer.neighborhoodAlert}"
        } else {
            alertBannerView.visibility = View.GONE
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_notification_channel_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Desligar", stopIntent)
            .build()
    }

    private fun createGoalPresetButton(valueStr: String): TextView {
        return TextView(this).apply {
            text = valueStr
            setTextColor(Color.WHITE)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(3)
            }
            setOnClickListener {
                val targetVal = valueStr.toDoubleOrNull() ?: 1.90
                serviceScope.launch(Dispatchers.IO) {
                    prefsRepo.setMinTargetPerKm(targetVal)
                    reanalyzeLatestOffer()
                }
            }
        }
    }

    private fun reanalyzeLatestOffer() {
        val latest = ServiceRuntimeBus.state.value.latestOffer ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val app = application as RotaInteligenteApp
                app.container.analysisCoordinator.analyzeStructuredOffer(
                    priceBrl = latest.offerPriceBrl,
                    pickupAddress = latest.pickupAddress,
                    customerAddress = latest.customerAddress,
                    source = OfferCaptureSource.valueOf(latest.source),
                    rawSnippet = latest.rawSnippet,
                    storeName = latest.storeName,
                    pickupNeighborhoodHint = latest.pickupNeighborhood,
                    customerNeighborhoodHint = latest.customerNeighborhood
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun showGoalEditDialog() {
        try {
            val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            builder.setTitle("Meta Mínima (R$/km)")
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(Locale("pt", "BR"), "%.2f", currentSettings.minTargetPerKm).replace('.', ','))
                selectAll()
            }
            builder.setView(input)
            builder.setPositiveButton("Salvar") { _, _ ->
                val rawText = input.text.toString().replace("R$", "").trim().replace(',', '.')
                val parsed = rawText.toDoubleOrNull()
                if (parsed != null && parsed in 0.5..10.0) {
                    serviceScope.launch(Dispatchers.IO) {
                        prefsRepo.setMinTargetPerKm(parsed)
                        reanalyzeLatestOffer()
                    }
                }
            }
            builder.setNegativeButton("Cancelar", null)
            val dialog = builder.create()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            dialog.show()
        } catch (_: Exception) {
        }
    }
}
