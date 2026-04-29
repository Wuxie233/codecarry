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
import kotlinx.serialization.SerializationException
import java.io.IOException
import org.junit.Test
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelTest {

    @Test
    fun loadMapsEmptyConfigToEmptyConfigStateNotEmptyGeneric() = runTest {
        val filePath = "/workspace/project/.opencode/config.json"
        val controller = newController(
            scope = this,
            readState = { _, _ ->
                McpConfigLoadState.Empty(
                    config = McpConfig(
                        filePath = filePath,
                        rawJson = "{}",
                        servers = emptyMap(),
                    )
                )
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.EmptyConfig)
        assertEquals(filePath, (state as McpUiState.EmptyConfig).filePath)
        assertTrue(state.fallbackExhausted)
    }

    @Test
    fun loadMapsNotFoundToMissingConfigState() = runTest {
        val checkedPaths = listOf(
            "/workspace/project/.opencode/opencode.json",
            "/workspace/project/.opencode/config.json",
        )
        val controller = newController(
            scope = this,
            readState = { _, _ -> McpConfigLoadState.NotFound(checkedPaths) },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.MissingConfig)
        assertEquals(checkedPaths, (state as McpUiState.MissingConfig).checkedPaths)
    }

    @Test
    fun loadMapsParseFailureToParseErrorState() = runTest {
        val filePath = "/workspace/project/.opencode/config.json"
        val controller = newController(
            scope = this,
            readState = { _, _ ->
                McpConfigLoadState.Error(
                    filePath = filePath,
                    message = "Failed to parse MCP config",
                    cause = SerializationException("Unexpected JSON token"),
                )
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.ParseError)
        assertEquals(filePath, (state as McpUiState.ParseError).filePath)
        assertEquals("Failed to parse MCP config", state.message)
    }

    @Test
    fun loadMapsReadFailurePreservesLastLoaded() = runTest {
        val errorMessage = "Failed to read OpenCode file /workspace/project/.opencode/config.json: 500"
        val states = ArrayDeque<McpConfigLoadState>().apply {
            add(McpConfigLoadState.Loaded(sampleConfig(command = "npx")))
            add(
                McpConfigLoadState.Error(
                    filePath = "/workspace/project/.opencode/config.json",
                    message = errorMessage,
                    cause = IOException(errorMessage),
                )
            )
        }
        val controller = newController(
            scope = this,
            readState = { _, _ -> states.removeFirst() },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()

        val errorState = controller.state.value
        assertTrue(errorState is McpUiState.ReadError)
        assertEquals(errorMessage, (errorState as McpUiState.ReadError).message)

        controller.toggleServer("filesystem")

        val loadedState = controller.state.value as McpUiState.Loaded
        assertTrue(loadedState.dirty)
        assertEquals(false, loadedState.editedServers.getValue("filesystem").enabled)
    }

    @Test
    fun `load maps fetch failure to explicit read error state`() = runTest {
        val controller = newController(
            scope = this,
            readState = { _, _ ->
                McpConfigLoadState.Error(
                    filePath = "/workspace/project/.opencode/config.json",
                    message = "boom",
                    cause = IOException("boom"),
                )
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.ReadError)
        assertEquals("boom", (state as McpUiState.ReadError).message)
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

    @Test
    fun refreshAfterSaveFailurePreservesUnsavedEdits() = runTest {
        val states = ArrayDeque<McpConfigLoadState>().apply {
            add(McpConfigLoadState.Loaded(configWithServers("alpha" to true, "beta" to true)))
            add(McpConfigLoadState.Loaded(configWithServers("alpha" to true, "beta" to true)))
        }
        val controller = newController(
            scope = this,
            readState = { _, _ -> states.removeFirst() },
            writeMcpConfig = { _, _ -> Result.failure(IllegalStateException("network blip")) },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.toggleServer("beta")
        controller.save()
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()

        val state = controller.state.value as McpUiState.Loaded
        assertTrue(state.dirty)
        assertEquals(false, state.editedServers.getValue("beta").enabled)
        assertEquals(true, state.editedServers.getValue("alpha").enabled)
    }

    @Test
    fun refreshAfterServerKeysChangeDropsStaleEdits() = runTest {
        val refreshedConfig = configWithServers("alpha" to true, "charlie" to false)
        val states = ArrayDeque<McpConfigLoadState>().apply {
            add(McpConfigLoadState.Loaded(configWithServers("alpha" to true, "beta" to true)))
            add(McpConfigLoadState.Loaded(refreshedConfig))
        }
        val controller = newController(
            scope = this,
            readState = { _, _ -> states.removeFirst() },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.toggleServer("beta")
        controller.refresh()
        advanceUntilIdle()

        val state = controller.state.value as McpUiState.Loaded
        assertEquals(false, state.dirty)
        assertEquals(refreshedConfig.servers, state.editedServers)
    }

    @Test
    fun refreshAfterUnchangedServerDisappearsPreservesDirtyEdit() = runTest {
        val refreshedConfig = configWithServers("beta" to true, "gamma" to true)
        val states = ArrayDeque<McpConfigLoadState>().apply {
            add(McpConfigLoadState.Loaded(configWithServers("alpha" to true, "beta" to true, "gamma" to true)))
            add(McpConfigLoadState.Loaded(refreshedConfig))
        }
        val controller = newController(
            scope = this,
            readState = { _, _ -> states.removeFirst() },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.toggleServer("beta")
        controller.refresh()
        advanceUntilIdle()

        val state = controller.state.value as McpUiState.Loaded
        assertTrue(state.dirty)
        assertEquals(false, state.editedServers.getValue("beta").enabled)
        assertEquals(true, state.editedServers.getValue("gamma").enabled)
    }

    @Test
    fun refreshAfterToggleBackToOriginalRemainsClean() = runTest {
        val config = configWithServers("alpha" to true, "beta" to true)
        val states = ArrayDeque<McpConfigLoadState>().apply {
            add(McpConfigLoadState.Loaded(config))
            add(McpConfigLoadState.Loaded(config))
        }
        val controller = newController(
            scope = this,
            readState = { _, _ -> states.removeFirst() },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.toggleServer("beta")
        controller.toggleServer("beta")
        controller.refresh()
        advanceUntilIdle()

        val state = controller.state.value as McpUiState.Loaded
        assertEquals(false, state.dirty)
        assertEquals(config.servers, state.editedServers)
    }

    private fun newController(
        scope: CoroutineScope,
        readState: suspend (ServerConnection, String) -> McpConfigLoadState,
        writeMcpConfig: suspend (ServerConnection, McpConfig) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    ): McpStateController {
        return McpStateController(
            scope = scope,
            readMcpConfigState = readState,
            writeMcpConfig = writeMcpConfig,
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

    private fun configWithServers(vararg servers: Pair<String, Boolean>) = McpConfig(
        filePath = "/workspace/project/.opencode/config.json",
        rawJson = "{}",
        servers = servers.associate { (name, enabled) ->
            name to McpServer(
                name = name,
                type = "stdio",
                command = "node",
                args = listOf("$name.js"),
                enabled = enabled,
            )
        },
    )

    private val testConn = ServerConnection.from("http://127.0.0.1:4096")
}
