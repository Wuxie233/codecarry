package dev.minios.ocremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.theme.AppDimensions

@Composable
fun StatusLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    dynamic: Boolean = false,
) {
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                stateDescription = text
                if (dynamic) liveRegion = LiveRegionMode.Polite
            }
            .then(
                if (emphasized) {
                    Modifier
                        .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                        .padding(horizontal = AppDimensions.space2, vertical = AppDimensions.space1)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
