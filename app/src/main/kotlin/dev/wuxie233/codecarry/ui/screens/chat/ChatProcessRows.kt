package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ToolState

@Composable
internal fun ThinkProcessRow(part: Part.Reasoning) {
    val autoExpand = LocalCollapseTools.current
    var expanded by remember(autoExpand, part.id) { mutableStateOf(autoExpand) }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val summary = part.text.lineSequence().firstOrNull().orEmpty().trim()
    ProcessDisclosureRow(
        title = stringResource(R.string.chat_status_thinking),
        subtitle = summary.takeIf { it.isNotBlank() },
        icon = Icons.Default.Psychology,
        expandable = part.text.isNotBlank(),
        expanded = expanded,
        running = false,
        onToggle = {
            performHaptic(hapticView, hapticOn)
            expanded = !expanded
        },
    ) {
        SelectionContainer {
            Text(
                text = part.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
internal fun SkillProcessRow(part: Part.Tool) {
    val autoExpand = LocalCollapseTools.current
    var expanded by remember(autoExpand, part.id) { mutableStateOf(autoExpand) }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val name = skillRowName(part)
    val output = toolResultText(part)
    val running = part.state is ToolState.Running || part.state is ToolState.Pending
    ProcessDisclosureRow(
        title = stringResource(R.string.chat_skill_row_title, name),
        subtitle = null,
        icon = Icons.Default.AutoAwesome,
        expandable = output.isNotBlank(),
        expanded = expanded,
        running = running,
        failed = part.state is ToolState.Error,
        onToggle = {
            performHaptic(hapticView, hapticOn)
            expanded = !expanded
        },
    ) {
        Text(
            text = stringResource(R.string.chat_skill_instructions),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        SelectionContainer {
            Text(
                text = output,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
private fun ProcessDisclosureRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expandable: Boolean,
    expanded: Boolean,
    running: Boolean,
    failed: Boolean = false,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val outline = when {
        failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.75f else 0.55f)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, outline),
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    PulsingDotsIndicator(
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank() && !expanded) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (expandable) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
            AnimatedVisibility(visible = expanded && expandable) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = { content() },
                )
            }
        }
    }
}
