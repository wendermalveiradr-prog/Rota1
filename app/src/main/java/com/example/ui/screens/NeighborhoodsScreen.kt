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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.example.data.local.NeighborhoodRuleEntity
import com.example.data.local.NeighborhoodStatus
import com.example.ui.theme.LocalRotaColors

@Composable
fun NeighborhoodsScreen(
    rules: List<NeighborhoodRuleEntity>,
    onUpdateStatus: (name: String, status: NeighborhoodStatus, notes: String) -> Unit,
    onAddNeighborhood: (name: String, status: NeighborhoodStatus, notes: String) -> Unit,
    onDeleteCustom: (name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf<NeighborhoodStatus?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val filtered = remember(rules, searchQuery, filterStatus) {
        rules.filter { rule ->
            val matchesQuery = searchQuery.isBlank() ||
                rule.name.contains(searchQuery, ignoreCase = true) ||
                rule.notes.contains(searchQuery, ignoreCase = true)
            val matchesStatus = filterStatus == null || rule.status == filterStatus?.name
            matchesQuery && matchesStatus
        }
    }

    if (showAddDialog) {
        AddNeighborhoodDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, status, notes ->
                onAddNeighborhood(name, status, notes)
                showAddDialog = false
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("add_neighborhood_fab")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Adicionar bairro"
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "Bairros de Montes Claros",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Defina bairros preferidos e áreas perigosas ou evitadas. Bairros evitados marcam a oferta como VERMELHO automaticamente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Buscar bairro...") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_neighborhood_input")
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filterStatus == null,
                            onClick = { filterStatus = null },
                            label = { Text("Todos") }
                        )
                        FilterChip(
                            selected = filterStatus == NeighborhoodStatus.AVOIDED,
                            onClick = { filterStatus = NeighborhoodStatus.AVOIDED },
                            label = { Text("Evitados") }
                        )
                        FilterChip(
                            selected = filterStatus == NeighborhoodStatus.PREFERRED,
                            onClick = { filterStatus = NeighborhoodStatus.PREFERRED },
                            label = { Text("Preferidos") }
                        )
                        FilterChip(
                            selected = filterStatus == NeighborhoodStatus.NEUTRAL,
                            onClick = { filterStatus = NeighborhoodStatus.NEUTRAL },
                            label = { Text("Neutros") }
                        )
                    }
                }

                items(filtered, key = { it.name }) { rule ->
                    NeighborhoodRuleCard(
                        rule = rule,
                        onStatusSelected = { newStatus ->
                            onUpdateStatus(rule.name, newStatus, rule.notes)
                        },
                        onDelete = { onDeleteCustom(rule.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NeighborhoodRuleCard(
    rule: NeighborhoodRuleEntity,
    onStatusSelected: (NeighborhoodStatus) -> Unit,
    onDelete: () -> Unit
) {
    val rotaColors = LocalRotaColors.current
    val currentStatus = NeighborhoodStatus.entries.firstOrNull { it.name == rule.status }
        ?: NeighborhoodStatus.NEUTRAL

    val borderColor = when (currentStatus) {
        NeighborhoodStatus.PREFERRED -> rotaColors.green.copy(alpha = 0.5f)
        NeighborhoodStatus.AVOIDED -> rotaColors.red.copy(alpha = 0.6f)
        NeighborhoodStatus.NEUTRAL -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (rule.notes.isNotBlank()) {
                        Text(
                            text = rule.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (rule.isCustom) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Remover bairro customizado"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentStatus == NeighborhoodStatus.PREFERRED,
                    onClick = { onStatusSelected(NeighborhoodStatus.PREFERRED) },
                    label = { Text("Preferido") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentStatus == NeighborhoodStatus.NEUTRAL,
                    onClick = { onStatusSelected(NeighborhoodStatus.NEUTRAL) },
                    label = { Text("Neutro") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentStatus == NeighborhoodStatus.AVOIDED,
                    onClick = { onStatusSelected(NeighborhoodStatus.AVOIDED) },
                    label = { Text("Evitado") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AddNeighborhoodDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, status: NeighborhoodStatus, notes: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(NeighborhoodStatus.AVOIDED) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar Bairro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do bairro") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observação (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = status == NeighborhoodStatus.PREFERRED,
                        onClick = { status = NeighborhoodStatus.PREFERRED },
                        label = { Text("Preferido") }
                    )
                    FilterChip(
                        selected = status == NeighborhoodStatus.AVOIDED,
                        onClick = { status = NeighborhoodStatus.AVOIDED },
                        label = { Text("Evitado") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, status, notes) }
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
