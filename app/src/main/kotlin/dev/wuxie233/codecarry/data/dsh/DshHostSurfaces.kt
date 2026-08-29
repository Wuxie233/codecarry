package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DshHostSurfaceCatalog(
    val availableMethods: Set<String>,
    val loopbackOnlyHidden: Set<String>,
    val isLoopback: Boolean,
) {
    fun can(method: String): Boolean = method in availableMethods

    val canBrowseHost: Boolean get() = can("directoryPicker/list") && can("directoryPicker/createDirectory")
    val canManageWorkspaces: Boolean get() = can("workspace/create")
    val canListSkills: Boolean get() = can("skills/list")
    val canDescribeGit: Boolean get() = can("git/describe")
    val canListPresets: Boolean get() = can("agentPresets/list")
    val canSelectPreset: Boolean get() = can("agentPresets/select")
    val canManageGoals: Boolean get() = can("goals/create")
    val canManageAutomation: Boolean get() = can("automation/list")
    val canMutateSettings: Boolean get() = can("settings/describe") && can("settings/mutate")
    val canListLlm: Boolean get() = can("llm/listProviders") && can("session/modelCatalog")
    val canListSubagents: Boolean get() = can("subagents/list")
    val canListSystemPrompt: Boolean get() = can("systemPrompt/list")
}

fun dshHostSurfaceCatalog(connection: DshConnection): DshHostSurfaceCatalog {
    val available = DshMethods.availableOn(connection).toSet()
    return DshHostSurfaceCatalog(
        availableMethods = available,
        loopbackOnlyHidden = DshRpc.LOOPBACK_ONLY_METHODS - available,
        isLoopback = connection.isLoopback,
    )
}

class DshHostSurfaceController(
    private val client: DshApiClient,
    private val connection: DshConnection,
    private val catalog: DshHostSurfaceCatalog = dshHostSurfaceCatalog(connection),
) {
    fun catalog(): DshHostSurfaceCatalog = catalog

    suspend fun createWorkspace(path: String): DshWorkspaceCreateValue {
        requireMethod("workspace/create")
        return client.workspaceCreate(connection, path)
    }

    suspend fun renameWorkspace(workspaceId: String, title: String): DshWorkspaceValue {
        requireMethod("workspace/rename")
        return client.workspaceRename(connection, workspaceId, title)
    }

    suspend fun deleteWorkspace(workspaceId: String): DshDeletedValue {
        requireMethod("workspace/delete")
        return client.workspaceDelete(connection, workspaceId)
    }

    suspend fun hideWorkspace(workspaceId: String): DshHiddenWorkspacesValue {
        requireMethod("workspace/hide")
        return client.workspaceHide(connection, workspaceId)
    }

    suspend fun showWorkspace(workspaceId: String): DshHiddenWorkspacesValue {
        requireMethod("workspace/show")
        return client.workspaceShow(connection, workspaceId)
    }

    suspend fun addWorkspaceFolder(workspaceId: String, path: String): DshWorkspaceValue {
        requireMethod("workspace/addFolder")
        return client.workspaceAddFolder(connection, workspaceId, path)
    }

    suspend fun removeWorkspaceFolder(workspaceId: String, path: String): DshWorkspaceValue {
        requireMethod("workspace/removeFolder")
        return client.workspaceRemoveFolder(connection, workspaceId, path)
    }

    suspend fun listDirectory(path: String? = null): DshDirectoryListing {
        requireMethod("directoryPicker/list")
        return client.hostListDirectory(connection, path)
    }

    suspend fun createDirectory(path: String, name: String): DshCreateDirectoryValue {
        requireMethod("directoryPicker/createDirectory")
        return client.hostCreateDirectory(connection, path, name)
    }

    suspend fun skillList(sessionId: String): DshSkillListValue {
        requireMethod("skills/list")
        return client.skillList(connection, sessionId)
    }

    suspend fun gitDescribe(sessionId: String? = null, workspaceId: String? = null): DshSessionGitView {
        requireMethod("git/describe")
        return client.gitDescribe(connection, sessionId, workspaceId)
    }

    suspend fun gitCheckout(sessionId: String, branch: String): DshSessionGitView {
        requireMethod("git/checkout")
        return client.gitCheckout(connection, sessionId, branch)
    }

    suspend fun gitCreateBranch(sessionId: String, branch: String): DshSessionGitView {
        requireMethod("git/createBranch")
        return client.gitCreateBranch(connection, sessionId, branch)
    }

    suspend fun agentPresetList(): DshAgentPresetListValue {
        requireMethod("agentPresets/list")
        return client.agentPresetList(connection)
    }

    suspend fun agentPresetSelect(sessionId: String, agentPreset: String): DshAgentPresetSelectValue {
        requireMethod("agentPresets/select")
        return client.agentPresetSelect(connection, sessionId, agentPreset)
    }

    suspend fun goalCreate(sessionId: String, objective: String, maxGoalRounds: Int? = null): DshGoalRefValue {
        requireMethod("goals/create")
        return client.goalCreate(connection, sessionId, objective, maxGoalRounds)
    }

    suspend fun goalPause(sessionId: String, ref: DshGoalRef): DshGoalRefValue {
        requireMethod("goals/pause")
        return client.goalPause(connection, sessionId, ref)
    }

    suspend fun goalResume(sessionId: String, ref: DshGoalRef): DshGoalRefValue {
        requireMethod("goals/resume")
        return client.goalResume(connection, sessionId, ref)
    }

    suspend fun goalComplete(sessionId: String, ref: DshGoalRef): DshGoalRefValue {
        requireMethod("goals/complete")
        return client.goalComplete(connection, sessionId, ref)
    }

    suspend fun goalClear(sessionId: String, ref: DshGoalRef): DshGoalClearedValue {
        requireMethod("goals/clear")
        return client.goalClear(connection, sessionId, ref)
    }

    suspend fun automationList(): DshAutomationListValue {
        requireMethod("automation/list")
        return client.automationList(connection)
    }

    suspend fun automationCreate(payload: JsonObject): DshAutomationRuleValue {
        requireMethod("automation/create")
        return client.automationCreate(connection, payload)
    }

    suspend fun automationSetEnabled(id: String, enabled: Boolean): DshAutomationRuleValue {
        requireMethod("automation/setEnabled")
        return client.automationSetEnabled(connection, id, enabled)
    }

    suspend fun automationDelete(id: String): DshAutomationDeleteValue {
        requireMethod("automation/delete")
        return client.automationDelete(connection, id)
    }

    suspend fun settingsDescribe(): DshSettingsDescribeValue {
        requireMethod("settings/describe")
        return client.settingsDescribe(connection)
    }

    suspend fun settingsMutate(
        ns: String,
        ops: JsonArray,
        expectedRevision: Long? = null,
    ): DshSettingsNamespaceView {
        requireMethod("settings/mutate")
        return client.settingsMutate(connection, ns, ops, expectedRevision)
    }

    suspend fun settingsUpdate(
        ns: String,
        patch: JsonObject,
        expectedRevision: Long? = null,
    ): DshSettingsNamespaceView {
        requireMethod("settings/update")
        return client.settingsUpdate(connection, ns, patch, expectedRevision)
    }

    suspend fun settingsReplace(
        ns: String,
        section: JsonObject,
        expectedRevision: Long? = null,
    ): DshSettingsNamespaceView {
        requireMethod("settings/replace")
        return client.settingsReplace(connection, ns, section, expectedRevision)
    }

    suspend fun llmProviders(): DshLlmProvidersValue {
        requireMethod("llm/listProviders")
        return client.llmProviders(connection)
    }

    suspend fun llmModels(): DshLlmModelsValue {
        requireMethod("session/modelCatalog")
        return client.llmModels(connection)
    }

    suspend fun subagentList(parentSessionId: String): DshSubagentCatalog {
        requireMethod("subagents/list")
        return client.subagentList(connection, parentSessionId)
    }

    suspend fun subagentPrompt(
        parentSessionId: String,
        childSessionId: String,
        text: String,
        clientTimeZone: String? = null,
    ): DshSubagentPromptReceipt {
        requireMethod("subagents/prompt")
        return client.subagentPrompt(
            connection,
            parentSessionId,
            childSessionId,
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            },
            clientTimeZone,
        )
    }

    suspend fun subagentInterrupt(parentSessionId: String, childSessionId: String): DshAcceptedValue {
        requireMethod("subagents/interruptByParent")
        return client.subagentInterrupt(connection, parentSessionId, childSessionId)
    }

    suspend fun systemPromptList(): DshSystemPromptListValue {
        requireMethod("systemPrompt/list")
        return client.systemPromptList(connection)
    }

    private fun requireMethod(method: String) {
        if (!catalog.can(method)) throw DshLoopbackUnavailableException(method)
    }
}
