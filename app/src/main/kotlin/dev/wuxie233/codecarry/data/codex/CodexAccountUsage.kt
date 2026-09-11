package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.*

data class CodexUsageWindow(val usedPercent: Double?, val windowDurationMins: Long?, val resetsAt: Long?) {
    val remainingPercent: Double? get() = usedPercent?.let { (100.0 - it).coerceIn(0.0, 100.0) }
}
data class CodexUsageCredits(val hasCredits: Boolean?, val unlimited: Boolean?, val balance: String?)
data class CodexUsageBucket(
    val id: String, val name: String?, val primary: CodexUsageWindow?, val secondary: CodexUsageWindow?,
    val credits: CodexUsageCredits?, val planType: String?,
)
data class CodexAccountUsageState(
    val buckets: List<CodexUsageBucket> = emptyList(),
    val isCurrent: Boolean = false,
    val isRefreshing: Boolean = false,
    val updatedAtMillis: Long? = null,
    val unsupported: Boolean = false,
    val errorMessage: String? = null,
)

/** Owned under the connection manager lock; epochs fence account and socket changes. */
internal class CodexAccountUsageStore {
    var state = CodexAccountUsageState()
        private set
    private var epoch = 0L
    private var revision = 0L
    private var raw = emptyMap<String, JsonObject>()
    private var accountId: String? = null
    private var readUpdates = emptyMap<String, JsonObject>()
    data class Read(val epoch: Long, val revision: Long)
    fun beginRead(): Read {
        readUpdates = emptyMap()
        state = state.copy(isRefreshing = true, errorMessage = null)
        return Read(epoch, revision)
    }
    fun invalidate(clear: Boolean = false) {
        epoch++
        if (clear) {
            raw = emptyMap(); accountId = null
            state = CodexAccountUsageState()
        } else state = state.copy(isCurrent = false, isRefreshing = false)
    }
    fun applyRead(read: Read, payload: JsonObject, now: Long) {
        if (read.epoch != epoch) return
        val newAccount = payload.string("accountId")
        if (newAccount != null && accountId != null && newAccount != accountId) {
            raw = emptyMap(); readUpdates = emptyMap()
        }
        if (newAccount != null) accountId = newAccount
        val snapshot = buckets(payload)
        // A rolling push wins for its bucket; other snapshot buckets still hydrate.
        raw = snapshot.toMutableMap().apply {
            readUpdates.forEach { (id, update) ->
                this[id] = sparseMerge(this[id] ?: raw[id] ?: JsonObject(emptyMap()), update)
            }
        }
        publish(now)
    }
    fun applyPush(payload: JsonObject, now: Long) {
        revision++
        buckets(payload).forEach { (id, value) ->
            raw = raw + (id to sparseMerge(raw[id] ?: JsonObject(emptyMap()), value))
            if (state.isRefreshing) readUpdates = readUpdates +
                (id to sparseMerge(readUpdates[id] ?: JsonObject(emptyMap()), value))
        }
        publish(now, refreshing = state.isRefreshing)
    }
    fun fail(read: Read, error: Throwable) {
        if (read.epoch != epoch) return
        if (revision > read.revision) {
            state = state.copy(isRefreshing = false)
            return
        }
        val message = error.message.orEmpty()
        val unsupported = error is CodexRpcException && (
            error.code == -32601L ||
                Regex("api.?key|not supported|unsupported|requires? .*chatgpt|only .*chatgpt", RegexOption.IGNORE_CASE)
                    .containsMatchIn(message)
            )
        state = state.copy(isRefreshing = false, isCurrent = false, unsupported = unsupported,
            errorMessage = if (unsupported) null else message.takeIf { it.isNotBlank() })
    }
    private fun publish(now: Long, refreshing: Boolean = false) {
        state = CodexAccountUsageState(raw.map { (id, value) ->
            CodexUsageBucket(id, value.string("limitName"), value.window("primary"), value.window("secondary"),
                (value["credits"] as? JsonObject)?.let {
                    CodexUsageCredits(it.boolean("hasCredits"), it.boolean("unlimited"), it.string("balance"))
                }, value.string("planType"))
        }, isCurrent = true, isRefreshing = refreshing, updatedAtMillis = now)
    }
}

private fun buckets(payload: JsonObject): Map<String, JsonObject> {
    val multi = (payload["rateLimitsByLimitId"] as? JsonObject)?.mapNotNull { (id, value) ->
        (value as? JsonObject)?.let { id to it }
    }?.toMap().orEmpty()
    if (multi.isNotEmpty()) return multi
    val single = payload["rateLimits"] as? JsonObject ?: return emptyMap()
    return mapOf((single.string("limitId") ?: "codex") to single)
}
private fun sparseMerge(previous: JsonObject, update: JsonObject): JsonObject = JsonObject(previous.toMutableMap().apply {
    update.forEach { (key, value) ->
        if (value != JsonNull || key == "spendControlReached") {
            this[key] = if (value is JsonObject && this[key] is JsonObject) sparseMerge(this[key] as JsonObject, value) else value
        }
    }
})
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.window(key: String): CodexUsageWindow? = (this[key] as? JsonObject)?.let {
    CodexUsageWindow((it["usedPercent"] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite),
        (it["windowDurationMins"] as? JsonPrimitive)?.longOrNull, (it["resetsAt"] as? JsonPrimitive)?.longOrNull)
}
