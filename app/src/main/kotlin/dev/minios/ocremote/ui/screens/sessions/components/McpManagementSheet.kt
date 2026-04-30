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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.McpRuntimeSnapshot
import dev.minios.ocremote.domain.model.McpRuntimeState
import dev.minios.ocremote.domain.model.McpRuntimeStatus
import dev.minios.ocremote.ui.components.EmptyStateCard
import dev.minios.ocremote.ui.components.ErrorStateCard
import dev.minios.ocremote.ui.components.LoadingStateCard
import dev.minios.ocremote.ui.screens.sessions.McpUiState
import dev.minios.ocremote.ui.screens.sessions.McpViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManagementSheet(
    projectName: String,
    viewModel: McpViewModel,
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        McpManagementSheetContent(
            projectName = projectName,
            state = state,
            canReload = viewModel.canReload(),
            onRefresh = viewModel::refresh,
            onRetry = viewModel::retry,
            onToggle = viewModel::toggleServer,
            onDismissRowError = viewModel::dismissRowError,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun McpManagementSheetContent(
    projectName: String,
    state: McpUiState,
    canReload: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onToggle: (String) -> Unit,
    onDismissRowError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val requestRefresh = {
        if (canReload) {
            onRefresh()
        }
    }

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
                enabled = canReload,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新 MCP 状态",
                )
            }
        }

        when (val current = state) {
            McpUiState.Loading -> LoadingStateCard(label = "正在加载 MCP 运行时状态")

            McpUiState.Empty -> EmptyStateCard(
                title = "暂无 MCP 服务器",
                message = "当前项目没有运行时 MCP 服务器。",
                action = { CloseButton(onDismiss) },
            )

            is McpUiState.LoadError -> {
                ErrorStateCard(
                    title = "无法加载 MCP 运行时状态",
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
                        onClick = onRetry,
                        enabled = canReload,
                    ) {
                        Text("重试")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }

            is McpUiState.FallbackReadOnly -> {
                Text(
                    text = "运行时控制需要更新的 OpenCode 服务器；当前仅显示配置文件中声明的 MCP 服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                McpRuntimeList(
                    snapshot = current.snapshot,
                    pendingNames = emptySet(),
                    rowErrors = emptyMap(),
                    readOnly = true,
                    onToggle = {},
                    onDismissRowError = {},
                )
                CloseButton(onDismiss)
            }

            is McpUiState.Runtime -> {
                current.sheetError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                McpRuntimeList(
                    snapshot = current.snapshot,
                    pendingNames = current.pendingNames,
                    rowErrors = current.rowErrors,
                    readOnly = false,
                    onToggle = onToggle,
                    onDismissRowError = onDismissRowError,
                )
                CloseButton(onDismiss)
            }
        }
    }
}

@Composable
private fun McpRuntimeList(
    snapshot: McpRuntimeSnapshot,
    pendingNames: Set<String>,
    rowErrors: Map<String, String>,
    readOnly: Boolean,
    onToggle: (String) -> Unit,
    onDismissRowError: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
    ) {
        items(snapshot.servers, key = { it.name }) { server ->
            McpServerRow(
                server = server,
                pending = server.name in pendingNames,
                rowError = rowErrors[server.name],
                readOnly = readOnly,
                onToggle = { onToggle(server.name) },
                onDismissError = { onDismissRowError(server.name) },
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun McpServerRow(
    server: McpRuntimeStatus,
    pending: Boolean,
    rowError: String?,
    readOnly: Boolean,
    onToggle: () -> Unit,
    onDismissError: () -> Unit,
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
            Text(
                text = stateLabel(server.state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            server.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            rowError?.let { message ->
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) {
                        Text("隐藏")
                    }
                }
            }
        }

        if (!readOnly) {
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            Switch(
                checked = server.state == McpRuntimeState.CONNECTED,
                onCheckedChange = { onToggle() },
                enabled = !pending,
                modifier = Modifier.padding(start = if (pending) 8.dp else 16.dp),
            )
        }
    }
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) {
            Text("关闭")
        }
    }
}

private fun stateLabel(state: McpRuntimeState): String = when (state) {
    McpRuntimeState.CONNECTED -> "已连接"
    McpRuntimeState.DISABLED -> "未启用"
    McpRuntimeState.FAILED -> "连接失败"
    McpRuntimeState.NEEDS_AUTH -> "需要授权"
    McpRuntimeState.NEEDS_CLIENT_REGISTRATION -> "需要注册"
    McpRuntimeState.UNKNOWN -> "状态未知"
}
