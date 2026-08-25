package dev.wuxie233.codecarry.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.wuxie233.codecarry.domain.model.ServerType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.test.runTest
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ForegroundStatusRefreshObserverTest {

    @Test
    fun `each foreground transition refreshes once while background does nothing`() {
        val owner = TestLifecycleOwner()
        var refreshes = 0
        owner.lifecycle.addObserver(ForegroundStatusRefreshObserver { refreshes++ })

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertEquals(2, refreshes)
    }

    @Test
    fun `foreground reconciliation selects only connected OpenCode servers`() {
        assertTrue(shouldReconcileForegroundStatus(ServerType.OPENCODE, isConnected = true))
        assertFalse(shouldReconcileForegroundStatus(ServerType.OPENCODE, isConnected = false))
    }

    @Test
    fun `foreground reconciliation runs every connected OpenCode target and isolates failures`() = runTest {
        val targets = listOf(
            Target("open-a", ServerType.OPENCODE, true),
            Target("open-b", ServerType.OPENCODE, true),
            Target("open-offline", ServerType.OPENCODE, false),
        )
        val reconciled = mutableSetOf<String>()

        val failures = reconcileConnectedOpenCodeTargets(
            targets = targets,
            serverType = Target::type,
            isConnected = Target::connected,
        ) { target ->
            reconciled += target.id
            if (target.id == "open-a") throw IOException("unreachable")
        }

        assertEquals(setOf("open-a", "open-b"), reconciled)
        assertEquals(listOf("unreachable"), failures.map(Throwable::message))
    }

    @Test
    fun `scope discovery fails closed without cache and reuses complete cache`() = runTest {
        val noCache = resolveStatusProjectDirectories(cachedDirectories = null) {
            throw IOException("scope unavailable")
        }
        val cached = resolveStatusProjectDirectories(cachedDirectories = listOf("/workspace/a")) {
            throw IOException("scope unavailable")
        }

        assertEquals(null, noCache)
        assertEquals(listOf("/workspace/a"), cached)
    }

    @Test
    fun `OpenCode notification dedup keys are isolated by server`() {
        assertNotEquals(
            openCodeNotificationDedupKey("server-a", "shared"),
            openCodeNotificationDedupKey("server-b", "shared"),
        )
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private data class Target(val id: String, val type: ServerType, val connected: Boolean)
}
