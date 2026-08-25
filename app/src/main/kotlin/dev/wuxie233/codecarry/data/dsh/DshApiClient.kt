package dev.wuxie233.codecarry.data.dsh

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

interface DshDownlink {
    val isOpen: Boolean
    suspend fun receive(): String?
    suspend fun close()
}

interface DshDownlinkFactory {
    suspend fun openMux(connection: DshConnection): DshDownlink
    suspend fun openHost(connection: DshConnection): DshDownlink
}

class DshTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class DshApiClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val mintRpcId: () -> String = DshRpc::mintRpcId,
    private val downlinkFactory: DshDownlinkFactory = KtorDshDownlinkFactory(httpClient),
) {
    suspend fun call(
        connection: DshConnection,
        method: String,
        payload: JsonElement = JsonObject(emptyMap()),
        rpcId: String = mintRpcId(),
    ): DshServerResponse {
        if (DshRpc.isLoopbackOnly(method) && !connection.isLoopback) {
            throw DshLoopbackUnavailableException(method)
        }
        val request = DshClientRequest(
            rpcId = rpcId,
            method = method,
            payload = payload,
        )
        val bodyText = json.encodeToString(DshClientRequest.serializer(), request)
        val response = try {
            httpClient.post("${connection.baseUrl}${DshRpc.unaryPath(method)}") {
                contentType(ContentType.Application.Json)
                setBody(bodyText)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw DshTransportException("transport failure for $method", error)
        }
        if (!response.status.isSuccess()) {
            throw DshTransportException("transport failure for $method: HTTP ${response.status.value}")
        }
        val parsed = json.decodeFromString(DshServerResponse.serializer(), response.bodyAsText())
        if (parsed.rpcId != rpcId) {
            throw DshTransportException("rpcId mismatch for $method: sent $rpcId, got ${parsed.rpcId}")
        }
        return parsed
    }

    suspend fun callValue(
        connection: DshConnection,
        method: String,
        payload: JsonElement = JsonObject(emptyMap()),
    ): JsonElement {
        val response = call(connection, method, payload)
        val result = response.result
        if (!result.ok) {
            throw DshRpcException(response.rpcId, result.error ?: DshRpcError("internal", "missing error"))
        }
        return result.value ?: JsonObject(emptyMap())
    }

    suspend fun describe(connection: DshConnection): DshHostDescribe {
        val value = callValue(connection, "host.describe")
        return json.decodeFromJsonElement(DshHostDescribe.serializer(), value)
    }

    suspend fun sessionList(connection: DshConnection, cursor: String? = null): DshSessionListValue =
        decode(callValue(connection, "session.list", optionalObject("cursor" to cursor)))

    suspend fun sessionSearch(connection: DshConnection, query: String): DshSessionSearchValue =
        decode(callValue(connection, "session.search", buildJsonObject { put("query", query) }))

    suspend fun sessionCreate(
        connection: DshConnection,
        workspaceId: String? = null,
        cwd: String? = null,
        sessionId: String? = null,
        agentPreset: String? = null,
    ): DshSessionCreateValue = decode(
        callValue(
            connection,
            "session.create",
            optionalObject(
                "workspaceId" to workspaceId,
                "cwd" to cwd,
                "sessionId" to sessionId,
                "agentPreset" to agentPreset,
            ),
        ),
    )

    suspend fun sessionHistory(
        connection: DshConnection,
        sessionId: String,
        beforeSeq: Long? = null,
        maxMessages: Int? = null,
    ): DshSessionHistoryValue = decode(
        callValue(
            connection,
            "session.history",
            buildJsonObject {
                put("sessionId", sessionId)
                beforeSeq?.let { put("beforeSeq", it) }
                maxMessages?.let { put("maxMessages", it) }
            },
        ),
    )

    suspend fun sessionModels(connection: DshConnection, sessionId: String): DshSessionModels =
        decode(callValue(connection, "session.models", buildJsonObject { put("sessionId", sessionId) }))

    suspend fun sessionSelectModel(
        connection: DshConnection,
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): DshSelectModelValue = decode(
        callValue(
            connection,
            "session.selectModel",
            buildJsonObject {
                put("sessionId", sessionId)
                put("provider", provider)
                put("model", model)
                reasoningEffort?.let { put("reasoningEffort", it) }
            },
        ),
    )

    suspend fun sessionRename(connection: DshConnection, sessionId: String, title: String): DshRenameValue =
        decode(
            callValue(
                connection,
                "session.rename",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("title", title)
                },
            ),
        )

    suspend fun sessionRehome(connection: DshConnection, sessionId: String, path: String): DshRehomeValue =
        decode(
            callValue(
                connection,
                "session.rehome",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("path", path)
                },
            ),
        )

    suspend fun sessionFork(connection: DshConnection, sessionId: String, atSeq: Long? = null): DshForkValue =
        decode(
            callValue(
                connection,
                "session.fork",
                buildJsonObject {
                    put("sessionId", sessionId)
                    atSeq?.let { put("atSeq", it) }
                },
            ),
        )

    suspend fun sessionRewrite(
        connection: DshConnection,
        sessionId: String,
        atSeq: Long,
        content: JsonArray,
        clientTimeZone: String? = null,
    ): DshAcceptedValue = decode(
        callValue(
            connection,
            "session.rewrite",
            buildJsonObject {
                put("sessionId", sessionId)
                put("atSeq", atSeq)
                put("content", content)
                clientTimeZone?.let { put("clientTimeZone", it) }
            },
        ),
    )

    suspend fun sessionPrompt(
        connection: DshConnection,
        sessionId: String,
        mode: String,
        content: JsonArray,
        clientTimeZone: String? = null,
    ): DshAcceptedValue = decode(
        callValue(
            connection,
            "session.prompt",
            buildJsonObject {
                put("sessionId", sessionId)
                put("mode", mode)
                put("content", content)
                clientTimeZone?.let { put("clientTimeZone", it) }
            },
        ),
    )

    suspend fun sessionAttachment(
        connection: DshConnection,
        sessionId: String,
        attachmentId: String,
    ): DshAttachmentValue = decode(
        callValue(
            connection,
            "session.attachment",
            buildJsonObject {
                put("sessionId", sessionId)
                put("attachmentId", attachmentId)
            },
        ),
    )

    suspend fun sessionUpdateQueue(
        connection: DshConnection,
        sessionId: String,
        itemId: String,
        action: JsonObject,
    ): DshAcceptedValue = decode(
        callValue(
            connection,
            "session.updateQueue",
            buildJsonObject {
                put("sessionId", sessionId)
                put("itemId", itemId)
                put("action", action)
            },
        ),
    )

    suspend fun sessionCancel(connection: DshConnection, sessionId: String): DshAcceptedValue =
        decode(callValue(connection, "session.cancel", buildJsonObject { put("sessionId", sessionId) }))

    suspend fun hostListDirectory(connection: DshConnection, path: String? = null): DshDirectoryListing =
        decode(callValue(connection, "host.listDirectory", optionalObject("path" to path)))

    suspend fun hostCreateDirectory(connection: DshConnection, path: String, name: String): DshCreateDirectoryValue =
        decode(
            callValue(
                connection,
                "host.createDirectory",
                buildJsonObject {
                    put("path", path)
                    put("name", name)
                },
            ),
        )

    suspend fun workspaceList(connection: DshConnection): DshWorkspaceListValue =
        decode(callValue(connection, "workspace.list"))

    suspend fun workspaceCreate(connection: DshConnection, path: String): DshWorkspaceCreateValue =
        decode(callValue(connection, "workspace.create", buildJsonObject { put("path", path) }))

    suspend fun workspaceRename(connection: DshConnection, workspaceId: String, title: String): DshWorkspaceValue =
        decode(
            callValue(
                connection,
                "workspace.rename",
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    put("title", title)
                },
            ),
        )

    suspend fun workspaceDelete(connection: DshConnection, workspaceId: String): DshDeletedValue =
        decode(callValue(connection, "workspace.delete", buildJsonObject { put("workspaceId", workspaceId) }))

    suspend fun workspaceInsertBefore(
        connection: DshConnection,
        workspaceId: String,
        beforeWorkspaceId: String? = null,
    ): DshWorkspaceOrderValue = decode(
        callValue(
            connection,
            "workspace.insertBefore",
            optionalObject("workspaceId" to workspaceId, "beforeWorkspaceId" to beforeWorkspaceId),
        ),
    )

    suspend fun workspaceInsertSessionBefore(
        connection: DshConnection,
        workspaceId: String,
        sessionId: String,
        beforeSessionId: String? = null,
    ): DshWorkspaceValue = decode(
        callValue(
            connection,
            "workspace.insertSessionBefore",
            buildJsonObject {
                put("workspaceId", workspaceId)
                put("sessionId", sessionId)
                beforeSessionId?.let { put("beforeSessionId", it) }
            },
        ),
    )

    suspend fun workspaceArchiveSession(connection: DshConnection, sessionId: String): DshArchivedSessionsValue =
        decode(callValue(connection, "workspace.archiveSession", buildJsonObject { put("sessionId", sessionId) }))

    suspend fun workspaceUnarchiveSession(connection: DshConnection, sessionId: String): DshArchivedSessionsValue =
        decode(callValue(connection, "workspace.unarchiveSession", buildJsonObject { put("sessionId", sessionId) }))

    suspend fun workspaceHide(connection: DshConnection, workspaceId: String): DshHiddenWorkspacesValue =
        decode(callValue(connection, "workspace.hide", buildJsonObject { put("workspaceId", workspaceId) }))

    suspend fun workspaceShow(connection: DshConnection, workspaceId: String): DshHiddenWorkspacesValue =
        decode(callValue(connection, "workspace.show", buildJsonObject { put("workspaceId", workspaceId) }))

    suspend fun workspaceAddFolder(connection: DshConnection, workspaceId: String, path: String): DshWorkspaceValue =
        decode(
            callValue(
                connection,
                "workspace.addFolder",
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    put("path", path)
                },
            ),
        )

    suspend fun workspaceRemoveFolder(connection: DshConnection, workspaceId: String, path: String): DshWorkspaceValue =
        decode(
            callValue(
                connection,
                "workspace.removeFolder",
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    put("path", path)
                },
            ),
        )

    suspend fun skillList(connection: DshConnection, sessionId: String): DshSkillListValue =
        decode(callValue(connection, "skill.list", buildJsonObject { put("sessionId", sessionId) }))

    suspend fun skillCatalog(connection: DshConnection): DshSkillCatalogValue =
        decode(callValue(connection, "skill.catalog"))

    suspend fun gitDescribe(
        connection: DshConnection,
        sessionId: String? = null,
        workspaceId: String? = null,
    ): DshSessionGitView = decode(
        callValue(connection, "git.describe", optionalObject("sessionId" to sessionId, "workspaceId" to workspaceId)),
    )

    suspend fun gitCheckout(connection: DshConnection, sessionId: String, branch: String): DshSessionGitView =
        decode(
            callValue(
                connection,
                "git.checkout",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("branch", branch)
                },
            ),
        )

    suspend fun gitCreateBranch(connection: DshConnection, sessionId: String, branch: String): DshSessionGitView =
        decode(
            callValue(
                connection,
                "git.createBranch",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("branch", branch)
                },
            ),
        )

    suspend fun agentPresetList(connection: DshConnection): DshAgentPresetListValue =
        decode(callValue(connection, "agentPreset.list"))

    suspend fun agentPresetSelect(
        connection: DshConnection,
        sessionId: String,
        agentPreset: String,
    ): DshAgentPresetSelectValue = decode(
        callValue(
            connection,
            "agentPreset.select",
            buildJsonObject {
                put("sessionId", sessionId)
                put("agentPreset", agentPreset)
            },
        ),
    )

    suspend fun goalCreate(
        connection: DshConnection,
        sessionId: String,
        objective: String,
        maxGoalRounds: Int? = null,
    ): DshGoalRefValue = decode(
        callValue(
            connection,
            "goal.create",
            buildJsonObject {
                put("sessionId", sessionId)
                put("objective", objective)
                maxGoalRounds?.let { put("maxGoalRounds", it) }
            },
        ),
    )

    suspend fun goalEdit(
        connection: DshConnection,
        sessionId: String,
        ref: DshGoalRef,
        objective: String? = null,
        maxGoalRounds: Int? = null,
    ): DshGoalRefValue = decode(
        callValue(
            connection,
            "goal.edit",
            buildJsonObject {
                put("sessionId", sessionId)
                put("ref", json.encodeToJsonElement(DshGoalRef.serializer(), ref))
                objective?.let { put("objective", it) }
                maxGoalRounds?.let { put("maxGoalRounds", it) }
            },
        ),
    )

    suspend fun goalPause(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalRefValue =
        goalVerb(connection, "goal.pause", sessionId, ref)

    suspend fun goalResume(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalRefValue =
        goalVerb(connection, "goal.resume", sessionId, ref)

    suspend fun goalComplete(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalRefValue =
        goalVerb(connection, "goal.complete", sessionId, ref)

    suspend fun goalClear(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalClearedValue =
        decode(callValue(connection, "goal.clear", goalRefPayload(sessionId, ref)))

    suspend fun automationList(connection: DshConnection): DshAutomationListValue =
        decode(callValue(connection, "automation.list"))

    suspend fun automationCreate(connection: DshConnection, payload: JsonObject): DshAutomationRuleValue =
        decode(callValue(connection, "automation.create", payload))

    suspend fun automationUpdate(connection: DshConnection, payload: JsonObject): DshAutomationRuleValue =
        decode(callValue(connection, "automation.update", payload))

    suspend fun automationDelete(connection: DshConnection, id: String): DshAutomationDeleteValue =
        decode(callValue(connection, "automation.delete", buildJsonObject { put("id", id) }))

    suspend fun automationSetEnabled(connection: DshConnection, id: String, enabled: Boolean): DshAutomationRuleValue =
        decode(
            callValue(
                connection,
                "automation.setEnabled",
                buildJsonObject {
                    put("id", id)
                    put("enabled", enabled)
                },
            ),
        )

    suspend fun automationRunNow(connection: DshConnection, id: String): DshAutomationRunValue =
        decode(callValue(connection, "automation.runNow", buildJsonObject { put("id", id) }))

    suspend fun automationListRuns(connection: DshConnection, id: String, limit: Int? = null): DshAutomationRunsValue =
        decode(
            callValue(
                connection,
                "automation.listRuns",
                buildJsonObject {
                    put("id", id)
                    limit?.let { put("limit", it) }
                },
            ),
        )

    suspend fun automationDeleteRun(connection: DshConnection, id: String): DshAutomationDeleteValue =
        decode(callValue(connection, "automation.deleteRun", buildJsonObject { put("id", id) }))

    suspend fun settingsDescribe(connection: DshConnection): DshSettingsDescribeValue =
        decode(callValue(connection, "settings.describe"))

    suspend fun settingsUpdate(
        connection: DshConnection,
        ns: String,
        patch: JsonObject,
        expectedRevision: Long? = null,
    ): DshSettingsNamespaceView = decode(
        callValue(
            connection,
            "settings.update",
            buildJsonObject {
                put("ns", ns)
                put("patch", patch)
                expectedRevision?.let { put("expectedRevision", it) }
            },
        ),
    )

    suspend fun settingsReplace(
        connection: DshConnection,
        ns: String,
        section: JsonObject,
        expectedRevision: Long? = null,
    ): DshSettingsNamespaceView = decode(
        callValue(
            connection,
            "settings.replace",
            buildJsonObject {
                put("ns", ns)
                put("section", section)
                expectedRevision?.let { put("expectedRevision", it) }
            },
        ),
    )

    suspend fun settingsMutate(
        connection: DshConnection,
        ns: String,
        ops: JsonArray,
        expectedRevision: Long? = null,
    ): DshSettingsNamespaceView = decode(
        callValue(
            connection,
            "settings.mutate",
            buildJsonObject {
                put("ns", ns)
                put("ops", ops)
                expectedRevision?.let { put("expectedRevision", it) }
            },
        ),
    )

    suspend fun llmProviders(connection: DshConnection): DshLlmProvidersValue =
        decode(callValue(connection, "llm.providers"))

    suspend fun llmModels(connection: DshConnection): DshLlmModelsValue =
        decode(callValue(connection, "llm.models"))

    suspend fun subagentList(connection: DshConnection, parentSessionId: String): DshSubagentCatalog =
        decode(callValue(connection, "subagent.list", buildJsonObject { put("parentSessionId", parentSessionId) }))

    suspend fun subagentHistory(
        connection: DshConnection,
        parentSessionId: String,
        childSessionId: String,
        mode: String,
        beforeSeq: Long? = null,
        maxMessages: Int? = null,
    ): DshSessionHistoryValue = decode(
        callValue(
            connection,
            "subagent.history",
            buildJsonObject {
                put("parentSessionId", parentSessionId)
                put("childSessionId", childSessionId)
                put("mode", mode)
                beforeSeq?.let { put("beforeSeq", it) }
                maxMessages?.let { put("maxMessages", it) }
            },
        ),
    )

    suspend fun subagentPrompt(
        connection: DshConnection,
        parentSessionId: String,
        childSessionId: String,
        content: JsonArray,
        clientTimeZone: String? = null,
    ): DshSubagentPromptReceipt = decode(
        callValue(
            connection,
            "subagent.prompt",
            buildJsonObject {
                put("parentSessionId", parentSessionId)
                put("childSessionId", childSessionId)
                put("mode", "continuable")
                put("content", content)
                clientTimeZone?.let { put("clientTimeZone", it) }
            },
        ),
    )

    suspend fun subagentInterrupt(
        connection: DshConnection,
        parentSessionId: String,
        childSessionId: String,
    ): DshAcceptedValue = decode(
        callValue(
            connection,
            "subagent.interrupt",
            buildJsonObject {
                put("parentSessionId", parentSessionId)
                put("childSessionId", childSessionId)
                put("mode", "continuable")
            },
        ),
    )

    suspend fun systemPromptList(connection: DshConnection): DshSystemPromptListValue =
        decode(callValue(connection, "systemPrompt.list"))

    private suspend fun goalVerb(
        connection: DshConnection,
        method: String,
        sessionId: String,
        ref: DshGoalRef,
    ): DshGoalRefValue = decode(callValue(connection, method, goalRefPayload(sessionId, ref)))

    private fun goalRefPayload(sessionId: String, ref: DshGoalRef): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("ref", json.encodeToJsonElement(DshGoalRef.serializer(), ref))
    }

    private inline fun <reified T> decode(value: JsonElement): T =
        json.decodeFromJsonElement(serializer(), value)

    private fun optionalObject(vararg pairs: Pair<String, String?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> value?.let { put(key, it) } }
    }

    suspend fun respond(
        connection: DshConnection,
        rpcId: String,
        result: DshRpcResult,
    ): DshRpcReceipt {
        val message = DshClientResponse(rpcId = rpcId, result = result)
        val bodyText = json.encodeToString(DshClientResponse.serializer(), message)
        val response = try {
            httpClient.post("${connection.baseUrl}${DshRpc.RESPOND_PATH}") {
                contentType(ContentType.Application.Json)
                setBody(bodyText)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw DshTransportException("transport failure for /api/respond", error)
        }
        if (!response.status.isSuccess()) {
            throw DshTransportException("transport failure for /api/respond: HTTP ${response.status.value}")
        }
        return json.decodeFromString(DshRpcReceipt.serializer(), response.bodyAsText())
    }

    suspend fun answerApproval(
        connection: DshConnection,
        rpcId: String,
        sessionId: String,
        approvalId: String,
        outcome: String,
    ): DshRpcReceipt = respond(
        connection,
        rpcId,
        DshRpcResult(
            ok = true,
            value = buildJsonObject {
                put("sessionId", sessionId)
                put("approvalId", approvalId)
                put("outcome", outcome)
            },
        ),
    )

    suspend fun answerQuestion(
        connection: DshConnection,
        rpcId: String,
        sessionId: String,
        answers: JsonElement,
    ): DshRpcReceipt = respond(
        connection,
        rpcId,
        DshRpcResult(
            ok = true,
            value = buildJsonObject {
                put("sessionId", sessionId)
                put("answer", answers)
            },
        ),
    )

    fun muxFrames(downlink: DshDownlink): Flow<DshEnvelope<DshMuxFrame>> = flow {
        while (true) {
            val text = downlink.receive() ?: break
            val envelope = parseServerRequest(text) ?: continue
            val frame = parseMuxFrame(envelope.payload)
            if (frame is DshMuxFrame.StreamError) {
                throw DshTransportException("mux stream/error: ${frame.error.code}: ${frame.error.message}")
            }
            emit(DshEnvelope(envelope.rpcId, frame))
        }
    }

    fun hostFrames(downlink: DshDownlink): Flow<DshEnvelope<DshHostFrame>> = flow {
        while (true) {
            val text = downlink.receive() ?: break
            val envelope = parseServerRequest(text) ?: continue
            val frame = parseHostFrame(envelope.payload)
            if (frame is DshHostFrame.StreamError) {
                throw DshTransportException("host stream/error: ${frame.error.code}: ${frame.error.message}")
            }
            emit(DshEnvelope(envelope.rpcId, frame))
        }
    }

    suspend fun openMux(connection: DshConnection): DshDownlink = downlinkFactory.openMux(connection)

    suspend fun openHost(connection: DshConnection): DshDownlink = downlinkFactory.openHost(connection)

    private fun parseServerRequest(text: String): DshServerRequest? {
        return runCatching {
            json.decodeFromString(DshServerRequest.serializer(), text)
        }.getOrNull()
    }
}

class KtorDshDownlinkFactory(
    private val httpClient: HttpClient,
) : DshDownlinkFactory {
    override suspend fun openMux(connection: DshConnection): DshDownlink =
        KtorDshDownlink.open(httpClient, dshHttpToWebSocketUrl(connection.baseUrl, DshRpc.MUX_EVENTS_PATH))

    override suspend fun openHost(connection: DshConnection): DshDownlink =
        KtorDshDownlink.open(httpClient, dshHttpToWebSocketUrl(connection.baseUrl, DshRpc.HOST_EVENTS_PATH))
}

class KtorDshDownlink private constructor(
    private val session: WebSocketSession,
) : DshDownlink {
    override val isOpen: Boolean
        get() = !session.incoming.isClosedForReceive

    override suspend fun receive(): String? {
        return try {
            when (val frame = session.incoming.receiveCatching().getOrNull()) {
                is Frame.Text -> frame.readText()
                null -> null
                else -> receive()
            }
        } catch (_: ClosedReceiveChannelException) {
            null
        }
    }

    override suspend fun close() {
        runCatching { session.close() }
    }

    companion object {
        suspend fun open(httpClient: HttpClient, url: String): KtorDshDownlink {
            val session = httpClient.webSocketSession {
                method = HttpMethod.Get
                url(url)
            }
            return KtorDshDownlink(session)
        }
    }
}
