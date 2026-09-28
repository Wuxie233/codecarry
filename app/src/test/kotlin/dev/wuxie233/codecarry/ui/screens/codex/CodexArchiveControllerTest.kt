package dev.wuxie233.codecarry.ui.screens.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexArchiveControllerTest {
    @Test fun `only confirmed members of partial archive can be undone`() = runTest {
        val calls = mutableListOf<Pair<String, CodexArchiveOperation>>()
        val controller = CodexArchiveController(backgroundScope) { id, operation ->
            calls += id to operation
            when {
                operation == CodexArchiveOperation.RESTORE -> CodexArchiveResult.CONFIRMED
                id == "failed" -> CodexArchiveResult.FAILED
                id == "unknown" -> CodexArchiveResult.UNKNOWN
                else -> CodexArchiveResult.CONFIRMED
            }
        }
        controller.archive(listOf("ok", "failed", "unknown"))
        runCurrent()
        val result = controller.feedback.value.single()
        assertEquals(1, result.archivedCount)
        assertEquals(1, result.failedCount)
        assertEquals(1, result.unknownCount)
        controller.undo(result.id)
        controller.undo(result.id)
        runCurrent()
        assertEquals(listOf("ok"), calls.filter { it.second == CodexArchiveOperation.RESTORE }.map { it.first })
        assertEquals(1, controller.feedback.value.single().restoredCount)
    }

    @Test fun `same frame duplicate archive and restore are reserved before launch`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val controller = CodexArchiveController(backgroundScope) { id, _ ->
            calls += id
            gate.await()
            CodexArchiveResult.CONFIRMED
        }
        controller.archive(listOf("one", "one"))
        controller.archive(listOf("one"))
        controller.restore(listOf("one"))
        runCurrent()
        assertEquals(listOf("one"), calls)
        assertEquals(setOf("one"), controller.busy.value)
        gate.complete(Unit)
        runCurrent()
        controller.archive(listOf("one")) // Stale active projection must not archive again.
        runCurrent()
        assertEquals(listOf("one"), calls)
        assertTrue(controller.busy.value.isEmpty())
    }

    @Test fun `feedback remains FIFO and waiting undo window starts only on presentation`() = runTest {
        val controller = CodexArchiveController(backgroundScope) { _, _ -> CodexArchiveResult.CONFIRMED }
        controller.archive(listOf("first"))
        runCurrent()
        controller.archive(listOf("second"))
        runCurrent()
        val first = controller.feedback.value[0].id
        val second = controller.feedback.value[1].id
        controller.present(first, 8_000)
        advanceTimeBy(4_000)
        controller.present(first, 8_000) // Recreation does not extend the original deadline.
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf(second), controller.feedback.value.map { it.id })
        advanceTimeBy(80_000)
        runCurrent()
        assertTrue(controller.feedback.value.single().canUndo)
        controller.present(second, 16_000) // Accessibility-adjusted duration.
        advanceTimeBy(15_999)
        runCurrent()
        assertEquals(second, controller.feedback.value.single().id)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(controller.feedback.value.isEmpty())
        controller.undo(second)
        runCurrent()
        assertTrue(controller.feedback.value.isEmpty())
    }

    @Test fun `unknown writes cannot be replayed until read reconciliation and never gain undo`() = runTest {
        var calls = 0
        val controller = CodexArchiveController(backgroundScope) { _, _ ->
            calls++
            CodexArchiveResult.UNKNOWN
        }
        controller.archive(listOf("unknown"))
        runCurrent()
        controller.archive(listOf("unknown"))
        controller.restore(listOf("unknown"))
        runCurrent()
        assertEquals(1, calls)
        assertFalse(controller.feedback.value.single().canUndo)
        controller.reconciled(setOf("unknown"))
        runCurrent()
        assertEquals(1, calls) // Readback must not replay anything.
        controller.archive(listOf("unknown")) // A new explicit user operation is now allowed.
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun `manual restore consumes pending undo claim`() = runTest {
        val calls = mutableListOf<CodexArchiveOperation>()
        val controller = CodexArchiveController(backgroundScope) { _, operation ->
            calls += operation
            CodexArchiveResult.CONFIRMED
        }
        controller.archive(listOf("one"))
        runCurrent()
        val feedback = controller.feedback.value.single()
        controller.restore(listOf("one"))
        runCurrent()
        assertFalse(controller.feedback.value.first().canUndo)
        controller.undo(feedback.id)
        runCurrent()
        assertEquals(listOf(CodexArchiveOperation.ARCHIVE, CodexArchiveOperation.RESTORE), calls)
    }

    @Test fun `immediate read reconciliation cannot leave a stale unknown guard`() = runTest {
        lateinit var controller: CodexArchiveController
        var calls = 0
        controller = CodexArchiveController(
            scope = backgroundScope,
            onUnknown = { controller.reconciled(setOf(it)) },
        ) { _, _ -> calls++; CodexArchiveResult.UNKNOWN }
        controller.archive(listOf("one"))
        runCurrent()
        assertTrue(controller.busy.value.isEmpty())
        controller.archive(listOf("one"))
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun `same thread id on another server has independent undo rights`() = runTest {
        val restored = mutableListOf<String>()
        fun controller(server: String) = CodexArchiveController(backgroundScope) { _, operation ->
            if (operation == CodexArchiveOperation.RESTORE) restored += server
            CodexArchiveResult.CONFIRMED
        }
        val first = controller("first")
        val second = controller("second")
        first.archive(listOf("same-id"))
        second.archive(listOf("same-id"))
        runCurrent()
        first.undo(first.feedback.value.single().id)
        runCurrent()
        assertEquals(listOf("first"), restored)
        assertTrue(second.feedback.value.single().canUndo)
    }

    @Test fun `cancellation propagates and releases busy reservations`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = CodexArchiveController(backgroundScope) { _, _ ->
            gate.await()
            CodexArchiveResult.CONFIRMED
        }
        controller.archive(listOf("one"))
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        assertTrue(controller.busy.value.isEmpty())
        assertTrue(controller.feedback.value.isEmpty())
    }
}
