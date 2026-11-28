package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.preferences.DriverSettings
import com.example.ui.theme.LocalRotaColors
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: DriverSettings,
    onUpdateTargetPerKm: (Double) -> Unit,
    onToggleOcrFallback: (Boolean) -> Unit,
    onToggleOsrmRouting: (Boolean) -> Unit,
    onToggleAutoShowOverlay: (Boolean) -> Unit,
    onToggleStrictMobLets: (Boolean) -> Unit,
    onToggleFilter99: (Boolean) -> Unit = {},
    onToggleFilterUber: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rotaColors = LocalRotaColors.current
    val ptBr = Locale("pt", "BR")

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Ajustes e Transparência",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Calibre sua meta mínima por km e os módulos de leitura e roteamento.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Target R$/km calibration
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
                            Text(
                                text = "Meta Mínima por Quilômetro",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = String.format(ptBr, "R$ %.2f / km", settings.minTargetPerKm),
                                style = MaterialTheme.typography.titleLarge,
                                color = rotaColors.green
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Slider(
                            value = settings.minTargetPerKm.toFloat(),
                            onValueChange = { raw ->
                                val rounded = Math.round(raw * 20.0) / 20.0
                                onUpdateTargetPerKm(rounded)
                            },
                            valueRange = 1.20f..3.50f,
                            modifier = Modifier.testTag("target_per_km_slider")
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(1.70, 1.90, 2.20, 2.50).forEach { preset ->
                                val label = if (preset == 1.90) "R$ 1,90 (Padrão)" else String.format(ptBr, "R$ %.2f", preset)
                                AssistChip(
                                    onClick = { onUpdateTargetPerKm(preset) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // Engine toggles
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
                            text = "Motor de Detecção e Rota",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingToggleRow(
                            title = "Fallback OCR (Google ML Kit)",
                            subtitle = "Usa captura de tela via Acessibilidade (Android 11+) quando o texto da tela não estiver acessível.",
                            checked = settings.ocrFallbackEnabled,
                            onCheckedChange = onToggleOcrFallback,
                            testTag = "toggle_ocr_fallback"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        SettingToggleRow(
                            title = "Rotas Reais via API OSRM",
                            subtitle = "Calcula distância viária real de carro/moto em Montes Claros com fallback offline automático.",
                            checked = settings.useOsrmRoadRouting,
                            onCheckedChange = onToggleOsrmRouting,
                            testTag = "toggle_osrm_routing"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        SettingToggleRow(
                            title = "Abrir Janela Flutuante ao Detectar Oferta",
                            subtitle = "Atualiza ou exibe o painel sobre o Mob Lets quando uma nova oferta é analisada.",
                            checked = settings.autoShowOverlayOnOffer,
                            onCheckedChange = onToggleAutoShowOverlay,
                            testTag = "toggle_auto_overlay"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        SettingToggleRow(
                            title = "Filtrar App Mob Lets",
                            subtitle = "Analisa ofertas de entrega na tela do aplicativo Mob Lets.",
                            checked = settings.strictMobLetsPackageFilter,
                            onCheckedChange = onToggleStrictMobLets,
                            testTag = "toggle_strict_package"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        SettingToggleRow(
                            title = "Filtrar App 99 (99Moto / Entregas)",
                            subtitle = "Analisa ofertas de corrida/entrega na tela do aplicativo 99.",
                            checked = settings.filter99Enabled,
                            onCheckedChange = onToggleFilter99,
                            testTag = "toggle_filter_99"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        SettingToggleRow(
                            title = "Filtrar App Uber (Uber Flash / Driver)",
                            subtitle = "Analisa ofertas de entrega/viagem na tela do aplicativo Uber.",
                            checked = settings.filterUberEnabled,
                            onCheckedChange = onToggleFilterUber,
                            testTag = "toggle_filter_uber"
                        )
                    }
                }
            }

            // Explicit Permissions & External APIs Disclosure
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Transparência: Permissões e APIs Externas",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                        DisclosureItem(
                            title = "1. AccessibilityService (Android 15)",
                            body = "Permissão especial do sistema para ler preço, loja e endereço do cliente na tela do Mob Lets e acionar takeScreenshot() para OCR. Nunca executa cliques ou aceita/recusa corridas."
                        )
                        DisclosureItem(
                            title = "2. SYSTEM_ALERT_WINDOW + ForegroundService (specialUse)",
                            body = "Permite desenhar o semáforo flutuante (VERDE/AMARELO/VERMELHO) sobre o Mob Lets enquanto o motorista dirige."
                        )
                        DisclosureItem(
                            title = "3. ACCESS_FINE_LOCATION (GPS)",
                            body = "Opcional: obtém a posição atual do motorista para a Etapa 1. Se negada, usa o Centro de Montes Claros como ponto de partida."
                        )
                        DisclosureItem(
                            title = "4. APIs Externas e On-Device",
                            body = "• OSRM Public Driving API (router.project-osrm.org) + Android Geocoder: calcula quilometragem real das 3 etapas (sem necessidade de chave paga; possui fallback urbano offline).\n• Google ML Kit Text Recognition: OCR executado 100% no dispositivo."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun DisclosureItem(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
