package dev.minios.ocremote.ui.screens.roundtable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.ui.screens.chat.MermaidMarkdownDiagram
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundtableSummaryScreen(
    onNavigateBack: () -> Unit,
    viewModel: RoundtableSummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportToUri(context, uri) { success ->
            coroutineScope.launch { snackbarHostState.showSnackbar(if (success) "Roundtable summary exported" else "Export failed") }
        }
    }

    LaunchedEffect(uiState.exportReadyUri) {
        val uri = uiState.exportReadyUri ?: return@LaunchedEffect
        context.startActivity(roundtableSummaryShareIntent(uri))
        viewModel.clearShareUri()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Roundtable Summary", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${uiState.serverName} · ${uiState.roundtableId}",
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
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh summary")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.error != null -> SummaryErrorState(error = uiState.error ?: "", onRetry = viewModel::refresh, modifier = Modifier.align(Alignment.Center))
                else -> RoundtableSummaryContent(
                    uiState = uiState,
                    onExport = { exportLauncher.launch("roundtable-${uiState.roundtableId}-summary.md") },
                    onShare = { viewModel.prepareShare(context) { success -> if (!success) coroutineScope.launch { snackbarHostState.showSnackbar("Share failed") } } },
                )
            }
        }
    }
}

@Composable
private fun RoundtableSummaryContent(
    uiState: RoundtableSummaryUiState,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SummaryHeroCard(
                roundtableId = uiState.roundtableId,
                questionCount = uiState.openQuestions.size,
                onExport = onExport,
                onShare = onShare,
                isAmoled = isAmoled,
            )
        }
        item {
            SummarySectionCard(title = "Knowledge network", isAmoled = isAmoled) {
                val mermaid = uiState.knowledgeNetworkMermaid
                if (mermaid == null) {
                    Text("No knowledge-network mermaid graph was included in this transcript.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MermaidMarkdownDiagram(
                        source = mermaid,
                        fallbackContent = { MonospaceBlock(mermaid) },
                    )
                }
            }
        }
        item {
            SummarySectionCard(title = "Open questions", isAmoled = isAmoled) {
                if (uiState.openQuestions.isEmpty()) {
                    Text("No open questions were exported.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.openQuestions.forEachIndexed { index, question ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                AssistChip(onClick = {}, label = { Text("Q${index + 1}") })
                                Text(question, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        item {
            SummarySectionCard(title = "Verbatim minutes", isAmoled = isAmoled) {
                Text(
                    text = uiState.markdown,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun SummaryHeroCard(
    roundtableId: String,
    questionCount: Int,
    onExport: () -> Unit,
    onShare: () -> Unit,
    isAmoled: Boolean,
) {
    SummarySectionCard(title = "Export-ready transcript", isAmoled = isAmoled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Final minutes for $roundtableId include author-tagged turns, operational events, commands, the knowledge network, and open questions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AssistChip(onClick = {}, label = { Text("$questionCount open question${if (questionCount == 1) "" else "s"}") })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                Button(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun SummarySectionCard(
    title: String,
    isAmoled: Boolean,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.72f else 0.35f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SummaryErrorState(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
    }
}

@Composable
private fun MonospaceBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .horizontalScroll(rememberScrollState()),
    )
}
