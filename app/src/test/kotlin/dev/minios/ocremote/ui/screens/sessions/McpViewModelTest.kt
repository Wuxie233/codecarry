package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpRuntimeSnapshot
import dev.minios.ocremote.domain.model.McpRuntimeState
import dev.minios.ocremote.domain.model.McpRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelTest {

    @Test
    fun `load maps runtime snapshot to runtime state`() = runTest {
        val snapshot = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "filesystem", state = McpRuntimeState.CONNECTED),
        )
        val controller = newController(
            scope = this,
            loadRuntime = { _, _ -> Result.success(snapshot) },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.Runtime)
        assertEquals(snapshot, (state as McpUiState.Runtime).snapshot)
    }

    @Test
    fun `load maps unsupported runtime snapshot to fallback read only`() = runTest {
        val snapshot = snapshot(
            supportsRuntimeControl = false,
            McpRuntimeStatus(name = "filesystem", state = McpRuntimeState.UNKNOWN),
        )
        val controller = newController(
            scope = this,
            loadRuntime = { _, _ -> Result.success(snapshot) },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.FallbackReadOnly)
        assertEquals(snapshot, (state as McpUiState.FallbackReadOnly).snapshot)
    }

    @Test
    fun `load maps empty supported runtime snapshot to empty state`() = runTest {
        val controller = newController(
            scope = this,
            loadRuntime = { _, _ -> Result.success(snapshot(supportsRuntimeControl = true)) },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        assertTrue(controller.state.value is McpUiState.Empty)
    }

    @Test
    fun `load maps runtime failure to load error`() = runTest {
        val controller = newController(
            scope = this,
            loadRuntime = { _, _ -> Result.failure(IllegalStateException("boom")) },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is McpUiState.LoadError)
        assertEquals("boom", (state as McpUiState.LoadError).message)
    }

    @Test
    fun `toggle on auth required row sets row error without calling api`() = runTest {
        var toggleCalled = false
        val snapshot = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "github", state = McpRuntimeState.NEEDS_AUTH),
        )
        val controller = newController(
            scope = this,
            loadRuntime = { _, _ -> Result.success(snapshot) },
            toggleRuntime = { _, _, _, _ ->
                toggleCalled = true
                Result.success(snapshot)
            },
        )

        controller.load(testConn, "/workspace/project")
        advanceUntilIdle()
        controller.toggle("github")
        advanceUntilIdle()

        val state = controller.state.value as McpUiState.Runtime
        assertTrue(state.rowErrors.getValue("github").isNotBlank())
        assertFalse(toggleCalled)
    }

    private fun newController(
        scope: CoroutineScope,
        loadRuntime: suspend (ServerConnection, String) -> Result<McpRuntimeSnapshot>,
        toggleRuntime: suspend (ServerConnection, String, String, McpRuntimeSnapshot) -> Result<McpRuntimeSnapshot> =
            { _, _, _, previous -> Result.success(previous) },
    ): McpRuntimeController {
        return McpRuntimeController(
            scope = scope,
            loadRuntime = loadRuntime,
            toggleRuntime = toggleRuntime,
        )
    }

    private fun snapshot(
        supportsRuntimeControl: Boolean,
        vararg servers: McpRuntimeStatus,
    ) = McpRuntimeSnapshot(
        servers = servers.toList(),
        supportsRuntimeControl = supportsRuntimeControl,
    )

    private val testConn = ServerConnection.from("http://127.0.0.1:4096")
}
