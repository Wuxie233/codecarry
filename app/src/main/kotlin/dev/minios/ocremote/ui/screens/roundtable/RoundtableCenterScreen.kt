package dev.minios.ocremote.ui.screens.roundtable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.domain.model.Roundtable
import dev.minios.ocremote.ui.screens.chat.PiSenderIdentity
import dev.minios.ocremote.ui.screens.chat.piSenderAccentColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundtableCenterScreen(
    onNavigateBack: () -> Unit,
    viewModel: RoundtableCenterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Roundtable Center", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = uiState.serverName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh roundtables")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::createRoundtable) {
                Icon(Icons.Default.Add, contentDescription = "New roundtable")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            RoundtableCenterControls(
                filter = uiState.filter,
                sort = uiState.sort,
                runningCount = uiState.runningCount,
                onFilter = viewModel::setFilter,
                onSort = viewModel::setSort,
            )

            if (uiState.error != null) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    uiState.items.isEmpty() -> RoundtableEmptyState(
                        modifier = Modifier.align(Alignment.Center),
                        onCreate = viewModel::createRoundtable,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.items, key = { item -> item.id }) { roundtable ->
                            RoundtableCard(
                                roundtable = roundtable,
                                isAmoled = isAmoled,
                                onResume = { viewModel.resumeRoundtable(roundtable.id) },
                                onArchive = { viewModel.archiveRoundtable(roundtable.id) },
                                onDelete = { viewModel.deleteRoundtable(roundtable.id) },
                                onDuplicate = { viewModel.duplicateAsTemplate(roundtable) },
                            )
                        }
                    }
                }

                if (uiState.isMutating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundtableCenterControls(
    filter: RoundtableFilter,
    sort: RoundtableSort,
    runningCount: Int,
    onFilter: (RoundtableFilter) -> Unit,
    onSort: (RoundtableSort) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$runningCount running roundtable${if (runningCount == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundtableFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilter(option) },
                    label = { Text(option.label()) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundtableSort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { onSort(option) },
                    label = { Text(option.label()) },
                )
            }
        }
    }
}

@Composable
private fun RoundtableCard(
    roundtable: Roundtable,
    isAmoled: Boolean,
    onResume: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.35f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = roundtable.topic?.takeIf { it.isNotBlank() } ?: "Untitled roundtable topic",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundtableRosterDots(roundtable.roster)
                        StatusChip(roundtable.status)
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Roundtable actions")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Duplicate as template") },
                            onClick = { showMenu = false; onDuplicate() },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            onClick = { showMenu = false; onArchive() },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${roundtable.roundCount} rounds · ${formatRoundtableActivity(roundtable.time.updated)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onResume, enabled = roundtable.status != Roundtable.Status.Archived) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Resume")
                }
            }
        }
    }
}

@Composable
private fun RoundtableRosterDots(roster: List<Roundtable.RoleSummary>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-2).dp), verticalAlignment = Alignment.CenterVertically) {
        roster.take(6).forEach { role ->
            val color = piSenderAccentColor(
                PiSenderIdentity(
                    id = role.id,
                    name = role.name,
                    mbti = null,
                    role = role.role,
                    colorSeed = role.colorSeed,
                ),
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(color, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun StatusChip(status: Roundtable.Status) {
    val label = when (status) {
        Roundtable.Status.Running -> "running"
        Roundtable.Status.Paused, Roundtable.Status.AwaitingCommand, Roundtable.Status.AwaitingSkip -> "paused"
        Roundtable.Status.Archived -> "archived"
        Roundtable.Status.Completed -> "ended"
        Roundtable.Status.Error -> "error"
        Roundtable.Status.Unknown -> "unknown"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

@Composable
private fun RoundtableEmptyState(
    modifier: Modifier,
    onCreate: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("No roundtables yet", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Create a topic-led roundtable without adding anything to OpenCode sessions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("New roundtable")
        }
    }
}

private fun RoundtableFilter.label(): String = when (this) {
    RoundtableFilter.Active -> "Active"
    RoundtableFilter.Running -> "Running"
    RoundtableFilter.Archived -> "Archived"
    RoundtableFilter.All -> "All"
}

private fun RoundtableSort.label(): String = when (this) {
    RoundtableSort.LastActivity -> "Activity"
    RoundtableSort.Created -> "Created"
    RoundtableSort.Topic -> "Topic"
}
