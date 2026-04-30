package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.domain.model.McpSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelRuntimeStateTest {

    private val conn = ServerConnection.from("http://x")
    private val projectDir = "/p"

    @Test
    fun `runtime loaded propagates source to UI state`() = runTest {
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> runtimeLoaded() },
            writeMcpConfig = { _, _ -> Result.success(Unit) },
        )

        controller.load(conn, projectDir)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.Loaded)
        assertEquals(McpSource.Runtime, (state as McpUiState.Loaded).source)
        assertEquals(setOf("github", "stitch"), state.editedServers.keys)
    }

    @Test
    fun `toggle is a no-op when source is runtime`() = runTest {
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> runtimeLoaded() },
            writeMcpConfig = { _, _ -> Result.success(Unit) },
        )

        controller.load(conn, projectDir)
        advanceUntilIdle()
        val before = (controller.state.value as McpUiState.Loaded).editedServers.getValue("github").enabled
        controller.toggleServer("github")
        val after = (controller.state.value as McpUiState.Loaded).editedServers.getValue("github").enabled

        assertEquals("toggle suppressed for runtime source", before, after)
    }

    @Test
    fun `save is a no-op when source is runtime`() = runTest {
        var writeCalls = 0
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> runtimeLoaded() },
            writeMcpConfig = { _, _ ->
                writeCalls += 1
                Result.success(Unit)
            },
        )

        controller.load(conn, projectDir)
        advanceUntilIdle()
        controller.save()
        advanceUntilIdle()

        assertEquals(0, writeCalls)
    }

    @Test
    fun `runtime unavailable wraps fallback ui state`() = runTest {
        val checkedPaths = listOf("/p/.opencode/opencode.json")
        val fallback = McpConfigLoadState.NotFound(checkedPaths = checkedPaths)
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> McpConfigLoadState.RuntimeUnavailable(fallback) },
            writeMcpConfig = { _, _ -> Result.success(Unit) },
        )

        controller.load(conn, projectDir)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.RuntimeUnavailable)
        val inner = (state as McpUiState.RuntimeUnavailable).fallback
        assertTrue(inner is McpUiState.MissingConfig)
        assertEquals(checkedPaths, (inner as McpUiState.MissingConfig).checkedPaths)
    }

    @Test
    fun `file loaded keeps file source so save and toggle remain available`() = runTest {
        var writeCalls = 0
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> fileLoaded() },
            writeMcpConfig = { _, _ ->
                writeCalls += 1
                Result.success(Unit)
            },
        )

        controller.load(conn, projectDir)
        advanceUntilIdle()
        controller.toggleServer("a")
        val toggled = controller.state.value as McpUiState.Loaded
        controller.save()
        advanceUntilIdle()

        assertEquals(McpSource.File, toggled.source)
        assertEquals(false, toggled.editedServers.getValue("a").enabled)
        assertEquals(1, writeCalls)
    }

    private fun runtimeLoaded() = McpConfigLoadState.Loaded(
        config = McpConfig(
            filePath = "<runtime>",
            rawJson = "{}",
            servers = mapOf(
                "github" to McpServer(
                    name = "github",
                    type = null,
                    command = null,
                    args = emptyList(),
                    url = null,
                    enabled = true,
                ),
                "stitch" to McpServer(
                    name = "stitch",
                    type = null,
                    command = null,
                    args = emptyList(),
                    url = null,
                    enabled = true,
                ),
            ),
        ),
        source = McpSource.Runtime,
    )

    private fun fileLoaded() = McpConfigLoadState.Loaded(
        config = McpConfig(
            filePath = "/p/.opencode/opencode.json",
            rawJson = """{"mcp":{"a":{"command":"node"}}}""",
            servers = mapOf(
                "a" to McpServer(
                    name = "a",
                    type = null,
                    command = "node",
                    args = emptyList(),
                    url = null,
                    enabled = true,
                ),
            ),
        ),
        source = McpSource.File,
    )
}
