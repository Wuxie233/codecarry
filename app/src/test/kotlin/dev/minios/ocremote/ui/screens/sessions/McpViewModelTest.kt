package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelTest {

    @Test
    fun `load maps empty MCP config to explicit empty state`() = runTest {
        val controller = newController(
            scope = this,
            readState = { _, _ ->
                McpConfigLoadState.Empty(
                    config = McpConfig(
                        filePath = "/workspace/project/.opencode/config.json",
                        rawJson = "{}",
                        servers = emptyMap(),
                    )
                )
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.Empty)
        assertEquals("暂无 MCP 服务器", (state as McpUiState.Empty).title)
    }

    @Test
    fun `load maps fetch failure to explicit error state`() = runTest {
        val controller = newController(
            scope = this,
            readState = { _, _ ->
                McpConfigLoadState.Error(
                    filePath = "/workspace/project/.opencode/config.json",
                    message = "boom",
                )
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.Error)
        assertEquals("boom", (state as McpUiState.Error).message)
    }

    @Test
    fun `retry triggers a second load attempt after error`() = runTest {
        var attempts = 0
        val states = ArrayDeque<McpConfigLoadState>().apply {
            add(
                McpConfigLoadState.Error(
                    filePath = "/workspace/project/.opencode/config.json",
                    message = "boom",
                )
            )
            add(McpConfigLoadState.Loaded(sampleConfig(command = "npx")))
        }
        val controller = newController(
            scope = this,
            readState = { _, _ ->
                attempts += 1
                states.removeFirst()
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.retry()
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertTrue(controller.state.value is McpUiState.Loaded)
    }

    @Test
    fun `refresh triggers a second load attempt after success`() = runTest {
        var attempts = 0
        val states = ArrayDeque<McpConfigLoadState>().apply {
            add(McpConfigLoadState.Loaded(sampleConfig(command = "npx")))
            add(McpConfigLoadState.Loaded(sampleConfig(command = "node")))
        }
        val controller = newController(
            scope = this,
            readState = { _, _ ->
                attempts += 1
                states.removeFirst()
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()

        assertEquals(2, attempts)
        val state = controller.state.value as McpUiState.Loaded
        assertEquals("node", state.config.servers.getValue("filesystem").command)
    }

    private fun newController(
        scope: CoroutineScope,
        readState: suspend (ServerConnection, String) -> McpConfigLoadState,
    ): McpStateController {
        return McpStateController(
            scope = scope,
            readMcpConfigState = readState,
            writeMcpConfig = { _, _ -> Result.success(Unit) },
        )
    }

    private fun sampleConfig(command: String) = McpConfig(
        filePath = "/workspace/project/.opencode/config.json",
        rawJson = "{}",
        servers = mapOf(
            "filesystem" to McpServer(
                name = "filesystem",
                type = "stdio",
                command = command,
                args = listOf("server.js"),
            )
        ),
    )

    private val testConn = ServerConnection.from("http://127.0.0.1:4096")
}
