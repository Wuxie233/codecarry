package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.McpRuntimeState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectGroupHeader(
    projectName: String,
    tildeDirectory: String,
    sessionCount: Int,
    activeCount: Int,
    unreadCount: Int,
    additions: Int,
    deletions: Int,
    isPinned: Boolean,
    isCollapsed: Boolean,
    isHidden: Boolean,
    onToggleCollapsed: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleHidden: () -> Unit,
    onNewSession: () -> Unit,
    onCopyPath: () -> Unit,
    onArchiveAll: () -> Unit,
    onManageMcp: (() -> Unit)? = null,
    mcpServerCount: Int? = null,
    mcpSupportsRuntimeControl: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val unreadColor = Color(0xFF2196F3)
    val isAmoled = rememberIsAmoledTheme()
    val arrowRotation by animateFloatAsState(
        targetValue = if (isCollapsed) -90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "project_group_arrow_rotation",
    )
    val arrowTint = if (isAmoled) {
        colors.primary.copy(alpha = 0.7f)
    } else {
        colors.onSurface.copy(alpha = 0.55f)
    }

    val contentAlpha = if (isHidden) 0.45f else 1f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f), thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isAmoled) Color.Black else colors.surface.copy(alpha = 0.98f)
                )
                .height(48.dp)
                .combinedClickable(
                    onClick = onToggleCollapsed,
                    onLongClick = onTogglePinned,
                )
                .padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$projectName, $tildeDirectory"
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (isCollapsed) R.string.sessions_project_expand else R.string.sessions_project_collapse,
                ),
                tint = arrowTint,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(arrowRotation),
            )

            if (isHidden) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurface.copy(alpha = 0.35f),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primary.copy(alpha = 0.7f),
                )
            }

            Text(
                text = projectName,
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary.copy(alpha = 0.85f * contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = sessionCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface.copy(alpha = 0.5f),
            )

            if (activeCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(colors.tertiary, CircleShape),
                    )
                    Text(
                        text = activeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.tertiary,
                    )
                }
            }

            if (unreadCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(unreadColor, CircleShape),
                    )
                    Text(
                        text = unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = unreadColor,
                    )
                }
            }

            if (additions > 0 || deletions > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.widthIn(min = 0.dp),
                ) {
                    if (additions > 0) {
                        Text(
                            text = "+$additions",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50),
                        )
                    }
                    if (deletions > 0) {
                        Text(
                            text = "-$deletions",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFFE53935),
                        )
                    }
                }
            }

            if (isPinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = colors.primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.sessions_project_actions),
                        modifier = Modifier.size(20.dp),
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_project_new_here)) },
                        onClick = {
                            menuExpanded = false
                            onNewSession()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (isPinned) R.string.sessions_project_unpin else R.string.sessions_project_pin,
                                ),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePinned()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessions_project_copy_path)) },
                        onClick = {
                            menuExpanded = false
                            onCopyPath()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (isHidden) R.string.sessions_project_unhide
                                    else R.string.sessions_project_hide,
                                ),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleHidden()
                        },
                    )
                    onManageMcp?.let { action ->
                        val mcpHint = mcpHintLabel(mcpServerCount, mcpSupportsRuntimeControl)
                        val mcpHintDescription = mcpHintContentDescription(mcpServerCount)
                        DropdownMenuItem(
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            text = {
                                Column {
                                    Text("管理 MCP")
                                    if (mcpServerCount == 0 && mcpHint != null) {
                                        Text(
                                            text = mcpHint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                                        )
                                    }
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                action()
                            },
                            trailingIcon = if (mcpServerCount != null && mcpServerCount > 0 && mcpHint != null) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .semantics {
                                                contentDescription = mcpHintDescription.orEmpty()
                                            }
                                            .background(colors.primary, CircleShape)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = mcpHint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.onPrimary,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.sessions_project_archive_all),
                                color = colors.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onArchiveAll()
                        },
                    )
                }
            }
        }

        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f), thickness = 1.dp)
    }
}

@Composable
private fun rememberIsAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}

internal fun mcpHintLabel(count: Int?, supportsRuntimeControl: Boolean): String? {
    if (supportsRuntimeControl) {
        return if (count != null && count > 0) "MCP: $count" else null
    }
    return when (count) {
        null -> null
        0 -> "未启用"
        else -> count.toString()
    }
}

internal fun runtimeMcpHintLabel(
    states: List<McpRuntimeState>,
    supportsRuntimeControl: Boolean,
    fallbackCount: Int?,
): String? {
    val count = if (supportsRuntimeControl) {
        states.count { it == McpRuntimeState.CONNECTED }
    } else {
        fallbackCount
    }
    return mcpHintLabel(count, supportsRuntimeControl)
}

internal fun mcpHintContentDescription(count: Int?): String? = count
    ?.takeIf { it > 0 }
    ?.let { "该项目已配置 $it 个 MCP 服务器" }
