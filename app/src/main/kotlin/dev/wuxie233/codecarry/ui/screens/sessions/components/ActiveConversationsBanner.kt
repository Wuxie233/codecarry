package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R

@Composable
fun ActiveConversationsBanner(
    items: List<ActiveConversationItem>,
    onClick: (sessionId: String, directory: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val colors = MaterialTheme.colorScheme
    val isAmoled = colors.background == Color.Black && colors.surface == Color.Black

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isAmoled) Color.Black else colors.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            if (isAmoled) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = stringResource(R.string.sessions_active_conversations_title, items.size),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface.copy(alpha = 0.75f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.sessionId }) { item ->
                    ActiveConversationCard(item = item, isAmoled = isAmoled, onClick = onClick)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isAmoled) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f), thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun ActiveConversationCard(
    item: ActiveConversationItem,
    isAmoled: Boolean,
    onClick: (sessionId: String, directory: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val unreadColor = Color(0xFF2196F3)
    val (statusColor, statusLabelRes) = when (item.status) {
        ConversationStatus.UNREAD -> unreadColor to R.string.sessions_conversation_status_unread
        ConversationStatus.AWAITING_QUESTION -> colors.tertiary to R.string.sessions_conversation_status_question
        ConversationStatus.AWAITING_PERMISSION -> colors.secondary to R.string.sessions_conversation_status_permission
        ConversationStatus.BUSY -> colors.primary to R.string.sessions_conversation_status_busy
        ConversationStatus.RETRY -> colors.error to R.string.sessions_conversation_status_retry
    }
    val statusLabel = if (item.pendingCount > 0) {
        stringResource(R.string.sessions_conversation_status_pending_count, stringResource(statusLabelRes), item.pendingCount)
    } else {
        stringResource(statusLabelRes)
    }
    val (timeRes, timeValue) = relativeTimeString(item.updatedAt)
    val relativeTime = if (timeValue == null) {
        stringResource(timeRes)
    } else {
        stringResource(timeRes, timeValue)
    }

    Card(
        modifier = Modifier
            .width(230.dp)
            .height(72.dp)
            .clickable { onClick(item.sessionId, item.directory) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAmoled) colors.surfaceContainerLow else colors.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationStatusIcon(
                status = item.status,
                color = statusColor,
                modifier = Modifier.size(18.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = item.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.session_untitled),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusLabel,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    item.projectName?.takeIf { it.isNotBlank() }?.let { projectName ->
                        Text(
                            text = "· $projectName",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Text(
                text = relativeTime,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun ConversationStatusIcon(
    status: ConversationStatus,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (status) {
        ConversationStatus.UNREAD -> Icon(
            imageVector = Icons.Default.MarkChatUnread,
            contentDescription = stringResource(R.string.sessions_conversation_status_unread),
            tint = color,
            modifier = modifier,
        )
        ConversationStatus.AWAITING_QUESTION -> Icon(
            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = stringResource(R.string.sessions_conversation_status_question),
            tint = color,
            modifier = modifier,
        )
        ConversationStatus.AWAITING_PERMISSION -> Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = stringResource(R.string.sessions_conversation_status_permission),
            tint = color,
            modifier = modifier,
        )
        ConversationStatus.BUSY -> PulsingStatusDot(
            color = color,
            animate = true,
            modifier = modifier,
        )
        ConversationStatus.RETRY -> Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = stringResource(R.string.sessions_conversation_status_retry),
            tint = color,
            modifier = modifier,
        )
    }
}

private fun relativeTimeString(updated: Long, now: Long = System.currentTimeMillis()): Pair<Int, Int?> {
    val diff = (now - updated).coerceAtLeast(0)
    val m = diff / 60_000
    val h = diff / 3_600_000
    val d = diff / 86_400_000
    return when {
        m < 1L -> R.string.session_time_now to null
        h < 1L -> R.string.session_time_minutes to m.toInt()
        d < 1L -> R.string.session_time_hours to h.toInt()
        else -> R.string.session_time_days to d.toInt()
    }
}

@Composable
private fun PulsingStatusDot(color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val transition = if (animate) rememberInfiniteTransition(label = "conversation_status_dot") else null
    val scale by if (transition != null) {
        transition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "conversation_status_scale"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1.0f) }
    }
    val alpha by if (transition != null) {
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "conversation_status_alpha"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1.0f) }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(color)
    )
}
