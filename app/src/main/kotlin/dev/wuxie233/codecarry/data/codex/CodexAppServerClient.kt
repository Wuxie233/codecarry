package dev.wuxie233.codecarry.data.codex

import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

class CodexRpcException(
    val code: Long? = null,
    override val message: String,
    val data: JsonElement? = null,
) : RuntimeException(message)

class CodexDisconnectedException(
    message: String = "Codex app-server connection closed",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

sealed interface CodexClientConnectionState {
    data object Disconnected : CodexClientConnectionState
    data object Connecting : CodexClientConnectionState
    data class Connected(val initialized: CodexInitializeResult) : CodexClientConnectionState
    data class Failed(val error: Throwable) : CodexClientConnectionState
}

internal sealed interface CodexInboundEvent {
    val connectionGeneration: Long

    data class Notification(
        val value: CodexNotification,
        override val connectionGeneration: Long,
    ) : CodexInboundEvent

    data class ServerRequest(
        val value: CodexServerRequest,
        override val connectionGeneration: Long,
    ) : CodexInboundEvent
}

internal interface CodexRpcTransport : Closeable {
    suspend fun connect()
    suspend fun send(text: String)
    suspend fun receive(): String?
}

internal class KtorCodexRpcTransport(
    private val httpClient: HttpClient,
    private val endpointUrl: String,
    private val token: String?,
) : CodexRpcTransport {
    private val mutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null

    override suspend fun connect() {
        normalizeCodexWebSocketUrl(endpointUrl)
        mutex.withLock {
            if (session?.isActive == true) return
            session = httpClient.webSocketSession {
                url(endpointUrl)
                token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
            }
        }
    }

    override suspend fun send(text: String) {
        val current = mutex.withLock { session }
            ?: throw CodexDisconnectedException("Codex app-server is not connected")
        current.send(Frame.Text(text))
    }

    override suspend fun receive(): String? {
        val current = mutex.withLock { session }
            ?: throw CodexDisconnectedException("Codex app-server is not connected")
        while (true) {
            when (val frame = current.incoming.receiveCatching().getOrNull() ?: return null) {
                is Frame.Text -> return frame.readText()
                is Frame.Close -> return null
                else -> Unit
            }
        }
    }

    override fun close() {
        val current = runBlocking {
            mutex.withLock {
                session.also { session = null }
            }
        }
        if (current != null) {
            current.cancel()
        }
    }
}

open class CodexAppServerClient internal constructor(
    private val transport: CodexRpcTransport,
    private val json: Json,
    private val clientName: String = "codecarry",
    private val clientVersion: String = BuildConfig.VERSION_NAME,
    private val requestTimeoutMillis: Long = 120_000,
    private val currentTimeSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    scope: CoroutineScope? = null,
) : Closeable {
    private val ownsScope = scope == null
    private val clientScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(0)
    private val readerGenerations = AtomicLong(0)
    private val freshThreads = ConcurrentHashMap<String, CodexThreadSession>()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val sendMutex = Mutex()
    private val connectMutex = Mutex()
    private val _notifications = MutableSharedFlow<CodexNotification>(extraBufferCapacity = 64)
    private val _serverRequests = MutableSharedFlow<CodexServerRequest>(extraBufferCapacity = 32)
    private val _inboundEvents = MutableSharedFlow<CodexInboundEvent>(extraBufferCapacity = 96)
    private val _connectionState = MutableStateFlow<CodexClientConnectionState>(
        CodexClientConnectionState.Disconnected,
    )
    private var readerJob: Job? = null
    @Volatile private var initialized: CodexInitializeResult? = null
    @Volatile private var closed = false

    val notifications: SharedFlow<CodexNotification> = _notifications.asSharedFlow()
    val serverRequests: SharedFlow<CodexServerRequest> = _serverRequests.asSharedFlow()
    internal val inboundEvents: SharedFlow<CodexInboundEvent> = _inboundEvents.asSharedFlow()
    val connectionState: StateFlow<CodexClientConnectionState> = _connectionState.asStateFlow()

    internal fun currentConnectionGeneration(): Long = readerGenerations.get()

    suspend fun connect(): CodexInitializeResult = connectMutex.withLock {
        check(!closed) { "Codex app-server client is closed" }
        initialized?.let { return@withLock it }
        _connectionState.value = CodexClientConnectionState.Connecting
        try {
            transport.connect()
            val readerGeneration = readerGenerations.incrementAndGet()
            readerJob = clientScope.launch { receiveLoop(readerGeneration) }
            val result = request(
                method = "initialize",
                params = buildJsonObject {
                    put("clientInfo", buildJsonObject {
                        put("name", clientName)
                        put("title", "CodeCarry")
                        put("version", clientVersion)
                    })
                    put("capabilities", buildJsonObject {
                        put("experimentalApi", true)
                        put("mcpServerOpenaiFormElicitation", false)
                    })
                },
            ).let(CodexInitializeResult::fromJson)
            notify("initialized")
            initialized = result
            _connectionState.value = CodexClientConnectionState.Connected(result)
            result
        } catch (error: Throwable) {
            _connectionState.value = CodexClientConnectionState.Failed(error)
            failConnection(error)
            throw error
        }
    }

    suspend fun request(
        method: String,
        params: JsonElement? = null,
    ): JsonElement {
        check(!closed) { "Codex app-server client is closed" }
        val id = requestIds.incrementAndGet().toString()
        val requestKey = JsonPrimitive(id).requestKey()
        val response = CompletableDeferred<JsonElement>()
        pendingRequests[requestKey] = response
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        try {
            sendJson(message)
        } catch (error: Throwable) {
            pendingRequests.remove(requestKey)
            response.cancel()
            throw error
        }
        return try {
            withTimeout(requestTimeoutMillis) { response.await() }
        } finally {
            pendingRequests.remove(requestKey, response)
        }
    }

    suspend fun notify(method: String, params: JsonElement? = null) {
        val message = buildJsonObject {
            put("method", method)
            params?.let { put("params", it) }
        }
        sendJson(message)
    }

    suspend fun reply(request: CodexServerRequest, result: JsonElement = JsonObject(emptyMap())) {
        requireCurrentGeneration(request.connectionGeneration)
        reply(request.id, result)
    }

    suspend fun reply(requestId: JsonPrimitive, result: JsonElement = JsonObject(emptyMap())) {
        sendJson(buildJsonObject {
            put("id", requestId)
            put("result", result)
        })
    }

    suspend fun replyError(
        request: CodexServerRequest,
        code: Long,
        message: String,
        data: JsonElement? = null,
    ) {
        requireCurrentGeneration(request.connectionGeneration)
        sendJson(buildJsonObject {
            put("id", request.id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            })
        })
    }

    suspend fun replyApproval(request: CodexApprovalRequest, decision: JsonElement) {
        requireCurrentGeneration(request.connectionGeneration)
        require(request.kind != CodexApprovalKind.PERMISSIONS) {
            "Permission approvals require a granted permission profile"
        }
        (decision as? JsonPrimitive)?.contentOrNull?.let { wireDecision ->
            require(request.allowsDecision(wireDecision)) {
                "Approval decision is not offered by the Codex app-server: $wireDecision"
            }
        }
        val result = buildJsonObject { put("decision", decision) }
        reply(request.requestId, result)
    }

    suspend fun replyApproval(request: CodexApprovalRequest, decision: String) {
        require(request.kind != CodexApprovalKind.PERMISSIONS) {
            "Permission approvals require a granted permission profile"
        }
        replyApproval(request, JsonPrimitive(decision))
    }

    suspend fun replyPermissionApproval(
        request: CodexApprovalRequest,
        decision: String,
        grant: CodexPermissionGrant,
    ) {
        requireCurrentGeneration(request.connectionGeneration)
        require(request.kind == CodexApprovalKind.PERMISSIONS) {
            "The request is not a permission approval"
        }
        require(request.allowsDecision(decision)) {
            "Approval decision is not offered by the Codex app-server: $decision"
        }
        require(
            (decision == "acceptForSession") == (grant.scope == CodexPermissionGrantScope.SESSION),
        ) {
            "Permission approval scope does not match decision: $decision"
        }
        reply(request.requestId, grant.toJson())
    }

    suspend fun replyPermissionApproval(
        request: CodexApprovalRequest,
        decision: String,
        permissions: JsonObject,
        scope: CodexPermissionGrantScope = CodexPermissionGrantScope.TURN,
        strictAutoReview: Boolean? = null,
    ) {
        replyPermissionApproval(
            request,
            decision,
            CodexPermissionGrant(
                permissions = permissions,
                scope = scope,
                strictAutoReview = strictAutoReview,
            ),
        )
    }

    suspend fun replyPermissionApproval(
        request: CodexApprovalRequest,
        decision: String,
        permissions: JsonObject,
        scope: String,
        strictAutoReview: Boolean? = null,
    ) {
        val typedScope = CodexPermissionGrantScope.entries.firstOrNull { it.wireValue == scope }
            ?: throw IllegalArgumentException("Unknown permission grant scope: $scope")
        replyPermissionApproval(request, decision, permissions, typedScope, strictAutoReview)
    }

    suspend fun replyUserInput(
        request: CodexToolUserInputRequest,
        answers: Map<String, List<String>>,
    ) {
        requireCurrentGeneration(request.connectionGeneration)
        reply(
            request.requestId,
            buildJsonObject {
                put("answers", buildJsonObject {
                    answers.forEach { (questionId, values) ->
                        put(questionId, buildJsonObject {
                            put("answers", stringArray(values))
                        })
                    }
                })
            },
        )
    }

    suspend fun listThreads(
        cursor: String? = null,
        limit: Int? = null,
        archived: Boolean? = null,
        cwd: String? = null,
        cwdFilters: List<String>? = null,
        modelProviders: List<String>? = null,
        sourceKinds: List<String>? = null,
        searchTerm: String? = null,
        sortKey: String? = null,
        sortDirection: String? = null,
        parentThreadId: String? = null,
        ancestorThreadId: String? = null,
        useStateDbOnly: Boolean = false,
    ): CodexThreadListPage = request(
        "thread/list",
        paramsOf(
            "cursor" to cursor,
            "limit" to limit,
            "archived" to archived,
            "cwd" to (cwdFilters?.let(::stringArray) ?: cwd),
            "modelProviders" to modelProviders?.let(::stringArray),
            "sourceKinds" to sourceKinds?.let(::stringArray),
            "searchTerm" to searchTerm,
            "sortKey" to sortKey,
            "sortDirection" to sortDirection,
            "parentThreadId" to parentThreadId,
            "ancestorThreadId" to ancestorThreadId,
            "useStateDbOnly" to useStateDbOnly.takeIf { it },
        ),
    ).let(CodexThreadListPage::fromJson)

    suspend fun readThread(threadId: String, includeTurns: Boolean = true): CodexThread {
        freshThreads[threadId]?.let { return it.thread }
        val result = request(
            "thread/read",
            paramsOf("threadId" to threadId, "includeTurns" to includeTurns),
        ).objectOrEmpty()
        return CodexThread.fromJson(result["thread"].objectOrEmpty())
    }

    suspend fun startThread(
        cwd: String? = null,
        model: String? = null,
        modelProvider: String? = null,
        permissions: String? = null,
        ephemeral: Boolean? = null,
        developerInstructions: String? = null,
        serviceTier: String? = null,
        extraParams: JsonObject = JsonObject(emptyMap()),
    ): CodexThreadSession = request(
        "thread/start",
        paramsOf(
            "cwd" to cwd,
            "model" to model,
            "modelProvider" to modelProvider,
            "permissions" to permissions,
            "ephemeral" to ephemeral,
            "developerInstructions" to developerInstructions,
            "serviceTier" to serviceTier,
            extras = extraParams,
        ),
    ).let(CodexThreadSession::fromJson).also { freshThreads[it.thread.id] = it }

    suspend fun resumeThread(
        threadId: String,
        cwd: String? = null,
        model: String? = null,
        permissions: String? = null,
        excludeTurns: Boolean = false,
        extraParams: JsonObject = JsonObject(emptyMap()),
    ): CodexThreadSession = freshThreads[threadId] ?: request(
        "thread/resume",
        paramsOf(
            "threadId" to threadId,
            "cwd" to cwd,
            "model" to model,
            "permissions" to permissions,
            "excludeTurns" to excludeTurns.takeIf { it },
            extras = extraParams,
        ),
    ).let(CodexThreadSession::fromJson)

    suspend fun forkThread(
        threadId: String,
        lastTurnId: String? = null,
        cwd: String? = null,
        model: String? = null,
        modelProvider: String? = null,
        permissions: String? = null,
        ephemeral: Boolean? = null,
        excludeTurns: Boolean = false,
        extraParams: JsonObject = JsonObject(emptyMap()),
    ): CodexThreadSession = request(
        "thread/fork",
        paramsOf(
            "threadId" to threadId,
            "lastTurnId" to lastTurnId,
            "cwd" to cwd,
            "model" to model,
            "modelProvider" to modelProvider,
            "permissions" to permissions,
            "ephemeral" to ephemeral,
            "excludeTurns" to excludeTurns.takeIf { it },
            extras = extraParams,
        ),
    ).let(CodexThreadSession::fromJson)

    suspend fun archiveThread(threadId: String) {
        request("thread/archive", paramsOf("threadId" to threadId))
    }

    suspend fun unarchiveThread(threadId: String): CodexThread {
        val result = request("thread/unarchive", paramsOf("threadId" to threadId)).objectOrEmpty()
        return CodexThread.fromJson(result["thread"].objectOrEmpty())
    }

    suspend fun deleteThread(threadId: String) {
        request("thread/delete", paramsOf("threadId" to threadId))
    }

    suspend fun unsubscribeThread(threadId: String): String {
        val result = request("thread/unsubscribe", paramsOf("threadId" to threadId)).objectOrEmpty()
        return (result["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    suspend fun setThreadName(threadId: String, name: String) {
        request("thread/name/set", paramsOf("threadId" to threadId, "name" to name))
    }

    suspend fun compactThread(threadId: String) {
        request("thread/compact/start", paramsOf("threadId" to threadId))
    }

    suspend fun startTurn(
        threadId: String,
        input: List<CodexUserInput>,
        cwd: String? = null,
        model: String? = null,
        permissions: String? = null,
        effort: String? = null,
        summary: String? = null,
        clientUserMessageId: String? = null,
        serviceTier: String? = null,
        extraParams: JsonObject = JsonObject(emptyMap()),
    ): CodexTurn? {
        freshThreads.remove(threadId)
        val result = request(
            "turn/start",
            paramsOf(
                "threadId" to threadId,
                "input" to input.toJsonArray(),
                "cwd" to cwd,
                "model" to model,
                "permissions" to permissions,
                "effort" to effort,
                "summary" to summary,
                "clientUserMessageId" to clientUserMessageId,
                "serviceTier" to serviceTier,
                extras = extraParams,
            ),
        ).objectOrEmpty()
        return (result["turn"] as? JsonObject)?.let(CodexTurn::fromJson)?.takeIf { it.id.isNotBlank() }
    }

    suspend fun startTurn(
        threadId: String,
        text: String,
        cwd: String? = null,
        model: String? = null,
        permissions: String? = null,
        effort: String? = null,
        clientUserMessageId: String? = null,
    ) = startTurn(
        threadId = threadId,
        input = listOf(CodexUserInput.Text(text)),
        cwd = cwd,
        model = model,
        permissions = permissions,
        effort = effort,
        clientUserMessageId = clientUserMessageId,
    )

    suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        input: List<CodexUserInput>,
        clientUserMessageId: String? = null,
    ): String {
        val result = request(
            "turn/steer",
            paramsOf(
                "threadId" to threadId,
                "expectedTurnId" to expectedTurnId,
                "input" to input.toJsonArray(),
                "clientUserMessageId" to clientUserMessageId,
            ),
        ).objectOrEmpty()
        return (result["turnId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        text: String,
        clientUserMessageId: String? = null,
    ): String =
        steerTurn(
            threadId = threadId,
            expectedTurnId = expectedTurnId,
            input = listOf(CodexUserInput.Text(text)),
            clientUserMessageId = clientUserMessageId,
        )

    suspend fun interruptTurn(threadId: String, turnId: String) {
        request("turn/interrupt", paramsOf("threadId" to threadId, "turnId" to turnId))
    }

    suspend fun getGoal(threadId: String): CodexGoal? {
        val result = request("thread/goal/get", paramsOf("threadId" to threadId)).objectOrEmpty()
        return result["goal"].objectOrNull()?.let(CodexGoal::fromJson)
    }

    suspend fun setGoal(
        threadId: String,
        objective: String? = null,
        status: String? = null,
        tokenBudget: Long? = null,
    ): CodexGoal {
        val result = request(
            "thread/goal/set",
            paramsOf(
                "threadId" to threadId,
                "objective" to objective,
                "status" to status,
                "tokenBudget" to tokenBudget,
            ),
        ).objectOrEmpty()
        return CodexGoal.fromJson(result["goal"].objectOrEmpty())
    }

    suspend fun clearGoal(threadId: String): Boolean {
        val result = request("thread/goal/clear", paramsOf("threadId" to threadId)).objectOrEmpty()
        return (result["cleared"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
    }

    suspend fun setMemoryMode(threadId: String, mode: CodexMemoryMode) {
        request(
            "thread/memoryMode/set",
            paramsOf("threadId" to threadId, "mode" to mode.wireValue),
        )
    }

    suspend fun listModels(
        cursor: String? = null,
        limit: Int? = null,
        includeHidden: Boolean? = null,
    ): CodexModelListPage = request(
        "model/list",
        paramsOf(
            "cursor" to cursor,
            "limit" to limit,
            "includeHidden" to includeHidden,
        ),
    ).let(CodexModelListPage::fromJson)

    suspend fun listSkills(cwd: String, forceReload: Boolean = false): List<CodexSkill> =
        listSkillsResult(cwd, forceReload).skills

    suspend fun listSkillsResult(cwd: String, forceReload: Boolean = false): CodexSkillsResult {
        val result = capabilityRequest("skills/list", buildJsonObject {
            put("cwds", JsonArray(listOf(JsonPrimitive(cwd))))
            put("forceReload", forceReload)
        }).objectOrEmpty()
        check(result["data"] is JsonArray) { "Invalid Codex skills/list response" }
        val entries = result.controlObjects("data")
        val errors = entries.flatMap { it.controlObjects("errors") }
        val warnings = errors.map { (it["message"] as? JsonPrimitive)?.contentOrNull ?: "Skill loading failed" }
        val skills = entries.flatMap { it.controlObjects("skills") }.map(CodexSkill::fromJson).distinctBy { it.path }
        if (skills.isEmpty() && warnings.isNotEmpty()) throw IllegalStateException(warnings.joinToString("\n"))
        return CodexSkillsResult(skills, warnings)
    }

    suspend fun searchFiles(query: String, roots: List<String>): List<CodexFileMatch> {
        require(roots.isNotEmpty()) { "Remote file search requires a workspace root" }
        val result = capabilityRequest("fuzzyFileSearch", buildJsonObject {
            put("query", query)
            put("roots", JsonArray(roots.map(::JsonPrimitive)))
            put("cancellationToken", JsonNull)
        }).objectOrEmpty()
        check(result["files"] is JsonArray) { "Invalid Codex fuzzyFileSearch response" }
        return result.controlObjects("files").map(CodexFileMatch::fromJson)
    }

    fun defaultDirectory(preferredPath: String? = null): String? =
        preferredPath?.let(::normalizeCodexDirectoryPath)
            ?: initialized?.codexHome?.let(::normalizeCodexDirectoryPath)
            ?: "/".takeIf { initialized?.platformFamily == "unix" }

    suspend fun readDirectory(path: String): CodexDirectoryListing {
        val absolutePath = requireNotNull(normalizeCodexDirectoryPath(path)) {
            "Remote directory must be an absolute path"
        }
        val result = withTimeout(15_000) {
            capabilityRequest("fs/readDirectory", buildJsonObject { put("path", absolutePath) })
        }
        return parseCodexDirectoryListing(absolutePath, result.objectOrEmpty())
    }

    /** Read bytes from the daemon filesystem, never from Android-local paths. */
    suspend fun readImageFile(path: String): ByteArray {
        require(normalizeCodexDirectoryPath(path) != null) {
            "Remote image must be an absolute path"
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(15_000) {
            capabilityRequest("fs/readFile", buildJsonObject { put("path", path) })
        }?.objectOrEmpty() ?: throw java.io.IOException("Timed out reading remote image")
        return withContext(Dispatchers.Default) {
            val encoded = result["dataBase64"] as? JsonPrimitive
            check(encoded?.isString == true) { "Invalid Codex fs/readFile response" }
            val data = encoded.content
            val maxBytes = 16 * 1024 * 1024
            require(data.length <= ((maxBytes + 2) / 3) * 4) { "Remote image exceeds 16 MiB" }
            Base64.getDecoder().decode(data).also {
                require(it.size <= maxBytes) { "Remote image exceeds 16 MiB" }
            }
        }
    }

    private suspend fun capabilityRequest(method: String, params: JsonObject): JsonElement = try {
        request(method, params)
    } catch (error: CodexRpcException) {
        if (error.code == -32601L) throw CodexCapabilityUnavailableException(method, error)
        throw error
    }

    private suspend fun sendJson(message: JsonObject) {
        sendMutex.withLock {
            transport.send(json.encodeToString(JsonObject.serializer(), message))
        }
    }

    private suspend fun receiveLoop(readerGeneration: Long) {
        var failure: Throwable? = null
        try {
            while (!closed) {
                val text = transport.receive() ?: break
                handleIncoming(text, readerGeneration)
            }
        } catch (error: CancellationException) {
            if (!closed) failure = error
        } catch (_: ClosedReceiveChannelException) {
            // A normal WebSocket close is handled below as a disconnect.
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (!closed && readerGenerations.get() == readerGeneration) {
                initialized = null
                transport.close()
                _connectionState.value = failure
                    ?.let(CodexClientConnectionState::Failed)
                    ?: CodexClientConnectionState.Disconnected
                failPending(CodexDisconnectedException(cause = failure))
            }
        }
    }

    internal suspend fun handleIncoming(
        text: String,
        connectionGeneration: Long = readerGenerations.get(),
    ) {
        if (connectionGeneration != readerGenerations.get()) return
        val message = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val id = message["id"] as? JsonPrimitive
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        when {
            id != null && method == "currentTime/read" -> {
                reply(
                    id,
                    buildJsonObject { put("currentTimeAt", currentTimeSeconds()) },
                )
            }

            id != null && method != null -> {
                CodexServerRequest.fromJson(message, connectionGeneration)?.let { request ->
                    _inboundEvents.emit(CodexInboundEvent.ServerRequest(request, connectionGeneration))
                    _serverRequests.emit(request)
                }
            }

            id != null && (message.containsKey("result") || message.containsKey("error")) -> {
                val pending = pendingRequests.remove(id.requestKey()) ?: return
                val error = message["error"] as? JsonObject
                if (error != null) {
                    pending.completeExceptionally(
                        CodexRpcException(
                            code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                            message = (error["message"] as? JsonPrimitive)?.contentOrNull
                                ?: "Codex app-server request failed",
                            data = error["data"],
                        ),
                    )
                } else {
                    pending.complete(message["result"] ?: JsonNull)
                }
            }

            method != null -> {
                val notification = CodexNotification.fromJson(message)
                _inboundEvents.emit(CodexInboundEvent.Notification(notification, connectionGeneration))
                _notifications.emit(notification)
            }
        }
    }

    private fun failConnection(error: Throwable) {
        freshThreads.clear()
        failPending(error)
        readerGenerations.incrementAndGet()
        readerJob?.cancel()
        readerJob = null
        initialized = null
        transport.close()
    }

    private fun requireCurrentGeneration(connectionGeneration: Long) {
        check(connectionGeneration == readerGenerations.get()) {
            "Codex server request belongs to an earlier connection"
        }
    }

    private fun failPending(error: Throwable) {
        pendingRequests.values.forEach { deferred -> deferred.completeExceptionally(error) }
        pendingRequests.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        readerGenerations.incrementAndGet()
        initialized = null
        _connectionState.value = CodexClientConnectionState.Disconnected
        failPending(CodexDisconnectedException("Codex app-server client closed"))
        readerJob?.cancel()
        readerJob = null
        transport.close()
        if (ownsScope) clientScope.cancel()
    }
}

class CodexAppServerClientFactory @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    fun create(server: ServerConfig): CodexAppServerClient {
        val websocketUrl = normalizeCodexWebSocketUrl(server.url)
        return CodexAppServerClient(
            transport = KtorCodexRpcTransport(
                httpClient = httpClient,
                endpointUrl = websocketUrl,
                token = server.token,
            ),
            json = json,
        )
    }
}

internal fun normalizeCodexWebSocketUrl(url: String): String {
    val trimmed = url.trim()
    require(trimmed.isNotEmpty()) { "Codex app-server URL is empty" }
    val uri = runCatching { URI(trimmed) }
        .getOrElse { throw IllegalArgumentException("Invalid Codex app-server URL", it) }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "ws" || scheme == "wss") {
        "Codex app-server URL must use ws:// or wss://"
    }
    require(uri.userInfo == null && uri.host != null && uri.fragment == null) {
        "Invalid Codex app-server URL"
    }
    require(uri.port in -1..65535) { "Invalid Codex app-server port" }
    if (scheme == "ws") {
        val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
        require(host == "localhost" || host == "127.0.0.1" || host == "::1") {
            "Remote Codex app-server URLs must use wss://"
        }
    }
    return trimmed
}


private fun paramsOf(
    vararg values: Pair<String, Any?>,
    extras: JsonObject = JsonObject(emptyMap()),
): JsonObject = buildJsonObject {
    extras.forEach { (key, value) -> put(key, value) }
    values.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is JsonElement -> put(key, value)
            is String -> put(key, value)
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            else -> error("Unsupported JSON-RPC parameter type: ${value::class.java.name}")
        }
    }
}

private fun stringArray(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
