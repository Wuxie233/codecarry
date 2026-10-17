package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.data.dsh.DshSessionEvent
import dev.wuxie233.codecarry.data.dsh.parseDshSurfaceOp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Human prompts on the current model surface, not merely loaded log records. */
internal fun dshRewindableSeqs(events: List<DshSessionEvent>): Set<Long> {
    val visible = linkedSetOf<Long>()
    events.sortedBy { it.seq }.forEach { event ->
        val op = parseDshSurfaceOp(event.surfaceOp) ?: return@forEach
        if (!op.append) visible.removeAll { it in op.start!!..op.end!! }
        val source = (event.data as? JsonObject)?.get("source") as? JsonObject
        if (event.type == "user/message" && source?.get("kind")?.jsonPrimitive?.content == "user") {
            visible.add(event.seq)
        }
    }
    return visible
}

data class DshRewindState(
    val messageSeqs: Set<Long> = emptySet(),
    val inFlight: Boolean = false,
)
