package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class DshSessionSummary(
    val sessionId: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val interrupted: Boolean? = null,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val projections: DshProjectionsBlock? = null,
)

@Serializable
data class DshProjectionsBlock(
    val asOfSeq: Long,
    val values: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DshSessionListValue(
    val items: List<DshSessionSummary> = emptyList(),
)

@Serializable
data class DshSessionSearchItem(
    val sessionId: String,
    val snippet: String,
)

@Serializable
data class DshSessionSearchValue(
    val items: List<DshSessionSearchItem> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
data class DshSessionCreateValue(
    val sessionId: String,
    val agentPreset: String? = null,
)

@Serializable
data class DshHistoryEntry(
    val event: DshSessionEventDto,
    val view: JsonElement? = null,
)

@Serializable
data class DshSessionEventDto(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement? = null,
    val sourceEventSeqs: List<Long>? = null,
    val surfaceOp: JsonElement? = null,
    val ignorable: Boolean? = null,
)

@Serializable
data class DshSessionHistoryValue(
    val events: List<DshHistoryEntry> = emptyList(),
    val hasMore: Boolean = false,
    val projections: DshProjectionsBlock? = null,
    val cursor: Long? = null,
)

@Serializable
data class DshHistoryRecord(
    val type: String,
    val event: DshSessionEventDto,
)

@Serializable
data class DshSessionPageValue(
    val records: List<DshHistoryRecord> = emptyList(),
    val hasMore: Boolean = false,
)

fun DshSessionPageValue.toHistory(projections: DshProjectionsBlock? = null): DshSessionHistoryValue =
    DshSessionHistoryValue(
        events = records.map { DshHistoryEntry(event = it.event) },
        hasMore = hasMore,
        projections = projections,
    )

@Serializable
data class DshModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

@Serializable
data class DshModelReasoningEffort(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class DshModelReasoning(
    val efforts: List<DshModelReasoningEffort> = emptyList(),
    val defaultEffort: String? = null,
)

@Serializable
data class DshModelCatalogModel(
    val id: String,
    val name: String,
    val description: String? = null,
    val reasoning: DshModelReasoning? = null,
)

@Serializable
data class DshModelProviderGroup(
    val id: String,
    val name: String,
    val models: List<DshModelCatalogModel> = emptyList(),
)

@Serializable
data class DshModelCatalogFailure(
    val id: String,
    val name: String,
    val message: String,
)

@Serializable
data class DshSessionModels(
    /** Host catalog default; a session-scoped selection rides projections. */
    val default: DshModelSelection,
    val routableProviders: List<String> = emptyList(),
    val groups: List<DshModelProviderGroup> = emptyList(),
    val failures: List<DshModelCatalogFailure> = emptyList(),
)

@Serializable
data class DshSelectModelValue(
    val selected: DshModelSelection,
)

@Serializable
data class DshRenameValue(
    val title: String,
    val seq: Long,
)

@Serializable
data class DshRehomeValue(
    val workspaceId: String,
    val path: String,
    val cwd: String,
)

@Serializable
data class DshForkValue(
    val sessionId: String,
)

@Serializable
data class DshAcceptedValue(
    val accepted: Boolean = true,
    val command: DshCommandResult? = null,
)

@Serializable
data class DshCommandResult(
    val kind: String,
    val text: String? = null,
)

@Serializable
data class DshImageAttachmentRef(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val name: String? = null,
)

@Serializable
data class DshAttachmentValue(
    val attachment: DshImageAttachmentRef,
    val data: String,
)

@Serializable
data class DshDirectoryEntry(
    val name: String,
    val path: String,
    val hidden: Boolean,
)

@Serializable
data class DshDirectoryListing(
    val path: String,
    val home: String,
    val crumbs: List<DshDirectoryEntry> = emptyList(),
    val entries: List<DshDirectoryEntry> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class DshCreateDirectoryValue(
    val path: String,
)

@Serializable
data class DshWorkspaceView(
    val workspaceId: String,
    val path: String,
    val folders: List<String> = emptyList(),
    val title: String,
    val sessionIds: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class DshWorkspaceListValue(
    val items: List<DshWorkspaceView> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
    val hiddenWorkspaceIds: List<String> = emptyList(),
)

@Serializable
data class DshWorkspaceCreateValue(
    val workspace: DshWorkspaceView,
    val created: Boolean,
)

@Serializable
data class DshWorkspaceValue(
    val workspace: DshWorkspaceView,
)

@Serializable
data class DshDeletedValue(
    val deleted: Boolean = true,
)

@Serializable
data class DshWorkspaceOrderValue(
    val workspaceIds: List<String> = emptyList(),
)

@Serializable
data class DshArchivedSessionsValue(
    val archivedSessionIds: List<String> = emptyList(),
)

@Serializable
data class DshHiddenWorkspacesValue(
    val hiddenWorkspaceIds: List<String> = emptyList(),
)

@Serializable
data class DshSkillEntry(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val modelInvocable: Boolean,
)

@Serializable
data class DshSkillCatalogEntry(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val modelInvocable: Boolean,
    val userInvocable: Boolean,
    val source: String,
    val provider: String,
)

@Serializable
data class DshSkillListValue(
    val skills: List<DshSkillEntry> = emptyList(),
)

@Serializable
data class DshSkillCatalogValue(
    val skills: List<DshSkillCatalogEntry> = emptyList(),
)

@Serializable
data class DshGitBranchView(
    val name: String,
    val current: Boolean,
    val remote: Boolean,
)

@Serializable
data class DshSessionGitView(
    val currentBranch: String,
    val detached: Boolean,
    val worktreePath: String,
    val isolated: Boolean,
    val dirtyCount: Int,
    val unpushedCount: Int,
    val branches: List<DshGitBranchView> = emptyList(),
)

@Serializable
data class DshAgentPresetEntry(
    val id: String,
    val trust: String,
    val isDefault: Boolean,
    val name: String? = null,
    val description: String? = null,
    val broken: String? = null,
)

@Serializable
data class DshAgentPresetListValue(
    val presets: List<DshAgentPresetEntry> = emptyList(),
    val authorable: Boolean = false,
    val hasDocument: Boolean = false,
)

@Serializable
data class DshAgentPresetSelectValue(
    val agentPreset: String,
)

@Serializable
data class DshGoalRef(
    val id: String,
    val revision: Long,
)

@Serializable
data class DshGoalRefValue(
    val ref: DshGoalRef,
)

@Serializable
data class DshGoalClearedValue(
    val cleared: Boolean = true,
)

@Serializable
data class DshAutomationLocalAt(
    val date: String,
    val time: String,
    @SerialName("time_zone") val timeZone: String,
)

@Serializable
data class DshAutomationLocalClock(
    val time: String,
    @SerialName("time_zone") val timeZone: String,
    val weekdays: List<Int>? = null,
)

@Serializable
data class DshAutomationRuleView(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val task: String,
    val workspaceId: String,
    val agentPreset: String? = null,
    val permissionPreset: String? = null,
    val onOverlap: String,
    val selector: JsonElement? = null,
    val scheduledAt: String,
    val createdAt: String,
    val updatedAt: String,
    val state: String,
    val nextAt: String,
)

@Serializable
data class DshAutomationRunView(
    val id: String,
    val ruleId: String,
    val sessionId: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val outcome: String,
    val source: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class DshAutomationListValue(
    val items: List<DshAutomationRuleView> = emptyList(),
)

@Serializable
data class DshAutomationRuleValue(
    val rule: DshAutomationRuleView,
)

@Serializable
data class DshAutomationDeleteValue(
    val id: String,
    val deleted: Boolean,
)

@Serializable
data class DshAutomationRunValue(
    val run: DshAutomationRunView,
)

@Serializable
data class DshAutomationRunsValue(
    val items: List<DshAutomationRunView> = emptyList(),
)

@Serializable
data class DshSettingsSecretView(
    val path: List<String> = emptyList(),
    val set: Boolean,
)

@Serializable
data class DshSettingsNamespaceView(
    val ns: String,
    val schema: JsonElement? = null,
    val value: JsonElement? = null,
    val base: JsonElement? = null,
    val user: JsonElement? = null,
    val applies: String,
    val secrets: List<DshSettingsSecretView> = emptyList(),
    val revision: Long,
)

@Serializable
data class DshSettingsDescribeValue(
    val writable: Boolean,
    val hasDocument: Boolean,
    val namespaces: List<DshSettingsNamespaceView> = emptyList(),
)

@Serializable
data class DshConfigurableProviderView(
    val provider: String,
    val displayName: String,
    val settingsNs: String,
    val settingsPath: List<String> = emptyList(),
    val active: Boolean,
    val declared: Boolean? = null,
)

@Serializable
data class DshLlmProvidersValue(
    val providers: List<DshLlmProviderInfo> = emptyList(),
)

@Serializable
data class DshLlmProviderInfo(
    val id: String,
    val name: String,
)

@Serializable
data class DshLlmModelsValue(
    val groups: List<DshModelProviderGroup> = emptyList(),
    val failures: List<DshModelCatalogFailure> = emptyList(),
)

@Serializable
data class DshSubagentChild(
    val kind: String,
    val id: String,
    val activity: String? = null,
    val hasChildren: Boolean? = null,
    val mode: String? = null,
    val label: String? = null,
    val reason: String? = null,
)

@Serializable
data class DshSubagentCatalog(
    val entries: List<DshSubagentChild> = emptyList(),
    val parentAvailable: Boolean,
)

@Serializable
data class DshSubagentPromptReceipt(
    val messageId: String,
)

@Serializable
data class DshPromptSectionView(
    val name: String,
    val order: Double,
    val text: String,
    val complete: Boolean,
)

@Serializable
data class DshSystemPromptListValue(
    val sections: List<DshPromptSectionView> = emptyList(),
)

@Serializable
data class DshQuestionAnswerItem(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

@Serializable
data class DshQuestionAnswer(
    val answers: List<DshQuestionAnswerItem> = emptyList(),
)
