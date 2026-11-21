package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.DashboardUiState
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.TrafficLightDecisionCard
import com.example.ui.theme.LocalRotaColors
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRefreshPermissions: () -> Unit,
    onToggleOverlay: () -> Unit,
    onAnalyzeManual: (price: String, pickup: String, customer: String, store: String) -> Unit,
    onAnalyzeOcrImage: (Uri) -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rotaColors = LocalRotaColors.current
    val ptBr = Locale("pt", "BR")

    var priceInput by rememberSaveable { mutableStateOf("18,50") }
    var storeInput by rememberSaveable { mutableStateOf("") }
    var pickupInput by rememberSaveable {
        mutableStateOf("Av. Deputado Esteves Rodrigues, 450 - Centro, Montes Claros")
    }
    var customerInput by rememberSaveable {
        mutableStateOf("Av. José Corrêa Machado, 920 - Ibituruna, Montes Claros")
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshPermissions()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefreshPermissions()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAnalyzeOcrImage(uri)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero HUD Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_route_banner),
                            contentDescription = "Mapa estratégico de entregas em Montes Claros",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(188.dp),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xCC070B14),
                                            Color(0xEE090E1A)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ROTA INTELIGENTE IA",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = rotaColors.routeCyan
                                    )
                                    Text(
                                        text = "Mob Lets • Montes Claros",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White
                                    )
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = rotaColors.greenContainer
                                ) {
                                    Text(
                                        text = String.format(ptBr, "Meta R$ %.2f/km", state.settings.minTargetPerKm),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = rotaColors.onGreenContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusPillBadge(
                                    label = if (state.permissions.accessibilityEnabled) "Acessibilidade ON" else "Acessibilidade OFF",
                                    active = state.permissions.accessibilityEnabled
                                )
                                StatusPillBadge(
                                    label = if (state.runtime.isOverlayServiceRunning) "Flutuante Ativa" else "Flutuante Parada",
                                    active = state.runtime.isOverlayServiceRunning
                                )
                                StatusPillBadge(
                                    label = if (state.permissions.locationGranted) "GPS Pronto" else "GPS Padrão MOC",
                                    active = state.permissions.locationGranted
                                )
                                StatusPillBadge(
                                    label = if (state.settings.ocrFallbackEnabled) "OCR ML Kit ON" else "OCR OFF",
                                    active = state.settings.ocrFallbackEnabled
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = state.runtime.lastStatusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }

            // Transient feedback banner
            item {
                AnimatedVisibility(visible = !state.feedbackBanner.isNullOrBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.feedbackBanner.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissFeedback,
                                modifier = Modifier.testTag("dismiss_feedback_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Fechar aviso",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Native Services & Permissions Control Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Serviços Nativos e Permissões",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Somente leitura — nunca aceita nem recusa corridas automaticamente.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = "Transparência de permissões",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        PermissionActionRow(
                            icon = Icons.Rounded.AccessibilityNew,
                            title = "Acessibilidade (Leitura Mob Lets + OCR)",
                            subtitle = "Lê preço, retirada e destino exibidos na tela",
                            granted = state.permissions.accessibilityEnabled,
                            actionLabel = if (state.permissions.accessibilityEnabled) "Configurar" else "Ativar",
                            testTag = "accessibility_permission_button",
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        PermissionActionRow(
                            icon = Icons.Rounded.Layers,
                            title = "Janela Flutuante (Overlay)",
                            subtitle = "Exibe semáforo e R$/km sobre o app Mob Lets",
                            granted = state.permissions.overlayGranted,
                            actionLabel = if (state.permissions.overlayGranted) "Concedida" else "Permitir",
                            testTag = "overlay_permission_button",
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        PermissionActionRow(
                            icon = Icons.Rounded.LocationOn,
                            title = "Localização GPS em Tempo Real",
                            subtitle = "Calcula Etapa 1 (sua posição até a loja)",
                            granted = state.permissions.locationGranted,
                            actionLabel = if (state.permissions.locationGranted) "Ativa" else "Permitir",
                            testTag = "location_permission_button",
                            onClick = {
                                locationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Spacer(modifier = Modifier.height(8.dp))
                            PermissionActionRow(
                                icon = Icons.Rounded.NotificationsActive,
                                title = "Notificações do Serviço (Android 15)",
                                subtitle = "Mantém a janela flutuante ativa em segundo plano",
                                granted = state.permissions.notificationsGranted,
                                actionLabel = if (state.permissions.notificationsGranted) "Ativa" else "Permitir",
                                testTag = "notification_permission_button",
                                onClick = {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = onToggleOverlay,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("toggle_overlay_service_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.runtime.isOverlayServiceRunning) {
                                    rotaColors.redContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                contentColor = if (state.runtime.isOverlayServiceRunning) {
                                    rotaColors.onRedContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                }
                            )
                        ) {
                            Icon(
                                imageVector = if (state.runtime.isOverlayServiceRunning) {
                                    Icons.Rounded.Stop
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.runtime.isOverlayServiceRunning) {
                                    "Parar Janela Flutuante"
                                } else {
                                    "Iniciar Janela Flutuante sobre Mob Lets"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Latest Analyzed Offer Hero Card
            item {
                when {
                    state.runtime.isAnalyzing -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            border = BorderStroke(1.dp, rotaColors.routeCyan.copy(alpha = 0.6f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(34.dp))
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Calculando rota real em Montes Claros...",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.runtime.lastStatusMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    !state.runtime.lastAnalysisError.isNullOrBlank() -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("unresolved_address_card"),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = rotaColors.yellowContainer.copy(alpha = 0.45f)
                            ),
                            border = BorderStroke(1.5.dp, rotaColors.yellow)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocationOn,
                                        contentDescription = "Endereço não localizado",
                                        tint = rotaColors.yellow,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Endereço não localizado — cálculo bloqueado",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = rotaColors.onYellowContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.runtime.lastAnalysisError.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Nenhuma coordenada artificial foi usada. Informe rua e bairro válidos em Montes Claros.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    state.latestOffer != null -> {
                        TrafficLightDecisionCard(offer = state.latestOffer)
                    }

                    else -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Route,
                                    contentDescription = "Aguardando primeira análise",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(38.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Nenhuma oferta analisada ainda",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Ative a Acessibilidade para detectar corridas do Mob Lets automaticamente ou use o Analisador Manual / OCR abaixo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Manual Offer & OCR Lab Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Análise Manual & Fallback OCR (ML Kit)",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Teste rotas completas em Montes Claros ou selecione um print de oferta para extração via OCR.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Quick Montes Claros scenario presets
                        Text(
                            text = "Cenários rápidos em Montes Claros:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                onClick = {
                                    priceInput = "18,50"
                                    pickupInput = "Av. Deputado Esteves Rodrigues, 450 - Centro"
                                    customerInput = "Av. José Corrêa Machado, 920 - Ibituruna"
                                },
                                label = { Text("Lucrativa (Centro → Ibituruna)") },
                                modifier = Modifier.testTag("preset_green_button")
                            )
                            AssistChip(
                                onClick = {
                                    priceInput = "17,00"
                                    pickupInput = "Rua Tupinambás, 210 - Melo"
                                    customerInput = "Rua Santa Lúcia, 340 - Maracanã"
                                },
                                label = { Text("Atenção (Melo → Maracanã)") },
                                modifier = Modifier.testTag("preset_yellow_button")
                            )
                            AssistChip(
                                onClick = {
                                    priceInput = "22,00"
                                    pickupInput = "Praça Dr. Carlos Versiani, 80 - Centro"
                                    customerInput = "Rua Principal, 150 - Village do Lago"
                                },
                                label = { Text("Bairro Evitado (Village do Lago)") },
                                modifier = Modifier.testTag("preset_red_button")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = priceInput,
                            onValueChange = { priceInput = it },
                            label = { Text("Valor da corrida (R$)") },
                            prefix = { Text("R$ ") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_price_input")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = storeInput,
                            onValueChange = { storeInput = it },
                            label = { Text("Nome da loja (opcional)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_store_input")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = pickupInput,
                            onValueChange = { pickupInput = it },
                            label = { Text("Endereço de Retirada (Loja)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_pickup_input")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = customerInput,
                            onValueChange = { customerInput = it },
                            label = { Text("Endereço de Entrega (Cliente)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_customer_input")
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    onAnalyzeManual(priceInput, pickupInput, customerInput, storeInput)
                                },
                                enabled = !state.isManualProcessing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("analyze_manual_button")
                            ) {
                                if (state.isManualProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Analytics,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Analisar Rota")
                            }

                            OutlinedButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !state.isManualProcessing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("ocr_photo_picker_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DocumentScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ler Print OCR")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    val rotaColors = LocalRotaColors.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Rounded.CheckCircle else icon,
                contentDescription = title,
                tint = if (granted) rotaColors.green else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier
                    .height(48.dp)
                    .testTag(testTag),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
