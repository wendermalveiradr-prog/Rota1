package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.TrafficLightDecision
import com.example.domain.MontesClarosGeoCatalog
import com.example.domain.RouteCalculator
import com.example.ui.components.RouteFourLegDiagram
import com.example.ui.theme.LocalRotaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    offers: List<AnalyzedOfferEntity>,
    onDeleteOffer: (Long) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    val filteredOffers = remember(offers, selectedFilter) {
        if (selectedFilter == null) offers else offers.filter { it.decision == selectedFilter }
    }

    val ptBr = Locale("pt", "BR")
    val avgRate = remember(offers) {
        if (offers.isEmpty()) 0.0 else offers.map { it.earningsPerKm }.average()
    }
    val greenCount = remember(offers) {
        offers.count { it.decision == TrafficLightDecision.GREEN.name }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Limpar histórico?") },
            text = { Text("Todas as corridas analisadas salvas localmente serão removidas.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showClearDialog = false
                    }
                ) {
                    Text("Limpar tudo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Histórico de Ofertas",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "Armazenamento local offline via Room",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (offers.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.testTag("clear_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Limpar histórico"
                            )
                        }
                    }
                }
            }

            // KPI Summary Strip
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryMetricCard(
                        title = "Analisadas",
                        value = offers.size.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryMetricCard(
                        title = "Compensam",
                        value = "$greenCount",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryMetricCard(
                        title = "Média R$/km",
                        value = String.format(ptBr, "R$ %.2f", avgRate),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Filter Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        label = { Text("Todas") },
                        modifier = Modifier.testTag("filter_all_offers")
                    )
                    FilterChip(
                        selected = selectedFilter == TrafficLightDecision.GREEN.name,
                        onClick = { selectedFilter = TrafficLightDecision.GREEN.name },
                        label = { Text("Verde") },
                        modifier = Modifier.testTag("filter_green_offers")
                    )
                    FilterChip(
                        selected = selectedFilter == TrafficLightDecision.YELLOW.name,
                        onClick = { selectedFilter = TrafficLightDecision.YELLOW.name },
                        label = { Text("Amarela") },
                        modifier = Modifier.testTag("filter_yellow_offers")
                    )
                    FilterChip(
                        selected = selectedFilter == TrafficLightDecision.RED.name,
                        onClick = { selectedFilter = TrafficLightDecision.RED.name },
                        label = { Text("Vermelha") },
                        modifier = Modifier.testTag("filter_red_offers")
                    )
                }
            }

            if (filteredOffers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = "Sem histórico",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Nenhuma oferta registrada nesta categoria",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "As corridas detectadas pelo Mob Lets ou testadas manualmente ficam salvas aqui.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredOffers, key = { it.id }) { offer ->
                    HistoryOfferItem(
                        offer = offer,
                        expanded = expandedId == offer.id,
                        onToggleExpand = {
                            expandedId = if (expandedId == offer.id) null else offer.id
                        },
                        onDelete = { onDeleteOffer(offer.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HistoryOfferItem(
    offer: AnalyzedOfferEntity,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit
) {
    val rotaColors = LocalRotaColors.current
    val ptBr = Locale("pt", "BR")
    val dateFormat = SimpleDateFormat("dd/MM HH:mm", ptBr)

    val (accent, badgeLabel) = when (offer.decision) {
        TrafficLightDecision.GREEN.name -> rotaColors.green to "COMPENSA"
        TrafficLightDecision.YELLOW.name -> rotaColors.yellow to "ATENÇÃO"
        else -> rotaColors.red to "NÃO COMPENSA"
    }

    Card(
        onClick = onToggleExpand,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_offer_card_${offer.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = accent.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dateFormat.format(Date(offer.timestamp)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_offer_${offer.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remover oferta",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val storeLabel = offer.storeName.ifBlank { MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD }
                    val pickupNb = if (MontesClarosGeoCatalog.isUnidentified(offer.pickupNeighborhood)) {
                        MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD
                    } else {
                        offer.pickupNeighborhood
                    }
                    val customerNb = if (MontesClarosGeoCatalog.isUnidentified(offer.customerNeighborhood)) {
                        MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD
                    } else {
                        offer.customerNeighborhood
                    }
                    Text(
                        text = "Loja: $storeLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (offer.storeName.isNotBlank()) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            rotaColors.yellow
                        }
                    )
                    Text(
                        text = "$pickupNb → $customerNb",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val routeModeLabel = when (offer.routeSource) {
                        RouteCalculator.ROUTE_SOURCE_OSRM -> "Rota OSRM"
                        RouteCalculator.ROUTE_SOURCE_INSTANT_OFFLINE -> "Instantânea"
                        else -> "Estimada"
                    }
                    Text(
                        text = String.format(
                            ptBr,
                            "R$ %.2f • %.1f km (%s)",
                            offer.offerPriceBrl,
                            offer.totalKm,
                            routeModeLabel
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = String.format(ptBr, "R$ %.2f/km", offer.earningsPerKm),
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = offer.decisionReason,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                RouteFourLegDiagram(offer = offer)
            }
        }
    }
}
