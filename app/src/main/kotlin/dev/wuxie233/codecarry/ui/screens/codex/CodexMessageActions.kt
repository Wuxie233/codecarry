package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import kotlinx.coroutines.delay

/** Always reachable touch actions; copying preserves the original Markdown. */
@Composable
internal fun CodexMessageActions(
    text: String,
    onEdit: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onQuote: ((String) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Row {
        if (text.isNotBlank()) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) {
                Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    stringResource(if (copied) R.string.message_action_copied else R.string.message_action_copy_markdown),
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onQuote != null) IconButton(onClick = { onQuote(text) }) {
                Icon(Icons.Default.FormatQuote, stringResource(R.string.message_action_quote),
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onEdit != null) IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, stringResource(R.string.codex_message_edit),
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRetry != null) IconButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, stringResource(R.string.codex_message_retry),
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
