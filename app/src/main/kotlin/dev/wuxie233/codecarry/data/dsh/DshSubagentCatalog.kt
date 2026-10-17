package dev.wuxie233.codecarry.data.dsh

import dev.wuxie233.codecarry.domain.model.Session
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class DshSubagentCatalogEntry(
    val id: String,
    val mode: String,
    val label: String?,
    val createdAt: Long,
)

/** Host catalog entries describe established child Sessions, not sessionless Agents. */
internal fun DshSessionSnapshot.subagentCatalog(): List<DshSubagentCatalogEntry> =
    (projections["subagentCatalog"]?.second as? JsonArray).orEmpty().mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        val id = (row["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val mode = (row["mode"] as? JsonPrimitive)?.contentOrNull
        if (mode !in setOf("one-shot", "continuable", "unknown")) return@mapNotNull null
        DshSubagentCatalogEntry(
            id = id,
            mode = requireNotNull(mode),
            label = (row["label"] as? JsonPrimitive)?.contentOrNull,
            createdAt = (row["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }.distinctBy { it.id }

internal fun DshEventState.catalogAddress(sessionId: String): DshSessionAddress.Subagent? {
    sessions.values.forEach { parent ->
        parent.subagentCatalog().firstOrNull { it.id == sessionId }?.let { child ->
            return DshSessionAddress.Subagent(parent.sessionId, child.id, child.mode)
        }
    }
    return null
}

internal fun DshEventState.directSubagentSessions(
    parentSessionId: String,
    parentDirectory: String,
): List<Session> = sessions[parentSessionId]?.subagentCatalog().orEmpty().map { child ->
    val snapshot = sessions[child.id]
    Session(
        id = child.id,
        parentId = parentSessionId,
        title = snapshot?.let { dshProjectionTitle(it.projections) } ?: child.label,
        directory = snapshot?.cwd ?: parentDirectory,
        time = Session.Time(
            created = child.createdAt,
            updated = snapshot?.events?.maxOfOrNull { it.time } ?: child.createdAt,
        ),
    )
}
