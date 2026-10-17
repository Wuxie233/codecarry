package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.wuxie233.codecarry.R

/** Confirmation presentation; command execution and draft ownership stay in the ViewModel. */
@Composable
internal fun DshRewindAction(
    messageId: String,
    inFlight: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    val gate = LocalChatConfirmationGate.current ?: remember { ChatConfirmationGate() }
    val owner = "rewind:$messageId"
    DisposableEffect(gate, owner) { onDispose { gate.release(owner) } }
    TextButton(enabled = !inFlight && gate.owner == null, onClick = { gate.acquire(owner) }) {
        Text(stringResource(R.string.dsh_rewind_action))
    }
    if (gate.owner == owner) {
        AlertDialog(
            onDismissRequest = { gate.release(owner) },
            title = { Text(stringResource(R.string.dsh_rewind_action)) },
            text = { Column {
                Text(stringResource(R.string.dsh_rewind_body))
                if (busy) Text(stringResource(R.string.dsh_rewind_busy))
            } },
            confirmButton = { TextButton(enabled = !inFlight, onClick = {
                if (gate.owner == owner && !inFlight) {
                    gate.release(owner)
                    onConfirm()
                }
            }) { Text(stringResource(R.string.dsh_rewind_confirm)) } },
            dismissButton = { TextButton(onClick = { gate.release(owner) }) {
                Text(stringResource(R.string.cancel))
            } },
        )
    }
}
