package dev.wuxie233.codecarry.data.dsh

/** A preset changes a session's agent configuration, never a server-wide default. */
fun canSelectDshPreset(snapshot: DshSessionSnapshot?, ready: Boolean, sending: Boolean): Boolean =
    ready && !sending && snapshot != null && !snapshot.running &&
        snapshot.origin != "subagent" && snapshot.parentSessionId == null && snapshot.queue.isEmpty()

fun filterDshPresets(presets: List<DshAgentPresetEntry>, query: String): List<DshAgentPresetEntry> {
    val needle = query.trim()
    return presets.filter {
        needle.isEmpty() || it.id.contains(needle, ignoreCase = true) ||
            it.name.orEmpty().contains(needle, ignoreCase = true) ||
            it.description.orEmpty().contains(needle, ignoreCase = true)
    }
}
