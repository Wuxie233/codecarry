package dev.wuxie233.codecarry.data.dsh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DshConnectionObservationTest {
    @Test
    fun `another server reconnecting does not cancel the active preset read`() = runTest {
        val ready = DshGenerationState(generation = 1, status = DshGenerationStatus.Ready)
        val states = MutableStateFlow(mapOf("active" to ready))
        val response = CompletableDeferred<Unit>()
        var requests = 0
        var completed = false
        var read: Job? = null
        backgroundScope.launch {
            states.observeServerConnection("active").collect {
                read?.cancel()
                read = backgroundScope.launch {
                    requests++
                    response.await()
                    completed = true
                }
            }
        }
        runCurrent()
        repeat(4) { index ->
            states.value = states.value + ("other" to DshGenerationState(generation = index.toLong()))
            runCurrent()
        }
        assertEquals(1, requests)
        assertFalse(read!!.isCancelled)
        response.complete(Unit)
        runCurrent()
        assertTrue(completed)
    }

    @Test
    fun `disconnect removal and a new generation still reach the screen`() = runTest {
        val ready = DshGenerationState(generation = 1, status = DshGenerationStatus.Ready)
        val states = MutableStateFlow(mapOf("active" to ready))
        val observed = mutableListOf<DshGenerationState?>()
        backgroundScope.launch { states.observeServerConnection("active").collect { observed += it } }
        runCurrent()
        states.value = emptyMap()
        runCurrent()
        states.value = mapOf("active" to ready.copy(generation = 2))
        runCurrent()
        assertEquals(listOf(ready, null, ready.copy(generation = 2)), observed)
    }
}
