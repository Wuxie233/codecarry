package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Host projection wire shape is { lastUsed, next }; missing is not a default selection. */
fun dshProjectedModelSelection(value: JsonElement?, hostDefault: DshModelSelection?): DshModelSelection? {
    val projection = value as? JsonObject ?: return null
    val next = projection["next"] ?: return null
    if (next == JsonNull) return hostDefault
    val selected = next as? JsonObject ?: return null
    fun field(name: String) = (selected[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return DshModelSelection(
        provider = field("provider") ?: return null,
        model = field("model") ?: return null,
        reasoningEffort = field("reasoningEffort"),
    )
}

/** A later user action or reconnect makes an earlier asynchronous receipt obsolete. */
fun isCurrentDshModelReceipt(
    requestGeneration: Long,
    requestRevision: Long,
    currentGeneration: Long,
    currentRevision: Long,
    ready: Boolean,
): Boolean = ready && requestGeneration == currentGeneration && requestRevision == currentRevision
