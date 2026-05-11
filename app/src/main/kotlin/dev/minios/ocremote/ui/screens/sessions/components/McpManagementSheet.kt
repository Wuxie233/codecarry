package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.ui.components.EmptyStateCard
import dev.minios.ocremote.ui.components.ErrorStateCard
import dev.minios.ocremote.ui.components.LoadingStateCard
import dev.minios.ocremote.ui.screens.sessions.McpUiState
import dev.minios.ocremote.ui.screens.sessions.McpViewModel

private const val RUNTIME_SOURCE_SENTINEL = "<runtime>"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManagementSheet(
    projectName: String,
    viewModel: McpViewModel,
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var lastServers by remember { mutableStateOf<Map<String, McpServer>>(emptyMap()) }
    var lastSaveError by remember { mutableStateOf<String?>(null) }
    var pendingRefreshConfirm by remember { mutableStateOf(false) }

    val requestRefresh = {
        val loaded = state as? McpUiState.Loaded
        if (loaded?.dirty == true) {
            pendingRefreshConfirm = true
        } else if (viewModel.canReload()) {
            viewModel.refresh()
        }
    }

    LaunchedEffect(state) {
        when (val current = state) {
            is McpUiState.Loaded -> {
                lastServers = current.editedServers
                lastSaveError = current.saveError
            }

            McpUiState.SaveSuccess -> onSaveSuccess()
            else -> Unit
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MCP 服务器 · $projectName",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = requestRefresh,
                    enabled = viewModel.canReload(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新 MCP 配置",
                    )
                }
            }

            when (val current = state) {
                McpUiState.Loading -> {
                    LoadingStateCard(label = "正在加载 MCP 配置")
                }

                is McpUiState.EmptyConfig -> {
                    val message = if (current.fallbackExhausted) {
                        val pathText = if (current.filePath == RUNTIME_SOURCE_SENTINEL) "" else "。最近一次检查的配置位于 ${current.filePath}"
                        "已检查项目与全局 OpenCode 配置，但都未声明任何 MCP 服务器$pathText。在该文件中加入 mcp 后下拉刷新即可生效。"
                    } else {
                        val pathText = if (current.filePath == RUNTIME_SOURCE_SENTINEL) "" else " ${current.filePath}"
                        "已找到配置$pathText，但其中未声明任何 MCP 服务器。"
                    }
                    EmptyStateCard(
                        title = "暂无 MCP 服务器",
                        message = message,
                        action = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onDismiss) {
                                    Text("关闭")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = requestRefresh,
                                    enabled = viewModel.canReload(),
                                ) {
                                    Text("刷新")
                                }
                            }
                        },
                    )
                }

                is McpUiState.MissingConfig -> {
                    val firstPath = current.checkedPaths.firstOrNull { it != RUNTIME_SOURCE_SENTINEL } ?: "未知路径"
                    var pathsExpanded by remember(current.checkedPaths) { mutableStateOf(false) }
                    val allCheckedPaths = current.checkedPaths.filter { it != RUNTIME_SOURCE_SENTINEL }.joinToString("\n") { "• $it" }
                    EmptyStateCard(
                        title = "未找到 MCP 配置",
                        message = "优先检查路径：\n• $firstPath",
                        action = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (current.checkedPaths.size > 1) {
                                    TextButton(onClick = { pathsExpanded = !pathsExpanded }) {
                                        Text(if (pathsExpanded) "收起检查路径" else "查看全部检查路径")
                                    }
                                    if (pathsExpanded) {
                                        Text(
                                            text = allCheckedPaths,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = onDismiss) {
                                        Text("关闭")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = requestRefresh,
                                        enabled = viewModel.canReload(),
                                    ) {
                                        Text("刷新")
                                    }
                                }
                            }
                        },
                    )
                }

                is McpUiState.ReadError -> {
                    ErrorStateCard(
                        title = "无法读取 MCP 配置",
                        message = current.message,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = viewModel::retry,
                            enabled = viewModel.canReload(),
                        ) {
                            Text("重试")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }

                is McpUiState.ParseError -> {
                    ErrorStateCard(
                        title = "MCP 配置解析失败",
                        message = "${current.filePath}\n${current.message} (请检查是否使用了旧版的 mcpServers 字段)",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = viewModel::retry,
                            enabled = viewModel.canReload(),
                        ) {
                            Text("重试")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }

                is McpUiState.RuntimeUnavailable -> {
                    ErrorStateCard(
                        title = "OpenCode 运行时状态不可用",
                        message = "正在显示本地配置文件诊断结果。",
                    )
                    // Render fallback state
                    when (val fallback = current.fallback) {
                        is McpUiState.EmptyConfig -> {
                            val message = if (fallback.fallbackExhausted) {
                                val pathText = if (fallback.filePath == RUNTIME_SOURCE_SENTINEL) "" else "。最近一次检查的配置位于 ${fallback.filePath}"
                                "已检查项目与全局 OpenCode 配置，但都未声明任何 MCP 服务器$pathText。在该文件中加入 mcp 后下拉刷新即可生效。"
                            } else {
                                val pathText = if (fallback.filePath == RUNTIME_SOURCE_SENTINEL) "" else " ${fallback.filePath}"
                                "已找到配置$pathText，但其中未声明任何 MCP 服务器。"
                            }
                            EmptyStateCard(
                                title = "暂无 MCP 服务器",
                                message = message,
                                action = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = onDismiss) {
                                            Text("关闭")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = requestRefresh,
                                            enabled = viewModel.canReload(),
                                        ) {
                                            Text("刷新")
                                        }
                                    }
                                },
                            )
                        }
                        is McpUiState.MissingConfig -> {
                            val firstPath = fallback.checkedPaths.firstOrNull { it != RUNTIME_SOURCE_SENTINEL } ?: "未知路径"
                            var pathsExpanded by remember(fallback.checkedPaths) { mutableStateOf(false) }
                            val allCheckedPaths = fallback.checkedPaths.filter { it != RUNTIME_SOURCE_SENTINEL }.joinToString("\n") { "• $it" }
                            EmptyStateCard(
                                title = "未找到 MCP 配置",
                                message = "优先检查路径：\n• $firstPath",
                                action = {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (fallback.checkedPaths.size > 1) {
                                            TextButton(onClick = { pathsExpanded = !pathsExpanded }) {
                                                Text(if (pathsExpanded) "收起检查路径" else "查看全部检查路径")
                                            }
                                            if (pathsExpanded) {
                                                Text(
                                                    text = allCheckedPaths,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = onDismiss) {
                                                Text("关闭")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = requestRefresh,
                                                enabled = viewModel.canReload(),
                                            ) {
                                                Text("刷新")
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        is McpUiState.ReadError -> {
                            ErrorStateCard(
                                title = "无法读取 MCP 配置",
                                message = fallback.message,
                            )
                        }
                        is McpUiState.ParseError -> {
                            ErrorStateCard(
                                title = "MCP 配置解析失败",
                                message = "${fallback.filePath}\n${fallback.message} (请检查是否使用了旧版的 mcpServers 字段)",
                            )
                        }
                        is McpUiState.Loaded -> {
                            val servers = fallback.editedServers
                            val isReadOnly = fallback.source == dev.minios.ocremote.domain.model.McpSource.Runtime
                            val sourceText = if (isReadOnly) {
                                "来自 OpenCode 服务运行时状态（只读）"
                            } else if (fallback.config.filePath == RUNTIME_SOURCE_SENTINEL) {
                                ""
                            } else {
                                fallback.config.filePath
                            }

                            if (sourceText.isNotBlank()) {
                                Text(
                                    text = sourceText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                            ) {
                                items(servers.entries.toList(), key = { it.key }) { (name, server) ->
                                    McpServerRow(
                                        server = server,
                                        enabled = !isReadOnly,
                                        onToggle = { viewModel.toggleServer(name) },
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                            }
                        }
                        else -> {}
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = viewModel::retry,
                            enabled = viewModel.canReload(),
                        ) {
                            Text("重试")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }

                is McpUiState.Loaded,
                McpUiState.Saving,
                McpUiState.SaveSuccess,
                -> {
                    val loaded = current as? McpUiState.Loaded
                    val servers = loaded?.editedServers ?: lastServers
                    val isSaving = current is McpUiState.Saving
                    val isDirty = loaded?.dirty == true
                    val saveError = loaded?.saveError ?: lastSaveError
                    val isReadOnly = loaded?.source == dev.minios.ocremote.domain.model.McpSource.Runtime

                    val sourceText = if (isReadOnly) {
                        "来自 OpenCode 服务运行时状态（只读）"
                    } else if (loaded?.config?.filePath == RUNTIME_SOURCE_SENTINEL) {
                        ""
                    } else {
                        loaded?.config?.filePath ?: ""
                    }

                    if (sourceText.isNotBlank()) {
                        Text(
                            text = sourceText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    ) {
                        items(servers.entries.toList(), key = { it.key }) { (name, server) ->
                            McpServerRow(
                                server = server,
                                enabled = !isSaving && !isReadOnly,
                                onToggle = { viewModel.toggleServer(name) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                    }

                    saveError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !isSaving) {
                            Text("关闭")
                        }
                        if (!isReadOnly) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.save() },
                                enabled = !isSaving && isDirty,
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text("保存")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingRefreshConfirm) {
        AlertDialog(
            onDismissRequest = { pendingRefreshConfirm = false },
            title = { Text("将丢失未保存的修改") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRefreshConfirm = false
                        if (viewModel.canReload()) {
                            viewModel.refresh()
                        }
                    },
                ) {
                    Text("继续刷新")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRefreshConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServer,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val preview = listOfNotNull(
                server.command,
                server.args.take(2).joinToString(" ").takeIf { it.isNotBlank() },
            ).joinToString(" ")

            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Switch(
            checked = server.enabled,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            modifier = Modifier
                .padding(start = 16.dp)
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = "${server.name} 启用状态" },
        )
    }
}
