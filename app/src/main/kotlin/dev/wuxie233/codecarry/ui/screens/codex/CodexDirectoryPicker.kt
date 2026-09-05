package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexCapabilityUnavailableException
import dev.wuxie233.codecarry.data.codex.CodexDirectoryListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Browse the daemon filesystem; filtering applies only to the loaded directory. */
@Composable
fun CodexDirectoryPicker(
    recentDirectories: List<String>,
    defaultDirectory: suspend (String?) -> String?,
    readDirectory: suspend (String) -> CodexDirectoryListing,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val resolveDefault by rememberUpdatedState(defaultDirectory)
    val browse by rememberUpdatedState(readDirectory)
    val initialRecent = remember { recentDirectories.firstOrNull() }
    var requestedPath by remember { mutableStateOf<String?>(null) }
    var requestVersion by remember { mutableIntStateOf(0) }
    var pathInput by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<CodexDirectoryListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var browsingUnavailable by remember { mutableStateOf(false) }

    fun navigate(path: String?) {
        requestedPath = path
        pathInput = path.orEmpty()
        listing = null
        filter = ""
        failed = false
        browsingUnavailable = false
        loading = true
        requestVersion++
    }

    LaunchedEffect(requestVersion) {
        val version = requestVersion
        try {
            val result = withTimeout(20_000) {
                val path = requestedPath ?: resolveDefault(initialRecent)
                    ?: error("No remote default directory")
                browse(path)
            }
            // Some transports can finish a cancelled RPC. Never restore its old directory.
            currentCoroutineContext().ensureActive()
            if (version == requestVersion) {
                listing = result
                pathInput = result.path
                failed = false
                loading = false
            }
        } catch (_: TimeoutCancellationException) {
            if (version == requestVersion) {
                failed = true
                loading = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (version == requestVersion) {
                failed = true
                browsingUnavailable = error is CodexCapabilityUnavailableException
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 600.dp).fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.codex_directory_title), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        label = { Text(stringResource(R.string.codex_directory_path)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (pathInput.isNotBlank()) navigate(pathInput)
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { navigate(pathInput) }, enabled = pathInput.isNotBlank()) {
                        Text(stringResource(R.string.codex_directory_go))
                    }
                }
                if (recentDirectories.isNotEmpty()) {
                    Text(stringResource(R.string.codex_directory_recent), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentDirectories.distinct().forEach { path ->
                            OutlinedButton(onClick = { navigate(path) }) {
                                Text(path, maxLines = 1)
                            }
                        }
                    }
                }
                val current = listing
                if (current != null && !loading && !failed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { current.parentPath?.let(::navigate) },
                            enabled = current.parentPath != null,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.codex_directory_parent))
                        }
                        Text(current.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text(stringResource(R.string.codex_directory_filter)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(
                                if (browsingUnavailable) R.string.codex_directory_unavailable
                                else R.string.codex_directory_failed,
                            ))
                            TextButton(onClick = { navigate(requestedPath) }) {
                                Text(stringResource(R.string.codex_directory_retry))
                            }
                        }
                        current != null -> {
                            val visible = current.directories.filter { it.name.contains(filter, ignoreCase = true) }
                            if (visible.isEmpty()) {
                                Text(
                                    stringResource(if (filter.isBlank()) R.string.codex_directory_empty else R.string.codex_directory_no_matches),
                                    modifier = Modifier.align(Alignment.Center),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(visible, key = { it.path }) { entry ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable { navigate(entry.path) }.padding(vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Default.Folder, contentDescription = null)
                                            Text(entry.name, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.codex_directory_cancel)) }
                    Button(
                        onClick = { current?.let { onSelect(it.path) } },
                        enabled = current != null && !loading && !failed && pathInput == current.path,
                    ) {
                        Text(stringResource(R.string.codex_directory_select))
                    }
                }
            }
        }
    }
}
