package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.wuxie233.codecarry.R

/** A single input surface keeps the draft, model controls and primary action together. */
@Composable
internal fun CodexComposerSurface(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    canSend: Boolean,
    isSending: Boolean,
    sendLabel: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    canStop: Boolean = false,
    stopLabel: String = "",
    onStop: () -> Unit = {},
    controls: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
            CodexComposerTextField(value, onValueChange, placeholder, Modifier.fillMaxWidth(), enabled = enabled)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f)) { controls() }
                if (canStop) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, stopLabel, Modifier.size(22.dp))
                    }
                }
                if (!canStop || value.text.isNotBlank() || canSend || isSending) {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = enabled && canSend && !isSending,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        if (isSending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.ArrowUpward, sendLabel, Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/** Reviewing recovered attachments only unlocks the draft; the send action stays explicit. */
@Composable
internal fun CodexAttachmentRecoveryNotice(enabled: Boolean, onReviewed: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.codex_rewrite_attachments_missing),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onReviewed, enabled = enabled) {
            Text(stringResource(R.string.codex_rewrite_attachments_reviewed))
        }
    }
}
