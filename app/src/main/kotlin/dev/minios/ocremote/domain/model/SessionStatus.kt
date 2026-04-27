package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * Session Status - indicates if session is processing or idle.
 */
@Serializable
sealed class SessionStatus {
    @Serializable
    data object Idle : SessionStatus()

    @Serializable
    data object Busy : SessionStatus()

    @Serializable
    data class Retry(
        val attempt: Int,
        val message: String,
        val next: Long, // Timestamp of next retry
    ) : SessionStatus()
}

/**
 * Whether the user can interrupt this status by triggering an abort.
 *
 * `Busy` and `Retry` represent active work the user may want to halt.
 * `Idle` means there is nothing to interrupt.
 */
val SessionStatus.isInterruptible: Boolean
    get() = when (this) {
        is SessionStatus.Idle -> false
        is SessionStatus.Busy -> true
        is SessionStatus.Retry -> true
    }
