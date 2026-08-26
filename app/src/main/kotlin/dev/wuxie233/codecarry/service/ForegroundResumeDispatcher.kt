package dev.wuxie233.codecarry.service

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundResumeDispatcher @Inject constructor() {
    private val listeners = ConcurrentHashMap.newKeySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun dispatch() {
        listeners.forEach { listener ->
            runCatching { listener() }
        }
    }
}
