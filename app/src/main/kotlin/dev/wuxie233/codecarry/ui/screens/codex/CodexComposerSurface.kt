package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Native chat composer layout, independent of the connection and view model. */
@Composable
internal fun CodexComposerSurface(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    isSending: Boolean,
    sendLabel: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        controls()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CodexComposerTextField(value, onValueChange, placeholder, Modifier.weight(1f))
            IconButton(onClick = onSend, enabled = canSend && !isSending) {
                if (isSending) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = sendLabel,
                        modifier = Modifier.size(20.dp),
                        tint = if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}
