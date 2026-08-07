package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.wuxie233.codecarry.domain.model.McpRuntimeSnapshot
import dev.wuxie233.codecarry.domain.model.McpRuntimeState
import dev.wuxie233.codecarry.domain.model.McpRuntimeStatus
import dev.wuxie233.codecarry.ui.screens.sessions.McpUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpManagementSheetRuntimeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun runtimeConnectedServerRendersSwitchOn() {
        render(runtime(McpRuntimeStatus("fs", McpRuntimeState.CONNECTED)))

        compose.onNodeWithText("fs").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].assertIsOn()
    }

    @Test
    fun runtimeDisabledServerRendersSwitchOffAndDisabledLabel() {
        render(runtime(McpRuntimeStatus("fs", McpRuntimeState.DISABLED)))

        compose.onNodeWithText("未启用").assertExists()
        compose.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test
    fun runtimeFailedServerRendersSwitchOffAndFailedLabel() {
        render(runtime(McpRuntimeStatus("fs", McpRuntimeState.FAILED)))

        compose.onNodeWithText("连接失败").assertExists()
        compose.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test
    fun runtimeNeedsAuthServerRendersSwitchOffAndAuthLabel() {
        render(runtime(McpRuntimeStatus("fs", McpRuntimeState.NEEDS_AUTH)))

        compose.onNodeWithText("需要授权").assertExists()
        compose.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test
    fun pendingRuntimeRowShowsProgressAndDisabledSwitch() {
        render(
            McpUiState.Runtime(
                snapshot = snapshot(McpRuntimeStatus("fs", McpRuntimeState.CONNECTED)),
                pendingNames = setOf("fs"),
            ),
        )

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onAllNodes(isToggleable())[0].assertIsNotEnabled()
    }

    @Test
    fun rowErrorRendersMessageAndDismissButton() {
        render(
            McpUiState.Runtime(
                snapshot = snapshot(McpRuntimeStatus("fs", McpRuntimeState.FAILED)),
                rowErrors = mapOf("fs" to "boom"),
            ),
        )

        compose.onNodeWithText("boom").assertExists()
        compose.onNodeWithText("隐藏").assertExists()
    }

    @Test
    fun fallbackReadOnlyRendersBannerAndNoSwitches() {
        render(
            McpUiState.FallbackReadOnly(
                snapshot = snapshot(
                    McpRuntimeStatus("fs", McpRuntimeState.UNKNOWN),
                    supportsRuntimeControl = false,
                ),
            ),
        )

        compose.onNodeWithText("运行时控制需要更新的 OpenCode 服务器；当前仅显示配置文件中声明的 MCP 服务器。")
            .assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun emptyStateRendersEmptyMessage() {
        render(McpUiState.Empty)

        compose.onNodeWithText("暂无 MCP 服务器").assertExists()
    }

    @Test
    fun loadErrorRendersRetryButton() {
        render(McpUiState.LoadError("boom"), canReload = true)

        compose.onNodeWithText("重试").assertExists()
    }

    @Test
    fun runtimeEmptyListDoesNotCrashAndRendersNoSwitches() {
        render(McpUiState.Runtime(snapshot = snapshot()))

        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    private fun render(state: McpUiState, canReload: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                McpManagementSheetContent(
                    projectName = "proj",
                    state = state,
                    canReload = canReload,
                    onRefresh = {},
                    onRetry = {},
                    onToggle = {},
                    onDismissRowError = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun runtime(status: McpRuntimeStatus): McpUiState.Runtime =
        McpUiState.Runtime(snapshot = snapshot(status))

    private fun snapshot(
        vararg statuses: McpRuntimeStatus,
        supportsRuntimeControl: Boolean = true,
    ): McpRuntimeSnapshot = McpRuntimeSnapshot(
        servers = statuses.toList(),
        supportsRuntimeControl = supportsRuntimeControl,
    )
}
