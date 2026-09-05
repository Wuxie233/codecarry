package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import dev.wuxie233.codecarry.ui.screens.chat.isAmoledTheme
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Enter belongs to the draft; submission is owned by the separate send button. */
@Composable
internal fun CodexComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .then(if (isAmoled) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(22.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .heightIn(min = 24.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = false,
        maxLines = 5,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                innerTextField()
            }
        },
    )
}
