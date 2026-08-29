package dev.wuxie233.codecarry.data.dsh

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Downlink-only handle over the single `/api/remote.mux` WebSocket. Client
 * sends logical-stream `open`/`cancel` text frames; the Host answers with
 * `item`/`error`/`end` frames.
 */
interface DshDownlink {
    val isOpen: Boolean
    suspend fun receive(): String?
    suspend fun send(text: String)
    suspend fun close()
}

interface DshDownlinkFactory {
    suspend fun openMux(connection: DshConnection): DshDownlink
}

/** One raw frame off the remote.mux socket, before per-stream routing. */
sealed interface DshMuxWireMessage {
    val streamId: String

    data class Item(
        override val streamId: String,
        val value: JsonElement?,
    ) : DshMuxWireMessage

    data class WireError(
        override val streamId: String,
        val error: DshRpcError,
    ) : DshMuxWireMessage

    data class End(override val streamId: String) : DshMuxWireMessage
}

class DshTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class DshApiClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val mintRpcId: () -> String = DshRpc::mintRpcId,
    private val downlinkFactory: DshDownlinkFactory = KtorDshDownlinkFactory(httpClient),
) {
    /**
     * Cookies minted by [exchangeCookie], keyed by baseUrl. Screens build
     * their own [DshConnection] from route args without the cookie, so the
     * client attaches the cached session per request; a 401 drops the cache
     * and surfaces as [DshAuthRequiredException] so the manager re-exchanges.
     */
    private val cookieCache = mutableMapOf<String, String>()

    private fun cookieFor(connection: DshConnection): String? =
        connection.cookie ?: cookieCache[connection.baseUrl]

    /**
     * One unary Remote call: `POST /api/<namespace>/<method>` with the
     * `{ type, rpcId, method, payload: { args } }` envelope. Cookie first,
     * optional Basic for a fronting proxy.
     */
    suspend fun call(
        connection: DshConnection,
        method: String,
        args: JsonObject = JsonObject(emptyMap()),
        rpcId: String = mintRpcId(),
    ): DshServerResponse {
        if (DshRpc.isLoopbackOnly(method) && !connection.isLoopback) {
            throw DshLoopbackUnavailableException(method)
        }
        val request = DshClientRequest(
            rpcId = rpcId,
            method = method,
            payload = DshRpc.argsPayload(args),
        )
        val bodyText = json.encodeToString(DshClientRequest.serializer(), request)
        val response = try {
            httpClient.post("${connection.baseUrl}${DshRpc.unaryPath(method)}") {
                contentType(ContentType.Application.Json)
                connection.basicAuthorization?.let { header(HttpHeaders.Authorization, it) }
                cookieFor(connection)?.let { header(HttpHeaders.Cookie, it) }
                setBody(bodyText)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw DshTransportException("transport failure for $method", error)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            cookieCache.remove(connection.baseUrl)
            throw DshAuthRequiredException("DSH authentication failed for $method")
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
        args: JsonObject = JsonObject(emptyMap()),
    ): JsonElement {
        val response = call(connection, method, args)
        val result = response.result
        if (!result.ok) {
            throw DshRpcException(response.rpcId, result.error ?: DshRpcError("internal", "missing error"))
        }
        return result.value ?: JsonObject(emptyMap())
    }

    /**
     * Exchange the process launch token for the authority-bound Connection
     * cookie on `GET /?token=`. The Host answers 303 with Set-Cookie; this
     * client has redirects disabled so the cookie is observable. A passworded
     * public host keeps Basic so dsh-auth lets the GET through and attaches
     * the current process token itself.
     */
    suspend fun exchangeCookie(connection: DshConnection): DshConnection {
        if (!connection.cookie.isNullOrBlank()) {
            cookieCache[connection.baseUrl] = connection.cookie
            return connection
        }
        val indexUrl = dshIndexUrl(connection.baseUrl, connection.token)
        val response = try {
            httpClient.get(indexUrl) {
                connection.basicAuthorization?.let { header(HttpHeaders.Authorization, it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw DshTransportException("transport failure for cookie exchange", error)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw DshAuthRequiredException("DSH authentication failed during cookie exchange")
        }
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val cookie = setCookies.firstNotNullOfOrNull(::dshCookiePair)
            ?: throw DshAuthRequiredException(
                "DSH cookie exchange returned no session cookie (HTTP ${response.status.value})",
            )
        cookieCache[connection.baseUrl] = cookie
        return connection.withCookie(cookie)
    }

    suspend fun sessionList(connection: DshConnection, cursor: String? = null): DshSessionListValue =
        decode(callValue(connection, "session/list", DshRpc.listRequestArgs(cursor)))

    suspend fun sessionSearch(connection: DshConnection, query: String): DshSessionSearchValue =
        decode(
            callValue(
                connection,
                "session/search",
                buildJsonObject { put("request", buildJsonObject { put("query", query) }) },
            ),
        )

    suspend fun sessionCreate(
        connection: DshConnection,
        workspaceId: String? = null,
        cwd: String? = null,
        sessionId: String? = null,
        agentPreset: String? = null,
    ): DshSessionCreateValue = decode(
        callValue(
            connection,
            "session/create",
            wrapRequest(
                optionalObject(
                    "workspaceId" to workspaceId,
                    "cwd" to cwd,
                    "sessionId" to sessionId,
                    "agentPreset" to agentPreset,
                ),
            ),
        ),
    )

    /**
     * One backwards history page via `session/page`. `throughSeq` is the
     * inclusive log cut from the follow snapshot; when absent the newest cut
     * is requested with a very large sentinel.
     */
    suspend fun sessionPage(
        connection: DshConnection,
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long? = null,
        maxMessages: Int? = null,
    ): DshSessionPageValue = decode(
        callValue(
            connection,
            "session/page",
            buildJsonObject {
                put(
                    "request",
                    buildJsonObject {
                        put(
                            "address",
                            buildJsonObject {
                                put("kind", "session")
                                put("sessionId", sessionId)
                            },
                        )
                        put("throughSeq", throughSeq)
                        beforeSeq?.let { put("beforeSeq", it) }
                        maxMessages?.let { put("maxMessages", it) }
                    },
                )
            },
        ),
    )

    /** History helper preserving the old call shape; folds page records. */
    suspend fun sessionHistory(
        connection: DshConnection,
        sessionId: String,
        beforeSeq: Long? = null,
        maxMessages: Int? = null,
        throughSeq: Long = DshRpc.THROUGH_SEQ_LATEST,
    ): DshSessionHistoryValue = sessionPage(connection, sessionId, throughSeq, beforeSeq, maxMessages)
        .toHistory()

    /** Host-wide model catalog; the session-scoped selection rides projections. */
    suspend fun sessionModels(connection: DshConnection): DshSessionModels =
        decode(callValue(connection, "session/modelCatalog"))

    suspend fun sessionSelectModel(
        connection: DshConnection,
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
    ): DshSelectModelValue = decode(
        callValue(
            connection,
            "session/selectModel",
            wrapRequest(
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("provider", provider)
                    put("model", model)
                    reasoningEffort?.let { put("reasoningEffort", it) }
                },
            ),
        ),
    )

    suspend fun sessionRename(connection: DshConnection, sessionId: String, title: String): DshRenameValue =
        decode(
            callValue(
                connection,
                "session/rename",
                wrapRequest(
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("title", title)
                    },
                ),
            ),
        )

    suspend fun sessionRehome(connection: DshConnection, sessionId: String, path: String): DshRehomeValue =
        decode(
            callValue(
                connection,
                "session/rehome",
                wrapRequest(
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("path", path)
                    },
                ),
            ),
        )

    suspend fun sessionFork(connection: DshConnection, sessionId: String, atSeq: Long? = null): DshForkValue =
        decode(
            callValue(
                connection,
                "session/fork",
                wrapRequest(
                    buildJsonObject {
                        put("sessionId", sessionId)
                        atSeq?.let { put("atSeq", it) }
                    },
                ),
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
            "session/rewrite",
            wrapRequest(
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("atSeq", atSeq)
                    put("content", content)
                    clientTimeZone?.let { put("clientTimeZone", it) }
                },
            ),
        ),
    )

    /** `session/prompt` requires a client-minted `requestId`. */
    suspend fun sessionPrompt(
        connection: DshConnection,
        sessionId: String,
        mode: String,
        content: JsonArray,
        clientTimeZone: String? = null,
        requestId: String = DshRpc.mintRequestId(),
    ): DshAcceptedValue = decode(
        callValue(
            connection,
            "session/prompt",
            wrapRequest(
                buildJsonObject {
                    put("requestId", requestId)
                    put("sessionId", sessionId)
                    put("mode", mode)
                    put("content", content)
                    clientTimeZone?.let { put("clientTimeZone", it) }
                },
            ),
        ),
    )

    suspend fun sessionAttachment(
        connection: DshConnection,
        sessionId: String,
        attachmentId: String,
    ): DshAttachmentValue = decode(
        callValue(
            connection,
            "session/attachment",
            wrapRequest(
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("attachmentId", attachmentId)
                },
            ),
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
            "session/updateQueue",
            wrapRequest(
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("itemId", itemId)
                    put("action", action)
                },
            ),
        ),
    )

    suspend fun sessionCancel(connection: DshConnection, sessionId: String): DshAcceptedValue =
        decode(
            callValue(
                connection,
                "session/cancel",
                wrapRequest(buildJsonObject { put("sessionId", sessionId) }),
            ),
        )

    suspend fun canOpenWorkspacePath(connection: DshConnection): Boolean =
        callValue(connection, "session/canOpenWorkspacePath").let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        }

    suspend fun hostListDirectory(connection: DshConnection, path: String? = null): DshDirectoryListing =
        decode(callValue(connection, "directoryPicker/list", optionalObject("path" to path)))

    suspend fun hostCreateDirectory(connection: DshConnection, path: String, name: String): DshCreateDirectoryValue =
        decode(
            callValue(
                connection,
                "directoryPicker/createDirectory",
                buildJsonObject {
                    put("path", path)
                    put("name", name)
                },
            ),
        )

    suspend fun workspaceCreate(connection: DshConnection, path: String): DshWorkspaceCreateValue =
        decode(
            callValue(
                connection,
                "workspace/create",
                wrapRequest(buildJsonObject { put("path", path) }),
            ),
        )

    suspend fun workspaceRename(connection: DshConnection, workspaceId: String, title: String): DshWorkspaceValue =
        decode(
            callValue(
                connection,
                "workspace/rename",
                wrapRequest(
                    buildJsonObject {
                        put("workspaceId", workspaceId)
                        put("title", title)
                    },
                ),
            ),
        )

    suspend fun workspaceDelete(connection: DshConnection, workspaceId: String): DshDeletedValue =
        decode(
            callValue(
                connection,
                "workspace/delete",
                wrapRequest(buildJsonObject { put("workspaceId", workspaceId) }),
            ),
        )

    suspend fun workspaceInsertBefore(
        connection: DshConnection,
        workspaceId: String,
        beforeWorkspaceId: String? = null,
    ): DshWorkspaceOrderValue = decode(
        callValue(
            connection,
            "workspace/insertBefore",
            wrapRequest(
                optionalObject("workspaceId" to workspaceId, "beforeWorkspaceId" to beforeWorkspaceId),
            ),
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
            "workspace/insertSessionBefore",
            wrapRequest(
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    put("sessionId", sessionId)
                    beforeSessionId?.let { put("beforeSessionId", it) }
                },
            ),
        ),
    )

    suspend fun workspaceArchiveSession(connection: DshConnection, sessionId: String): DshArchivedSessionsValue =
        decode(
            callValue(
                connection,
                "workspace/archiveSession",
                wrapRequest(buildJsonObject { put("sessionId", sessionId) }),
            ),
        )

    suspend fun workspaceUnarchiveSession(connection: DshConnection, sessionId: String): DshArchivedSessionsValue =
        decode(
            callValue(
                connection,
                "workspace/unarchiveSession",
                wrapRequest(buildJsonObject { put("sessionId", sessionId) }),
            ),
        )

    suspend fun workspaceHide(connection: DshConnection, workspaceId: String): DshHiddenWorkspacesValue =
        decode(
            callValue(
                connection,
                "workspace/hide",
                wrapRequest(buildJsonObject { put("workspaceId", workspaceId) }),
            ),
        )

    suspend fun workspaceShow(connection: DshConnection, workspaceId: String): DshHiddenWorkspacesValue =
        decode(
            callValue(
                connection,
                "workspace/show",
                wrapRequest(buildJsonObject { put("workspaceId", workspaceId) }),
            ),
        )

    suspend fun workspaceAddFolder(connection: DshConnection, workspaceId: String, path: String): DshWorkspaceValue =
        decode(
            callValue(
                connection,
                "workspace/addFolder",
                wrapRequest(
                    buildJsonObject {
                        put("workspaceId", workspaceId)
                        put("path", path)
                    },
                ),
            ),
        )

    suspend fun workspaceRemoveFolder(connection: DshConnection, workspaceId: String, path: String): DshWorkspaceValue =
        decode(
            callValue(
                connection,
                "workspace/removeFolder",
                wrapRequest(
                    buildJsonObject {
                        put("workspaceId", workspaceId)
                        put("path", path)
                    },
                ),
            ),
        )

    suspend fun skillList(connection: DshConnection, sessionId: String): DshSkillListValue =
        decode(
            callValue(
                connection,
                "skills/list",
                buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) },
            ),
        )

    suspend fun gitDescribe(
        connection: DshConnection,
        sessionId: String? = null,
        workspaceId: String? = null,
    ): DshSessionGitView = decode(
        callValue(
            connection,
            "git/describe",
            optionalObject("sessionId" to sessionId, "workspaceId" to workspaceId),
        ),
    )

    suspend fun gitCheckout(connection: DshConnection, sessionId: String, branch: String): DshSessionGitView =
        decode(
            callValue(
                connection,
                "git/checkout",
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
                "git/createBranch",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("branch", branch)
                },
            ),
        )

    suspend fun agentPresetList(connection: DshConnection): DshAgentPresetListValue =
        decode(callValue(connection, "agentPresets/list"))

    /** `agentPresets/select` takes the wire Agent identity plus preset id. */
    suspend fun agentPresetSelect(
        connection: DshConnection,
        sessionId: String,
        agentPreset: String,
    ): DshAgentPresetSelectValue {
        val value = callValue(
            connection,
            "agentPresets/select",
            buildJsonObject {
                put("agentId", sessionId)
                put("agentPreset", agentPreset)
            },
        )
        val selected = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: agentPreset
        return DshAgentPresetSelectValue(agentPreset = selected)
    }

    suspend fun goalCreate(
        connection: DshConnection,
        sessionId: String,
        objective: String,
        maxGoalRounds: Int? = null,
    ): DshGoalRefValue = decode(
        callValue(
            connection,
            "goals/create",
            buildJsonObject {
                put("agentId", sessionId)
                put(
                    "request",
                    buildJsonObject {
                        put("objective", objective)
                        maxGoalRounds?.let { put("maxGoalRounds", it) }
                    },
                )
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
            "goals/edit",
            buildJsonObject {
                put("agentId", sessionId)
                put("ref", json.encodeToJsonElement(DshGoalRef.serializer(), ref))
                put(
                    "request",
                    buildJsonObject {
                        objective?.let { put("objective", it) }
                        maxGoalRounds?.let { put("maxGoalRounds", it) }
                    },
                )
            },
        ),
    )

    suspend fun goalPause(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalRefValue =
        goalVerb(connection, "goals/pause", sessionId, ref)

    suspend fun goalResume(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalRefValue =
        goalVerb(connection, "goals/resume", sessionId, ref)

    suspend fun goalComplete(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalRefValue =
        goalVerb(connection, "goals/complete", sessionId, ref)

    suspend fun goalClear(connection: DshConnection, sessionId: String, ref: DshGoalRef): DshGoalClearedValue =
        decode(callValue(connection, "goals/clear", goalRefArgs(sessionId, ref)))

    suspend fun automationList(connection: DshConnection): DshAutomationListValue =
        decode(callValue(connection, "automation/list"))

    suspend fun automationCreate(connection: DshConnection, payload: JsonObject): DshAutomationRuleValue =
        decode(callValue(connection, "automation/create", wrapRequest(payload)))

    suspend fun automationUpdate(connection: DshConnection, payload: JsonObject): DshAutomationRuleValue =
        decode(callValue(connection, "automation/update", wrapRequest(payload)))

    suspend fun automationDelete(connection: DshConnection, id: String): DshAutomationDeleteValue =
        decode(callValue(connection, "automation/delete", buildJsonObject { put("id", id) }))

    suspend fun automationSetEnabled(connection: DshConnection, id: String, enabled: Boolean): DshAutomationRuleValue =
        decode(
            callValue(
                connection,
                "automation/setEnabled",
                buildJsonObject {
                    put("id", id)
                    put("enabled", enabled)
                },
            ),
        )

    suspend fun automationRunNow(connection: DshConnection, id: String): DshAutomationRunValue =
        decode(callValue(connection, "automation/runNow", buildJsonObject { put("id", id) }))

    suspend fun automationListRuns(connection: DshConnection, id: String, limit: Int? = null): DshAutomationRunsValue =
        decode(
            callValue(
                connection,
                "automation/listRuns",
                buildJsonObject {
                    put("id", id)
                    limit?.let { put("limit", it) }
                },
            ),
        )

    suspend fun automationDeleteRun(connection: DshConnection, id: String): DshAutomationDeleteValue =
        decode(callValue(connection, "automation/deleteRun", buildJsonObject { put("id", id) }))

    suspend fun settingsDescribe(connection: DshConnection): DshSettingsDescribeValue =
        decode(callValue(connection, "settings/describe"))

    suspend fun settingsUpdate(
        connection: DshConnection,
        ns: String,
        patch: JsonObject,
        expectedRevision: Long? = null,
    ): DshSettingsNamespaceView = decode(
        callValue(
            connection,
            "settings/update",
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
            "settings/replace",
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
            "settings/mutate",
            buildJsonObject {
                put("ns", ns)
                put("ops", ops)
                expectedRevision?.let { put("expectedRevision", it) }
            },
        ),
    )

    /** `llm/listProviders` answers a raw array of `{ id, name }` rows. */
    suspend fun llmProviders(connection: DshConnection): DshLlmProvidersValue {
        val value = callValue(connection, "llm/listProviders")
        val rows = (value as? JsonArray).orEmpty()
        val providers = rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            DshLlmProviderInfo(
                id = obj.strOrNull("id") ?: return@mapNotNull null,
                name = obj.strOrNull("name") ?: return@mapNotNull null,
            )
        }
        return DshLlmProvidersValue(providers = providers)
    }

    /** `session/modelCatalog` backs the model list surface. */
    suspend fun llmModels(connection: DshConnection): DshLlmModelsValue =
        decode(callValue(connection, "session/modelCatalog"))

    suspend fun subagentList(connection: DshConnection, parentSessionId: String): DshSubagentCatalog =
        decode(
            callValue(
                connection,
                "subagents/list",
                buildJsonObject { put("parentSessionId", parentSessionId) },
            ),
        )

    suspend fun subagentPrompt(
        connection: DshConnection,
        parentSessionId: String,
        childSessionId: String,
        content: JsonArray,
        clientTimeZone: String? = null,
        requestId: String = DshRpc.mintRequestId(),
    ): DshSubagentPromptReceipt = decode(
        callValue(
            connection,
            "subagents/prompt",
            buildJsonObject {
                put(
                    "request",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("parentSessionId", parentSessionId)
                        put("childSessionId", childSessionId)
                        put("content", content)
                        clientTimeZone?.let { put("clientTimeZone", it) }
                    },
                )
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
            "subagents/interruptByParent",
            buildJsonObject {
                put("childSessionId", childSessionId)
                put("parentSessionId", parentSessionId)
                put("mode", "continuable")
            },
        ),
    )

    suspend fun systemPromptList(connection: DshConnection): DshSystemPromptListValue =
        decode(callValue(connection, "systemPrompt/list"))

    /** Answer one `approval/request` waterfall on `$events/result`. */
    suspend fun answerApproval(
        connection: DshConnection,
        clientId: String,
        eventId: String,
        outcome: String,
    ) {
        call(
            connection,
            DshRpc.EVENTS_RESULT_ENDPOINT,
            buildJsonObject {
                put("clientId", clientId)
                put("eventId", eventId)
                put("outcome", outcome)
            },
        )
    }

    /** Answer one `user-questions/request` waterfall on `$events/result`. */
    suspend fun answerQuestion(
        connection: DshConnection,
        clientId: String,
        eventId: String,
        answers: JsonElement,
    ) {
        call(
            connection,
            DshRpc.EVENTS_RESULT_ENDPOINT,
            buildJsonObject {
                put("clientId", clientId)
                put("eventId", eventId)
                put(
                    "outcome",
                    buildJsonObject {
                        put("kind", "result")
                        put("value", answers)
                    },
                )
            },
        )
    }

    /** Reject one `user-questions/request` waterfall on `$events/result`. */
    suspend fun rejectQuestion(
        connection: DshConnection,
        clientId: String,
        eventId: String,
    ) {
        call(
            connection,
            DshRpc.EVENTS_RESULT_ENDPOINT,
            buildJsonObject {
                put("clientId", clientId)
                put("eventId", eventId)
                put(
                    "outcome",
                    buildJsonObject {
                        put("kind", "rejected")
                        put(
                            "error",
                            buildJsonObject {
                                put("name", "Error")
                                put("message", "cancelled by the user")
                            },
                        )
                    },
                )
            },
        )
    }

    suspend fun openMux(connection: DshConnection): DshDownlink =
        downlinkFactory.openMux(connection.withCookie(cookieFor(connection)))

    /** Send one logical-stream open request on the mux socket. */
    suspend fun sendStreamOpen(downlink: DshDownlink, endpoint: String, streamId: String, args: JsonObject) {
        val frame = buildJsonObject {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", endpoint)
            put("payload", DshRpc.argsPayload(args))
        }
        downlink.send(json.encodeToString(JsonObject.serializer(), frame))
    }

    /** Send one logical-stream cancel request on the mux socket. */
    suspend fun sendStreamCancel(downlink: DshDownlink, streamId: String) {
        val frame = buildJsonObject {
            put("type", "cancel")
            put("streamId", streamId)
        }
        downlink.send(json.encodeToString(JsonObject.serializer(), frame))
    }

    /** Demux raw mux text frames into `item`/`error`/`end` wire messages. */
    fun muxMessages(downlink: DshDownlink): Flow<DshMuxWireMessage> = flow {
        while (true) {
            val text = downlink.receive() ?: break
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val streamId = obj.strOrNull("streamId") ?: continue
            when (obj.strOrNull("type")) {
                "item" -> emit(DshMuxWireMessage.Item(streamId, obj["value"]))
                "error" -> emit(
                    DshMuxWireMessage.WireError(streamId, parseRpcErrorJson(obj["error"])),
                )
                "end" -> emit(DshMuxWireMessage.End(streamId))
                else -> continue
            }
        }
    }

    private suspend fun goalVerb(
        connection: DshConnection,
        method: String,
        sessionId: String,
        ref: DshGoalRef,
    ): DshGoalRefValue = decode(callValue(connection, method, goalRefArgs(sessionId, ref)))

    private fun goalRefArgs(sessionId: String, ref: DshGoalRef): JsonObject = buildJsonObject {
        put("agentId", sessionId)
        put("ref", json.encodeToJsonElement(DshGoalRef.serializer(), ref))
    }

    private fun wrapRequest(request: JsonObject): JsonObject = DshRpc.requestArgs(request)

    private inline fun <reified T> decode(value: JsonElement): T =
        json.decodeFromJsonElement(serializer(), value)

    private fun optionalObject(vararg pairs: Pair<String, String?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> value?.let { put(key, it) } }
    }
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

private fun parseRpcErrorJson(element: JsonElement?): DshRpcError {
    val obj = element as? JsonObject
    return DshRpcError(
        code = obj?.strOrNull("code").orEmpty().ifBlank { "internal" },
        message = obj?.strOrNull("message").orEmpty(),
        details = obj?.get("details") ?: JsonObject(emptyMap()),
    )
}

class KtorDshDownlinkFactory(
    private val httpClient: HttpClient,
) : DshDownlinkFactory {
    override suspend fun openMux(connection: DshConnection): DshDownlink =
        KtorDshDownlink.open(
            httpClient,
            dshHttpToWebSocketUrl(connection.baseUrl, DshRpc.REMOTE_MUX_PATH),
            connection.basicAuthorization,
            connection.cookie,
        )
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

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close() {
        runCatching { session.close() }
    }

    companion object {
        suspend fun open(
            httpClient: HttpClient,
            url: String,
            authorization: String? = null,
            cookie: String? = null,
        ): KtorDshDownlink {
            val session = httpClient.webSocketSession {
                method = HttpMethod.Get
                url(url)
                authorization?.let { header(HttpHeaders.Authorization, it) }
                cookie?.let { header(HttpHeaders.Cookie, it) }
                timeout {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                }
            }
            return KtorDshDownlink(session)
        }
    }
}
