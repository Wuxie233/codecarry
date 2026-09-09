package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R

@Composable
internal fun SubagentDisclosureRow(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    secondary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (secondary) {
        colors.onSurface.copy(alpha = 0.55f)
    } else {
        colors.primary.copy(alpha = 0.85f)
    }
    Row(
        modifier = modifier
            .padding(start = if (secondary) 32.dp else 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(
                if (expanded) R.string.sessions_subagents_hide else R.string.sessions_subagents_show,
            ),
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}
