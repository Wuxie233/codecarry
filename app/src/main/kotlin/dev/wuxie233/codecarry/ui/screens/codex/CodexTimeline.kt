package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexFileChange
import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.data.codex.CodexTurnPlan
import dev.wuxie233.codecarry.ui.screens.chat.MessageMarkdownContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Backend presentation stays independent from chat navigation and transport ownership. */
@Composable
internal fun CodexTimelineItem(item: CodexThreadItem, onOpenThread: (String) -> Unit) {
    when (item.type) {
        "userMessage" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (!item.text.isNullOrBlank()) MessageMarkdownContent(
                        markdown = item.text,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        isUser = true,
                    )
                    // The daemon may omit image bytes from restored history.
                    val images = codexTimelineImageCount(item)
                    if (images > 0) Text(
                        stringResource(R.string.codex_timeline_images, images),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        "agentMessage" -> CodexTimelineMarkdown(item.text.orEmpty())
        "reasoning" -> CodexDisclosure(
            key = item.id ?: item.type,
            title = stringResource(R.string.codex_thinking),
            status = item.status,
        ) {
            val summary = item.reasoningSummary.joinToString("\n\n").ifBlank { item.text.orEmpty() }
            if (summary.isNotBlank()) CodexTimelineMarkdown(summary)
            val content = item.reasoningContent.joinToString("\n\n")
            if (content.isNotBlank() && content != summary) CodexTimelineMarkdown(content)
        }
        "plan" -> CodexDisclosure(item.id ?: item.type, stringResource(R.string.codex_timeline_plan), item.status) {
            CodexTimelineMarkdown(item.text.orEmpty())
        }
        "fileChange" -> CodexDisclosure(item.id ?: item.type, stringResource(R.string.codex_tool_file_changes), item.status) {
            if (item.fileChanges.isEmpty()) Text(stringResource(R.string.codex_timeline_details_unavailable))
            item.fileChanges.forEach { change -> CodexFileChangeRow(change) }
        }
        "subAgentActivity" -> CodexSubAgentActivityRow(item, onOpenThread)
        "contextCompaction" -> Text(
            stringResource(R.string.codex_context_compacted),
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            val title = when (item.type) {
                "commandExecution" -> item.command ?: stringResource(R.string.codex_tool_command)
                "mcpToolCall" -> stringResource(R.string.codex_tool_mcp)
                "webSearch" -> stringResource(R.string.codex_tool_web_search)
                "collabAgentToolCall" -> stringResource(R.string.codex_tool_collaboration)
                else -> item.type.replaceFirstChar { it.uppercase() }
            }
            CodexDisclosure(item.id ?: item.type, title, item.status) {
                val collaboration = item.collabAgentCall
                if (collaboration != null) {
                    collaboration.prompt?.takeIf { it.isNotBlank() }?.let { CodexTimelineMarkdown(it) }
                    collaboration.receiverThreadIds.distinct().forEach { threadId ->
                        val state = collaboration.agentsStates[threadId]
                        TextButton(onClick = { onOpenThread(threadId) }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.codex_timeline_open_subagent), style = MaterialTheme.typography.labelLarge)
                                Text(threadId, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                state?.status?.let { Text(codexTimelineStatus(it), style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        state?.message?.takeIf { it.isNotBlank() }?.let { CodexTimelineMarkdown(it) }
                    }
                } else {
                    val details = item.output ?: item.text ?: item.raw.toString()
                    CodexMonospaceContent(details)
                }
            }
        }
    }
}

@Composable
private fun CodexSubAgentActivityRow(item: CodexThreadItem, onOpenThread: (String) -> Unit) {
    val threadId = (item.raw["agentThreadId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val path = (item.raw["agentPath"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val kind = (item.raw["kind"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.codex_timeline_subagent_activity), style = MaterialTheme.typography.labelLarge)
        path?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        kind?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item.status?.let { Text(codexTimelineStatus(it), style = MaterialTheme.typography.labelSmall) }
        if (threadId != null) TextButton(onClick = { onOpenThread(threadId) }) {
            Text(stringResource(R.string.codex_timeline_open_subagent))
        }
    }
}

@Composable
internal fun CodexTurnPlanCard(plan: CodexTurnPlan, modifier: Modifier = Modifier) {
    CodexDisclosure("turn-plan", stringResource(R.string.codex_timeline_plan), modifier = modifier, initiallyExpanded = true) {
        plan.explanation?.takeIf { it.isNotBlank() }?.let { CodexTimelineMarkdown(it) }
        plan.steps.forEach { step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                if (step.status == "completed") Icon(Icons.Default.Check, contentDescription = stringResource(R.string.codex_timeline_completed))
                else Text(if (step.status == "inProgress" || step.status == "in_progress") "●" else "○", Modifier.padding(horizontal = 5.dp))
                Column(Modifier.padding(start = 8.dp)) {
                    Text(step.step, style = MaterialTheme.typography.bodyMedium)
                    Text(codexTimelineStatus(step.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun CodexFileChangeRow(change: CodexFileChange) {
    CodexDisclosure("file:${change.path}", change.path, change.kind) {
        change.movePath?.let { Text(stringResource(R.string.codex_timeline_moved_to, it), style = MaterialTheme.typography.labelMedium) }
        CodexDiffContent(change.diff)
    }
}

@Composable
internal fun CodexDiffContent(diff: String, modifier: Modifier = Modifier) {
    if (diff.isBlank()) {
        Text(stringResource(R.string.codex_timeline_diff_unavailable), modifier, style = MaterialTheme.typography.bodySmall)
        return
    }
    val colors = MaterialTheme.colorScheme
    val highlighted = remember(diff, colors) {
        buildAnnotatedString {
            diff.lineSequence().forEachIndexed { index, line ->
                if (index > 0) append('\n')
                val color = when {
                    line.startsWith("+") -> colors.primary
                    line.startsWith("-") -> colors.error
                    line.startsWith("@@") -> colors.tertiary
                    else -> colors.onSurfaceVariant
                }
                withStyle(SpanStyle(color = color)) { append(line) }
            }
        }
    }
    SelectionContainer(modifier) {
        Text(
            text = highlighted,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
        )
    }
}

@Composable
private fun CodexDisclosure(
    key: String,
    title: String,
    status: String? = null,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = stringResource(if (expanded) R.string.codex_timeline_collapse else R.string.codex_timeline_expand))
            Text(title, Modifier.weight(1f).padding(start = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            status?.let { Text(codexTimelineStatus(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (expanded) Column(Modifier.padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun CodexTimelineMarkdown(text: String) {
    if (text.isNotBlank()) MessageMarkdownContent(
        markdown = text,
        textColor = MaterialTheme.colorScheme.onSurface,
        isUser = false,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun CodexMonospaceContent(text: String) {
    SelectionContainer {
        Text(text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), softWrap = false, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun codexTimelineStatus(status: String): String = when (status) {
    "completed" -> stringResource(R.string.codex_timeline_completed)
    "inProgress", "in_progress", "running" -> stringResource(R.string.codex_timeline_running)
    "pending" -> stringResource(R.string.codex_timeline_pending)
    "failed", "errored" -> stringResource(R.string.codex_timeline_failed)
    "add", "added" -> stringResource(R.string.codex_timeline_added)
    "delete", "deleted" -> stringResource(R.string.codex_timeline_deleted)
    "update", "modified" -> stringResource(R.string.codex_timeline_modified)
    else -> status
}

private fun codexTimelineImageCount(item: CodexThreadItem): Int =
    (item.raw["content"] as? JsonArray).orEmpty().count { block ->
        val type = ((block as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        type == "image" || type == "localImage"
    }
