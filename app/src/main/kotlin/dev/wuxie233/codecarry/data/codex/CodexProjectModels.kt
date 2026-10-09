package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class CodexProjectRoot(val path: String)

data class CodexProject(
    val id: String,
    val name: String,
    val roots: List<CodexProjectRoot>,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val position: Long? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val recencyAt: Long? = null,
) {
    companion object {
        fun fromJson(value: JsonObject): CodexProject = CodexProject(
            id = (value["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            name = (value["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            roots = (value["roots"] as? JsonArray).orEmpty().mapNotNull {
                ((it as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull?.let(::CodexProjectRoot)
            },
            metadata = value["metadata"].objectOrEmpty(),
            position = (value["position"] as? JsonPrimitive)?.longOrNull,
            createdAt = (value["createdAt"] as? JsonPrimitive)?.longOrNull,
            updatedAt = (value["updatedAt"] as? JsonPrimitive)?.longOrNull,
            recencyAt = (value["recencyAt"] as? JsonPrimitive)?.longOrNull,
        )
    }
}

data class CodexProjectListPage(val projects: List<CodexProject>, val nextCursor: String?)

data class CodexProjectCatalogState(
    val projects: List<CodexProject> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)


data class CodexPendingProjectRemoval(val projectId: String, val name: String)

data class CodexProjectRemovalState(
    val pending: List<CodexPendingProjectRemoval> = emptyList(),
    val deletingProjectIds: Set<String> = emptySet(),
    val error: String? = null,
)
