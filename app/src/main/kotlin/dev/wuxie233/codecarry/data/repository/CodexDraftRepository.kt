package dev.wuxie233.codecarry.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Text only: attachments and their binary payloads never enter this store or a Bundle. */
@Singleton
class CodexDraftRepository private constructor(private val preferences: SharedPreferences?) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("codex_text_drafts", Context.MODE_PRIVATE),
    )
    constructor() : this(null)

    data class Snapshot(val text: String, val revision: String)

    private val memory = mutableMapOf<String, Snapshot>()
    private fun key(serverId: String, threadId: String) = "${serverId.length}:$serverId:$threadId"

    @Synchronized
    fun get(serverId: String, threadId: String): Snapshot? {
        val key = key(serverId, threadId)
        if (preferences == null) return memory[key]
        val revision = preferences.getString("$key:revision", null) ?: return null
        return Snapshot(preferences.getString("$key:text", "").orEmpty(), revision)
    }

    @Synchronized
    fun replaceIfRevision(serverId: String, threadId: String, revision: String, text: String): Snapshot? {
        if (get(serverId, threadId)?.revision != revision) return null
        return save(serverId, threadId, text)
    }

    @Synchronized
    fun save(serverId: String, threadId: String, text: String): Snapshot {
        get(serverId, threadId)?.takeIf { it.text == text }?.let { return it }
        val snapshot = Snapshot(text, UUID.randomUUID().toString())
        val key = key(serverId, threadId)
        // Empty drafts retain a revision: stale saved-state text must not resurrect a sent draft.
        if (preferences != null) {
            preferences.edit().putString("$key:text", text)
                .putString("$key:revision", snapshot.revision).apply()
        } else memory[key] = snapshot
        return snapshot
    }
}
