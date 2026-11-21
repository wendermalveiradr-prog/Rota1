package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.TrafficLightDecision
import com.example.domain.FieldConfidence
import com.example.domain.MontesClarosGeoCatalog
import com.example.domain.RouteCalculator
import com.example.ui.theme.LocalRotaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusPillBadge(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val rotaColors = LocalRotaColors.current
    val dotColor by animateColorAsState(
        targetValue = if (active) rotaColors.green else MaterialTheme.colorScheme.outline,
        label = "statusDot"
    )
    val bgColor = if (active) {
        rotaColors.greenContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = bgColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (active) rotaColors.green.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) rotaColors.onGreenContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TrafficLightDecisionCard(
    offer: AnalyzedOfferEntity,
    modifier: Modifier = Modifier
) {
    val rotaColors = LocalRotaColors.current
    val ptBr = Locale("pt", "BR")

    val (accentColor, containerColor, onContainerColor, badgeLabel, icon) = when (offer.decision) {
        TrafficLightDecision.GREEN.name -> DecisionVisuals(
            accent = rotaColors.green,
            container = rotaColors.greenContainer,
            onContainer = rotaColors.onGreenContainer,
            label = "VERDE • COMPENSA (WORTH IT)",
            icon = Icons.Rounded.CheckCircle
        )
        TrafficLightDecision.YELLOW.name -> DecisionVisuals(
            accent = rotaColors.yellow,
            container = rotaColors.yellowContainer,
            onContainer = rotaColors.onYellowContainer,
            label = "AMARELO • ATENÇÃO (CAUTION)",
            icon = Icons.Rounded.Warning
        )
        else -> DecisionVisuals(
            accent = rotaColors.red,
            container = rotaColors.redContainer,
            onContainer = rotaColors.onRedContainer,
            label = "VERMELHO • NÃO COMPENSA",
            icon = Icons.Rounded.Dangerous
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = containerColor
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = badgeLabel,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = onContainerColor
                        )
                    }
                }

                // Traffic light 3-dot visual indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrafficDot(
                        color = rotaColors.red,
                        active = offer.decision == TrafficLightDecision.RED.name
                    )
                    TrafficDot(
                        color = rotaColors.yellow,
                        active = offer.decision == TrafficLightDecision.YELLOW.name
                    )
                    TrafficDot(
                        color = rotaColors.green,
                        active = offer.decision == TrafficLightDecision.GREEN.name
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Price, Total KM, and R$/km KPI strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "VALOR DA OFERTA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(ptBr, "R$ %.2f", offer.offerPriceBrl),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "GANHO REAL / KM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(ptBr, "R$ %.2f/km", offer.earningsPerKm),
                        style = MaterialTheme.typography.headlineMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(
                            ptBr,
                            "Total %.1f km • Meta R$ %.2f/km",
                            offer.totalKm,
                            offer.targetPerKm
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Explanation / Reason
            Text(
                text = offer.decisionReason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!offer.neighborhoodAlert.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = rotaColors.redContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = "Alerta de bairro",
                            tint = rotaColors.red,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = offer.neighborhoodAlert,
                            style = MaterialTheme.typography.bodyMedium,
                            color = rotaColors.onRedContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            val hasUnidentifiedNeighborhood =
                MontesClarosGeoCatalog.isUnidentified(offer.pickupNeighborhood) ||
                    MontesClarosGeoCatalog.isUnidentified(offer.customerNeighborhood)
            val hasUnidentifiedStore = offer.storeName.isBlank()
            if (hasUnidentifiedNeighborhood || hasUnidentifiedStore) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = rotaColors.yellowContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = "Identificação pendente",
                            tint = rotaColors.yellow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val warningMessage = when {
                            hasUnidentifiedNeighborhood && hasUnidentifiedStore ->
                                "Nome da loja e bairro: Não identificado — confira antes de aceitar."
                            hasUnidentifiedNeighborhood ->
                                "Bairro: Não identificado com segurança — confira o endereço antes de aceitar."
                            else ->
                                "Nome da loja: Não identificado na tela — confira antes de aceitar."
                        }
                        Text(
                            text = warningMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = rotaColors.onYellowContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            RouteFourLegDiagram(offer = offer)

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata footer
            val timeFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
            val sourceLabel = when (offer.source) {
                "ACCESSIBILITY" -> "Acessibilidade"
                "OCR_SCREENSHOT" -> "OCR Automático"
                "OCR_IMAGE" -> "OCR Imagem"
                else -> "Análise Manual"
            }
            val routeLabel = when (offer.routeSource) {
                RouteCalculator.ROUTE_SOURCE_OSRM -> "Rota Real (OSRM)"
                RouteCalculator.ROUTE_SOURCE_INSTANT_OFFLINE -> "Instantânea Offline (Refinando OSRM)"
                else -> "Distância Estimada (Offline)"
            }
            val gpsLabel = if (offer.usedGpsStart) "GPS em tempo real" else "Início: Centro MOC"

            Text(
                text = "$sourceLabel • $routeLabel • $gpsLabel • ${timeFormat.format(Date(offer.timestamp))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrafficDot(color: Color, active: Boolean) {
    Box(
        modifier = Modifier
            .size(if (active) 12.dp else 8.dp)
            .clip(CircleShape)
            .background(if (active) color else color.copy(alpha = 0.22f))
    )
}

@Composable
fun RouteFourLegDiagram(
    offer: AnalyzedOfferEntity,
    modifier: Modifier = Modifier
) {
    val rotaColors = LocalRotaColors.current
    val ptBr = Locale("pt", "BR")
    val lineColor = rotaColors.routeCyan
    val surfaceLineTrack = MaterialTheme.colorScheme.outlineVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "ROTA COMPLETA (4 PONTOS)",
                style = MaterialTheme.typography.labelSmall,
                color = lineColor
            )
            Spacer(modifier = Modifier.height(10.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(horizontal = 12.dp)
            ) {
                val y = size.height / 2f
                val step = size.width / 3f
                val points = listOf(0f, step, step * 2f, size.width)

                drawLine(
                    color = surfaceLineTrack,
                    start = Offset(points.first(), y),
                    end = Offset(points.last(), y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )

                drawLine(
                    color = lineColor,
                    start = Offset(points[0], y),
                    end = Offset(points[2], y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )

                drawLine(
                    color = rotaColors.yellow,
                    start = Offset(points[2], y),
                    end = Offset(points[3], y),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                )

                points.forEachIndexed { index, x ->
                    val nodeColor = when (index) {
                        0 -> lineColor
                        1 -> rotaColors.green
                        2 -> rotaColors.yellow
                        else -> lineColor
                    }
                    drawCircle(
                        color = nodeColor,
                        radius = 6.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LegLabel(
                    title = "1. Atual → Loja",
                    km = String.format(ptBr, "%.1f km", offer.leg1CurrentToPickupKm),
                    subtitle = offer.pickupNeighborhood,
                    icon = Icons.Rounded.DirectionsBike,
                    modifier = Modifier.weight(1f)
                )
                LegLabel(
                    title = "2. Loja → Cliente",
                    km = String.format(ptBr, "%.1f km", offer.leg2PickupToCustomerKm),
                    subtitle = offer.customerNeighborhood,
                    icon = Icons.Rounded.Storefront,
                    modifier = Modifier.weight(1f)
                )
                LegLabel(
                    title = "3. Retorno Centro",
                    km = String.format(ptBr, "%.1f km", offer.leg3CustomerToCenterKm),
                    subtitle = "Praça Dr. Carlos",
                    icon = Icons.Rounded.LocationOn,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val pickupNeighborhoodDisplay = if (MontesClarosGeoCatalog.isUnidentified(offer.pickupNeighborhood)) {
                MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD
            } else {
                offer.pickupNeighborhood
            }
            val customerNeighborhoodDisplay = if (MontesClarosGeoCatalog.isUnidentified(offer.customerNeighborhood)) {
                MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD
            } else {
                offer.customerNeighborhood
            }

            SeparatedFieldRow(
                label = "Nome da loja",
                value = offer.storeName.ifBlank { MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD },
                confidence = FieldConfidence.fromStored(offer.storeConfidence)
            )
            SeparatedFieldRow(
                label = "Endereço de retirada",
                value = offer.pickupAddress.ifBlank { MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD },
                confidence = FieldConfidence.fromStored(offer.pickupAddressConfidence)
            )
            SeparatedFieldRow(
                label = "Bairro de retirada",
                value = pickupNeighborhoodDisplay,
                confidence = FieldConfidence.fromStored(offer.pickupNeighborhoodConfidence)
            )
            SeparatedFieldRow(
                label = "Endereço de entrega",
                value = offer.customerAddress.ifBlank { MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD },
                confidence = FieldConfidence.fromStored(offer.customerAddressConfidence)
            )
            SeparatedFieldRow(
                label = "Bairro de entrega",
                value = customerNeighborhoodDisplay,
                confidence = FieldConfidence.fromStored(offer.customerNeighborhoodConfidence)
            )
        }
    }
}

@Composable
private fun SeparatedFieldRow(
    label: String,
    value: String,
    confidence: FieldConfidence
) {
    val rotaColors = LocalRotaColors.current
    val isMissing = value.equals(MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD, ignoreCase = true)
    val badgeBg = when (confidence) {
        FieldConfidence.HIGH -> rotaColors.greenContainer.copy(alpha = 0.7f)
        FieldConfidence.MEDIUM -> rotaColors.yellowContainer.copy(alpha = 0.7f)
        FieldConfidence.LOW, FieldConfidence.UNIDENTIFIED -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val badgeTextColor = when (confidence) {
        FieldConfidence.HIGH -> rotaColors.onGreenContainer
        FieldConfidence.MEDIUM -> rotaColors.onYellowContainer
        FieldConfidence.LOW, FieldConfidence.UNIDENTIFIED -> rotaColors.yellow
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = label.uppercase(Locale("pt", "BR")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isMissing) rotaColors.yellow else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isMissing) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = badgeBg
        ) {
            Text(
                text = confidence.labelPt,
                style = MaterialTheme.typography.labelSmall,
                color = badgeTextColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun LegLabel(
    title: String,
    km: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = km,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        val isUnidentified = com.example.domain.MontesClarosGeoCatalog.isUnidentified(subtitle)
        val rotaColors = LocalRotaColors.current
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (isUnidentified) rotaColors.yellow else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isUnidentified) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class DecisionVisuals(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
