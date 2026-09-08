package dev.wuxie233.codecarry.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Presentation only. The caller owns scope, localized labels and safe recovery actions. */
@Composable
fun ChatErrorNotice(
    message: String,
    detail: String?,
    title: String,
    detailsLabel: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    var expanded by remember(message, detail) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Column {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (expanded && !detail.isNullOrBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!detail.isNullOrBlank() || (actionLabel != null && onAction != null)) {
                Row {
                    if (!detail.isNullOrBlank()) {
                        TextButton(onClick = { expanded = !expanded }) { Text(detailsLabel) }
                    }
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                }
            }
        }
    }
}
