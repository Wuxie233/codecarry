package dev.wuxie233.codecarry.ui.screens.sessions

import dev.wuxie233.codecarry.data.api.ServerConnection
import dev.wuxie233.codecarry.data.repository.McpToggleException
import dev.wuxie233.codecarry.domain.model.McpRuntimeSnapshot
import dev.wuxie233.codecarry.domain.model.McpRuntimeState
import dev.wuxie233.codecarry.domain.model.McpRuntimeStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpRuntimeViewModelTest {

    @Test
    fun `connected toggle marks row pending then clears with refreshed snapshot`() = runTest {
        val previous = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.CONNECTED),
        )
        val refreshed = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.DISABLED),
        )
        val controller = newLoadedController(
            scope = this,
            initial = previous,
            toggleRuntime = { _, _, name, prior ->
                assertEquals("fs", name)
                assertEquals(previous, prior)
                Result.success(refreshed)
            },
        )

        controller.toggle("fs")

        assertEquals(setOf("fs"), runtimeState(controller).pendingNames)

        advanceUntilIdle()

        val state = runtimeState(controller)
        assertEquals(refreshed, state.snapshot)
        assertTrue(state.pendingNames.isEmpty())
    }

    @Test
    fun `toggle failure reverts to previous snapshot clears pending and records row error`() = runTest {
        val previous = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.CONNECTED),
        )
        val controller = newLoadedController(
            scope = this,
            initial = previous,
            toggleRuntime = { _, _, _, _ ->
                Result.failure(
                    McpToggleException(
                        name = "fs",
                        previous = previous,
                        cause = IllegalStateException("network down"),
                    )
                )
            },
        )

        controller.toggle("fs")
        assertEquals(setOf("fs"), runtimeState(controller).pendingNames)

        advanceUntilIdle()

        val state = runtimeState(controller)
        assertEquals(previous, state.snapshot)
        assertTrue(state.pendingNames.isEmpty())
        assertNotNull(state.rowErrors["fs"])
    }

    @Test
    fun `needs auth toggle sets OAuth row error without calling toggle lambda`() = runTest {
        var toggleCalls = 0
        val initial = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.NEEDS_AUTH),
        )
        val controller = newLoadedController(
            scope = this,
            initial = initial,
            toggleRuntime = { _, _, _, _ ->
                toggleCalls += 1
                Result.success(initial)
            },
        )

        controller.toggle("fs")
        advanceUntilIdle()

        val error = runtimeState(controller).rowErrors.getValue("fs")
        assertEquals(0, toggleCalls)
        assertTrue(error.contains("OAuth") || error.contains("授权"))
    }

    @Test
    fun `two parallel toggles on different rows are pending simultaneously`() = runTest {
        val fsResult = CompletableDeferred<McpRuntimeSnapshot>()
        val githubResult = CompletableDeferred<McpRuntimeSnapshot>()
        val initial = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.CONNECTED),
            McpRuntimeStatus(name = "github", state = McpRuntimeState.DISABLED),
        )
        val calls = mutableListOf<String>()
        val controller = newLoadedController(
            scope = this,
            initial = initial,
            toggleRuntime = { _, _, name, _ ->
                calls += name
                Result.success(
                    when (name) {
                        "fs" -> fsResult.await()
                        "github" -> githubResult.await()
                        else -> error("unexpected MCP server: $name")
                    }
                )
            },
        )

        controller.toggle("fs")
        controller.toggle("github")
        runCurrent()

        val state = runtimeState(controller)
        assertEquals(setOf("fs", "github"), state.pendingNames)
        assertEquals(listOf("fs", "github"), calls)

        fsResult.complete(initial)
        githubResult.complete(initial)
        advanceUntilIdle()
    }

    @Test
    fun `dismissRowError clears only requested row`() = runTest {
        val controller = newLoadedController(
            scope = this,
            initial = snapshot(
                supportsRuntimeControl = true,
                McpRuntimeStatus(name = "fs", state = McpRuntimeState.CONNECTED),
                McpRuntimeStatus(name = "github", state = McpRuntimeState.DISABLED),
            ),
        )
        controller.forceRuntimeState(
            runtimeState(controller).copy(rowErrors = mapOf("fs" to "boom", "github" to "still broken"))
        )

        controller.dismissRowError("fs")

        val state = runtimeState(controller)
        assertFalse(state.rowErrors.containsKey("fs"))
        assertEquals("still broken", state.rowErrors["github"])
    }

    @Test
    fun `second toggle while row is already pending is no-op`() = runTest {
        val result = CompletableDeferred<McpRuntimeSnapshot>()
        var toggleCalls = 0
        val initial = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.CONNECTED),
        )
        val controller = newLoadedController(
            scope = this,
            initial = initial,
            toggleRuntime = { _, _, _, _ ->
                toggleCalls += 1
                Result.success(result.await())
            },
        )

        controller.toggle("fs")
        controller.toggle("fs")
        runCurrent()

        assertEquals(setOf("fs"), runtimeState(controller).pendingNames)
        assertEquals(1, toggleCalls)

        result.complete(initial)
        advanceUntilIdle()
    }

    @Test
    fun `successful toggle clears sheet error`() = runTest {
        val previous = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.CONNECTED),
        )
        val refreshed = snapshot(
            supportsRuntimeControl = true,
            McpRuntimeStatus(name = "fs", state = McpRuntimeState.DISABLED),
        )
        val controller = newLoadedController(
            scope = this,
            initial = previous,
            toggleRuntime = { _, _, _, _ -> Result.success(refreshed) },
        )
        controller.forceRuntimeState(runtimeState(controller).copy(sheetError = "old sheet error"))

        controller.toggle("fs")
        advanceUntilIdle()

        assertEquals(null, runtimeState(controller).sheetError)
    }

    private suspend fun newLoadedController(
        scope: TestScope,
        initial: McpRuntimeSnapshot,
        toggleRuntime: suspend (ServerConnection, String, String, McpRuntimeSnapshot) -> Result<McpRuntimeSnapshot> =
            { _, _, _, previous -> Result.success(previous) },
    ): McpRuntimeController {
        return newController(
            scope = scope,
            loadRuntime = { _, _ -> Result.success(initial) },
            toggleRuntime = toggleRuntime,
        ).also { controller ->
            controller.load(testConn, projectDir)
            with(scope) { advanceUntilIdle() }
        }
    }

    private fun newController(
        scope: CoroutineScope,
        loadRuntime: suspend (ServerConnection, String) -> Result<McpRuntimeSnapshot>,
        toggleRuntime: suspend (ServerConnection, String, String, McpRuntimeSnapshot) -> Result<McpRuntimeSnapshot>,
    ): McpRuntimeController {
        return McpRuntimeController(
            scope = scope,
            loadRuntime = loadRuntime,
            toggleRuntime = toggleRuntime,
        )
    }

    private fun runtimeState(controller: McpRuntimeController): McpUiState.Runtime =
        controller.state.value as McpUiState.Runtime

    @Suppress("UNCHECKED_CAST")
    private fun McpRuntimeController.forceRuntimeState(state: McpUiState.Runtime) {
        val field = McpRuntimeController::class.java.getDeclaredField("_state")
        field.isAccessible = true
        val stateFlow = field.get(this) as MutableStateFlow<McpUiState>
        stateFlow.value = state
    }

    private fun snapshot(
        supportsRuntimeControl: Boolean,
        vararg servers: McpRuntimeStatus,
    ) = McpRuntimeSnapshot(
        servers = servers.toList(),
        supportsRuntimeControl = supportsRuntimeControl,
    )

    private val testConn = ServerConnection.from("http://127.0.0.1:4096")
    private val projectDir = "/workspace/project"
}
