package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/** Enter belongs to the draft; submission is owned by the separate send button. */
@Composable
internal fun CodexComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder) },
        singleLine = false,
        minLines = 1,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
    )
}
