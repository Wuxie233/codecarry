package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dev.wuxie233.codecarry.domain.model.*
import dev.wuxie233.codecarry.data.api.AgentInfo
import dev.wuxie233.codecarry.data.api.CommandInfo
import dev.wuxie233.codecarry.data.api.PromptPart
import dev.wuxie233.codecarry.data.api.ProviderInfo
import dev.wuxie233.codecarry.data.api.ProviderModel
import dev.wuxie233.codecarry.MainActivity
import dev.wuxie233.codecarry.ui.theme.CodeTypography
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

import android.net.Uri
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.wuxie233.codecarry.BuildConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.ui.components.ProviderIcon


/**
 * Chat Screen - conversation view with native markdown rendering.
 * Shows messages with streaming text rendered via mikepenz markdown renderer.
 */

// ============ Chat Settings via CompositionLocal ============

/** Chat font size setting: "small", "medium", "large". */
val LocalChatFontSize = compositionLocalOf { "medium" }

/** Whether code blocks use word wrap instead of horizontal scroll. */
val LocalCodeWordWrap = compositionLocalOf { false }

/** Whether compact message spacing is enabled. */
val LocalCompactMessages = compositionLocalOf { false }

/** Whether process rows (Think, Skill, tools) start expanded. */
val LocalCollapseTools = compositionLocalOf { false }

/** Whether haptic feedback is enabled. */
val LocalHapticFeedbackEnabled = compositionLocalOf { true }

internal const val WidePlainTextTag = "wide-plain-text"

/** Image save request callback available to image preview composables. */
val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }

@Composable
internal fun isAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}

@Composable
private fun toolOutputContainerColor(isAmoled: Boolean): Color {
    return when {
        isAmoled -> Color.Black
        isSystemInDarkTheme() -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f)
    }
}

/**
 * Perform a light haptic tick if haptic feedback is enabled.
 * Call from composable context or from a click lambda that has access to a View.
 */
@Suppress("DEPRECATION")
internal fun performHaptic(view: android.view.View, enabled: Boolean) {
    if (enabled) {
        view.performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}

/**
 * Agent color matching the TUI's opencode theme.
 * Color cycle: secondary, accent, success, warning, primary, error, info
 * (same order as TUI's local.tsx color array).
 */
private val agentColorCycle = listOf(
    Color(0xFF5C9CF5), // secondary — build (blue)
    Color(0xFF9D7CD8), // accent — plan (purple)
    Color(0xFF7FD88F), // success (green)
    Color(0xFFF5A742), // warning (orange)
    Color(0xFFFAB283), // primary (peach)
    Color(0xFFE06C75), // error (red)
    Color(0xFF56B6C2)  // info (cyan)
)

private fun agentColor(agentName: String, agents: List<AgentInfo> = emptyList()): Color {
    val index = agents.indexOfFirst { it.name == agentName }
    return if (index >= 0) {
        agentColorCycle[index % agentColorCycle.size]
    } else {
        agentColorCycle[0]
    }
}

internal data class PiSenderIdentity(
    val id: String?,
    val name: String,
    val mbti: String?,
    val role: String?,
    val colorSeed: String,
)

internal data class PiInShortContent(
    val highlight: String?,
    val markdown: String,
)

internal fun piSenderIdentity(@Suppress("UNUSED_PARAMETER") message: Message.Assistant?): PiSenderIdentity? {
    // Roundtable sender chrome was product-deleted in 1.9.0.
    return null
}

internal fun piSenderAccentColor(identity: PiSenderIdentity): Color =
    agentColorCycle[Math.floorMod(identity.colorSeed.hashCode(), agentColorCycle.size)]

internal fun isPiModerator(identity: PiSenderIdentity?): Boolean =
    identity?.role.equals("moderator", ignoreCase = true)

internal fun isSamePiSender(current: ChatMessage, previous: ChatMessage?): Boolean {
    val currentIdentity = piSenderIdentity(current.message as? Message.Assistant) ?: return false
    val previousIdentity = piSenderIdentity(previous?.message as? Message.Assistant) ?: return false
    return currentIdentity.senderGroupingKey() == previousIdentity.senderGroupingKey()
}

private fun PiSenderIdentity.senderGroupingKey(): String = id ?: colorSeed.ifBlank { name }

internal fun splitPiInShortHighlight(markdown: String): PiInShortContent {
    val lines = markdown.lines()
    val highlightIndex = lines.indexOfFirst { line -> line.isPiInShortLine() }
    if (highlightIndex < 0) return PiInShortContent(highlight = null, markdown = markdown)

    val highlight = lines[highlightIndex].cleanPiInShortLine()
    val remaining = lines
        .filterIndexed { index, _ -> index != highlightIndex }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return PiInShortContent(highlight = highlight, markdown = remaining)
}

private fun String.isPiInShortLine(): Boolean {
    val normalized = trim()
        .removePrefix("-")
        .removePrefix("*")
        .removePrefix(">")
        .trim()
        .removePrefix("**")
        .removePrefix("__")
    return normalized.startsWith("简言之")
}

private fun String.cleanPiInShortLine(): String {
    return trim()
        .removePrefix("-")
        .removePrefix("*")
        .removePrefix(">")
        .trim()
        .replace("**", "")
        .replace("__", "")
        .trim()
}

/**
 * Conditionally applies horizontalScroll for code blocks.
 * When word wrap is enabled, no horizontal scroll is applied.
 */
@Composable
private fun Modifier.codeHorizontalScroll(): Modifier {
    return chatCodeOverflow(codeWordWrap = LocalCodeWordWrap.current)
}

/**
 * Slash command definition for the suggestion popup.
 * @param name Command name without the "/" prefix
 * @param description Human-readable description
 * @param type "server" commands are sent via API, "client" commands trigger local actions
 * @param source Server command source, e.g. "command", "mcp", or "skill"; null for client commands
 */
internal data class SlashCommand(
    val name: String,
    val description: String?,
    val type: String, // "server" or "client"
    val source: String? = null
)

internal fun mergeSlashCommands(
    client: List<SlashCommand>,
    server: List<CommandInfo>
): List<SlashCommand> {
    val clientNames = client.map { it.name }.toSet()
    val serverSlash = server
        .filter { it.source != "skill" && it.name !in clientNames }
        .map { SlashCommand(it.name, it.description, "server", it.source) }
    return client + serverSlash
}

internal enum class MessageCardAction {
    ForkFromHere,
    CopyText,
    CopyMarkdown,
    QuoteIntoInput,
    RestoreToInput,
    RestoreToHere,
}

internal enum class MessageCardActionDisabledReason {
    StreamingOrBusy,
}

internal data class MessageCardActionState(
    val action: MessageCardAction,
    val visible: Boolean,
    val enabled: Boolean,
    val disabledReason: MessageCardActionDisabledReason? = null,
)

internal fun buildMessageCardActions(
    chatMessage: ChatMessage,
    selectedMessageStreaming: Boolean,
    sessionBusy: Boolean,
    sessionReady: Boolean,
    supportsFork: Boolean = true,
    supportsRestore: Boolean = true,
): List<MessageCardActionState> {
    val hasStableMessageId = chatMessage.message.id.isNotBlank()
    val hasCopyableText = messagePlainText(chatMessage).trim().isNotBlank()
    val blockedByBusyOrStreaming = sessionBusy || selectedMessageStreaming
    val canMutateSession = sessionReady && !blockedByBusyOrStreaming
    val unavailableReason = if (blockedByBusyOrStreaming) {
        MessageCardActionDisabledReason.StreamingOrBusy
    } else {
        null
    }

    return listOf(
        MessageCardActionState(
            action = MessageCardAction.ForkFromHere,
            visible = hasStableMessageId && supportsFork,
            enabled = hasStableMessageId && supportsFork && canMutateSession,
            disabledReason = if (hasStableMessageId && supportsFork && !canMutateSession) unavailableReason else null,
        ),
        MessageCardActionState(
            action = MessageCardAction.CopyText,
            visible = hasCopyableText,
            enabled = hasCopyableText,
        ),
        MessageCardActionState(
            action = MessageCardAction.CopyMarkdown,
            visible = hasCopyableText,
            enabled = hasCopyableText,
        ),
        MessageCardActionState(
            action = MessageCardAction.QuoteIntoInput,
            visible = hasCopyableText,
            enabled = hasCopyableText,
        ),
        MessageCardActionState(
            action = MessageCardAction.RestoreToInput,
            visible = chatMessage.isUser && hasCopyableText,
            enabled = chatMessage.isUser && hasCopyableText,
        ),
        MessageCardActionState(
            action = MessageCardAction.RestoreToHere,
            visible = chatMessage.isUser && hasStableMessageId && supportsRestore,
            enabled = chatMessage.isUser && hasStableMessageId && supportsRestore && canMutateSession,
            disabledReason = if (chatMessage.isUser && hasStableMessageId && supportsRestore && !canMutateSession) unavailableReason else null,
        ),
    ).filter { it.visible }
}

internal fun messagePlainText(chatMessage: ChatMessage): String {
    val text = chatMessage.parts
        .filterIsInstance<Part.Text>()
        .filter { it.synthetic != true && it.ignored != true }
        .map { it.text.trim() }
        .filter { it.isNotBlank() }

    if (text.isNotEmpty()) {
        return text.joinToString("\n\n")
    }

    val userMessage = chatMessage.message as? Message.User
    return userMessage?.summary?.body?.takeIf { it.isNotBlank() }
        ?: userMessage?.summary?.title?.takeIf { it.isNotBlank() }
        ?: ""
}

internal fun messageMarkdown(chatMessage: ChatMessage): String {
    val role = if (chatMessage.isUser) "User" else "Assistant"
    return "> $role\n\n${messagePlainText(chatMessage)}".trimEnd()
}

internal fun quoteMessageText(chatMessage: ChatMessage): String {
    val capped = capQuotedSource(messagePlainText(chatMessage), maxLines = 40, maxBytes = 8 * 1024)
    if (capped.isBlank()) return ""

    return capped
        .lineSequence()
        .joinToString("\n") { line -> "> $line" }
        .plus("\n\n")
}

private fun capQuotedSource(text: String, maxLines: Int, maxBytes: Int): String {
    val limitedByLines = text.lineSequence().take(maxLines + 1).toList()
    val lineTruncated = limitedByLines.size > maxLines
    val lineCapped = limitedByLines.take(maxLines).joinToString("\n")
    val byteCapped = truncateUtf8(lineCapped, maxBytes)
    val byteTruncated = byteCapped.length < lineCapped.length
    return if (lineTruncated || byteTruncated) {
        byteCapped.trimEnd() + "\n…"
    } else {
        byteCapped
    }
}

private fun truncateUtf8(text: String, maxBytes: Int): String {
    var usedBytes = 0
    val builder = StringBuilder()
    for (char in text) {
        val charBytes = char.toString().toByteArray(Charsets.UTF_8).size
        if (usedBytes + charBytes > maxBytes) break
        builder.append(char)
        usedBytes += charBytes
    }
    return builder.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionMenu(
    actions: List<MessageCardActionState>,
    onActionSelected: (MessageCardAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.message_actions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            actions.forEach { actionState ->
                MessageActionMenuRow(
                    actionState = actionState,
                    onClick = { onActionSelected(actionState.action) },
                )
            }
        }
    }
}

@Composable
private fun MessageActionMenuRow(
    actionState: MessageCardActionState,
    onClick: () -> Unit,
) {
    val contentColor = if (actionState.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val supportingText = when (actionState.disabledReason) {
        MessageCardActionDisabledReason.StreamingOrBusy -> stringResource(R.string.message_action_unavailable_streaming)
        null -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = actionState.enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = messageActionIcon(actionState.action),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = contentColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = messageActionLabel(actionState.action),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun messageActionLabel(action: MessageCardAction): String {
    return when (action) {
        MessageCardAction.ForkFromHere -> stringResource(R.string.message_action_fork_from_here)
        MessageCardAction.CopyText -> stringResource(R.string.message_action_copy_text)
        MessageCardAction.CopyMarkdown -> stringResource(R.string.message_action_copy_markdown)
        MessageCardAction.QuoteIntoInput -> stringResource(R.string.message_action_quote)
        MessageCardAction.RestoreToInput -> stringResource(R.string.message_action_restore_input)
        MessageCardAction.RestoreToHere -> stringResource(R.string.message_action_restore_here)
    }
}

private fun messageActionIcon(action: MessageCardAction) = when (action) {
    MessageCardAction.ForkFromHere -> Icons.AutoMirrored.Filled.CallSplit
    MessageCardAction.CopyText -> Icons.Default.ContentCopy
    MessageCardAction.CopyMarkdown -> Icons.AutoMirrored.Filled.Article
    MessageCardAction.QuoteIntoInput -> Icons.Default.FormatQuote
    MessageCardAction.RestoreToInput -> Icons.Default.Edit
    MessageCardAction.RestoreToHere -> Icons.AutoMirrored.Filled.Undo
}

private enum class ChatInputMode {
    NORMAL,
    SHELL
}

/** Client-side slash commands that mirror the original opencode TUI. */
@Composable
private fun clientCommands(): List<SlashCommand> {
    return listOf(
        SlashCommand("new", stringResource(R.string.cmd_new), "client"),
        SlashCommand("compact", stringResource(R.string.cmd_compact), "client"),
        SlashCommand("fork", stringResource(R.string.cmd_fork), "client"),
        SlashCommand("share", stringResource(R.string.cmd_share), "client"),
        SlashCommand("unshare", stringResource(R.string.cmd_unshare), "client"),
        SlashCommand("undo", stringResource(R.string.cmd_undo), "client"),
        SlashCommand("redo", stringResource(R.string.cmd_redo), "client"),
        SlashCommand("rename", stringResource(R.string.cmd_rename), "client"),
        SlashCommand("shell", stringResource(R.string.cmd_shell_mode), "client"),
    )
}

/** Pulsing dots loading indicator — 3 dots that scale up/down in sequence. */
@Composable
internal fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotSpacing: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "pulsing_dots")
    val scales = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    0.4f at 0
                    1.0f at 300
                    0.4f at 600
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_$index"
        )
    }
    // Stagger: shift each dot's time by reading at offset phase
    val phaseShift = 150 // ms between dots
    val scales2 = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    val offset = index * phaseShift
                    0.4f at 0 + offset
                    1.0f at 300 + offset
                    0.4f at 600 + offset
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_scale_$index"
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scales2.forEach { scale ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = 0.3f + 0.7f * ((scale.value - 0.4f) / 0.6f)
                    }
                    .background(color, CircleShape)
            )
        }
    }
}

/** Breathing circle loading indicator — single circle that pulses smoothly. */
@Composable
private fun BreathingCircleIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "breathing_circle")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_alpha"
    )
    
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(color, CircleShape)
        )
    }
}

/** Format a token count to a human-readable string (e.g., 1.2k, 45.3k, 1.2M). */
private fun formatTokenCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}

private fun formatAssistantErrorMessage(error: Message.Assistant.ErrorInfo?): String? {
    if (error == null) return null
    val raw = error.message.ifBlank { error.name }
    return raw.ifBlank { null }
}

private enum class HtmlErrorViewMode {
    Page,
    Code,
}

@Composable
private fun ErrorPayloadContent(
    text: String,
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!looksLikeHtmlPayload(text)) {
        SelectionContainer {
            ScrollablePlainText(
                text = text,
                style = textStyle,
                color = textColor,
                modifier = modifier,
            )
        }
        return
    }

    var mode by rememberSaveable(text) { mutableStateOf(HtmlErrorViewMode.Code) }
    val htmlForPreview = remember(text) { normalizeHtmlForEmbeddedPreview(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == HtmlErrorViewMode.Code,
                onClick = { mode = HtmlErrorViewMode.Code },
                label = { Text(stringResource(R.string.chat_error_view_code)) },
            )
            FilterChip(
                selected = mode == HtmlErrorViewMode.Page,
                onClick = { mode = HtmlErrorViewMode.Page },
                label = { Text(stringResource(R.string.chat_error_view_page)) },
            )
        }

        if (mode == HtmlErrorViewMode.Page) {
            val isAmoled = isAmoledTheme()
            val bgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.textZoom = 85
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        setOnTouchListener { v, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        }
                        setBackgroundColor(bgColor.toArgb())
                    }
                },
                update = { webView ->
                    if (webView.tag != htmlForPreview) {
                        webView.tag = htmlForPreview
                        webView.loadDataWithBaseURL(
                            "https://localhost/",
                            htmlForPreview,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            SelectionContainer {
                ScrollablePlainText(
                    text = text,
                    style = textStyle,
                    color = textColor,
                )
            }
        }
    }
}

/**
 * VisualTransformation that highlights confirmed @file mentions as colored pills.
 * Only paths present in [confirmedFilePaths] are highlighted; unconfirmed @queries
 * remain unstyled so the user can see they haven't been selected yet.
 */
private class FileMentionVisualTransformation(
    private val confirmedFilePaths: Set<String>,
    private val highlightColor: Color,
    private val bgColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (confirmedFilePaths.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            // For each confirmed path, find all occurrences of @path in the text
            for (path in confirmedFilePaths) {
                val needle = "@$path"
                var searchFrom = 0
                while (true) {
                    val idx = raw.indexOf(needle, searchFrom)
                    if (idx == -1) break
                    // Ensure the match is not part of a longer token:
                    // next char after needle should be whitespace, end-of-string, or another @
                    val endIdx = idx + needle.length
                    if (endIdx < raw.length) {
                        val next = raw[endIdx]
                        if (!next.isWhitespace() && next != '@') {
                            searchFrom = endIdx
                            continue
                        }
                    }
                    addStyle(
                        SpanStyle(
                            color = highlightColor,
                            background = bgColor,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = idx,
                        end = endIdx
                    )
                    searchFrom = endIdx
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

/**
 * Splits raw input text into a list of [PromptPart] objects.
 * Text around confirmed @file mentions becomes type="text" parts,
 * and each @file mention becomes a type="file" part with a file:// URL.
 */
private fun buildPromptParts(
    text: String,
    confirmedPaths: Set<String>,
    sessionDirectory: String?
): List<PromptPart> {
    if (confirmedPaths.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Find all confirmed @path mentions with their positions
    data class Mention(val start: Int, val end: Int, val path: String)
    val mentions = mutableListOf<Mention>()

    for (path in confirmedPaths) {
        val needle = "@$path"
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf(needle, searchFrom)
            if (idx == -1) break
            val endIdx = idx + needle.length
            // Boundary check: next char must be whitespace, end-of-string, or @
            if (endIdx < text.length) {
                val next = text[endIdx]
                if (!next.isWhitespace() && next != '@') {
                    searchFrom = endIdx
                    continue
                }
            }
            mentions.add(Mention(idx, endIdx, path))
            searchFrom = endIdx
        }
    }

    if (mentions.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Sort by position
    mentions.sortBy { it.start }

    val parts = mutableListOf<PromptPart>()
    var cursor = 0

    for (mention in mentions) {
        // Add text before this mention
        if (mention.start > cursor) {
            val segment = text.substring(cursor, mention.start).trim()
            if (segment.isNotEmpty()) {
                parts.add(PromptPart(type = "text", text = segment))
            }
        }
        // Add file part
        val isDir = mention.path.endsWith("/")
        val absPath = if (sessionDirectory != null) "$sessionDirectory/${mention.path}" else mention.path
        val displayName = mention.path.trimEnd('/').substringAfterLast('/')
        parts.add(
            PromptPart(
                type = "file",
                path = mention.path,
                mime = if (isDir) "application/x-directory" else "text/plain",
                url = "file:///$absPath",
                filename = displayName
            )
        )
        cursor = mention.end
    }

    // Trailing text
    if (cursor < text.length) {
        val segment = text.substring(cursor).trim()
        if (segment.isNotEmpty()) {
            parts.add(PromptPart(type = "text", text = segment))
        }
    }

    return parts
}

private const val ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024

internal fun readAttachmentBytes(input: java.io.InputStream, maxBytes: Int = ATTACHMENT_MAX_BYTES): ByteArray? {
    require(maxBytes >= 0)
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val remainingWithSentinel = maxBytes - total + 1
        val read = input.read(buffer, 0, minOf(buffer.size, remainingWithSentinel))
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/** An image attachment ready to send. */
private data class ImageAttachment(
    val uri: Uri,
    val mime: String,
    val filename: String,
    val dataUrl: String // "data:<mime>;base64,..."
)

private data class ImageSaveRequest(
    val bytes: ByteArray,
    val mime: String,
    val filename: String,
)

private fun decodeDataUrlBytes(dataUrl: String): ByteArray? {
    val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

private fun decodePartFileBytes(file: Part.File): ByteArray? {
    val url = file.url ?: return null
    val encoded = if (url.contains(',')) url.substringAfter(',') else url
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

private fun extensionForMime(mime: String): String {
    return when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "img"
    }
}

private fun imageThumbnailModel(attachment: ImageAttachment): Any {
    if (attachment.uri.scheme.equals("data", ignoreCase = true)) {
        val encoded = attachment.dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (encoded.isNotBlank()) {
            return try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: Exception) {
                attachment.dataUrl
            }
        }
    }
    return attachment.uri
}

private data class PreparedAttachment(
    val attachment: ImageAttachment,
    val comparison: AttachmentComparison? = null
)

private data class AttachmentComparison(
    val originalBytes: Int,
    val optimizedBytes: Int,
    val originalEstimatedTokens: Int,
    val optimizedEstimatedTokens: Int
)

private fun estimateVisionTokens(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    return ((width.toLong() * height.toLong()) / 750.0).toInt()
}

private fun formatFileSize(bytes: Int): String {
    val value = bytes.toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format("%.2f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.1f KB", value / 1024.0)
        else -> "$bytes B"
    }
}

private suspend fun buildAttachmentFromUri(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    compressImages: Boolean,
    maxLongSidePx: Int = 1440,
    webpQuality: Int = 60
): PreparedAttachment? = withContext(Dispatchers.IO) {
    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

    val sizeHint = runCatching {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()
    if (sizeHint != null && sizeHint > ATTACHMENT_MAX_BYTES) return@withContext null
    val bytes = contentResolver.openInputStream(uri)?.use { readAttachmentBytes(it) } ?: return@withContext null
    val originalFilename = uri.lastPathSegment?.substringAfterLast('/') ?: "image.png"

    val shouldOptimize = compressImages && (mimeType == "image/png" || mimeType == "image/jpeg")
    if (!shouldOptimize) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val srcWidth = bitmap.width
    val srcHeight = bitmap.height
    val longSide = maxOf(srcWidth, srcHeight)
    val resizeEnabled = maxLongSidePx > 0
    val scale = if (resizeEnabled && longSide > maxLongSidePx) {
        maxLongSidePx.toFloat() / longSide.toFloat()
    } else {
        1f
    }
    val outWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
    val resizedBitmap = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true) else bitmap

    val output = java.io.ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
    val compressed = resizedBitmap.compress(format, webpQuality.coerceIn(1, 100), output)
    if (resizedBitmap !== bitmap) {
        resizedBitmap.recycle()
    }
    bitmap.recycle()

    if (!compressed) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val webpBytes = output.toByteArray()
    if (scale >= 0.999f && webpBytes.size >= bytes.size) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }
    val base64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP)
    val optimizedFilename = originalFilename.substringBeforeLast('.', originalFilename) + ".webp"
    return@withContext PreparedAttachment(
        attachment = ImageAttachment(
            uri = uri,
            mime = "image/webp",
            filename = optimizedFilename,
            dataUrl = "data:image/webp;base64,$base64"
        ),
        comparison = AttachmentComparison(
            originalBytes = bytes.size,
            optimizedBytes = webpBytes.size,
            originalEstimatedTokens = estimateVisionTokens(srcWidth, srcHeight),
            optimizedEstimatedTokens = estimateVisionTokens(outWidth, outHeight)
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (sessionId: String, directory: String) -> Unit = { _, _ -> },
    onCopyWebLink: () -> String = { "" },
    initialSharedImages: List<Uri> = emptyList(),
    onSharedImagesConsumed: () -> Unit = {},
    startInTerminalMode: Boolean = false,
    windowSizeClass: WindowSizeClass,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val draftText by viewModel.draftText.collectAsState()
    val draftAttachmentUris by viewModel.draftAttachmentUris.collectAsState()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // Sync inputText once from draft on first composition
    var draftTextInitialized by remember { mutableStateOf(false) }
    if (!draftTextInitialized && draftText.isNotEmpty()) {
        inputText = TextFieldValue(draftText, TextRange(draftText.length))
        draftTextInitialized = true
    } else if (!draftTextInitialized) {
        draftTextInitialized = true
    }
    // Listen for revert events that should restore text to the input field
    LaunchedEffect(Unit) {
        viewModel.revertedDraftEvent.collect { payload ->
            inputText = TextFieldValue(payload.text, TextRange(payload.text.length))
        }
    }
    val listState = rememberLazyListState()
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showVariantPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSubagentDrawer by rememberSaveable { mutableStateOf(false) }
    var actionMenuMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var restoreConfirmMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var forkFromMessageInFlight by remember { mutableStateOf(false) }
    var isTerminalMode by rememberSaveable { mutableStateOf(startInTerminalMode) }
    LaunchedEffect(uiState.supportsTerminal) {
        if (!uiState.supportsTerminal) isTerminalMode = false
    }
    var terminalCtrlLatched by rememberSaveable { mutableStateOf(false) }
    var terminalAltLatched by rememberSaveable { mutableStateOf(false) }
    var terminalVirtualCtrlDown by remember { mutableStateOf(false) }
    var terminalVirtualFnDown by remember { mutableStateOf(false) }
    var suppressFnTildeUntil by remember { mutableStateOf(0L) }
    val terminalFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.retryNowFailureEvent.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.chat_retry_now_failed))
        }
    }
    val isAmoled = isAmoledTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val view = LocalView.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var terminalOverlayHeightPx by remember { mutableStateOf(0) }

    // @ file mention state
    val fileSearchResults by viewModel.fileSearchResults.collectAsState()
    val confirmedFilePaths by viewModel.confirmedFilePaths.collectAsState()

    // Settings
    val chatFontSize by viewModel.chatFontSize.collectAsState()
    val codeWordWrap by viewModel.codeWordWrap.collectAsState()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsState()
    val compactMessages by viewModel.compactMessages.collectAsState()
    val collapseTools by viewModel.collapseTools.collectAsState()
    val hapticEnabled by viewModel.hapticFeedback.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsState()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsState()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsState()
    val terminalVersion by viewModel.terminalVersion.collectAsState()
    val terminalConnected by viewModel.terminalConnected.collectAsState()
    val terminalTabs by viewModel.terminalTabs.collectAsState()
    val activeTerminalTabId by viewModel.activeTerminalTabId.collectAsState()
    val terminalFontSizeSp by viewModel.terminalFontSizeSp.collectAsState()
    if (BuildConfig.DEBUG) {
        LaunchedEffect(terminalFontSizeSp) {
            Log.d("TerminalZoom", "ChatScreen: terminalFontSizeSp CHANGED to $terminalFontSizeSp (flow identity=${System.identityHashCode(viewModel.terminalFontSizeSp)})")
        }
    }
    val terminalDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showSendConfirmDialog by remember { mutableStateOf(false) }
    // Pending send action: stored so the confirm dialog can trigger it
    var pendingSendAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(ChatInputMode.NORMAL.name) }
    val isShellMode = inputMode == ChatInputMode.SHELL.name


    BackHandler(enabled = isTerminalMode) {
        if (terminalDrawerState.isOpen) {
            coroutineScope.launch { terminalDrawerState.close() }
        } else if (startInTerminalMode) {
            // Opened directly in terminal mode (e.g. from sessions list) —
            // back should navigate away, not show the chat view.
            onNavigateBack()
        } else {
            isTerminalMode = false
        }
    }

    BackHandler(enabled = showSubagentDrawer && !isTerminalMode) {
        showSubagentDrawer = false
    }

    LaunchedEffect(isTerminalMode) {
        if (isTerminalMode) {
            viewModel.openTerminalSession { ok ->
                if (!ok) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                    }
                    isTerminalMode = false
                }
            }
        } else {
            terminalCtrlLatched = false
            terminalAltLatched = false
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
        }
    }

    DisposableEffect(isTerminalMode) {
        val activity = context as? MainActivity
        if (isTerminalMode && activity != null) {
            activity.setTerminalKeyInterceptor { event ->
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        terminalVirtualCtrlDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        val wasDown = terminalVirtualFnDown
                        terminalVirtualFnDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        if (BuildConfig.DEBUG) {
                            Log.d("TerminalInput", "VOL_UP: action=${if (event.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"} wasDown=$wasDown nowDown=$terminalVirtualFnDown")
                        }
                        if (wasDown && !terminalVirtualFnDown) {
                            // FN key released — some IMEs leak a delayed '~' character
                            // from the underlying key (e.g., Shift+` or dead-key residue).
                            // Suppress any standalone '~' arriving shortly after release.
                            suppressFnTildeUntil = SystemClock.elapsedRealtime() + 3_000L
                            if (BuildConfig.DEBUG) {
                                Log.d("TerminalInput", "FN released -> suppressFnTildeUntil set for 3s")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        } else {
            activity?.setTerminalKeyInterceptor(null)
        }
        onDispose {
            activity?.setTerminalKeyInterceptor(null)
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
        }
    }

    // Force status bar black while terminal is visible.
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    DisposableEffect(isTerminalMode) {
        val activity = context as? android.app.Activity
        if (isTerminalMode && activity != null) {
            activity.window.statusBarColor = android.graphics.Color.BLACK
            androidx.core.view.WindowCompat.getInsetsController(
                activity.window, activity.window.decorView
            ).isAppearanceLightStatusBars = false
        }
        onDispose {
            val act = context as? android.app.Activity ?: return@onDispose
            act.window.statusBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowCompat.getInsetsController(
                act.window, act.window.decorView
            ).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    LaunchedEffect(isTerminalMode, terminalConnected) {
        if (isTerminalMode && terminalConnected) {
            terminalFocusRequester.requestFocus()
        }
    }

    fun pasteClipboardToTerminal() {
        if (!terminalConnected) return
        val clip = clipboardManager.getText()?.text ?: return
        if (clip.isEmpty()) return
        val cleaned = clip
            .replace(Regex("[\u001B\u0080-\u009F]"), "")
            .replace("\r\n", "\r")
            .replace('\n', '\r')
        if (cleaned.isNotEmpty()) {
            viewModel.sendTerminalInput(cleaned)
        }
    }

    fun sendTerminalChunk(chunk: String) {
        if (BuildConfig.DEBUG) {
            val codes = chunk.map { String.format("%04x", it.code) }
            val remain = suppressFnTildeUntil - SystemClock.elapsedRealtime()
            Log.d("TerminalInput", "sendTerminalChunk: chunk=$codes fnDown=$terminalVirtualFnDown suppressRemain=${remain}ms")
        }
        if (!terminalVirtualFnDown) {
            val now = SystemClock.elapsedRealtime()
            if (now < suppressFnTildeUntil && chunk.contains('~')) {
                // Guard against a leaked '~' after an FN key combo (e.g., Fn+0/F10).
                // The tilde may arrive alone ("~") or bundled with other characters.
                if (BuildConfig.DEBUG) {
                    Log.d("TerminalInput", "SUPPRESSING tilde from chunk='$chunk'")
                }
                val stripped = chunk.replace("~", "")
                suppressFnTildeUntil = 0L
                if (stripped.isEmpty()) return
                // Forward the non-tilde remainder.
                @Suppress("NAME_SHADOWING")
                val chunk = stripped
                // fall through with the cleaned chunk
                val ctrlActive2 = terminalCtrlLatched || terminalVirtualCtrlDown
                val altActive2 = terminalAltLatched
                val processed = applyTerminalModifiers(input = chunk, ctrl = ctrlActive2, alt = altActive2)
                if (processed.isEmpty()) return
                viewModel.sendTerminalInput(processed)
                if (terminalCtrlLatched) terminalCtrlLatched = false
                if (terminalAltLatched) terminalAltLatched = false
                return
            }
            if (chunk.isNotEmpty() && !chunk.contains('~')) {
                // Any other explicit input clears the temporary suppression window.
                suppressFnTildeUntil = 0L
            }
        }

        val ctrlActive = terminalCtrlLatched || terminalVirtualCtrlDown
        val altActive = terminalAltLatched

        // Termux-compatible shortcut: Ctrl+Alt+V pastes clipboard into terminal.
        if (!terminalVirtualFnDown && ctrlActive && altActive && chunk.length == 1 && chunk[0].lowercaseChar() == 'v') {
            pasteClipboardToTerminal()
            if (terminalCtrlLatched) terminalCtrlLatched = false
            if (terminalAltLatched) terminalAltLatched = false
            return
        }

        val processed = if (terminalVirtualFnDown) {
            val fnResult = applyTermuxFnBindings(chunk, viewModel.terminalEmulator.cursorKeysApplicationMode)
            if (fnResult.showVolumeUi) {
                val audio = context.getSystemService(AudioManager::class.java)
                audio?.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_SAME,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            if (fnResult.toggleKeyboard) {
                if (imeVisible) {
                    keyboardController?.hide()
                } else {
                    terminalFocusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
            if (fnResult.output.contains("~")) {
                // Any FN binding that produces '~' in its escape sequence (F5-F12, Insert,
                // Delete, PageUp, PageDown) may cause the IME to leak a standalone '~' after
                // the Volume-Up (FN) key is released.
                suppressFnTildeUntil = SystemClock.elapsedRealtime() + 3_000L
            }
            fnResult.output
        } else {
            applyTerminalModifiers(
                input = chunk,
                ctrl = ctrlActive,
                alt = altActive
            )
        }
        if (processed.isEmpty()) return
        if (BuildConfig.DEBUG && processed.contains('~')) {
            Log.d("TerminalInput", "SENDING to server: '${processed.map { String.format("%04x", it.code) }}' fnDown=$terminalVirtualFnDown")
        }
        viewModel.sendTerminalInput(processed)
        if (terminalCtrlLatched) terminalCtrlLatched = false
        if (terminalAltLatched) terminalAltLatched = false
    }

    // Keep screen on while on chat screen (if enabled in settings)
    DisposableEffect(keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Image attachments — backed by ViewModel URIs for draft persistence
    val attachments = remember { mutableStateListOf<ImageAttachment>() }

    // Rebuild attachment objects from persisted draft URIs on first composition
    LaunchedEffect(draftAttachmentUris, compressImageAttachments, imageAttachmentMaxLongSide, imageAttachmentWebpQuality) {
        // Only rebuild if attachments list doesn't match URIs (e.g. on session restore)
        val currentUris = attachments.map { it.uri.toString() }.toSet()
        val draftUriSet = draftAttachmentUris.toSet()
        if (currentUris == draftUriSet) return@LaunchedEffect

        val restored = mutableListOf<ImageAttachment>()
        for (uriStr in draftAttachmentUris) {
            // Skip URIs already present
            if (uriStr in currentUris) {
                val existing = attachments.first { it.uri.toString() == uriStr }
                restored.add(existing)
                continue
            }
            try {
                val uri = android.net.Uri.parse(uriStr)
                if (uriStr.startsWith("data:image/", ignoreCase = true)) {
                    val mime = uriStr.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
                    val syntheticName = "image.${mime.substringAfter('/', "png")}".lowercase()
                    restored.add(
                        ImageAttachment(
                            uri = uri,
                            mime = mime,
                            filename = syntheticName,
                            dataUrl = uriStr,
                        )
                    )
                    continue
                }
                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImageAttachments,
                    maxLongSidePx = imageAttachmentMaxLongSide,
                    webpQuality = imageAttachmentWebpQuality
                )
                if (prepared != null) {
                    restored.add(prepared.attachment)
                }
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to restore attachment $uriStr: ${e.message}")
                // Remove invalid URI from draft
                viewModel.removeDraftAttachment(draftAttachmentUris.indexOf(uriStr))
            }
        }
        attachments.clear()
        attachments.addAll(restored)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        coroutineScope.launch {
            val optimizedComparisons = mutableListOf<AttachmentComparison>()
            for (uri in uris) {
                try {
                    // Take persistable URI permission so the URI survives app restarts
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // Not all URIs support persistable permissions — that's OK
                    }

                    val prepared = buildAttachmentFromUri(
                        contentResolver = context.contentResolver,
                        uri = uri,
                        compressImages = compressImageAttachments,
                        maxLongSidePx = imageAttachmentMaxLongSide,
                        webpQuality = imageAttachmentWebpQuality
                    ) ?: continue

                    attachments.add(prepared.attachment)
                    viewModel.addDraftAttachment(uri.toString())
                    prepared.comparison?.let { optimizedComparisons.add(it) }
                } catch (_: Exception) {
                    // Skip files that fail to read
                }
            }
            if (optimizedComparisons.isNotEmpty()) {
                val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
                val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
                val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
                val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.chat_images_optimized_summary,
                        optimizedComparisons.size,
                        formatFileSize(totalOriginal),
                        formatFileSize(totalOptimized),
                        totalTokensBefore,
                        totalTokensAfter
                    )
                )
            }
        }
    }

    // Session export via SAF (Storage Access Framework)
    // Flow: menu click → SAF file picker → stream API responses directly to file
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportSession(context, uri) { success ->
                coroutineScope.launch {
                    if (success) {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_exported))
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_export_failed))
                    }
                }
            }
        }
    }

    var pendingImageSave by remember { mutableStateOf<ImageSaveRequest?>(null) }
    val saveImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri: Uri? ->
        val request = pendingImageSave
        pendingImageSave = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(request.bytes) }
                    ?: error("Unable to open output stream")
            }.onSuccess {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_image_saved))
            }.onFailure {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_image_save_failed))
            }
        }
    }

    val requestSaveImage: (ByteArray, String, String?) -> Unit = { bytes, mime, filenameHint ->
        val baseName = filenameHint
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "image_${System.currentTimeMillis()}"
        val fileName = "$baseName.${extensionForMime(mime)}"
        pendingImageSave = ImageSaveRequest(bytes = bytes, mime = mime, filename = fileName)
        saveImageLauncher.launch(fileName)
    }

    // Consume images shared from other apps via ACTION_SEND (one-shot)
    LaunchedEffect(initialSharedImages) {
        if (initialSharedImages.isEmpty()) return@LaunchedEffect
        val optimizedComparisons = mutableListOf<AttachmentComparison>()
        for (uri in initialSharedImages) {
            try {
                // Take persistable URI permission so the URI survives app restarts
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Not all URIs support persistable permissions — that's OK
                }

                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImageAttachments,
                    maxLongSidePx = imageAttachmentMaxLongSide,
                    webpQuality = imageAttachmentWebpQuality
                ) ?: continue

                attachments.add(prepared.attachment)
                prepared.comparison?.let { optimizedComparisons.add(it) }
                viewModel.addDraftAttachment(uri.toString())
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to read shared image: ${e.message}")
            }
        }
        if (optimizedComparisons.isNotEmpty()) {
            val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
            val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
            val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
            val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.chat_images_optimized_summary,
                    optimizedComparisons.size,
                    formatFileSize(totalOriginal),
                    formatFileSize(totalOptimized),
                    totalTokensBefore,
                    totalTokensAfter
                )
            )
        }
        onSharedImagesConsumed()
    }

    // Show errors as persistent snackbar when messages are already loaded
    LaunchedEffect(uiState.error, uiState.pendingSendError) {
        val error = uiState.pendingSendError ?: uiState.error
        if (error != null && (uiState.messages.isNotEmpty() || uiState.pendingSendError != null)) {
            val result = snackbarHostState.showSnackbar(
                message = error,
                actionLabel = if (uiState.pendingSendError != null) context.getString(R.string.retry) else null,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed && uiState.pendingSendError != null) {
                viewModel.retryPendingSend()
            }
        }
    }

    var followTailState by remember { mutableStateOf(ChatFollowTailState()) }
    var programmaticScrollInProgress by remember { mutableStateOf(false) }

    // True when the very bottom of the list is visible (accounting for offset within tall items)
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            val totalItems = info.totalItemsCount
            if (lastVisible.index < totalItems - 1) return@derivedStateOf false
            // Last item is visible — check if its bottom edge is within the viewport
            val itemBottom = lastVisible.offset + lastVisible.size
            val viewportEnd = info.viewportEndOffset
            itemBottom <= viewportEnd + 50 // 50px tolerance
        }
    }

    suspend fun scrollToTail() {
        programmaticScrollInProgress = true
        try {
            val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
            listState.scrollToItem(lastIndex)
            // scrollToItem aligns a tall last row at its top; consume the remaining
            // overflow so streaming content reaches the actual tail.
            val lastItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastItem != null) {
                val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                val overflow = lastItem.size - viewport
                if (overflow > 0) {
                    listState.scrollBy(overflow.toFloat())
                }
            }
        } finally {
            programmaticScrollInProgress = false
        }
    }

    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        followTailState = ChatFollowTailPolicy.onViewportChanged(
            state = followTailState,
            isAtTail = isAtBottom,
            isUserScrollInProgress = listState.isScrollInProgress && !programmaticScrollInProgress,
        )
    }


    val messageCount = uiState.messages.size
    val markdownPlanningState = remember { ChatMessageRowPlanningState() }
    val messageRows = remember(uiState.messages) {
        planChatMessageRows(uiState.messages, markdownPlanningState)
    }

    val autoFollowTarget = remember(uiState.messages, messageRows) {
        chatAutoFollowTarget(uiState.messages, messageRows)
    }
    val pendingCount = uiState.pendingPermissions.size + uiState.pendingQuestions.size
    val isBusy = uiState.sessionStatus is SessionStatus.Busy
    val sessionBusyForMessageActions = uiState.sessionStatus !is SessionStatus.Idle || uiState.isSending || forkFromMessageInFlight
    val sessionReadyForMessageActions = !uiState.isLoading &&
        (uiState.error == null) &&
        viewModel.sessionId.isNotBlank() &&
        (!uiState.supportsFork)
    LaunchedEffect(messageCount, autoFollowTarget) {
        val transition = ChatFollowTailPolicy.onContentChanged(
            state = followTailState,
            hasContent = messageCount > 0,
        )
        followTailState = transition.state
        if (transition.scrollToTail) {
            scrollToTail()
        }
    }

    CompositionLocalProvider(
        LocalChatFontSize provides chatFontSize,
        LocalCodeWordWrap provides codeWordWrap,
        LocalCompactMessages provides compactMessages,
        LocalCollapseTools provides collapseTools,
        LocalHapticFeedbackEnabled provides hapticEnabled,
        LocalImageSaveRequest provides requestSaveImage,
    ) {
    val runningSubagentCount = uiState.subagents.count(ChatSubagentItem::isRunning)
    val showSubagentContext = showSubagentDrawer &&
        !isTerminalMode &&
        
        true
    ChatAdaptiveShell(
        windowSizeClass = windowSizeClass,
        contextVisible = showSubagentContext,
        primaryContent = {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isTerminalMode) {
                val totalTokens = uiState.totalInputTokens + uiState.totalOutputTokens
                val usageParts = buildList {
                    if (totalTokens > 0) {
                        add(stringResource(R.string.chat_tokens_summary, formatTokenCount(totalTokens)))
                    }
                    if (uiState.totalCost > 0) {
                        add(stringResource(R.string.chat_cost_format, String.format("%.4f", uiState.totalCost)))
                    }
                }
                val backendLabel = stringResource(
                    if (uiState.isDsh) R.string.server_type_dsh else R.string.server_type_opencode
                )
                val statusLabel = stringResource(
                    when (uiState.sessionStatus) {
                        is SessionStatus.Idle -> R.string.session_status_idle
                        is SessionStatus.Busy -> R.string.session_status_busy
                        is SessionStatus.Retry -> R.string.sessions_retrying
                    },
                )
                val showOverflow = !uiState.supportsSessionCreate || uiState.supportsFork ||
                    uiState.supportsCompact || uiState.supportsCommands || uiState.supportsRename
                ChatHeader(
                    title = uiState.sessionTitle,
                    context = viewModel.getSessionDirectory().orEmpty(),
                    backendLabel = backendLabel,
                    statusLabel = statusLabel,
                    usageSummary = usageParts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                    canStop = uiState.sessionStatus.isInterruptible && uiState.supportsAbort,
                    showSubagents = true,
                    runningSubagentCount = runningSubagentCount,
                    showTerminal = uiState.supportsTerminal,
                    showOverflow = showOverflow,
                    onNavigateBack = onNavigateBack,
                    onStop = { viewModel.abortSession() },
                    onToggleSubagents = { showSubagentDrawer = !showSubagentDrawer },
                    onOpenTerminal = { isTerminalMode = true },
                    onOpenOverflow = { showMenu = true },
                    overflowMenu = { headerDensity ->
                        val isAmoled = isAmoledTheme()
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null
                        ) {
                            ChatHeaderCompactOverflowActions(
                                density = headerDensity,
                                showSubagents = true,
                                runningSubagentCount = runningSubagentCount,
                                showTerminal = uiState.supportsTerminal,
                                onToggleSubagents = {
                                    showMenu = false
                                    showSubagentDrawer = !showSubagentDrawer
                                },
                                onOpenTerminal = {
                                    showMenu = false
                                    isTerminalMode = true
                                },
                            )
                            if (!uiState.isDsh) DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_copy_web_link)) },
                                onClick = {
                                    showMenu = false
                                    val link = onCopyWebLink()
                                    if (link.isNotBlank()) {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(link))
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Language, contentDescription = null)
                                }
                            )
                            if (uiState.supportsSessionCreate) DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_new_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.createNewSession { session ->
                                        if (session != null) {
                                            onNavigateToSession(session.id, session.directory)
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                }
                            )
                            if (uiState.supportsFork) DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_fork_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.forkSession { session ->
                                        if (session != null) {
                                            onNavigateToSession(session.id, session.directory)
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CopyAll, contentDescription = null)
                                }
                            )
                            if (uiState.supportsCompact) DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_compact_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.compactSession { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Compress, contentDescription = null)
                                }
                            )
                            if (uiState.supportsCommands && !uiState.isDsh) DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_review_changes)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.executeCommand("review") { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.RateReview, contentDescription = null)
                                },
                            )
                            if (!uiState.isDsh) {
                            // Show Share or Unshare depending on current share status
                            if (uiState.shareUrl != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_copy_share_link)) },
                                    onClick = {
                                        showMenu = false
                                        uiState.shareUrl?.let { url ->
                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.CopyAll, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cmd_unshare)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.unshareSession { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.LinkOff, contentDescription = null)
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_share_session)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.shareSession { url ->
                                            coroutineScope.launch {
                                                if (url != null) {
                                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                                } else {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    }
                                )
                            }
                            }
                            if (uiState.supportsRename) DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_rename_session)) },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                            if (!uiState.isDsh) DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_export_session)) },
                                onClick = {
                                    showMenu = false
                                    val slug = uiState.sessionTitle
                                        .take(30)
                                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                        .ifBlank { "session" }
                                    exportLauncher.launch("$slug.json")
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileDownload, contentDescription = null)
                                }
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            val modelLabel = if (uiState.selectedModelId != null && uiState.providers.isNotEmpty()) {
                val provider = uiState.providers.find { it.id == uiState.selectedProviderId }
                val model = provider?.models?.get(uiState.selectedModelId)
                model?.name ?: uiState.selectedModelId ?: ""
            } else ""

            if (!isTerminalMode) {
                val composerIsSending = uiState.isSending && isShellMode
                val sendDisabledReasonResId = when {
                    composerIsSending -> R.string.chat_send_disabled_sending
                    viewModel.sessionId.isBlank() || (isShellMode && isBusy) -> R.string.chat_send_disabled_not_ready
                    else -> null
                }
                val responseDockItems = buildChatResponseDockItems(
                    hasRetry = uiState.sessionStatus is SessionStatus.Retry,
                    permissionIds = uiState.pendingPermissions.map { it.id },
                    questionIds = uiState.pendingQuestions.map { it.id },
                )

                ChatResponseDock(
                    items = responseDockItems,
                    responseContent = { item ->
                        when (item.kind) {
                            ChatResponseDockKind.Retry -> (uiState.sessionStatus as? SessionStatus.Retry)?.let { retry ->
                                RetryStatusBanner(
                                    retry = retry,
                                    isRetryingNow = uiState.isRetryingNow,
                                    onRetryNow = { viewModel.retrySessionNow() },
                                    onStop = { viewModel.abortSession() },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                            ChatResponseDockKind.Permission -> uiState.pendingPermissions
                                .firstOrNull { it.id == item.ownershipId }
                                ?.let { permission ->
                                    PermissionCard(
                                        permission = permission,
                                        onOnce = { viewModel.replyToPermission(permission.id, "once") },
                                        onAlways = { viewModel.replyToPermission(permission.id, "always") },
                                        onReject = { viewModel.replyToPermission(permission.id, "reject") },
                                        alwaysAvailable = uiState.permissionAlwaysAvailable,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }
                            ChatResponseDockKind.Question -> uiState.pendingQuestions
                                .firstOrNull { it.id == item.ownershipId }
                                ?.let { question ->
                                    QuestionCard(
                                        question = question,
                                        onSubmit = { answers -> viewModel.replyToQuestion(question.id, answers) },
                                        onReject = { viewModel.rejectQuestion(question.id) },
                                        unlockToken = uiState.questionUnlockEpoch,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }
                        }
                    },
                    composerContent = {
                        if (uiState.queuedPrompts.isNotEmpty()) {
                            DshQueueDock(
                                items = uiState.queuedPrompts,
                                onSteer = { viewModel.updateDshQueue(it, "steer") },
                                onRemove = { viewModel.updateDshQueue(it, "remove") },
                            )
                        }
                        ChatInputBar(
                textFieldValue = inputText,
                onTextFieldValueChange = { newValue ->
                    val shouldAutoShell = uiState.supportsShell && !isShellMode && newValue.text.startsWith("!")
                    val normalizedValue = if (shouldAutoShell) {
                        val stripped = newValue.text.drop(1).trimStart()
                        val newCursor = (newValue.selection.start - 1).coerceAtLeast(0)
                        TextFieldValue(
                            text = stripped,
                            selection = TextRange(newCursor.coerceAtMost(stripped.length))
                        )
                    } else {
                        newValue
                    }

                    if (shouldAutoShell) {
                        inputMode = ChatInputMode.SHELL.name
                    }

                    inputText = normalizedValue
                    viewModel.updateDraftText(normalizedValue.text)
                    if (isShellMode || shouldAutoShell) {
                        viewModel.clearFileSearch()
                        return@ChatInputBar
                    }
                    val cursorPos = normalizedValue.selection.start
                    val textBefore = normalizedValue.text.substring(0, cursorPos)
                    val atMatch = Regex("@(\\S*)$").find(textBefore)
                    if (atMatch != null) {
                        val query = atMatch.groupValues[1]
                        viewModel.searchFilesForMention(query)
                    } else {
                        viewModel.clearFileSearch()
                    }
                },
                onSend = {
                    val doSend = doSend@{
                        if (hapticEnabled) {
                            @Suppress("DEPRECATION")
                            val flags = android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM, flags)
                            } else {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK, flags)
                            }
                        }
                        val rawText = inputText.text
                        if (rawText.startsWith('/') && uiState.supportsCommands) {
                            val commandText = rawText.removePrefix("/")
                            val commandName = commandText.substringBefore(' ').trim()
                            val arguments = commandText.substringAfter(' ', missingDelimiterValue = "").trim()
                            if (uiState.commands.any { it.name.removePrefix("/") == commandName }) {
                                viewModel.executeCommand(commandName, arguments) { ok ->
                                    if (ok) {
                                        inputText = TextFieldValue("")
                                        viewModel.clearDraft()
                                    } else {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_command_failed, commandName))
                                        }
                                    }
                                }
                                return@doSend
                            }
                        }
                        val shellCommand = when {
                            isShellMode -> rawText.trim()
                            rawText.startsWith("!") -> rawText.drop(1).trimStart()
                            else -> null
                        }
                        if (shellCommand != null) {
                            if (shellCommand.isBlank()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_empty))
                                }
                                return@doSend
                            }
                            if (attachments.isNotEmpty()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_attachments_unsupported))
                                }
                                return@doSend
                            }
                            viewModel.runShellCommand(shellCommand) { ok ->
                                if (ok) {
                                    inputText = TextFieldValue("")
                                    if (isShellMode) {
                                        inputMode = ChatInputMode.NORMAL.name
                                    }
                                    viewModel.clearConfirmedPaths()
                                    viewModel.clearFileSearch()
                                    viewModel.clearDraft()
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_failed))
                                    }
                                }
                            }
                            return@doSend
                        }
                        // Build prompt parts: split text around confirmed @file mentions
                        val allParts = buildPromptParts(rawText, confirmedFilePaths, viewModel.getSessionDirectory())
                        // Add image attachments
                        val attachmentParts = if (uiState.supportsAttachments) {
                            attachments.map { att ->
                                PromptPart(
                                    type = "file",
                                    mime = att.mime,
                                    url = att.dataUrl,
                                    filename = att.filename
                                )
                            }
                        } else {
                            emptyList()
                        }
                        viewModel.sendMessage(allParts, attachmentParts) { ok ->
                            if (ok) {
                                inputText = TextFieldValue("")
                                attachments.clear()
                                viewModel.clearConfirmedPaths()
                                viewModel.clearFileSearch()
                                viewModel.clearDraft()
                            } else {
                                viewModel.updateDraftText(rawText)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_send_failed_draft_kept))
                                }
                            }
                        }
                    }
                    if (confirmBeforeSend) {
                        pendingSendAction = doSend
                        showSendConfirmDialog = true
                    } else {
                        doSend()
                    }
                },
                inputMode = if (isShellMode) ChatInputMode.SHELL else ChatInputMode.NORMAL,
                onInputModeChange = {
                    if (uiState.supportsShell || it != ChatInputMode.SHELL) {
                        inputMode = it.name
                        if (it == ChatInputMode.SHELL) {
                            viewModel.clearFileSearch()
                        }
                    }
                },
                supportsShell = uiState.supportsShell,
                supportsCompact = uiState.supportsCompact,
                isSending = composerIsSending,
                isBusy = uiState.sessionStatus is SessionStatus.Busy,
                sendDisabledReasonResId = sendDisabledReasonResId,
                messages = uiState.messages,
                attachments = if (uiState.supportsAttachments) attachments else emptyList(),
                onAttach = { imagePickerLauncher.launch("image/*") },
                supportsAttachments = uiState.supportsAttachments,
                onRemoveAttachment = { index ->
                    if (index in attachments.indices) {
                        attachments.removeAt(index)
                        viewModel.removeDraftAttachment(index)
                    }
                },
                onSaveAttachment = { bytes, mime, filename ->
                    requestSaveImage(bytes, mime, filename)
                },
                modelLabel = if (uiState.supportsModelSelection) modelLabel else "",
                selectedProviderId = uiState.selectedProviderId,
                onModelClick = { showModelPicker = true },
                agents = uiState.agents,
                selectedAgent = uiState.selectedAgent,
                onAgentClick = { showAgentPicker = true },
                variantNames = if (uiState.supportsThinkingSelection) uiState.variantNames else emptyList(),
                selectedVariant = uiState.selectedVariant,
                onVariantClick = { showVariantPicker = true },
                commands = if (uiState.supportsCommands) uiState.commands else emptyList(),
                fileSearchResults = fileSearchResults,
                confirmedFilePaths = confirmedFilePaths,
                onFileSelected = { path ->
                    // Replace @query with @path in text
                    val cursorPos = inputText.selection.start
                    val textBefore = inputText.text.substring(0, cursorPos)
                    val atMatch = Regex("@(\\S*)$").find(textBefore)
                    if (atMatch != null) {
                        val matchStart = atMatch.range.first
                        val replacement = "@$path "
                        val newText = inputText.text.substring(0, matchStart) + replacement +
                                inputText.text.substring(cursorPos)
                        val newCursor = matchStart + replacement.length
                        inputText = TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor)
                        )
                    }
                    viewModel.confirmFilePath(path)
                    viewModel.clearFileSearch()
                },
                onSlashCommand = { cmd ->
                    when (cmd.name) {
                        "new" -> {
                            // Create a new session and navigate to it
                            viewModel.createNewSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id, session.directory)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
                                    }
                                }
                            }
                        }
                        "compact" -> {
                            viewModel.compactSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                    )
                                }
                            }
                        }
                        "fork" -> {
                            viewModel.forkSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id, session.directory)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                    }
                                }
                            }
                        }
                        "share" -> {
                            viewModel.shareSession { url ->
                                coroutineScope.launch {
                                    if (url != null) {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                    }
                                }
                            }
                        }
                        "unshare" -> {
                            viewModel.unshareSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                    )
                                }
                            }
                        }
                        "undo" -> {
                            viewModel.undoMessage { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_message_undone) else context.getString(R.string.chat_message_undo_failed)
                                    )
                                }
                            }
                        }
                        "redo" -> {
                            viewModel.redoMessage { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_message_redone) else context.getString(R.string.chat_message_redo_failed)
                                    )
                                }
                            }
                        }
                        "rename" -> {
                            showRenameDialog = true
                        }
                        "shell" -> {
                            inputMode = ChatInputMode.SHELL.name
                        }
                        "review" -> {
                            viewModel.executeCommand("review") { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                    )
                                }
                            }
                        }
                        else -> {
                            // Server command — execute via API
                            viewModel.executeCommand(cmd.name) { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, cmd.name) else context.getString(R.string.chat_command_failed, cmd.name)
                                    )
                                }
                            }
                        }
                    }
                },
                contextWindow = uiState.contextWindow,
                lastContextTokens = uiState.lastContextTokens
            )
                    },
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isTerminalMode -> {
                    // IME inset relative to content area. Some devices report 0 for
                    // ime.exclude(navigationBars), so keep a robust fallback to raw ime.
                    val imeBottomRaw = WindowInsets.ime.getBottom(density)
                    val navBottom = WindowInsets.navigationBars.getBottom(density)
                    val imeBottomPx = (imeBottomRaw - navBottom).coerceAtLeast(0).let { adjusted ->
                        if (adjusted == 0 && imeBottomRaw > 0) imeBottomRaw else adjusted
                    }
                    val imeBottomDp = with(density) { imeBottomPx.toDp() }
                    val overlayHeightDp = with(density) { terminalOverlayHeightPx.toDp() }

                    ModalNavigationDrawer(
                        drawerState = terminalDrawerState,
                        gesturesEnabled = true,
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerContainerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                                drawerTonalElevation = 0.dp,
                                drawerShape = RoundedCornerShape(0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .widthIn(min = 240.dp, max = 320.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(vertical = 8.dp)
                                            .imePadding(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        items(terminalTabs, key = { it.id }) { tab ->
                                            val selected = tab.id == activeTerminalTabId
                                            val drawerItemShape = RoundedCornerShape(12.dp)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(drawerItemShape)
                                                    .then(
                                                        if (isAmoled && selected) {
                                                            Modifier.border(
                                                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                                                drawerItemShape
                                                            )
                                                        } else Modifier
                                                    )
                                            ) {
                                                NavigationDrawerItem(
                                                    label = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.weight(1f),
                                                                verticalArrangement = Arrangement.spacedBy(3.dp)
                                                            ) {
                                                                Text(
                                                                    text = tab.title,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    style = MaterialTheme.typography.titleMedium,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                                if (!tab.connected) {
                                                                    Surface(
                                                                        shape = RoundedCornerShape(999.dp),
                                                                        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                                                    ) {
                                                                        Row(
                                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                                            verticalAlignment = Alignment.CenterVertically,
                                                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                                        ) {
                                                                            Box(
                                                                                modifier = Modifier
                                                                                    .size(6.dp)
                                                                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                                                                            )
                                                                            Text(
                                                                                text = "Offline",
                                                                                style = MaterialTheme.typography.labelSmall,
                                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            if (!tab.connected) {
                                                                IconButton(
                                                                    onClick = {
                                                                        viewModel.reconnectTerminalTab(tab.id) { ok ->
                                                                            if (!ok) {
                                                                                coroutineScope.launch {
                                                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                                                                                }
                                                                            }
                                                                        }
                                                                    },
                                                                    modifier = Modifier.size(34.dp),
                                                                    colors = IconButtonDefaults.iconButtonColors(
                                                                        containerColor = if (isAmoled) {
                                                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                                                        } else {
                                                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                                                        }
                                                                    )
                                                                ) {
                                                                    Icon(Icons.Default.Refresh, contentDescription = "Reconnect tab")
                                                                }
                                                            }
                                                            IconButton(
                                                                onClick = { viewModel.closeTerminalTab(tab.id) },
                                                                modifier = Modifier.size(34.dp),
                                                                colors = IconButtonDefaults.iconButtonColors(
                                                                    containerColor = if (isAmoled) {
                                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                                                    } else {
                                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                                                    }
                                                                )
                                                            ) {
                                                                Icon(Icons.Default.Close, contentDescription = "Close tab")
                                                            }
                                                        }
                                                    },
                                                    selected = selected,
                                                    shape = drawerItemShape,
                                                    colors = NavigationDrawerItemDefaults.colors(
                                                        selectedContainerColor = if (isAmoled) {
                                                            Color.Black
                                                        } else {
                                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                                        },
                                                        unselectedContainerColor = if (isAmoled) Color.Black else Color.Transparent,
                                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                        unselectedTextColor = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    onClick = {
                                                        viewModel.switchTerminalTab(tab.id)
                                                        coroutineScope.launch { terminalDrawerState.close() }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.createTerminalTab { ok ->
                                                    if (!ok) {
                                                        coroutineScope.launch {
                                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("New")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                keyboardController?.show()
                                                coroutineScope.launch { terminalDrawerState.close() }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        ) {
                                            Icon(Icons.Default.Keyboard, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Keyboard")
                                        }
                                    }

                                    }

                                    if (isAmoled) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .fillMaxHeight()
                                                .width(1.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SessionTerminalInline(
                                emulator = viewModel.terminalEmulator,
                                terminalVersion = terminalVersion,
                                connected = terminalConnected,
                                focusRequester = terminalFocusRequester,
                                onSendInput = ::sendTerminalChunk,
                                onPaste = ::pasteClipboardToTerminal,
                                onResize = { cols, rows ->
                                    viewModel.resizeTerminal(cols, rows)
                                },
                                fontSizeSp = terminalFontSizeSp,
                                onFontSizeChange = viewModel::setTerminalFontSize,
                                contentBottomPadding = overlayHeightDp + imeBottomDp,
                                modifier = Modifier.fillMaxSize()
                            )

                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .padding(bottom = overlayHeightDp + imeBottomDp)
                                    .width(18.dp)
                                    .zIndex(0f)
                                    .pointerInput(terminalDrawerState) {
                                        detectTapGestures(
                                            onLongPress = {
                                                if (!terminalDrawerState.isOpen) {
                                                    coroutineScope.launch { terminalDrawerState.open() }
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(terminalDrawerState) {
                                        var dragged = 0f
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { _, dragAmount ->
                                                if (terminalDrawerState.isOpen) return@detectHorizontalDragGestures
                                                dragged += dragAmount
                                                if (dragged > 2f) {
                                                    coroutineScope.launch { terminalDrawerState.open() }
                                                    dragged = 0f
                                                }
                                            },
                                            onDragEnd = { dragged = 0f },
                                            onDragCancel = { dragged = 0f }
                                        )
                                    }
                            )

                        TerminalKeyboardOverlay(
                            connected = terminalConnected,
                            ctrlLatched = terminalCtrlLatched,
                            altLatched = terminalAltLatched,
                            cursorApp = viewModel.terminalEmulator.cursorKeysApplicationMode,
                            onToggleDrawer = { coroutineScope.launch { terminalDrawerState.apply { if (isOpen) close() else open() } } },
                            onToggleCtrl = { terminalCtrlLatched = !terminalCtrlLatched },
                            onToggleAlt = { terminalAltLatched = !terminalAltLatched },
                            onSendInput = ::sendTerminalChunk,
                            onCtrlC = { viewModel.sendTerminalInput("\u0003") },
                            onClear = { viewModel.clearTerminalBuffer() },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(1f)
                                    .fillMaxWidth()
                                    .padding(bottom = imeBottomDp)
                                    .onSizeChanged { terminalOverlayHeightPx = it.height }
                            )

                        }
                    }
                }
                uiState.isLoading && uiState.messages.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PulsingDotsIndicator()
                        Text(
                            text = stringResource(R.string.chat_loading_messages),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                 uiState.error != null && uiState.messages.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        ErrorPayloadContent(
                            text = uiState.error ?: stringResource(R.string.session_unknown_error),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            textColor = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { viewModel.loadMessages() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                uiState.messages.isEmpty() && !uiState.isLoading && pendingCount == 0 -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.chat_type_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                else -> {
                    val messageSpacing = if (LocalCompactMessages.current) 4.dp else 12.dp
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                        // "Load earlier messages" button at the top
                    if (uiState.hasOlderMessages) {
                        item(key = "load_older") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = messageSpacing),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (uiState.isLoadingOlder) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            PulsingDotsIndicator(
                                                dotSize = 6.dp,
                                                dotSpacing = 4.dp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = stringResource(R.string.chat_loading_earlier),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        TextButton(onClick = { viewModel.loadOlderMessages() }) {
                                            Text(stringResource(R.string.chat_load_earlier))
                                        }
                                    }
                                }
                            }
                        }

                        itemsIndexed(
                            messageRows,
                            key = { _, row -> row.key }
                        ) { rowIndex, messageRow ->
                            val index = messageRow.sourceMessageIndex
                            val chatMessage = messageRow.chatMessage
                            val isFirstMessageRow = messageRow.position == ChatMessageSegmentPosition.Single ||
                                messageRow.position == ChatMessageSegmentPosition.First
                            val nextBelongsToSameMessage = messageRows.getOrNull(rowIndex + 1)
                                ?.chatMessage?.message?.id == chatMessage.message.id
                            // Detect compaction trigger messages (user messages with Part.Compaction)
                            val isCompactionTrigger = isFirstMessageRow && chatMessage.isUser &&
                                chatMessage.parts.any { it is Part.Compaction }

                            // Show compact system-style divider for compaction triggers
                            // Long-press to revert (undo compaction and subsequent messages)
                            if (isCompactionTrigger) {
                                var showRevertDialog by remember { mutableStateOf(false) }

                                if (showRevertDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showRevertDialog = false },
                                        title = { Text(stringResource(R.string.chat_revert_title)) },
                                        text = { Text(stringResource(R.string.chat_revert_message)) },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    showRevertDialog = false
                                                    viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                        coroutineScope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                            )
                                                        }
                                                    }
                                                }
                                            ) {
                                                Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showRevertDialog = false }) {
                                                Text(stringResource(R.string.cancel))
                                            }
                                        }
                                    )
                                }

                                @OptIn(ExperimentalFoundationApi::class)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { },
                                            onLongClick = { showRevertDialog = true }
                                        )
                                        .padding(vertical = 4.dp, horizontal = 32.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_summarized),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                                return@itemsIndexed
                            }

                            Box(
                                modifier = Modifier.padding(
                                    bottom = if (nextBelongsToSameMessage) 1.dp else messageSpacing,
                                ),
                            ) {
                                when (messageRow) {
                                    is ChatMessageRow.Think -> ThinkProcessRow(messageRow.part)
                                    is ChatMessageRow.Skill -> SkillProcessRow(messageRow.part)
                                    is ChatMessageRow.Tool -> PartContent(
                                        part = messageRow.part,
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                        isUser = false,
                                    )
                                    is ChatMessageRow.Content -> PartContent(
                                        part = messageRow.part,
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                        isUser = false,
                                    )
                                    is ChatMessageRow.Whole,
                                    is ChatMessageRow.TextChunk -> ChatMessageBubble(
                                        chatMessage = chatMessage,
                                        showSenderHeader = isFirstMessageRow && !isSamePiSender(
                                            current = chatMessage,
                                            previous = uiState.messages.getOrNull(index - 1),
                                        ),
                                        segmentPosition = messageRow.position,
                                        plannedBlock = (messageRow as? ChatMessageRow.TextChunk)?.markdown,
                                        showAssistantMeta = when (messageRow) {
                                            is ChatMessageRow.TextChunk ->
                                                messageRow.position == ChatMessageSegmentPosition.Last ||
                                                    messageRow.position == ChatMessageSegmentPosition.Single
                                            is ChatMessageRow.Whole -> chatMessage.isAssistant
                                            else -> false
                                        },
                                        onRevert = if (chatMessage.isUser) {
                                            {
                                                val revertText = chatMessage.parts
                                                    .filterIsInstance<Part.Text>()
                                                    .joinToString("\n") { it.text }
                                                viewModel.abortSession()
                                                viewModel.revertMessage(chatMessage.message.id, revertText) { ok ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                        )
                                                    }
                                                }
                                            }
                                        } else null,
                                        onCopyText = {
                                            val text = chatMessage.parts
                                                .filterIsInstance<Part.Text>()
                                                .joinToString("\n") { it.text }
                                            if (text.isNotBlank()) {
                                                clipboardManager.setText(
                                                    androidx.compose.ui.text.AnnotatedString(text)
                                                )
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                }
                                            }
                                        },
                                        onMessageActionsRequested = { actionMenuMessage = it }
                                    )
                                }
                            }
                        }

                        // Revert banner
                        if (uiState.revert != null) {
                            item(key = "revert_banner") {
                                Box(modifier = Modifier.padding(bottom = messageSpacing)) {
                                    RevertBanner(onRedo = {
                                        viewModel.redoMessage { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                )
                                            }
                                        }
                                    })
                                }
                            }
                        }

                    }
                    }

                    if (!isAtBottom && followTailState.showAffordance) {
                        ChatTailAffordance(
                            hasNewContent = followTailState.hasNewContent,
                            onClick = {
                                coroutineScope.launch {
                                    val transition = ChatFollowTailPolicy.onReturnToTail()
                                    followTailState = transition.state
                                    if (transition.scrollToTail) scrollToTail()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
        },
        contextContent = { contextModifier ->
            ChatSubagentDrawer(
                items = uiState.subagents,
                onDismiss = { showSubagentDrawer = false },
                onOpenSession = { item ->
                    showSubagentDrawer = false
                    onNavigateToSession(item.id, item.directory)
                },
                modifier = contextModifier,
            )
        },
    )

    if (!showSubagentDrawer && !isTerminalMode && true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.End)
                .fillMaxHeight()
                .width(20.dp)
                .pointerInput(Unit) {
                    var horizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, amount ->
                            horizontalDrag += amount
                            if (horizontalDrag < -32.dp.toPx()) {
                                showSubagentDrawer = true
                                horizontalDrag = 0f
                            }
                        },
                        onDragEnd = { horizontalDrag = 0f },
                        onDragCancel = { horizontalDrag = 0f },
                    )
                },
        )
    }

    // Model picker dialog
    if (showModelPicker) {
        ModelPickerDialog(
            providers = uiState.providers,
            selectedProviderId = uiState.selectedProviderId,
            selectedModelId = uiState.selectedModelId,
            onSelect = { providerId, modelId ->
                viewModel.selectModel(providerId, modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    if (showAgentPicker) {
        val options = uiState.agents.map { agent ->
            ChoicePickerOption(
                key = agent.name,
                title = agent.name.replaceFirstChar { it.uppercase() },
                subtitle = agent.description,
                accentColor = agentColor(agent.name, uiState.agents),
            )
        }
        ChoicePickerDialog(
            title = stringResource(R.string.chat_agent_picker_title),
            options = options,
            selectedKey = uiState.selectedAgent,
            onSelect = {
                viewModel.selectAgent(it)
                showAgentPicker = false
            },
            onDismiss = { showAgentPicker = false },
        )
    }

    if (showVariantPicker) {
        val defaultKey = "__default__"
        val options = buildList {
            if (true) {
                add(
                    ChoicePickerOption(
                        key = defaultKey,
                        title = stringResource(R.string.chat_default_variant),
                    )
                )
            }
            addAll(
                uiState.variantNames.map { variant ->
                    ChoicePickerOption(
                        key = variant,
                        title = variant.replaceFirstChar { it.uppercase() },
                    )
                }
            )
        }
        ChoicePickerDialog(
            title = stringResource(R.string.chat_variant_picker_title),
            options = options,
            selectedKey = uiState.selectedVariant ?: options.firstOrNull()?.key.orEmpty(),
            onSelect = {
                viewModel.selectVariant(it.takeUnless { key -> key == defaultKey })
                showVariantPicker = false
            },
            onDismiss = { showVariantPicker = false },
        )
    }

    actionMenuMessage?.let { selectedMessage ->
        val selectedMessageStreaming = selectedMessage.parts.any { part ->
            part is Part.Tool && part.state is ToolState.Running
        }
        MessageActionMenu(
            actions = buildMessageCardActions(
                chatMessage = selectedMessage,
                selectedMessageStreaming = selectedMessageStreaming,
                sessionBusy = sessionBusyForMessageActions,
                sessionReady = sessionReadyForMessageActions,
                supportsFork = uiState.supportsFork,
                supportsRestore = uiState.supportsRestore,
            ),
            onActionSelected = { action ->
                actionMenuMessage = null
                when (action) {
                    MessageCardAction.CopyText -> {
                        clipboardManager.setText(AnnotatedString(messagePlainText(selectedMessage)))
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.message_action_copied))
                        }
                    }
                    MessageCardAction.CopyMarkdown -> {
                        clipboardManager.setText(AnnotatedString(messageMarkdown(selectedMessage)))
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.message_action_copied))
                        }
                    }
                    MessageCardAction.QuoteIntoInput -> {
                        val quotedText = quoteMessageText(selectedMessage)
                        val updatedText = if (inputText.text.isBlank()) {
                            quotedText
                        } else {
                            inputText.text + "\n\n" + quotedText
                        }
                        inputText = TextFieldValue(updatedText, TextRange(updatedText.length))
                        viewModel.updateDraftText(updatedText)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.message_action_quoted))
                        }
                    }
                    MessageCardAction.RestoreToInput -> {
                        val restoredText = messagePlainText(selectedMessage)
                        inputText = TextFieldValue(restoredText, TextRange(restoredText.length))
                        viewModel.updateDraftText(restoredText)
                        viewModel.clearFileSearch()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.message_action_restored_input))
                        }
                    }
                    MessageCardAction.ForkFromHere -> {
                        val messageId = selectedMessage.message.id
                        if (messageId.isBlank() || forkFromMessageInFlight) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.message_action_fork_failed))
                            }
                        } else {
                            forkFromMessageInFlight = true
                            viewModel.forkSessionFromMessage(messageId) { session ->
                                forkFromMessageInFlight = false
                                if (session != null) {
                                    onNavigateToSession(session.id, session.directory)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.message_action_fork_failed))
                                    }
                                }
                            }
                        }
                    }
                    MessageCardAction.RestoreToHere -> {
                        restoreConfirmMessage = selectedMessage
                    }
                }
            },
            onDismiss = { actionMenuMessage = null },
        )
    }

    restoreConfirmMessage?.let { selectedMessage ->
        AlertDialog(
            onDismissRequest = { restoreConfirmMessage = null },
            title = { Text(stringResource(R.string.message_action_restore_confirm_title)) },
            text = { Text(stringResource(R.string.message_action_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val messageId = selectedMessage.message.id
                        val revertedText = if (selectedMessage.isUser) messagePlainText(selectedMessage) else null
                        restoreConfirmMessage = null
                        viewModel.revertMessage(messageId, revertedText) { ok ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                )
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreConfirmMessage = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (forkFromMessageInFlight) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.message_action_fork_from_here)) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.message_action_fork_from_here),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(uiState.sessionTitle) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.session_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_rename_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(renameText) { ok ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) context.getString(R.string.chat_session_renamed) else context.getString(R.string.chat_session_rename_failed)
                                )
                            }
                        }
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text(stringResource(R.string.session_rename_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Send confirmation dialog
    if (showSendConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showSendConfirmDialog = false
                pendingSendAction = null
            },
            title = { Text(stringResource(R.string.settings_confirm_send_title)) },
            text = { Text(stringResource(R.string.settings_confirm_send_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSendConfirmDialog = false
                    pendingSendAction?.invoke()
                    pendingSendAction = null
                }) {
                    Text(stringResource(R.string.settings_send))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSendConfirmDialog = false
                    pendingSendAction = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    } // CompositionLocalProvider
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerDialog(
    providers: List<ProviderInfo>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    fun isModelFree(providerId: String, model: ProviderModel): Boolean {
        if (providerId != "opencode") return false
        val cost = model.cost ?: return true
        return cost.input == 0.0
    }

    // Sort providers: "opencode" first, then by name
    val sortedProviders = remember(providers) {
        providers
            .filter { it.models.isNotEmpty() }
            .sortedWith(compareBy<ProviderInfo> { it.id != "opencode" }.thenBy { it.name.lowercase() })
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                for ((index, provider) in sortedProviders.withIndex()) {
                    val topPad = if (index == 0) 0.dp else 12.dp

                    val sortedModels = provider.models.values
                        .sortedWith(compareBy<ProviderModel> { !isModelFree(provider.id, it) }.thenBy { it.name.lowercase() })

                    item(key = "provider_header_${provider.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topPad, bottom = 2.dp, start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProviderIcon(
                                providerId = provider.id,
                                size = 14.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = (provider.name.ifEmpty { provider.id }).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    items(
                        sortedModels,
                        key = { "model_${provider.id}_${it.id}" }
                    ) { model ->
                        val isSelected = provider.id == selectedProviderId && model.id == selectedModelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable { onSelect(provider.id, model.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.name.ifEmpty { model.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isModelFree(provider.id, model)) {
                                    Text(
                                        text = stringResource(R.string.chat_free_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ChoicePickerOption(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val accentColor: Color? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoicePickerDialog(
    title: String,
    options: List<ChoicePickerOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isAmoled = isAmoledTheme()

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(options, key = { it.key }) { option ->
                        val isSelected = option.key == selectedKey
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable { onSelect(option.key) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = option.accentColor ?: if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                option.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTerminalInline(
    emulator: TerminalEmulator,
    terminalVersion: Long,
    connected: Boolean,
    focusRequester: FocusRequester,
    onSendInput: (String) -> Unit,
    onPaste: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    val keyboard = LocalSoftwareKeyboardController.current
    val baseTextToolbar = LocalTextToolbar.current
    var inputCapture by remember { mutableStateOf(TextFieldValue("")) }
    val terminalScrollState = rememberScrollState()
    var terminalFollowMode by rememberSaveable { mutableStateOf(true) }
    // Dedup: some IMEs can fire onValueChange twice for a single keystroke.
    // Track the last chunk + timestamp to suppress duplicates.
    var lastSentChunk by remember { mutableStateOf("") }
    var lastSentTime by remember { mutableStateOf(0L) }

    val terminalTextToolbar = remember(baseTextToolbar, onPaste) {
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = baseTextToolbar.status

            override fun hide() {
                baseTextToolbar.hide()
            }

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                baseTextToolbar.showMenu(
                    rect = rect,
                    onCopyRequested = onCopyRequested,
                    onPasteRequested = {
                        onPaste()
                        onPasteRequested?.invoke()
                    },
                    onCutRequested = onCutRequested,
                    onSelectAllRequested = onSelectAllRequested
                )
            }
        }
    }

    val terminalStyle = remember(fontSizeSp) {
        CodeTypography.copy(
            fontSize = fontSizeSp.sp,
            // Tight line spacing is required for continuous box-drawing in TUIs (mc, htop).
            lineHeight = fontSizeSp.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }
    val latestFontSizeSp by rememberUpdatedState(fontSizeSp)

    Column(
        modifier = modifier
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = inputCapture,
            onValueChange = { next ->
                if (!connected) {
                    inputCapture = TextFieldValue("")
                    return@BasicTextField
                }
                val old = inputCapture.text
                val now = next.text
                val delta = when {
                    now.startsWith(old) -> now.drop(old.length)
                    old.startsWith(now) -> "\u007F".repeat((old.length - now.length).coerceAtLeast(0))
                    else -> now
                }
                if (delta.isNotEmpty()) {
                    if (BuildConfig.DEBUG && delta.contains('~')) {
                        Log.d("TerminalInput", "onValueChange: delta='$delta' old='$old' now='$now'")
                    }
                    // Dedup: suppress identical chunk within 100ms (IME double-fire).
                    val ts = SystemClock.elapsedRealtime()
                    if (delta == lastSentChunk && ts - lastSentTime < 100) {
                        if (BuildConfig.DEBUG) {
                            Log.d("TerminalInput", "DEDUP: suppressed duplicate delta='$delta'")
                        }
                        inputCapture = next.copy(selection = TextRange(next.text.length))
                        return@BasicTextField
                    }
                    lastSentChunk = delta
                    lastSentTime = ts
                    val mapped = delta
                        .replace("\r\n", "\r")
                        .replace('\n', '\r')
                    onSendInput(mapped)
                }
                // Keep IME context (caps/symbol lock, composing state) stable by
                // preserving TextFieldValue instead of clearing it after each key.
                inputCapture = next.copy(selection = TextRange(next.text.length))
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onSendInput("\r")
                            true
                        }
                        Key.Tab -> {
                            onSendInput("\t")
                            true
                        }
                        Key.Backspace -> {
                            onSendInput("\u007F")
                            true
                        }
                        else -> {
                            val native = event.nativeKeyEvent
                            val unicode = native.unicodeChar
                            if (unicode > 0 && (unicode and android.view.KeyCharacterMap.COMBINING_ACCENT) == 0) {
                                if (native.isCtrlPressed) {
                                    val lower = unicode.toChar().lowercaseChar()
                                    if (lower in 'a'..'z') {
                                        val ctrl = (lower.code - 'a'.code + 1).toChar().toString()
                                        onSendInput(ctrl)
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    onSendInput(String(Character.toChars(unicode)))
                                    true
                                }
                            } else {
                                val baseLetter = when (event.key) {
                                    Key.A -> 'a'
                                    Key.B -> 'b'
                                    Key.C -> 'c'
                                    Key.D -> 'd'
                                    Key.E -> 'e'
                                    Key.F -> 'f'
                                    Key.G -> 'g'
                                    Key.H -> 'h'
                                    Key.I -> 'i'
                                    Key.J -> 'j'
                                    Key.K -> 'k'
                                    Key.L -> 'l'
                                    Key.M -> 'm'
                                    Key.N -> 'n'
                                    Key.O -> 'o'
                                    Key.P -> 'p'
                                    Key.Q -> 'q'
                                    Key.R -> 'r'
                                    Key.S -> 's'
                                    Key.T -> 't'
                                    Key.U -> 'u'
                                    Key.V -> 'v'
                                    Key.W -> 'w'
                                    Key.X -> 'x'
                                    Key.Y -> 'y'
                                    Key.Z -> 'z'
                                    else -> null
                                }
                                if (baseLetter != null) {
                                    val upper = native.isShiftPressed.xor(native.isCapsLockOn)
                                    val out = if (upper) baseLetter.uppercaseChar() else baseLetter
                                    if (native.isCtrlPressed) {
                                        val ctrl = (baseLetter.code - 'a'.code + 1).toChar().toString()
                                        onSendInput(ctrl)
                                    } else {
                                        onSendInput(out.toString())
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                },
            singleLine = false,
            textStyle = terminalStyle,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { onSendInput("\r") },
                onDone = { onSendInput("\r") },
                onGo = { onSendInput("\r") }
            )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = contentBottomPadding)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    )
                }
        ) {
            // Measure character dimensions using native Paint for consistency with
            // Canvas rendering. This avoids mismatches between Compose textMeasurer
            // line height and native Paint font metrics that cause vertical gaps.
            val density = LocalDensity.current
            if (BuildConfig.DEBUG) {
                Log.d("TerminalZoom", "BoxWithConstraints recompose: fontSizeSp=$fontSizeSp connected=$connected viewW=${constraints.maxWidth} viewH=${constraints.maxHeight}")
            }
            val charWidthPx = remember(fontSizeSp) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = with(density) { fontSizeSp.sp.toPx() }
                }
                paint.measureText("X").also { w ->
                    if (BuildConfig.DEBUG) {
                        Log.d("TerminalZoom", "charWidthPx RECOMPUTED: fontSizeSp=$fontSizeSp -> charW=$w textSizePx=${paint.textSize}")
                    }
                }
            }
            // Row height: ceil(descent - ascent) snapped to int pixels.
            // This excludes inter-line leading so rows are compact and fill
            // the viewport correctly.  Anti-aliased seams are prevented by
            // drawing with nativeCanvas + isAntiAlias=false.
            val rowHeightPx = remember(fontSizeSp) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = with(density) { fontSizeSp.sp.toPx() }
                }
                val fm = paint.fontMetrics
                kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt().also { h ->
                    if (BuildConfig.DEBUG) {
                        Log.d("TerminalZoom", "rowHeightPx RECOMPUTED: fontSizeSp=$fontSizeSp -> rowH=$h textSizePx=${paint.textSize}")
                    }
                }
            }
            // Use inner constraints from BoxWithConstraints (already reflects bottom padding).
            val viewportWidthPx = constraints.maxWidth
            val viewportHeightPx = constraints.maxHeight
            val termCols = if (viewportWidthPx > 0) {
                (viewportWidthPx / charWidthPx).toInt().coerceAtLeast(20)
            } else 80
            // Simple integer division — our rows start at y=0 so no offset needed.
            val termRows = if (viewportHeightPx > 0) {
                (viewportHeightPx / rowHeightPx).coerceAtLeast(8)
            } else 24
            val maxScrollbackOffsetRows = remember(terminalVersion, termRows) {
                emulator.maxScrollbackOffset(termRows)
            }
            val totalRows = remember(terminalVersion) {
                emulator.totalRowsWithScrollback().coerceAtLeast(1)
            }
            val renderedOutput = remember(terminalVersion, totalRows) {
                emulator.render(
                    scrollbackOffsetRows = 0,
                    windowRows = totalRows,
                )
            }
            val renderedRuns = remember(terminalVersion, totalRows) {
                emulator.renderRuns(
                    scrollbackOffsetRows = 0,
                    windowRows = totalRows,
                )
            }
            val maxScrollPx = maxScrollbackOffsetRows * rowHeightPx
            val followThresholdPx = (rowHeightPx * 2).coerceAtLeast(1)
            val isNearBottom = terminalScrollState.value >= (maxScrollPx - followThresholdPx).coerceAtLeast(0)
            LaunchedEffect(isNearBottom) {
                if (isNearBottom) {
                    terminalFollowMode = true
                }
            }
            LaunchedEffect(maxScrollPx, terminalVersion, terminalFollowMode) {
                when {
                    terminalFollowMode -> {
                        if (terminalScrollState.value != maxScrollPx) {
                            terminalScrollState.scrollTo(maxScrollPx)
                        }
                    }
                    terminalScrollState.value > maxScrollPx -> {
                        terminalScrollState.scrollTo(maxScrollPx)
                    }
                }
            }
            val firstVisibleRow = (terminalScrollState.value / rowHeightPx)
                .coerceIn(0, maxScrollbackOffsetRows)
            val scrollbackOffsetRows = (maxScrollbackOffsetRows - firstVisibleRow).coerceAtLeast(0)
            val verticalOffsetPx = firstVisibleRow * rowHeightPx
            if (BuildConfig.DEBUG) {
                Log.d("TerminalZoom", "GRID CALC: fontSp=$fontSizeSp charW=$charWidthPx rowH=$rowHeightPx viewW=$viewportWidthPx viewH=$viewportHeightPx -> cols=$termCols rows=$termRows")
            }
            // Send resize immediately then retry after a short delay to handle
            // race conditions around PTY startup and IME transitions.
            LaunchedEffect(termCols, termRows, connected) {
                if (BuildConfig.DEBUG) {
                    Log.d("TerminalZoom", "LaunchedEffect FIRED: cols=$termCols rows=$termRows connected=$connected viewW=$viewportWidthPx viewH=$viewportHeightPx fontSp=$fontSizeSp")
                }
                if (connected && viewportWidthPx > 0 && viewportHeightPx > 0) {
                    if (BuildConfig.DEBUG) {
                        Log.d("TerminalInput", "resize: cols=$termCols rows=$termRows viewW=$viewportWidthPx viewH=$viewportHeightPx charW=$charWidthPx rowH=$rowHeightPx fontSp=$fontSizeSp")
                    }
                    onResize(termCols, termRows)
                    delay(120)
                    onResize(termCols, termRows)
                }
            }

            val cursorPos = remember(terminalVersion, scrollbackOffsetRows, termRows) {
                emulator.getCursorPositionInWindow(
                    scrollbackOffsetRows = scrollbackOffsetRows,
                    windowRows = termRows,
                )
            }
            val cursorAnim = rememberInfiniteTransition(label = "terminal_cursor")
            val cursorAlpha by cursorAnim.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "terminal_cursor_alpha"
            )

            val terminalBgColor = Color.Black
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(rowHeightPx, maxScrollbackOffsetRows) {
                        var accumulatedScale = 1f
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f) {
                                accumulatedScale *= zoom
                                if (BuildConfig.DEBUG) {
                                    Log.d("TerminalZoom", "gesture: zoom=$zoom accumulated=$accumulatedScale")
                                }
                                if (accumulatedScale < 0.9f || accumulatedScale > 1.1f) {
                                    val increase = accumulatedScale > 1f
                                    val current = latestFontSizeSp
                                    val next = (current + if (increase) 1f else -1f)
                                        .coerceIn(6f, 20f)
                                    if (BuildConfig.DEBUG) {
                                        Log.d("TerminalZoom", "threshold hit: increase=$increase current=$current next=$next")
                                    }
                                    if (next != current) {
                                        onFontSizeChange(next)
                                    }
                                    accumulatedScale = 1f
                                }
                            }

                            if (maxScrollbackOffsetRows > 0 && pan.y != 0f) {
                                terminalScrollState.dispatchRawDelta(-pan.y)
                                val nearBottomAfterPan = terminalScrollState.value >=
                                    (maxScrollPx - followThresholdPx).coerceAtLeast(0)
                                terminalFollowMode = nearBottomAfterPan
                            }
                        }
                    }
            ) {
                // Canvas layer: draw each character at its exact grid position to
                // guarantee monospaced alignment for box-drawing characters.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas

                    // Paint for background fills — no anti-aliasing for pixel-perfect
                    // row tiling (matches Termux approach).
                    val bgPaint = android.graphics.Paint().apply {
                        isAntiAlias = false
                        style = android.graphics.Paint.Style.FILL
                    }

                    // Fill the entire terminal area with the default background.
                    bgPaint.color = terminalBgColor.toArgb()
                    nativeCanvas.drawRect(0f, 0f, size.width, size.height, bgPaint)

                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = terminalStyle.fontSize.toPx()
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    // Baseline offset: -ascent positions glyphs correctly within
                    // each row (ascent is negative, so -ascent is positive).
                    val baseline = -textPaint.fontMetrics.ascent
                    val rowH = rowHeightPx.toFloat()

                    for ((rowIdx, runs) in renderedRuns.withIndex()) {
                        val y = ((rowIdx * rowHeightPx) - verticalOffsetPx).toFloat()
                        if (y + rowH <= 0f || y >= size.height) continue
                        for (run in runs) {
                            val x = run.col * charWidthPx
                            // Draw background rectangle for the whole run.
                            // Integer row height with integer y-positions tiles exactly —
                            // no overlap needed (matches Termux).
                            if (run.bg != Color.Unspecified && run.bg != terminalBgColor) {
                                bgPaint.color = run.bg.toArgb()
                                nativeCanvas.drawRect(
                                    x, y,
                                    x + run.text.length * charWidthPx, y + rowH,
                                    bgPaint
                                )
                            }
                            // Configure paint for this run's style.
                            textPaint.color = run.fg.toArgb()
                            val typefaceStyle = when {
                                run.bold && run.italic -> android.graphics.Typeface.BOLD_ITALIC
                                run.bold -> android.graphics.Typeface.BOLD
                                run.italic -> android.graphics.Typeface.ITALIC
                                else -> android.graphics.Typeface.NORMAL
                            }
                            textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, typefaceStyle)
                            textPaint.isUnderlineText = run.underline
                            // Draw each character individually at its grid position.
                            val textY = y + baseline
                            for ((i, ch) in run.text.withIndex()) {
                                if (ch != ' ') {
                                    nativeCanvas.drawText(
                                        ch.toString(),
                                        x + i * charWidthPx,
                                        textY,
                                        textPaint
                                    )
                                }
                            }
                        }
                    }
                }

                // Invisible text layer for native text selection (long-press copy).
                // We strip all explicit span colors so text is invisible, but the
                // Compose SelectionContainer still draws a visible selection highlight.
                val selectionOutput = remember(terminalVersion) {
                    buildAnnotatedString {
                        append(
                            emulator.renderSelectionText(
                                scrollbackOffsetRows = 0,
                                windowRows = totalRows,
                            )
                        )
                    }
                }
                // Match the selection overlay line height to the canvas row height
                // so selection handles align with the rendered text.
                val selectionLineHeight = with(LocalDensity.current) { rowHeightPx.toSp() }
                val selectionStyle = remember(fontSizeSp, selectionLineHeight) {
                    terminalStyle.copy(
                        color = Color.Transparent,
                        lineHeight = selectionLineHeight,
                    )
                }
                val selectionColors = TextSelectionColors(
                    handleColor = Color(0xFF4FC3F7),
                    backgroundColor = Color(0xFF4FC3F7).copy(alpha = 0.4f)
                )
                CompositionLocalProvider(
                    LocalTextToolbar provides terminalTextToolbar,
                    LocalTextSelectionColors provides selectionColors
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(terminalScrollState)
                    ) {
                        SelectionContainer {
                            Text(
                                text = selectionOutput,
                                style = selectionStyle,
                                softWrap = false,
                                maxLines = Int.MAX_VALUE,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (connected && cursorPos != null) {
                    val cursorCol = cursorPos.second.coerceIn(0, (termCols - 1).coerceAtLeast(0))
                    val cursorRow = cursorPos.first.coerceIn(0, (termRows - 1).coerceAtLeast(0))
                    val cursorX = with(LocalDensity.current) { (cursorCol * charWidthPx).toDp() }
                    val cursorY = with(LocalDensity.current) { (cursorRow * rowHeightPx).toDp() }
                    val cursorW = with(LocalDensity.current) { charWidthPx.toDp() }
                    val cursorH = with(LocalDensity.current) { rowHeightPx.toDp() }

                    Box(
                        modifier = Modifier
                            .offset(x = cursorX, y = cursorY)
                            .size(width = cursorW, height = cursorH)
                            .background(Color(0xFFD3D7CF).copy(alpha = cursorAlpha))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalKeyboardOverlay(
    connected: Boolean,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    cursorApp: Boolean,
    onToggleDrawer: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendInput: (String) -> Unit,
    onCtrlC: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Arrow / Home / End sequences depend on DECCKM
    val arrowUp    = if (cursorApp) "\u001BOA" else "\u001B[A"
    val arrowDown  = if (cursorApp) "\u001BOB" else "\u001B[B"
    val arrowRight = if (cursorApp) "\u001BOC" else "\u001B[C"
    val arrowLeft  = if (cursorApp) "\u001BOD" else "\u001B[D"
    val home       = if (cursorApp) "\u001BOH" else "\u001B[H"
    val end        = if (cursorApp) "\u001BOF" else "\u001B[F"

    Surface(
        modifier = modifier,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = Color(0xFF1A1A1A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.dp),
        ) {
            // Row 1: matches Termux default extra keys
            TerminalKeyRow(
                keys = listOf(
                    TerminalKey("ESC", popupLabel = "☰", popupAction = onToggleDrawer) { onSendInput("\u001B") },
                    TerminalKey("/") { onSendInput("/") },
                    TerminalKey("-", popupLabel = "|", popupAction = { onSendInput("|") }) { onSendInput("-") },
                    TerminalKey("HOME") { onSendInput(home) },
                    TerminalKey("\u2191") { onSendInput(arrowUp) },
                    TerminalKey("END") { onSendInput(end) },
                    TerminalKey("PGUP") { onSendInput("\u001B[5~") },
                )
            )
            // Thin divider between rows
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF333333))
            )
            // Row 2: matches Termux default extra keys
            TerminalKeyRow(
                keys = listOf(
                    TerminalKey("\u21B9") { onSendInput("\t") },
                    TerminalKey("CTRL", active = ctrlLatched, action = onToggleCtrl),
                    TerminalKey("ALT", active = altLatched, action = onToggleAlt),
                    TerminalKey("\u2190") { onSendInput(arrowLeft) },
                    TerminalKey("\u2193") { onSendInput(arrowDown) },
                    TerminalKey("\u2192") { onSendInput(arrowRight) },
                    TerminalKey("PGDN") { onSendInput("\u001B[6~") },
                )
            )
        }
    }
}

private data class TerminalKey(
    val label: String,
    val active: Boolean = false,
    val popupLabel: String? = null,
    val popupAction: (() -> Unit)? = null,
    val action: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalKeyRow(keys: List<TerminalKey>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        keys.forEachIndexed { index, key ->
            if (index > 0) {
                // Thin vertical divider between keys
                Box(
                    Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(Color(0xFF333333))
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .then(
                        if (key.active) Modifier.background(Color(0xFF333333))
                        else Modifier
                    )
                    .combinedClickable(
                        onClick = key.action,
                        onLongClick = { key.popupAction?.invoke() }
                    )
            ) {
                Text(
                    text = key.label,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 13.sp
                    ),
                    color = if (key.active) Color(0xFF80CBC4) else Color(0xFFCCCCCC)
                )
            }
        }
    }
}

private fun applyTerminalModifiers(input: String, ctrl: Boolean, alt: Boolean): String {
    if (input.isEmpty()) return input
    var out = input
    if (ctrl) {
        out = out.map { ch -> ctrlTransform(ch) }.joinToString("")
    }
    if (alt) {
        out = "\u001B$out"
    }
    return out
}

private data class FnBindingResult(
    val output: String,
    val showVolumeUi: Boolean = false,
    val toggleKeyboard: Boolean = false,
)

private fun applyTermuxFnBindings(input: String, cursorApp: Boolean): FnBindingResult {
    if (input.isEmpty()) return FnBindingResult(output = "")

    val up = if (cursorApp) "\u001BOA" else "\u001B[A"
    val down = if (cursorApp) "\u001BOB" else "\u001B[B"
    val right = if (cursorApp) "\u001BOC" else "\u001B[C"
    val left = if (cursorApp) "\u001BOD" else "\u001B[D"

    val out = StringBuilder()
    var showVolumeUi = false
    var toggleKeyboard = false
    for (ch in input) {
        when (ch.lowercaseChar()) {
            'w' -> out.append(up)
            'a' -> out.append(left)
            's' -> out.append(down)
            'd' -> out.append(right)

            'p' -> out.append("\u001B[5~")
            'n' -> out.append("\u001B[6~")

            't' -> out.append('\t')
            'i' -> out.append("\u001B[2~")
            'h' -> out.append('~')
            'u' -> out.append('_')
            'l' -> out.append('|')

            '1' -> out.append("\u001BOP")
            '2' -> out.append("\u001BOQ")
            '3' -> out.append("\u001BOR")
            '4' -> out.append("\u001BOS")
            '5' -> out.append("\u001B[15~")
            '6' -> out.append("\u001B[17~")
            '7' -> out.append("\u001B[18~")
            '8' -> out.append("\u001B[19~")
            '9' -> out.append("\u001B[20~")
            '0' -> out.append("\u001B[21~")

            'e' -> out.append('\u001B')
            '.' -> out.append(28.toChar()) // Ctrl+\

            'b', 'f', 'x' -> {
                out.append('\u001B')
                out.append(ch.lowercaseChar())
            }

            // Termux also handles FN+v (volume UI) and FN+q/k (toggle toolbar),
            // which are app-specific actions. We consume them with no terminal output.
            'v' -> showVolumeUi = true
            'q', 'k' -> toggleKeyboard = true

            else -> Unit
        }
    }
    return FnBindingResult(
        output = out.toString(),
        showVolumeUi = showVolumeUi,
        toggleKeyboard = toggleKeyboard,
    )
}

private fun ctrlTransform(ch: Char): Char {
    return when {
        ch in 'a'..'z' -> (ch.code - 96).toChar()
        ch in 'A'..'Z' -> (ch.code - 64).toChar()
        ch == ' ' -> 0.toChar()
        ch == '[' -> 27.toChar()
        ch == '\\' -> 28.toChar()
        ch == ']' -> 29.toChar()
        ch == '^' -> 30.toChar()
        ch == '_' -> 31.toChar()
        else -> ch
    }
}

/**
 * Determine the "status text" for a group of step parts (like WebUI).
 * E.g., "Making edits", "Running commands", "Searching codebase", "Thinking"
 */
@Composable
private fun resolveStepsStatus(stepParts: List<Part>): String {
    val toolParts = stepParts.filterIsInstance<Part.Tool>()
    val hasRunning = toolParts.any { it.state is ToolState.Running }
    if (!hasRunning && toolParts.all { it.state is ToolState.Completed || it.state is ToolState.Error }) {
        // All done — summarize
        val editCount = toolParts.count { it.tool in listOf("edit", "write", "apply_patch", "multiedit") }
        val bashCount = toolParts.count { it.tool == "bash" }
        val searchCount = toolParts.count { it.tool in listOf("glob", "grep", "read", "list", "listDirectory") }
        return when {
            editCount > 0 && bashCount == 0 && searchCount == 0 -> {
                if (editCount == 1) 
                    stringResource(R.string.chat_status_edits, editCount)
                else 
                    stringResource(R.string.chat_status_edits_plural, editCount)
            }
            bashCount > 0 && editCount == 0 && searchCount == 0 -> {
                if (bashCount == 1)
                    stringResource(R.string.chat_status_commands, bashCount)
                else
                    stringResource(R.string.chat_status_commands_plural, bashCount)
            }
            else -> {
                if (toolParts.size == 1)
                    stringResource(R.string.chat_status_steps, toolParts.size)
                else
                    stringResource(R.string.chat_status_steps_plural, toolParts.size)
            }
        }
    }
    // Currently running — describe what's happening
    val runningTool = toolParts.lastOrNull { it.state is ToolState.Running }
    return when (runningTool?.tool) {
        "edit", "write", "multiedit" -> stringResource(R.string.chat_status_making_edits)
        "bash" -> stringResource(R.string.chat_status_running_commands)
        "read", "glob", "grep", "list", "listDirectory" -> stringResource(R.string.chat_status_searching)
        "webfetch" -> stringResource(R.string.chat_status_fetching_url)
        "task" -> stringResource(R.string.chat_status_running_subagent)
        "todowrite" -> stringResource(R.string.chat_status_updating_tasks)
        else -> stringResource(R.string.chat_status_thinking)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatMessageBubble(
    chatMessage: ChatMessage,
    showSenderHeader: Boolean = true,
    segmentPosition: ChatMessageSegmentPosition = ChatMessageSegmentPosition.Single,
    plannedBlock: MarkdownRenderBlock? = null,
    showAssistantMeta: Boolean = false,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    onMessageActionsRequested: (ChatMessage) -> Unit = {},
) {
    val isUser = chatMessage.isUser
    val isAmoled = isAmoledTheme()
    val userMessage = chatMessage.message as? Message.User
    val assistantMessage = chatMessage.message as? Message.Assistant
    val senderIdentity = piSenderIdentity(assistantMessage)
    val senderAccentColor = senderIdentity?.let(::piSenderAccentColor)
    val isModeratorMessage = isPiModerator(senderIdentity)
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isAmoled) {
        Color.Black
    } else if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else if (isModeratorMessage) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleBorder = when {
        isAmoled -> BorderStroke(
            1.dp,
            when {
                isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                senderAccentColor != null -> senderAccentColor.copy(alpha = if (isModeratorMessage) 0.85f else 0.65f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
            }
        )
        senderAccentColor != null -> BorderStroke(
            1.dp,
            senderAccentColor.copy(alpha = if (isModeratorMessage) 0.42f else 0.28f)
        )
        else -> null
    }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val showsTop = segmentPosition == ChatMessageSegmentPosition.Single ||
        segmentPosition == ChatMessageSegmentPosition.First
    val showsBottom = segmentPosition == ChatMessageSegmentPosition.Single ||
        segmentPosition == ChatMessageSegmentPosition.Last

    // Separate parts into text/reasoning (shown directly) and step parts (behind toggle)
    val visibleParts = if (isUser) {
        chatMessage.parts.filter { part ->
            when (part) {
                is Part.Text -> part.synthetic != true && part.ignored != true && part.text.isNotBlank()
                else -> true
            }
        }
    } else {
        chatMessage.parts
    }

    val assistantErrorText = formatAssistantErrorMessage(assistantMessage?.error)
    val userFallbackText = userMessage?.summary?.body?.takeIf { it.isNotBlank() }
        ?: userMessage?.summary?.title?.takeIf { it.isNotBlank() }
    val userCommandLabel = if (isUser) {
        resolveUserCommandLabel(chatMessage.parts)
    } else {
        null
    }

    val contentParts: List<Part>
    if (!isUser) {
        contentParts = if (plannedBlock != null) emptyList() else visibleParts.filter { part ->
            part is Part.Text
        }
    } else {
        contentParts = visibleParts
    }
    val stepParts: List<Part> = emptyList()

    val hasRenderableUserPart = contentParts.any(::isBubbleRenderablePart)
    val hasRenderableUserContent = !isUser || hasRenderableUserPart || userFallbackText != null || userCommandLabel != null
    val hasRenderableAssistantContent = isUser ||
            plannedBlock != null ||
            contentParts.isNotEmpty() ||
            stepParts.isNotEmpty() ||
            assistantErrorText != null
    if (!hasRenderableUserContent || !hasRenderableAssistantContent) {
        return
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val assistantMetaText = remember(assistantMessage?.modelId, assistantMessage?.time?.created) {
        assistantMessage?.let { message ->
            val timeText = timeFormat.format(Date(message.time.created))
            if (!message.modelId.isNullOrBlank()) "$timeText  ·  ${message.modelId}" else timeText
        }
    }
    val isLivePiSender = senderIdentity != null && assistantMessage?.finish == null
    val requestMessageActions = {
        performHaptic(hapticView, hapticOn)
        onMessageActionsRequested(chatMessage)
    }

        val bubbleContent: @Composable () -> Unit = {
        val compact = LocalCompactMessages.current
        val horizontalPadding = if (compact) 10.dp else 16.dp
        val verticalPadding = if (compact) 8.dp else 14.dp
        val edgePadding = 2.dp
        val inner: @Composable () -> Unit = {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(
                        start = if (isUser) horizontalPadding else 4.dp,
                        top = if (isUser && showsTop) verticalPadding else if (isUser) edgePadding else 2.dp,
                        end = if (isUser) horizontalPadding else 4.dp,
                        bottom = if (isUser && showsBottom) verticalPadding else if (isUser) edgePadding else 2.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
                ) {
                    // Content parts (text, reasoning, patches, etc.)
                    // Group image file parts into a compact thumbnail row
                    val imageFiles = contentParts.filterIsInstance<Part.File>()
                        .filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
                    val otherParts = contentParts.filter { part ->
                        !(part is Part.File && part.mime.startsWith("image/") && !part.url.isNullOrBlank())
                    }
                    val renderableOtherParts = otherParts.filter(::isBubbleRenderablePart)

                    // Render image thumbnails as a horizontal row
                    if (plannedBlock != null) {
                        MessageMarkdownContent(
                            markdown = plannedBlock.source,
                            textColor = textColor,
                            isUser = false,
                            modifier = Modifier.fillMaxWidth(),
                            plannedBlock = plannedBlock,
                        )
                    } else if (imageFiles.isNotEmpty()) {
                        ImageThumbnailRow(imageFiles = imageFiles)
                    }

                    if (plannedBlock == null && !isUser && isLivePiSender && renderableOtherParts.isEmpty() && imageFiles.isEmpty() && assistantErrorText == null) {
                        PiSenderThinkingPlaceholder(
                            textColor = textColor,
                            accentColor = senderAccentColor ?: textColor,
                        )
                    }

                    // Render remaining parts
                    for (part in renderableOtherParts) {
                        if (senderIdentity != null && part is Part.Text) {
                            PiSenderTextPartContent(
                                part = part,
                                textColor = textColor,
                                isUser = isUser,
                                accentColor = senderAccentColor ?: textColor,
                            )
                        } else {
                            PartContent(
                                part = part,
                                textColor = textColor,
                                isUser = isUser
                            )
                        }
                    }

                    if (isUser && imageFiles.isEmpty() && renderableOtherParts.isEmpty() && userCommandLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RateReview,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = textColor.copy(alpha = 0.7f)
                            )
                            Text(
                                text = userCommandLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor.copy(alpha = 0.85f)
                            )
                        }
                    }

                    if (!isUser && showsBottom && assistantErrorText != null) {
                        Surface(
                            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) 0.75f else 0.35f)),
                            tonalElevation = 0.dp,
                        ) {
                            ErrorPayloadContent(
                                text = assistantErrorText,
                                textStyle = MaterialTheme.typography.bodySmall,
                                textColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }

                    if (!isUser && showAssistantMeta && assistantMetaText != null) {
                        Text(
                            text = assistantMetaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // If text parts are absent but server provided a summary, render it.
                    if (visibleParts.isEmpty() && isUser && userFallbackText != null) {
                        Text(
                            text = userFallbackText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }
                }
                if (showsBottom) {
                    MessageActionChromeGestureLayer(
                        modifier = Modifier,
                        verticalPadding = verticalPadding,
                        onRequest = requestMessageActions,
                    )
                }
            }
        }
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (!showsTop) 2.dp else 18.dp,
                    topEnd = if (!showsTop) 2.dp else 4.dp,
                    bottomStart = if (showsBottom) 18.dp else 2.dp,
                    bottomEnd = if (showsBottom) 18.dp else 2.dp,
                ),
                color = backgroundColor,
                border = bubbleBorder,
                tonalElevation = if (isAmoled) 0.dp else 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                inner()
            }
        } else {
            inner()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (isUser && onRevert != null) {
            // Swipe-to-revert for user messages with confirmation dialog
            var showRevertConfirmation by remember { mutableStateOf(false) }
            val hapticEnabled = LocalHapticFeedbackEnabled.current
            val bubbleView = LocalView.current

            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        if (hapticEnabled) {
                            @Suppress("DEPRECATION")
                            bubbleView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.LONG_PRESS,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            )
                        }
                        showRevertConfirmation = true
                    }
                    false // don't actually dismiss; wait for dialog confirmation
                }
            )

            if (showRevertConfirmation) {
                AlertDialog(
                    onDismissRequest = { showRevertConfirmation = false },
                    title = { Text(stringResource(R.string.chat_revert_title)) },
                    text = { Text(stringResource(R.string.chat_revert_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRevertConfirmation = false
                                onRevert()
                            }
                        ) {
                            Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRevertConfirmation = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val direction = dismissState.dismissDirection
                    val bgColor = MaterialTheme.colorScheme.errorContainer
                    val iconAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 4.dp,
                                bottomStart = 18.dp,
                                bottomEnd = 18.dp
                            ))
                            .background(bgColor)
                            .padding(horizontal = 20.dp),
                        contentAlignment = iconAlignment
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.chat_revert),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.chat_revert),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                },
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = true
            ) {
                bubbleContent()
            }
        } else {
            bubbleContent()
        }
    }
}

@Composable
private fun PiSenderHeader(
    identity: PiSenderIdentity,
    accentColor: Color,
    textColor: Color,
    showSenderHeader: Boolean,
    actionTag: String?,
    liveLabel: String?,
    onCopyText: (() -> Unit)?,
) {
    if (!showSenderHeader) {
        if (onCopyText != null) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.chat_copy),
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { onCopyText() },
                    tint = textColor.copy(alpha = 0.3f),
                )
            }
        }
        return
    }

    val isModerator = isPiModerator(identity)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = accentColor.copy(alpha = if (isModerator) 0.22f else 0.14f),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = if (isModerator) 0.8f else 0.55f)),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = identity.name.firstOrNull()?.uppercase() ?: "P",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = accentColor,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = identity.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isModerator) {
                        Text(
                            text = "主持总结",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.58f),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (onCopyText != null) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.chat_copy),
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { onCopyText() },
                    tint = textColor.copy(alpha = 0.3f),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            identity.mbti?.let { mbti ->
                PiSenderChip(
                    label = mbti,
                    accentColor = accentColor,
                    textColor = textColor,
                )
            }
            if (isModerator) {
                PiSenderChip(
                    label = "综合",
                    accentColor = accentColor,
                    textColor = textColor,
                )
            }
            actionTag?.takeIf { it.isNotBlank() }?.let { tag ->
                PiSenderChip(
                    label = tag,
                    accentColor = accentColor,
                    textColor = textColor,
                )
            }
            liveLabel?.takeIf { !isModerator }?.let { label ->
                PiLiveSenderChip(
                    label = label,
                    accentColor = accentColor,
                    textColor = textColor,
                )
            }
        }
    }
}

@Composable
private fun PiSenderThinkingPlaceholder(
    textColor: Color,
    accentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = accentColor)
        Text(
            text = stringResource(R.string.chat_tool_thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun PiLiveSenderChip(
    label: String,
    accentColor: Color,
    textColor: Color,
) {
    Surface(
        shape = CircleShape,
        color = accentColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.42f)),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PulsingDotsIndicator(dotSize = 3.dp, dotSpacing = 2.dp, color = accentColor)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = textColor.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun PiSenderChip(
    label: String,
    accentColor: Color,
    textColor: Color,
) {
    Surface(
        shape = CircleShape,
        color = accentColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.32f)),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = textColor.copy(alpha = 0.78f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PiSenderTextPartContent(
    part: Part.Text,
    textColor: Color,
    isUser: Boolean,
    accentColor: Color,
) {
    if (part.text.isBlank() || part.synthetic == true || part.ignored == true) return

    val inShort = remember(part.text) { splitPiInShortHighlight(part.text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        inShort.highlight?.let { highlight ->
            PiInShortHighlight(
                text = highlight,
                accentColor = accentColor,
                textColor = textColor,
            )
        }
        if (inShort.markdown.isNotBlank()) {
            MarkdownContent(
                markdown = inShort.markdown,
                textColor = textColor,
                isUser = isUser,
            )
        }
    }
}

@Composable
private fun PiInShortHighlight(
    text: String,
    accentColor: Color,
    textColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.30f)),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(6.dp)
                    .background(accentColor, CircleShape),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = textColor.copy(alpha = 0.88f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.MessageActionChromeGestureLayer(
    modifier: Modifier,
    verticalPadding: Dp,
    onRequest: () -> Unit,
) {
    val gestureModifier = Modifier.combinedClickable(
        onClick = {},
        onDoubleClick = onRequest,
        onLongClick = onRequest,
    )

    Box(
        modifier = modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(verticalPadding)
            .then(gestureModifier)
    )
    Box(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(verticalPadding)
            .then(gestureModifier)
    )
}

private fun isBubbleRenderablePart(part: Part): Boolean {
    return when (part) {
        is Part.Text,
        is Part.Reasoning,
        is Part.Patch,
        is Part.File,
        is Part.Permission,
        is Part.Question,
        is Part.Abort,
        is Part.Retry,
        is Part.Tool -> true
        else -> false
    }
}

@Composable
private fun resolveUserCommandLabel(parts: List<Part>): String? {
    val subtaskParts = parts.filterIsInstance<Part.Subtask>()

    val commandFromSubtask = subtaskParts
        .firstNotNullOfOrNull { it.command }
        ?.removePrefix("/")
        ?.trim()
        ?.lowercase()

    val commandFromText = parts
        .filterIsInstance<Part.Text>()
        .firstNotNullOfOrNull { textPart ->
            val text = textPart.text.trim()
            if (!text.startsWith("/")) return@firstNotNullOfOrNull null
            text.removePrefix("/").substringBefore(' ').trim().lowercase().takeIf { it.isNotBlank() }
        }

    val inferredReviewFromPrompt = subtaskParts.any { subtask ->
        val prompt = subtask.prompt.lowercase()
        val description = subtask.description?.lowercase().orEmpty()
        "review changes" in prompt || "review" in description
    }

    val command = commandFromSubtask ?: commandFromText ?: if (inferredReviewFromPrompt) "review" else null

    return when (command) {
        "review" -> stringResource(R.string.menu_review_changes)
        null -> {
            val hasNonRenderableOnly = parts.any { part ->
                part !is Part.Text &&
                        part !is Part.Reasoning &&
                        part !is Part.Patch &&
                        part !is Part.File &&
                        part !is Part.Permission &&
                        part !is Part.Question &&
                        part !is Part.Abort &&
                        part !is Part.Retry
            }
            if (hasNonRenderableOnly) stringResource(R.string.chat_tool_running_command) else null
        }
        else -> stringResource(R.string.chat_tool_running_command)
    }
}

/**
 * Banner shown when messages have been reverted.
 * Tapping restores (redo) the reverted messages.
 */
@Composable
private fun RevertBanner(onRedo: () -> Unit) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { performHaptic(hapticView, hapticOn); onRedo() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_messages_reverted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.chat_tap_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.Restore,
                contentDescription = stringResource(R.string.chat_restore),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
internal fun PartContent(
    part: Part,
    textColor: Color,
    isUser: Boolean = false
) {
    when (part) {
        is Part.Text -> {
            // Hide synthetic/ignored text parts (internal system content)
            if (part.text.isNotBlank() && part.synthetic != true && part.ignored != true) {
                MarkdownContent(
                    markdown = part.text,
                    textColor = textColor,
                    isUser = isUser
                )
            }
        }
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                ReasoningBlock(text = part.text)
            }
        }
        is Part.Tool -> {
            // todoread parts are filtered out entirely (WebUI convention)
            if (part.tool == "todoread") {
                // skip
            } else if (part.tool == "todowrite") {
                TodoListCard(tool = part)
            } else {
                // Dispatch to tool-specific renderers (like WebUI)
                when (part.tool) {
                    "edit", "multiedit" -> EditToolCard(tool = part)
                    "write" -> WriteToolCard(tool = part)
                    "bash" -> BashToolCard(tool = part)
                    "read" -> ReadToolCard(tool = part)
                    "glob", "grep" -> SearchToolCard(tool = part)
                    "task" -> TaskToolCard(tool = part)
                    else -> ToolCallCard(tool = part)
                }
            }
        }
        is Part.StepStart -> {
            // Visual separator between steps (hidden - WebUI doesn't show these)
        }
        is Part.StepFinish -> {
            // Token/cost info hidden from message bubbles (WebUI convention)
        }
        is Part.Patch -> {
            PatchCard(patch = part)
        }
        is Part.File -> {
            FileCard(file = part)
        }
        is Part.Permission -> {
            Text(
                text = stringResource(R.string.chat_permission_label, part.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Question -> {
            Text(
                text = stringResource(R.string.chat_question_inline, part.question),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Abort -> {
            Text(
                text = stringResource(R.string.chat_aborted, part.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        is Part.Retry -> {
            Text(
                text = stringResource(R.string.chat_retry, part.attempt, part.errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Ignore less relevant parts
        is Part.Snapshot, is Part.Subtask, is Part.Compaction,
        is Part.Agent, is Part.SessionTurn, is Part.Unknown -> { /* skip */ }
    }
}

/**
 * Renders markdown content using mikepenz markdown renderer with code syntax highlighting.
 */
@Composable
private fun MarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean
) {
    MessageMarkdownContent(
        markdown = markdown,
        textColor = textColor,
        isUser = isUser,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val HtmlDocumentHintRegex = Regex("(?is)<!doctype\\s+html\\b|<\\s*html\\b")
private val HtmlTagRegex = Regex("(?is)<\\s*/?\\s*[a-z][^>]*>")

private fun looksLikeHtmlPayload(text: String): Boolean {
    if (text.isBlank()) return false
    if (HtmlDocumentHintRegex.containsMatchIn(text)) return true
    return HtmlTagRegex.findAll(text).take(12).count() >= 6
}

private fun normalizeHtmlForEmbeddedPreview(html: String): String {
    if (html.isBlank()) return html
    val overrideCss = """
        html, body {
          margin: 0 !important;
          padding: 8px !important;
          min-height: auto !important;
          height: auto !important;
        }
        body {
          display: block !important;
          align-items: flex-start !important;
          justify-content: flex-start !important;
          overflow: auto !important;
        }
        .container {
          align-items: flex-start !important;
          justify-content: flex-start !important;
          height: auto !important;
          min-height: auto !important;
          width: 100% !important;
          margin: 0 !important;
        }
    """.trimIndent()

    val styleBlock = "<style>$overrideCss</style>"
    return if (html.contains("</head>", ignoreCase = true)) {
        html.replaceFirst(Regex("(?i)</head>"), "$styleBlock</head>")
    } else {
        "<head>$styleBlock</head>$html"
    }
}

@Composable
internal fun ReasoningBlock(text: String) {
    val isAmoled = isAmoledTheme()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Left accent border
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_status_thinking),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.6.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SelectionContainer {
                    ScrollablePlainText(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            lineHeight = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollablePlainText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val overflowTreatment = ChatOverflowPolicy.resolve(
        kind = ChatOverflowContentKind.PlainText,
        text = text,
    )
    if (overflowTreatment == ChatOverflowTreatment.Wrap) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
        )
        return
    }

    DisableSelection {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .testTag(WidePlainTextTag)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = text,
                style = style,
                color = color,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ToolCallCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val stateColor = when (tool.state) {
        is ToolState.Pending -> MaterialTheme.colorScheme.outline
        is ToolState.Running -> MaterialTheme.colorScheme.tertiary
        is ToolState.Completed -> MaterialTheme.colorScheme.primary
        is ToolState.Error -> MaterialTheme.colorScheme.error
    }

    // Extract input args for context-specific display
    val input = when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }

    // Resolve display info based on tool type
    val toolDisplay = resolveToolDisplay(tool.tool, tool.state, input)

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (tool.state is ToolState.Completed || tool.state is ToolState.Error || extractToolOutput(tool).isNotBlank()) {
                            mod.clickable { performHaptic(hapticView, hapticOn); expanded = !expanded }
                        } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (tool.state) {
                            is ToolState.Running -> Icons.Default.Sync
                            is ToolState.Completed -> toolDisplay.icon
                            is ToolState.Error -> Icons.Default.Error
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (tool.state is ToolState.Error) stateColor else toolDisplay.iconTint ?: stateColor
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toolDisplay.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (toolDisplay.subtitle != null) {
                            Text(
                                text = toolDisplay.subtitle,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Expand indicator for completed/errored tools
                val hasRunningOutput = extractToolOutput(tool).isNotBlank()
                if (tool.state is ToolState.Running && !hasRunningOutput) {
                    PulsingDotsIndicator(
                        dotSize = 5.dp,
                        dotSpacing = 3.dp,
                        color = stateColor
                    )
                } else if (tool.state is ToolState.Running || tool.state is ToolState.Completed || tool.state is ToolState.Error) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (tool.state is ToolState.Running) {
                            PulsingDotsIndicator(
                                dotSize = 5.dp,
                                dotSpacing = 3.dp,
                                color = stateColor
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Expandable details
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val output = extractToolOutput(tool)
                    if (output.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = toolOutputContainerColor(isAmoled),
                            border = if (isAmoled) BorderStroke(1.dp, stateColor.copy(alpha = 0.6f)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = output.take(3000),
                                style = CodeTypography.copy(
                                    fontSize = 11.sp,
                                    color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .codeHorizontalScroll()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Display info for a tool call, resolved from tool name and input args.
 */
private data class ToolDisplayInfo(
    val title: String,
    val subtitle: String? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Check,
    val iconTint: Color? = null
)

/**
 * Resolve display info for a tool call based on its type and input arguments.
 * Matches WebUI tool registry behavior with human-readable titles.
 */
@Composable
private fun resolveToolDisplay(
    toolName: String,
    state: ToolState,
    input: Map<String, kotlinx.serialization.json.JsonElement>
): ToolDisplayInfo {
    // Use server-provided title if available
    val serverTitle = when (state) {
        is ToolState.Running -> state.title
        is ToolState.Completed -> state.title
        else -> null
    }

    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull
        ?: input["file"]?.jsonPrimitive?.contentOrNull
    val shortPath = filePath?.substringAfterLast('/')

    return when (toolName) {
        "read" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_read_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.Description
            )
        }
        "write" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_write_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.EditNote
            )
        }
        "edit" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_edit_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.Edit
            )
        }
        "bash" -> {
            val command = input["command"]?.jsonPrimitive?.contentOrNull
            val shortCmd = command?.let {
                if (it.length > 60) it.take(57) + "..." else it
            }
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_terminal),
                subtitle = shortCmd,
                icon = Icons.Default.Terminal
            )
        }
        "glob" -> {
            val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_find_files),
                subtitle = pattern,
                icon = Icons.Default.FolderOpen
            )
        }
        "grep" -> {
            val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_search_code),
                subtitle = pattern,
                icon = Icons.Default.Search
            )
        }
        "list", "listDirectory" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_list_directory),
                subtitle = filePath,
                icon = Icons.Default.Folder
            )
        }
        "webfetch" -> {
            val url = input["url"]?.jsonPrimitive?.contentOrNull
            val shortUrl = url?.let {
                try { java.net.URI(it).host } catch (_: Exception) { it.take(40) }
            }
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_fetch_url),
                subtitle = shortUrl,
                icon = Icons.Default.Language
            )
        }
        "task" -> {
            val description = input["description"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_sub_agent),
                subtitle = description,
                icon = Icons.Default.AccountTree
            )
        }
        "apply_patch" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_apply_patch),
                subtitle = shortPath,
                icon = Icons.Default.Compare
            )
        }
        else -> {
            ToolDisplayInfo(
                title = serverTitle ?: toolName,
                subtitle = null,
                icon = Icons.Default.Build
            )
        }
    }
}

// ============================================================================
// Tool-specific card renderers (matching WebUI tool registry)
// ============================================================================

/**
 * Extract common tool input values.
 */
private fun extractToolInput(tool: Part.Tool): Map<String, kotlinx.serialization.json.JsonElement> {
    return when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }
}

private fun extractToolOutput(tool: Part.Tool): String {
    return when (val s = tool.state) {
        is ToolState.Running -> s.output.ifBlank {
            s.metadata?.get("output")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        is ToolState.Completed -> s.output
        is ToolState.Error -> {
            val rawOutput = s.metadata?.get("output")?.jsonPrimitive?.contentOrNull.orEmpty()
            when {
                rawOutput.isBlank() -> s.error
                s.error.isBlank() -> rawOutput
                else -> "$rawOutput\n\n${s.error}"
            }
        }
        else -> ""
    }
}

@Composable
private fun RetryStatusBanner(
    retry: SessionStatus.Retry,
    isRetryingNow: Boolean,
    onRetryNow: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    val retryText = stringResource(R.string.chat_retry, retry.attempt, retry.message)
    val retryNowLabel = stringResource(R.string.chat_retry_now)
    val retryingNowLabel = stringResource(R.string.chat_retrying_now)
    val retryActionDescription = if (isRetryingNow) retryingNowLabel else retryNowLabel

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)) else null,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.sessions_retrying),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = retryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onRetryNow,
                    enabled = !isRetryingNow,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = retryActionDescription },
                ) {
                    if (isRetryingNow) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isRetryingNow) retryingNowLabel else retryNowLabel)
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = stringResource(R.string.chat_stop),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Edit tool card — shows file path + diff with red/green colored lines.
 * Like WebUI: trigger = "Edit" + filename + DiffChanges, content = diff view.
 */
@Composable
private fun EditToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val dirPath = if (filePath.contains('/')) filePath.substringBeforeLast('/') else ""
    val oldString = input["oldString"]?.jsonPrimitive?.contentOrNull ?: ""
    val newString = input["newString"]?.jsonPrimitive?.contentOrNull ?: ""

    // Try to get filediff from metadata (full file before/after)
    val metadata = when (val s = tool.state) {
        is ToolState.Completed -> s.metadata
        is ToolState.Running -> s.metadata
        else -> null
    }
    val filediffBefore = metadata?.get("filediff")?.jsonObject?.get("before")?.jsonPrimitive?.contentOrNull
    val filediffAfter = metadata?.get("filediff")?.jsonObject?.get("after")?.jsonPrimitive?.contentOrNull

    val diffBefore = filediffBefore ?: oldString
    val diffAfter = filediffAfter ?: newString

    // Compute additions/deletions
    val addCount = diffAfter.lines().size - diffBefore.lines().let { if (diffBefore.isBlank()) 0 else it.size }
    val additions = if (addCount > 0) addCount else 0
    val deletions = if (addCount < 0) -addCount else 0

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = oldString.isNotBlank() || newString.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.clickable { performHaptic(hapticView, hapticOn); expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_edit_label),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        if (shortPath.isNotBlank()) {
                            Text(
                                text = shortPath,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Diff stats + expand indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (additions > 0 || deletions > 0) {
                        DiffChangesInline(additions = additions, deletions = deletions)
                    }
                    if (isRunning) {
                        PulsingDotsIndicator(
                            dotSize = 5.dp,
                            dotSpacing = 3.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else if (hasContent) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Expanded diff view
            AnimatedVisibility(visible = expanded && hasContent) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    if (isError) {
                        val errorText = (tool.state as ToolState.Error).error
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer,
                            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ErrorPayloadContent(
                                text = errorText,
                                textStyle = CodeTypography.copy(
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                                textColor = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else {
                        DiffView(before = diffBefore, after = diffAfter)
                    }
                }
            }
        }
    }
}

/**
 * Inline diff change counts: +N -N with colors.
 */
@Composable
private fun DiffChangesInline(additions: Int, deletions: Int) {
    val addColor = diffAddColor()
    val delColor = diffDeleteColor()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (additions > 0) {
            Text(
                text = "+$additions",
                style = CodeTypography.copy(fontSize = 11.sp, color = addColor)
            )
        }
        if (deletions > 0) {
            Text(
                text = "-$deletions",
                style = CodeTypography.copy(fontSize = 11.sp, color = delColor)
            )
        }
    }
}

/**
 * Unified diff view — shows old lines in red, new lines in green.
 * Simple approach: compute line-level diff between before and after.
 */
@Composable
private fun DiffView(before: String, after: String) {
    val isAmoled = isAmoledTheme()
    val addColor = diffAddColor()
    val delColor = diffDeleteColor()
    val addBg = addColor.copy(alpha = if (isAmoled) 0.18f else 0.1f)
    val delBg = delColor.copy(alpha = if (isAmoled) 0.18f else 0.1f)

    // Simple diff: show removed lines, then added lines
    // For a proper diff we'd need a diff library, but line-level comparison works for edit tools
    val beforeLines = if (before.isBlank()) emptyList() else before.lines()
    val afterLines = if (after.isBlank()) emptyList() else after.lines()

    // Compute simple LCS-based diff
    val diffLines = remember(before, after) { computeSimpleDiff(beforeLines, afterLines) }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
    ) {
        Column(
            modifier = Modifier
                .codeHorizontalScroll()
                .verticalScroll(rememberScrollState())
                .padding(4.dp)
        ) {
            for (line in diffLines) {
                val (prefix, text, bgColor, fgColor) = when (line.type) {
                    DiffLineType.REMOVED -> DiffLineStyle("-", line.text, delBg, delColor)
                    DiffLineType.ADDED -> DiffLineStyle("+", line.text, addBg, addColor)
                    DiffLineType.UNCHANGED -> DiffLineStyle(" ", line.text, Color.Transparent, if (isAmoled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                ) {
                    Text(
                        text = "$prefix ",
                        style = CodeTypography.copy(fontSize = 13.sp, color = fgColor),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Text(
                        text = text,
                        style = CodeTypography.copy(fontSize = 13.sp, color = fgColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun diffAddColor(): Color {
    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF7FD88F)
    } else {
        Color(0xFF2E7D32)
    }
}

@Composable
private fun diffDeleteColor(): Color {
    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFFFF8A80)
    } else {
        Color(0xFFC62828)
    }
}

private data class DiffLineStyle(val prefix: String, val text: String, val bgColor: Color, val fgColor: Color)

private enum class DiffLineType { REMOVED, ADDED, UNCHANGED }
private data class DiffLine(val type: DiffLineType, val text: String)

/**
 * Simple diff algorithm: find common prefix/suffix lines, show removed and added lines in between.
 * Not a full LCS but good enough for typical edit tool changes.
 */
private fun computeSimpleDiff(before: List<String>, after: List<String>): List<DiffLine> {
    if (before.isEmpty() && after.isEmpty()) return emptyList()
    if (before.isEmpty()) return after.map { DiffLine(DiffLineType.ADDED, it) }
    if (after.isEmpty()) return before.map { DiffLine(DiffLineType.REMOVED, it) }

    // Find common prefix
    var commonPrefixLen = 0
    while (commonPrefixLen < before.size && commonPrefixLen < after.size &&
        before[commonPrefixLen] == after[commonPrefixLen]) {
        commonPrefixLen++
    }

    // Find common suffix (after prefix)
    var commonSuffixLen = 0
    while (commonSuffixLen < (before.size - commonPrefixLen) &&
        commonSuffixLen < (after.size - commonPrefixLen) &&
        before[before.size - 1 - commonSuffixLen] == after[after.size - 1 - commonSuffixLen]) {
        commonSuffixLen++
    }

    val result = mutableListOf<DiffLine>()

    // Show a few context lines from prefix (max 3)
    val contextLines = 3
    val prefixStart = (commonPrefixLen - contextLines).coerceAtLeast(0)
    for (i in prefixStart until commonPrefixLen) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[i]))
    }

    // Removed lines (from before, between prefix and suffix)
    for (i in commonPrefixLen until (before.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.REMOVED, before[i]))
    }

    // Added lines (from after, between prefix and suffix)
    for (i in commonPrefixLen until (after.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.ADDED, after[i]))
    }

    // Show a few context lines from suffix (max 3)
    val suffixEnd = commonSuffixLen.coerceAtMost(contextLines)
    for (i in 0 until suffixEnd) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[before.size - commonSuffixLen + i]))
    }

    return result
}

/**
 * Write tool card — shows file path + code content.
 * Like WebUI: trigger = "Write" + filename, content = code view.
 */
@Composable
private fun WriteToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val content = input["content"]?.jsonPrimitive?.contentOrNull ?: ""

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = content.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.clickable { performHaptic(hapticView, hapticOn); expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.EditNote,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_write_label),
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (shortPath.isNotBlank()) {
                            Text(
                                text = shortPath,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isRunning) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasContent) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasContent) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 400.dp)
                ) {
                    Text(
                        text = content.take(5000),
                        style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .codeHorizontalScroll()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * Bash tool card — shows $ command + output.
 * Like WebUI: trigger = "Shell" + description, content = code block with command+output.
 */
@Composable
private fun BashToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val input = extractToolInput(tool)
    val command = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)
    val cleanedOutput = output.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
    val displayText = buildString {
        if (command.isNotBlank()) {
            append("$ $command")
        }
        if (cleanedOutput.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(cleanedOutput.take(5000))
        }
    }

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = command.isNotBlank() || output.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasContent && !isRunning) mod.clickable { performHaptic(hapticView, hapticOn); expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = serverTitle ?: stringResource(R.string.tool_shell),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (description != null) {
                            Text(
                                text = description,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isRunning) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasContent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (cleanedOutput.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(displayText))
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_copy),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && hasContent) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 400.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = displayText,
                            style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                            modifier = Modifier
                                .padding(8.dp)
                                .codeHorizontalScroll()
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

/**
 * Read tool card — shows file path only, no expandable content (like WebUI).
 */
@Composable
private fun ReadToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val offset = input["offset"]?.jsonPrimitive?.contentOrNull
    val limit = input["limit"]?.jsonPrimitive?.contentOrNull

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error

    // Build args string like WebUI: [offset=N, limit=N]
    val args = buildList {
        offset?.let { add("offset=$it") }
        limit?.let { add("limit=$it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = serverTitle ?: stringResource(R.string.tool_read),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (shortPath.isNotBlank()) {
                            Text(
                                text = shortPath,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (args != null) {
                            Text(
                                text = args,
                                style = CodeTypography.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            if (isRunning) {
                PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/**
 * Search tool card (glob/grep) — shows pattern + expandable output.
 * Like WebUI: trigger = "Glob"/"Grep" + directory + [pattern=...], content = markdown output.
 */
@Composable
private fun SearchToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
    val include = input["include"]?.jsonPrimitive?.contentOrNull
    val dirPath = input["path"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val title = when (tool.tool) {
        "glob" -> serverTitle ?: stringResource(R.string.tool_find_files)
        "grep" -> serverTitle ?: stringResource(R.string.tool_search_code)
        else -> serverTitle ?: tool.tool
    }

    // Build args display
    val argsText = buildList {
        pattern?.let { add("pattern=$it") }
        include?.let { add("include=$it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasOutput && !isRunning) mod.clickable { performHaptic(hapticView, hapticOn); expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (dirPath != null) {
                                Text(
                                    text = dirPath.substringAfterLast('/').ifEmpty { dirPath },
                                    style = CodeTypography.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (argsText != null) {
                                Text(
                                    text = argsText,
                                    style = CodeTypography.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (isRunning) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasOutput) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasOutput) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 300.dp)
                ) {
                    Text(
                        text = output.take(5000),
                        style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .codeHorizontalScroll()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * Task (sub-agent) tool card — shows description + child info.
 * Like WebUI: trigger = "Agent (task)" + description, content = child tool list.
 */
@Composable
private fun TaskToolCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        if (hasOutput && !isRunning) mod.clickable { performHaptic(hapticView, hapticOn); expanded = !expanded } else mod
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = serverTitle ?: stringResource(R.string.tool_sub_agent),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        if (description != null) {
                            Text(
                                text = description,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isRunning) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (hasOutput) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasOutput) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 300.dp)
                ) {
                    Text(
                        text = output.take(5000),
                        style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(8.dp)
                            .codeHorizontalScroll()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
@Composable
private fun TodoListCard(tool: Part.Tool) {
    val isAmoled = isAmoledTheme()
    // Extract todos from metadata first, then fall back to input
    val todos = remember(tool) {
        val source = when (val state = tool.state) {
            is ToolState.Completed -> state.metadata?.get("todos") ?: state.input["todos"]
            is ToolState.Running -> state.metadata?.get("todos") ?: state.input["todos"]
            is ToolState.Pending -> state.input["todos"]
            is ToolState.Error -> state.metadata?.get("todos") ?: state.input["todos"]
        }
        if (source != null) {
            try {
                source.jsonArray.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                        val priority = obj["priority"]?.jsonPrimitive?.contentOrNull ?: "medium"
                        TodoItem(content = content, status = status, priority = priority)
                    } catch (_: Exception) { null }
                }
            } catch (_: Exception) { emptyList() }
        } else {
            emptyList()
        }
    }

    if (todos.isEmpty()) {
        // Fallback to generic tool card if we can't parse todos
        ToolCallCard(tool = tool)
        return
    }

    val completedCount = todos.count { it.status == "completed" }
    val totalCount = todos.size
    var expanded by remember { mutableStateOf(true) }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { performHaptic(hapticView, hapticOn); expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (completedCount == totalCount) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = stringResource(R.string.chat_tasks_label),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$completedCount/$totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Todo items
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (todo in todos) {
                        TodoItemRow(todo = todo)
                    }
                }
            }
        }
    }
}

private data class TodoItem(
    val content: String,
    val status: String,
    val priority: String
)

@Composable
private fun TodoItemRow(todo: TodoItem) {
    val isCompleted = todo.status == "completed"
    val isInProgress = todo.status == "in_progress"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isCompleted,
            onCheckedChange = null,
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = if (isInProgress) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        )
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (isCompleted) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StepFinishInfo(step: Part.StepFinish) {
    if (step.tokens != null || step.cost != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            step.tokens?.let { tokens ->
                Text(
                    text = stringResource(R.string.chat_tokens_format, tokens.input, tokens.output),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            step.cost?.let { cost ->
                Text(
                    text = stringResource(R.string.chat_cost_format, String.format("%.4f", cost)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun PatchCard(patch: Part.Patch) {
    val isAmoled = isAmoledTheme()
    val autoExpand = LocalCollapseTools.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember(autoExpand) { mutableStateOf(autoExpand) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { performHaptic(hapticView, hapticOn); expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (patch.files.size == 1)
                            stringResource(R.string.chat_files_changed, patch.files.size)
                        else
                            stringResource(R.string.chat_files_changed_plural, patch.files.size),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            // Expanded file list
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (filePath in patch.files) {
                        Text(
                            text = filePath.substringAfterLast('/'),
                            style = CodeTypography.copy(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact horizontal row of image thumbnails with tap-to-preview.
 */
@Composable
private fun ImageThumbnailRow(
    imageFiles: List<Part.File>,
) {
    var previewIndex by remember { mutableStateOf(-1) }
    val requestSaveImage = LocalImageSaveRequest.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for ((index, file) in imageFiles.withIndex()) {
            val bitmap = remember(file.url) {
                try {
                    val url = file.url ?: return@remember null
                    val base64Data = if (url.contains(",")) url.substringAfter(",") else url
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.e("FileCard", "Failed to decode image: ${e.message}")
                    null
                }
            }

            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.filename ?: stringResource(R.string.chat_image),
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { previewIndex = index },
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback placeholder for failed decode
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }

    // Fullscreen image preview dialog
    if (previewIndex >= 0 && previewIndex < imageFiles.size) {
        val file = imageFiles[previewIndex]
        val imageBytes = remember(file.url) { decodePartFileBytes(file) }
        val bitmap = remember(imageBytes) {
            imageBytes?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }

        if (bitmap != null) {
            ImagePreviewDialog(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.filename ?: stringResource(R.string.chat_image),
                onDismiss = { previewIndex = -1 },
                onSave = {
                    if (imageBytes != null) {
                        requestSaveImage(imageBytes, file.mime, file.filename)
                    }
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ImagePreviewDialog(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (isAmoled) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
            } else {
                null
            },
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit,
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val actionContainerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    val actionBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) 0.85f else 0.8f)
                    val actionTintColor = MaterialTheme.colorScheme.onSurface

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = actionContainerColor,
                        border = BorderStroke(1.dp, actionBorderColor),
                    ) {
                        IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.chat_save_image),
                                tint = actionTintColor,
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = actionContainerColor,
                        border = BorderStroke(1.dp, actionBorderColor),
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = actionTintColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(file: Part.File) {
    // Images are handled by ImageThumbnailRow, so FileCard only handles non-image files
    FileCardFallback(file)
}

@Composable
private fun FileCardFallback(file: Part.File) {
    val isAmoled = isAmoledTheme()
    val containerColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (isAmoled) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
    }
    val contentColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = file.filename
                    ?: file.url?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: file.mime,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    permission: SseEvent.PermissionAsked,
    onOnce: () -> Unit,
    onAlways: () -> Unit,
    onReject: () -> Unit,
    alwaysAvailable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)) else null,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isAmoled) MaterialTheme.colorScheme.tertiary else contentColor
                )
                Text(
                    text = stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
            }
            Text(
                text = permission.permission,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            if (permission.patterns.isNotEmpty()) {
                Text(
                    text = permission.patterns.joinToString(", "),
                    style = CodeTypography.copy(
                        fontSize = 11.sp,
                        color = contentColor.copy(alpha = 0.7f)
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { performHaptic(hapticView, hapticOn); onReject() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.permission_deny), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { performHaptic(hapticView, hapticOn); onOnce() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.permission_allow_once), maxLines = 1)
                }
                if (alwaysAvailable) {
                    Button(
                        onClick = { performHaptic(hapticView, hapticOn); onAlways() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(R.string.permission_allow_always), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DshQueueDock(
    items: List<ChatQueueItem>,
    onSteer: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.text.ifBlank { item.placement },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.placement != "steering") {
                    TextButton(onClick = { onSteer(item.id) }) {
                        Text(stringResource(R.string.chat_queue_steer))
                    }
                }
                TextButton(onClick = { onRemove(item.id) }) {
                    Text(stringResource(R.string.chat_queue_remove))
                }
            }
        }
    }
}

/** Rotating placeholder hints for the input bar, similar to the WebUI prompt input. */
private val placeholderHintResIds = listOf(
    R.string.chat_hint_ask,
    R.string.chat_hint_fix,
    R.string.chat_hint_refactor,
    R.string.chat_hint_tests,
    R.string.chat_hint_explain,
    R.string.chat_hint_help,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    isBusy: Boolean = false,
    sendDisabledReasonResId: Int? = null,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    supportsAttachments: Boolean = true,
    onRemoveAttachment: (Int) -> Unit = {},
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit = { _, _, _ -> },
    modelLabel: String = "",
    selectedProviderId: String? = null,
    onModelClick: () -> Unit = {},
    agents: List<AgentInfo> = emptyList(),
    selectedAgent: String = "build",
    onAgentClick: () -> Unit = {},
    variantNames: List<String> = emptyList(),
    selectedVariant: String? = null,
    onVariantClick: () -> Unit = {},
    commands: List<CommandInfo> = emptyList(),
    fileSearchResults: List<String> = emptyList(),
    confirmedFilePaths: Set<String> = emptySet(),
    onFileSelected: (String) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {},
    inputMode: ChatInputMode = ChatInputMode.NORMAL,
    onInputModeChange: (ChatInputMode) -> Unit = {},
    supportsShell: Boolean = true,
    supportsCompact: Boolean = true,
    contextWindow: Int = 0,
    lastContextTokens: Int = 0,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    val isShellMode = inputMode == ChatInputMode.SHELL
    // Rotate placeholder hint every 4 seconds
    val hintIndex = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIndex.intValue = (hintIndex.intValue + 1) % placeholderHintResIds.size
        }
    }
    val placeholder = when {
        isShellMode -> stringResource(R.string.chat_shell_placeholder)
        else -> stringResource(placeholderHintResIds[hintIndex.intValue])
    }

    val text = textFieldValue.text
    val hasSendContent = text.isNotBlank() || attachments.isNotEmpty()
    val hasBlockingReason = sendDisabledReasonResId != null
    val canSend = hasSendContent && !isSending && (!isShellMode || !isBusy) && !hasBlockingReason
    val sendDisabledReasonText = sendDisabledReasonResId
        ?.takeIf { !canSend }
        ?.let { stringResource(it) }
    var previewAttachmentIndex by remember { mutableStateOf(-1) }

    // Build merged slash commands: client commands + server commands (deduplicated)
    val clientCmds = clientCommands().filter { cmd ->
        when (cmd.name) {
            "shell" -> supportsShell
            "compact", "share", "unshare", "undo", "redo" -> supportsCompact
            else -> true
        }
    }
    val allCommands = remember(commands, clientCmds) {
        mergeSlashCommands(clientCmds, commands)
    }

    // Slash command suggestions
    val showSlashSuggestions = !isShellMode && text.startsWith("/") && !text.contains(" ")
    val slashQuery = if (showSlashSuggestions) text.removePrefix("/").lowercase() else ""
    val filteredCommands = if (showSlashSuggestions) {
        allCommands.filter { cmd ->
            slashQuery.isEmpty() || cmd.name.lowercase().contains(slashQuery)
        }
    } else emptyList()
    val cursorPos = textFieldValue.selection.start.coerceIn(0, text.length)
    val textBeforeCursor = text.substring(0, cursorPos)

    Column(
        modifier = modifier
            .chatComposerPrimaryWidth()
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Thin divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp
        )

        // Slash command suggestions popup (scrollable, max 40% screen height)
        AnimatedVisibility(
            visible = showSlashSuggestions && filteredCommands.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .then(
                        if (isAmoled) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(12.dp),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 4.dp)
            ) {
                items(filteredCommands, key = { it.name }) { cmd ->
                    val showMcpChip = cmd.source == "mcp"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onTextFieldValueChange(TextFieldValue(""))
                                onSlashCommand(cmd)
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "/${cmd.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        if (cmd.description != null) {
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else if (showMcpChip) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        if (showMcpChip) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAmoled) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                                ),
                            ) {
                                Text(
                                    text = "MCP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // @ file mention suggestions popup
        AnimatedVisibility(
            visible = !isShellMode && fileSearchResults.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp)
            ) {
                items(
                    fileSearchResults.take(10),
                    key = { it }
                ) { path ->
                    val isDir = path.endsWith("/")
                    // Split into directory part + filename for display
                    val displayPath = if (isDir) path.trimEnd('/') else path
                    val lastSlash = displayPath.lastIndexOf('/')
                    val dirPart = if (lastSlash >= 0) displayPath.substring(0, lastSlash + 1) else ""
                    val namePart = if (lastSlash >= 0) displayPath.substring(lastSlash + 1) else displayPath

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFileSelected(path) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isDir)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = buildAnnotatedString {
                                if (dirPart.isNotEmpty()) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append(dirPart)
                                    }
                                }
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                    append(namePart)
                                }
                                if (isDir) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append("/")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Status row: working status (left) + context usage (right)
        val showContext = contextWindow > 0 && lastContextTokens > 0
        if (isBusy || showContext) {
            val lastRunningTool = if (isBusy) {
                messages.lastOrNull()?.parts
                    ?.filterIsInstance<Part.Tool>()
                    ?.lastOrNull { it.state is ToolState.Running }
            } else null

            val statusText = if (isBusy) {
                if (lastRunningTool != null) {
                    val title = (lastRunningTool.state as ToolState.Running).title
                    when (lastRunningTool.tool) {
                        "read" -> title ?: stringResource(R.string.chat_tool_reading_file)
                        "write" -> title ?: stringResource(R.string.chat_tool_writing_file)
                        "edit" -> title ?: stringResource(R.string.chat_tool_editing_file)
                        "bash" -> title ?: stringResource(R.string.chat_tool_running_command)
                        "glob", "list" -> title ?: stringResource(R.string.chat_tool_searching_files)
                        "grep" -> title ?: stringResource(R.string.chat_tool_searching_code)
                        "webfetch" -> title ?: stringResource(R.string.chat_tool_fetching_url)
                        "task" -> title ?: stringResource(R.string.chat_tool_running_subagent)
                        "todowrite" -> title ?: stringResource(R.string.chat_tool_updating_tasks)
                        else -> title ?: stringResource(R.string.chat_tool_running_tool, lastRunningTool.tool)
                    }
                } else {
                    stringResource(R.string.chat_tool_thinking)
                }
            } else null

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: working status
                if (isBusy && statusText != null) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDotsIndicator(
                            dotSize = 4.dp,
                            dotSpacing = 3.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }
                // Right: context usage (percentage)
                if (showContext) {
                    val percentage = Math.round(lastContextTokens.toDouble() / contextWindow * 100).toInt()
                    val contextColor = when {
                        percentage >= 90 -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        percentage >= 70 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Text(
                        text = stringResource(
                            R.string.chat_context_format,
                            percentage
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = contextColor
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Agent + Model + Variant + Attach selector row — small, subtle
            if (modelLabel.isNotEmpty() || agents.size > 1 || variantNames.isNotEmpty() || supportsAttachments) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scrollable area for agent/model/variant so paperclip always stays visible
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Agent selector — tap to open list picker
                        if (agents.size > 1) {
                            val agentColor = agentColor(selectedAgent, agents)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(agentColor.copy(alpha = 0.18f))
                                    .clickable(onClick = onAgentClick)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = selectedAgent.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = agentColor
                                )
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = agentColor.copy(alpha = 0.75f)
                                )
                            }
                        }

                        // Model selector — SECOND
                        if (modelLabel.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onModelClick() }
                                    .padding(horizontal = 3.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (selectedProviderId != null) {
                                    ProviderIcon(
                                        providerId = selectedProviderId,
                                        size = 13.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }

                        // Variant selector (thinking effort) — THIRD
                        if (variantNames.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onVariantClick)
                                    .padding(horizontal = 3.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = selectedVariant?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.chat_default_variant),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedVariant != null) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                                )
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }

                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Attach button (paperclip) — always visible, pinned right, aligned with Send button
                        if (supportsAttachments) {
                            IconButton(
                                onClick = onAttach,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = stringResource(R.string.chat_attach),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // Image attachment thumbnails
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments.size) { index ->
                        val attachment = attachments[index]
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            if (attachment.mime.startsWith("image/")) {
                                AsyncImage(
                                    model = imageThumbnailModel(attachment),
                                    contentDescription = attachment.filename,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { previewAttachmentIndex = index },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = attachment.filename,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .clickable { onRemoveAttachment(index) },
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chat_remove),
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (previewAttachmentIndex >= 0 && previewAttachmentIndex < attachments.size) {
                val attachment = attachments[previewAttachmentIndex]
                val imageBytes = remember(attachment.dataUrl) { decodeDataUrlBytes(attachment.dataUrl) }
                val bitmap = remember(imageBytes) {
                    imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                }

                if (bitmap != null) {
                    ImagePreviewDialog(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = attachment.filename,
                        onDismiss = { previewAttachmentIndex = -1 },
                        onSave = {
                            if (imageBytes != null) {
                                onSaveAttachment(imageBytes, attachment.mime, attachment.filename)
                            }
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = isShellMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        )
                        .then(
                            if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.chat_shell_mode_hold_send_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Input row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Text field — minimal style, no heavy outline
                val mentionHighlightColor = MaterialTheme.colorScheme.primary
                val mentionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                val visualTransformation = remember(confirmedFilePaths, mentionHighlightColor, mentionBgColor) {
                    if (isShellMode) {
                        VisualTransformation.None
                    } else {
                        FileMentionVisualTransformation(confirmedFilePaths, mentionHighlightColor, mentionBgColor)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        .then(
                            when {
                                isShellMode -> Modifier.border(
                                    width = if (isAmoled) 1.5.dp else 1.dp,
                                    color = if (isAmoled) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                    },
                                    shape = RoundedCornerShape(22.dp)
                                )
                                isAmoled -> Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                                else -> Modifier
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = onTextFieldValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (isShellMode) FontFamily.Monospace else FontFamily.Default
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        maxLines = 5,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = visualTransformation,
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // Send button — tap to send, long-press toggles shell mode
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isShellMode && !isSending) {
                                if (isAmoled) {
                                    Color.Black
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                }
                            } else {
                                Color.Transparent
                            }
                        )
                        .then(
                            if (isShellMode && !isSending) {
                                Modifier.border(
                                    width = if (isAmoled) 1.2.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) 0.88f else 0.75f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .combinedClickable(
                            onClick = {
                                if (canSend) {
                                    onSend()
                                }
                            },
                            onLongClick = {
                                if (supportsShell || isShellMode) {
                                    onInputModeChange(
                                        if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                                    )
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        BreathingCircleIndicator(
                            size = 20.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (isShellMode) {
                                stringResource(R.string.chat_send_shell)
                            } else {
                                stringResource(R.string.chat_send)
                            },
                            modifier = Modifier.size(20.dp),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else if (isShellMode && isAmoled && !isSending) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            }
                        )

                    }
                }
            }

            if (sendDisabledReasonText != null) {
                Text(
                    text = sendDisabledReasonText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * Card that displays a pending question from the server.
 *
 * Single-select: each option is an OutlinedButton that immediately submits.
 * Multi-select: checkboxes + Submit button.
 * "Type your own answer" expands an inline text field.
 */
@Composable
private fun QuestionCard(
    question: SseEvent.QuestionAsked,
    onSubmit: (answers: List<List<String>>) -> Unit,
    onReject: () -> Unit,
    unlockToken: Int = 0,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    val isSingle = question.questions.size == 1 && question.questions[0].multiple != true

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // Prevent multiple submissions. Unlock if the host rejected the reply so
    // the card is not left permanently disabled.
    var submitted by rememberSaveable(question.id) { mutableStateOf(false) }
    LaunchedEffect(unlockToken) {
        if (unlockToken > 0) submitted = false
    }

    // Track answers per question
    val answersPerQuestion = rememberSaveable(
        saver = listSaver<SnapshotStateList<List<String>>, ArrayList<String>>(
            save = { stateList -> stateList.map { ArrayList(it) } },
            restore = { saved -> saved.map { it.toList() }.toMutableStateList() },
        )
    ) {
        mutableStateListOf<List<String>>().apply {
            repeat(question.questions.size) { add(emptyList()) }
        }
    }

    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row — matches PermissionCard style
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    @Suppress("DEPRECATION")
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accentColor
                )
                Text(
                    text = stringResource(R.string.chat_question_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
            }

            // Question sections
            question.questions.forEachIndexed { index, q ->
                if (q.header.isNotBlank()) {
                    Text(
                        text = q.header,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor
                    )
                }
                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f)
                )

                Spacer(Modifier.height(2.dp))

                if (q.multiple) {
                    // ── Multi-select: checkboxes ──
                    q.options.forEach { option ->
                        val checked = index < answersPerQuestion.size && option.label in answersPerQuestion[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (checked) accentColor.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .toggleable(
                                    value = checked,
                                    enabled = !submitted,
                                    role = Role.Checkbox,
                                    onValueChange = { isChecked ->
                                        if (index < answersPerQuestion.size) {
                                            val current = answersPerQuestion[index].toMutableList()
                                            if (isChecked) {
                                                if (option.label !in current) current.add(option.label)
                                            } else {
                                                current.remove(option.label)
                                            }
                                            answersPerQuestion[index] = current.toList()
                                        }
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = accentColor,
                                    uncheckedColor = contentColor.copy(alpha = 0.5f)
                                )
                            )
                            Column {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor
                                )
                                if (option.description.isNotBlank()) {
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── Single-select: tappable option rows ──
                    q.options.forEach { option ->
                        val isSelected = index < answersPerQuestion.size && option.label in answersPerQuestion[index]
                        Surface(
                            onClick = {
                                if (!submitted) {
                                    performHaptic(hapticView, hapticOn)
                                    if (isSingle) {
                                        submitted = true
                                        onSubmit(listOf(listOf(option.label)))
                                    } else {
                                        if (index < answersPerQuestion.size) {
                                            answersPerQuestion[index] = listOf(option.label)
                                        }
                                    }
                                }
                            },
                                enabled = !submitted,
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) accentColor.copy(alpha = 0.12f) else if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                border = if (!isSelected && isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) accentColor else accentColor.copy(alpha = 0.7f)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) accentColor else contentColor
                                    )
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            text = option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // "Type your own answer" — inline text field
                if (q.custom != false) {
                    val currentAnswers = if (index < answersPerQuestion.size) answersPerQuestion[index] else emptyList()
                    val customAnswer = currentAnswers.firstOrNull { ans -> q.options.none { it.label == ans } }
                    
                    if (customAnswer != null) {
                        // Show selected custom answer
                         Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.RadioButtonChecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = accentColor
                                )
                                Text(
                                    text = customAnswer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = accentColor,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        if (!submitted && index < answersPerQuestion.size) {
                                            answersPerQuestion[index] = emptyList()
                                        }
                                    },
                                    enabled = !submitted,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chat_clear),
                                        modifier = Modifier.size(16.dp),
                                        tint = accentColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    } else {
                        var isEditingCustom by rememberSaveable(key = "qc_editing_$index") { mutableStateOf(false) }
                        var customText by rememberSaveable(key = "qc_customtext_$index") { mutableStateOf("") }

                        if (!isEditingCustom) {
                            Surface(
                                onClick = {
                                    isEditingCustom = true
                                },
                                enabled = !submitted,
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = accentColor.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = stringResource(R.string.question_custom_answer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = accentColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = customText,
                                onValueChange = { customText = it },
                                enabled = !submitted,
                                placeholder = {
                                    Text(
                                        stringResource(R.string.chat_type_answer),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(8.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                trailingIcon = {
                                    Row {
                                        IconButton(
                                            onClick = {
                                                val trimmed = customText.trim()
                                                if (trimmed.isNotBlank()) {
                                                    performHaptic(hapticView, hapticOn)
                                                    if (isSingle) {
                                                        submitted = true
                                                        onSubmit(listOf(listOf(trimmed)))
                                                    } else {
                                                        if (index < answersPerQuestion.size) {
                                                            answersPerQuestion[index] = listOf(trimmed)
                                                        }
                                                        isEditingCustom = false
                                                        customText = "" 
                                                    }
                                                }
                                            },
                                            enabled = customText.isNotBlank() && !submitted
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = stringResource(R.string.question_submit),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(onClick = { isEditingCustom = false; customText = "" }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.question_cancel),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Bottom actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = {
                        performHaptic(hapticView, hapticOn)
                        submitted = true
                        onReject()
                    },
                    enabled = !submitted
                ) {
                    Text(stringResource(R.string.chat_dismiss), style = MaterialTheme.typography.labelMedium)
                }
                if (!isSingle) {
                    Button(
                        onClick = {
                            performHaptic(hapticView, hapticOn)
                            submitted = true
                            onSubmit(answersPerQuestion.map { it.toList() })
                        },
                        enabled = answersPerQuestion.any { it.isNotEmpty() } && !submitted,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.question_submit), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
