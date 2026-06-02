package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Roundtable(
    val id: String,
    val topic: String? = null,
    val status: Status = Status.Unknown,
    val roundCount: Int = 0,
    val rosterSummary: String? = null,
    val roster: List<RoleSummary> = emptyList(),
    val time: Time = Time(),
) {
    @Serializable
    enum class Status {
        Unknown,
        Running,
        AwaitingCommand,
        AwaitingSkip,
        Paused,
        Completed,
        Archived,
        Error,
    }

    @Serializable
    data class RoleSummary(
        val id: String,
        val name: String,
        val role: String,
        val colorSeed: String,
    )

    @Serializable
    data class Time(
        val created: String? = null,
        val updated: String? = null,
        val completed: String? = null,
    )
}
