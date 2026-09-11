package dev.wuxie233.codecarry.ui.screens.codex

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wuxie233.codecarry.data.codex.CodexModel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Only unsent choices are local; a resumed server tier is authoritative after submission. */
@Singleton
class CodexFastPreferences private constructor(private val preferences: SharedPreferences?) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("codex_fast_preferences", Context.MODE_PRIVATE),
    )
    constructor() : this(null)

    private val memory = mutableMapOf<String, Boolean>()
    private fun key(serverId: String, threadId: String) = "pending:${serverId.length}:$serverId:$threadId"
    fun pending(serverId: String, threadId: String): Boolean? {
        val key = key(serverId, threadId)
        return if (preferences != null) {
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        } else memory[key]
    }
    fun setPending(serverId: String, threadId: String, enabled: Boolean) {
        val key = key(serverId, threadId)
        if (preferences != null) preferences.edit().putBoolean(key, enabled).apply() else memory[key] = enabled
    }
    fun clearPending(serverId: String, threadId: String) {
        val key = key(serverId, threadId)
        if (preferences != null) preferences.edit().remove(key).apply() else memory.remove(key)
    }
    fun consumeFirstEnable(): Boolean {
        val seen = preferences?.getBoolean("hint_seen", false) ?: (memory["hint_seen"] == true)
        if (preferences != null) preferences.edit().putBoolean("hint_seen", true).apply() else memory["hint_seen"] = true
        return !seen
    }
}

internal fun isCodexFastTier(tier: String?): Boolean = tier == "fast" || tier == "priority"
internal fun CodexModel?.codexFastTier(): String? = this?.serviceTiers
    ?.firstOrNull { isCodexFastTier(it.id) }?.id

/** Explicit JSON null clears a tier; omitting it inherits the previous turn's tier. */
internal fun codexFastTurnParams(model: CodexModel?, enabled: Boolean, pending: Boolean): JsonObject {
    val tier = model.codexFastTier()
    if (!pending) return JsonObject(emptyMap())
    if (enabled && tier == null) return JsonObject(emptyMap())
    return JsonObject(mapOf("serviceTier" to if (enabled) JsonPrimitive(tier!!) else JsonNull))
}
