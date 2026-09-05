package dev.wuxie233.codecarry.data.codex

import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.domain.model.ServerType
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexConnectionManagerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = ServerConfig(
        id = "codex-server",
        type = ServerType.CODEX,
        url = "ws://codex.example.test",
        token = "secret",
    )

    @Test
    fun `leases reuse one connection and idle release closes it`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also(transports::add).newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1_000,
        )

        val firstDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transports.single())
        val first = firstDeferred.await()
        val second = manager.acquire(server)

        assertEquals(1, transports.size)
        assertSame(first.connection.client, second.connection.client)
        first.close()
        second.close()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(manager.get(server.id) != null)
        advanceTimeBy(1)
        runCurrent()
        assertNull(manager.get(server.id))
    }

    @Test
    fun `pending server request is retained until explicitly resolved`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 10_000,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()

        transport.incoming.send(
            """
            {"id":"approval-1","method":"item/fileChange/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","startedAtMs":10}}
            """.trimIndent(),
        )
        runCurrent()

        assertEquals("approval-1", lease.connection.pendingRequests.value.single().id.content)
        assertEquals(
            "approval-1",
            manager.connections.value.getValue(server.id).pendingRequests.single().id.content,
        )

        manager.resolveRequest(server.id, JsonPrimitive("approval-1"))
        assertTrue(lease.connection.pendingRequests.value.isEmpty())
        assertTrue(manager.connections.value.getValue(server.id).pendingRequests.isEmpty())
        lease.close()
    }

    @Test
    fun `numeric and string request ids remain distinct`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()

        transport.incoming.send(
            """{"id":1,"method":"future/request","params":{"threadId":"thread-1"}}""",
        )
        transport.incoming.send(
            """{"id":"1","method":"future/request","params":{"threadId":"thread-1"}}""",
        )
        runCurrent()

        assertEquals(2, lease.connection.pendingRequests.value.size)
        manager.resolveRequest(server.id, JsonPrimitive(1))
        val remaining = lease.connection.pendingRequests.value.single().id
        assertTrue(remaining.isString)
        assertEquals("1", remaining.content)
        lease.close()
    }

    @Test
    fun `server resolved notification removes pending request without a collector`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.incoming.send(
            """
            {"id":17,"method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","questions":[]}}
            """.trimIndent(),
        )
        runCurrent()
        assertEquals(1, lease.connection.pendingRequests.value.size)

        transport.incoming.send(
            """
            {"method":"serverRequest/resolved","params":{"threadId":"thread-1","requestId":17}}
            """.trimIndent(),
        )
        runCurrent()
        assertTrue(lease.connection.pendingRequests.value.isEmpty())
        lease.close()
    }

    @Test
    fun `leased chat reconnects and resumes its open thread`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 10_000,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        assertEquals("initialized", transport.takeSentObject()["method"]?.jsonPrimitive?.content)

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        val initialize = transport.takeSentObject()
        assertEquals("initialize", initialize["method"]?.jsonPrimitive?.content)
        transport.respond(
            initialize.getValue("id").jsonPrimitive,
            buildJsonObject {
                put("userAgent", "codex-test")
                put("codexHome", "/tmp/codex")
                put("platformFamily", "unix")
                put("platformOs", "linux")
            },
        )
        runCurrent()
        assertEquals("initialized", transport.takeSentObject()["method"]?.jsonPrimitive?.content)
        val resume = transport.takeSentObject()
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
        assertEquals(
            "thread-1",
            resume["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content,
        )
        assertFalse(resume["params"]?.jsonObject?.containsKey("excludeTurns") == true)
        transport.respond(
            resume.getValue("id").jsonPrimitive,
            buildJsonObject {
                put("thread", buildJsonObject { put("id", "thread-1") })
                put("model", "gpt-5")
                put("modelProvider", "openai")
                put("cwd", "/workspace")
                put("approvalPolicy", "on-request")
                put("approvalsReviewer", "user")
                put("sandbox", "workspace-write")
            },
        )
        runCurrent()

        assertTrue(lease.connection.state.value is CodexClientConnectionState.Connected)
        lease.close()
    }

    @Test
    fun `failed thread resume retries on the current connection until it succeeds`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 10_000,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        initialize(transport)
        assertEquals("initialized", transport.takeSentObject()["method"]?.jsonPrimitive?.content)

        val firstResume = transport.takeSentObject()
        assertEquals("thread/resume", firstResume["method"]?.jsonPrimitive?.content)
        transport.respondError(
            firstResume.getValue("id").jsonPrimitive,
            code = -32_000,
            message = "thread temporarily unavailable",
        )
        runCurrent()
        assertTrue(lease.connection.state.value is CodexClientConnectionState.Connecting)
        assertTrue(
            manager.connections.value.getValue(server.id).state is
                CodexClientConnectionState.Connecting,
        )

        advanceTimeBy(2)
        runCurrent()
        val secondResume = transport.takeSentObject()
        assertEquals("thread/resume", secondResume["method"]?.jsonPrimitive?.content)
        assertEquals(2, transport.connectAttempts)
        transport.respond(
            secondResume.getValue("id").jsonPrimitive,
            threadSession("thread-1"),
        )
        runCurrent()

        assertTrue(lease.connection.state.value is CodexClientConnectionState.Connected)
        lease.close()
    }

    @Test
    fun `current time request is answered internally and never becomes pending`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(
                    scope = backgroundScope,
                    currentTimeSeconds = { 1_752_345_678L },
                )
            },
            scope = backgroundScope,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()

        transport.incoming.send(
            """{"id":21,"method":"currentTime/read","params":{}}""",
        )
        runCurrent()

        val reply = transport.takeSentObject()
        assertEquals("21", reply["id"]?.jsonPrimitive?.content)
        assertEquals(
            1_752_345_678L,
            reply["result"]?.jsonObject?.get("currentTimeAt")?.jsonPrimitive?.content?.toLong(),
        )
        assertTrue(lease.connection.pendingRequests.value.isEmpty())
        assertTrue(manager.connections.value.getValue(server.id).pendingRequests.isEmpty())
        lease.close()
    }

    @Test
    fun `unknown server request remains pending for explicit handling`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject() // initialized

        transport.incoming.send(
            """{"id":"future-1","method":"future/read","params":{"value":7}}""",
        )
        runCurrent()

        assertEquals("future/read", lease.connection.pendingRequests.value.single().method)
        assertEquals(
            "future/read",
            manager.connections.value.getValue(server.id).pendingRequests.single().method,
        )
        lease.close()
    }

    @Test
    fun `disconnect cancels thread resume retries`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        leaseDeferred.await()
        transport.takeSentObject() // initialized

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        initialize(transport)
        transport.takeSentObject() // initialized
        val firstResume = transport.takeSentObject()
        transport.respondError(
            firstResume.getValue("id").jsonPrimitive,
            code = -32_000,
            message = "thread temporarily unavailable",
        )
        runCurrent()
        assertEquals(1, transport.resumeAttempts)

        manager.disconnect(server.id)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, transport.resumeAttempts)
        assertNull(manager.get(server.id))
    }

    @Test
    fun `disconnect cancels retry after the first persistent connect failure`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport(connectFailuresRemaining = 1)
                    .also { transport = it }
                    .newClient(backgroundScope)
            },
            scope = backgroundScope,
            reconnectInitialMillis = 10,
            reconnectMaxMillis = 20,
        )

        val failure = runCatching { manager.connect(server) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, transport.connectAttempts)
        advanceTimeBy(5)
        manager.disconnect(server.id)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, transport.connectAttempts)
        assertFalse(transport.connected)
        assertNull(manager.get(server.id))
    }

    @Test
    fun `releasing persistent ownership keeps an active screen lease alive`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
        )
        val persistentDeferred = async { manager.connect(server) }
        runCurrent()
        initialize(transport)
        persistentDeferred.await()
        val lease = manager.acquire(server, threadId = "thread-1")

        manager.releasePersistent(server.id)

        assertSame(lease.connection, manager.get(server.id))
        assertTrue(transport.connected)
        lease.close()
        advanceTimeBy(1)
        runCurrent()
        assertNull(manager.get(server.id))
    }

    @Test
    fun `releasing persistent ownership unsubscribes background only threads`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 10_000,
        )
        val persistentDeferred = async { manager.connect(server) }
        runCurrent()
        initialize(transport)
        persistentDeferred.await()
        transport.takeSentObject()
        val lease = manager.acquire(server, threadId = "foreground-thread")
        transport.incoming.send(
            """{"method":"turn/started","params":{"threadId":"background-thread","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
        )
        runCurrent()

        manager.releasePersistent(server.id)
        runCurrent()

        val unsubscribe = transport.tryTakeSentObject()
        assertNotNull(unsubscribe)
        assertEquals("thread/unsubscribe", unsubscribe?.get("method")?.jsonPrimitive?.content)
        assertEquals(
            "background-thread",
            unsubscribe?.get("params")?.jsonObject?.get("threadId")?.jsonPrimitive?.content,
        )
        assertSame(lease.connection, manager.get(server.id))
        lease.close()
    }

    @Test
    fun `releasing persistent ownership stops a background-only retained thread`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
        )
        val persistentDeferred = async { manager.connect(server) }
        runCurrent()
        initialize(transport)
        persistentDeferred.await()
        transport.takeSentObject()
        transport.incoming.send(
            """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
        )
        runCurrent()

        manager.releasePersistent(server.id)

        assertNull(manager.get(server.id))
        assertFalse(transport.connected)
    }

    @Test
    fun `closing a stale lease does not release a replacement connection`() = runTest {
        val transports = mutableListOf<FakeTransport>()
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also(transports::add).newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 10,
        )
        val oldDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transports.single())
        val oldLease = oldDeferred.await()

        manager.disconnect(server.id)
        val replacementDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transports.last())
        val replacementLease = replacementDeferred.await()

        oldLease.close()
        advanceTimeBy(10)
        runCurrent()

        assertSame(replacementLease.connection, manager.get(server.id))
        replacementLease.close()
    }

    @Test
    fun `scoped notifications are emitted after reducer updates`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        val eventDeferred = async { manager.notificationEvents.first() }
        runCurrent()

        transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}}""",
        )
        val event = eventDeferred.await()

        assertEquals(server.id, event.serverId)
        assertEquals("turn/completed", event.notification.method)
        assertEquals(
            "completed",
            lease.connection.events.value.threads.getValue("thread-1").turns.single().status,
        )
        lease.close()
    }

    @Test
    fun `active thread tokens are reference counted`() = runTest {
        val manager = CodexConnectionManager(
            createClient = { FakeTransport().newClient(backgroundScope) },
            scope = backgroundScope,
        )
        val key = CodexThreadKey(server.id, "thread-1")

        val first = manager.activateThread(key)
        val second = manager.activateThread(key)
        assertEquals(setOf(key), manager.activeThreads.value)

        first.close()
        assertEquals(setOf(key), manager.activeThreads.value)
        second.close()
        assertTrue(manager.activeThreads.value.isEmpty())
    }

    @Test
    fun `background running thread resumes after its screen lease closes`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject() // initialized
        transport.incoming.send(
            """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
        )
        runCurrent()
        lease.close()

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        initialize(transport)
        transport.takeSentObject() // initialized

        val resume = transport.takeSentObject()
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
        assertEquals("thread-1", resume["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content)
        transport.respond(resume.getValue("id").jsonPrimitive, threadSession("thread-1"))
        runCurrent()
        assertTrue(manager.get(server.id)?.state?.value is CodexClientConnectionState.Connected)
    }

    @Test
    fun `item delta retains background thread when turn started notification is absent`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        transport.incoming.send(
            """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"actual-turn","itemId":"item-1","delta":"Working"}}""",
        )
        runCurrent()
        lease.close()

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        initialize(transport)
        transport.takeSentObject()

        val resume = transport.takeSentObject()
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
        assertEquals("thread-1", resume["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content)
    }

    @Test
    fun `terminal turn releases background retention after screen closes`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject() // initialized
        transport.incoming.send(
            """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
        )
        runCurrent()
        lease.close()
        transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}}""",
        )
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertNull(manager.get(server.id))
    }

    @Test
    fun `late item completion does not restore retention after terminal turn`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        transport.incoming.send(
            """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"actual-turn","itemId":"item-1","delta":"Done"}}""",
        )
        runCurrent()
        lease.close()
        transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"actual-turn","status":"completed","items":[],"itemsView":"notLoaded"}}}""",
        )
        transport.incoming.send(
            """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"actual-turn","item":{"id":"item-1","type":"agentMessage","text":"Done"}}}""",
        )
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertNull(manager.get(server.id))
    }

    @Test
    fun `reconnect snapshot releases a turn completed while disconnected`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        transport.incoming.send(
            """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
        )
        runCurrent()
        lease.close()

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        initialize(transport)
        transport.takeSentObject()
        val resume = transport.takeSentObject()
        transport.respond(
            resume.getValue("id").jsonPrimitive,
            buildJsonObject {
                put("thread", buildJsonObject {
                    put("id", "thread-1")
                    put("turns", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("id", "turn-1")
                            put("status", "completed")
                            put("items", kotlinx.serialization.json.JsonArray(emptyList()))
                        })
                    })
                })
                put("model", "gpt-5")
                put("modelProvider", "openai")
                put("cwd", "/workspace")
                put("approvalPolicy", "on-request")
                put("sandbox", "workspace-write")
            },
        )
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertNull(manager.get(server.id))
    }

    @Test
    fun `resolving one request keeps retention while turn remains active`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        transport.incoming.send(
            """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
        )
        transport.incoming.send(
            """{"id":"input-1","method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","questions":[]}}""",
        )
        runCurrent()
        lease.close()

        manager.resolveRequest(server.id, JsonPrimitive("input-1"))
        advanceTimeBy(1)
        runCurrent()

        assertTrue(manager.get(server.id) != null)
    }

    @Test
    fun `back to back server request and resolution leave no stale pending request`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()

        transport.incoming.send(
            """{"id":"input-1","method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","questions":[]}}""",
        )
        transport.incoming.send(
            """{"method":"serverRequest/resolved","params":{"threadId":"thread-1","turnId":"turn-1","requestId":"input-1"}}""",
        )
        runCurrent()

        assertTrue(lease.connection.pendingRequests.value.isEmpty())
        lease.close()
        advanceTimeBy(1)
        runCurrent()
        assertNull(manager.get(server.id))
    }

    @Test
    fun `stale request callback cannot answer a same id request after reconnect`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        transport.incoming.send(
            """{"id":"approval-1","method":"item/fileChange/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"old-item","availableDecisions":["accept","cancel"]}}""",
        )
        runCurrent()
        val staleRequest = lease.connection.pendingRequests.value.single()

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        initialize(transport)
        assertEquals("initialized", transport.takeSentObject()["method"]?.jsonPrimitive?.content)
        transport.incoming.send(
            """{"id":"approval-1","method":"item/fileChange/requestApproval","params":{"threadId":"thread-1","turnId":"turn-2","itemId":"new-item","availableDecisions":["accept","cancel"]}}""",
        )
        runCurrent()
        val currentRequest = lease.connection.pendingRequests.value.single()

        val error = runCatching {
            lease.connection.reply(staleRequest, buildJsonObject { put("decision", "accept") })
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertSame(currentRequest, lease.connection.pendingRequests.value.single())
        lease.close()
    }

    @Test
    fun `buffered server requests from an old reader generation are discarded`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            reconnectInitialMillis = 1,
            reconnectMaxMillis = 4,
        )
        val leaseDeferred = async { manager.acquire(server) }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        val oldGeneration = lease.connection.client.currentConnectionGeneration()

        transport.disconnect()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        initialize(transport)
        transport.takeSentObject()
        lease.connection.client.handleIncoming(
            """{"id":"stale-approval","method":"item/fileChange/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"item-1","availableDecisions":["accept","cancel"]}}""",
            connectionGeneration = oldGeneration,
        )
        runCurrent()

        assertTrue(lease.connection.pendingRequests.value.isEmpty())
        lease.close()
    }

    @Test
    fun `idle thread unsubscribes before a reacquired lease can resume it`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 10_000,
        )
        val firstDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val first = firstDeferred.await()
        transport.takeSentObject()

        val initialResume = async { first.connection.client.resumeThread("thread-1") }
        val initialResumeRequest = transport.takeSentObject()
        transport.respond(initialResumeRequest.getValue("id").jsonPrimitive, threadSession("thread-1"))
        initialResume.await()
        first.close()
        runCurrent()

        val unsubscribe = transport.takeSentObject()
        assertEquals("thread/unsubscribe", unsubscribe["method"]?.jsonPrimitive?.content)
        assertEquals(
            "thread-1",
            unsubscribe["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content,
        )
        val reacquiredDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        assertFalse(reacquiredDeferred.isCompleted)

        transport.respond(
            unsubscribe.getValue("id").jsonPrimitive,
            buildJsonObject { put("status", "unsubscribed") },
        )
        runCurrent()
        val reacquired = reacquiredDeferred.await()
        val resumed = async { reacquired.connection.client.resumeThread("thread-1") }
        val resume = transport.takeSentObject()
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
        transport.respond(resume.getValue("id").jsonPrimitive, threadSession("thread-1"))
        resumed.await()
        reacquired.close()
    }

    @Test
    fun `accepted turn remains retained before its first authoritative event`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        manager.retainProvisionalTurn(server.id, "thread-1")

        lease.close()
        advanceTimeBy(1)
        runCurrent()

        assertTrue(manager.get(server.id) != null)
        assertNull(transport.tryTakeSentObject())

        transport.incoming.send(
            """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress","items":[]}}}""",
        )
        runCurrent()
        transport.incoming.send(
            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}}""",
        )
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertNull(manager.get(server.id))
    }

    @Test
    fun `definitive turn start failure releases provisional retention`() = runTest {
        lateinit var transport: FakeTransport
        val manager = CodexConnectionManager(
            createClient = {
                FakeTransport().also { transport = it }.newClient(backgroundScope)
            },
            scope = backgroundScope,
            idleDisconnectMillis = 1,
        )
        val leaseDeferred = async { manager.acquire(server, threadId = "thread-1") }
        runCurrent()
        initialize(transport)
        val lease = leaseDeferred.await()
        transport.takeSentObject()
        manager.retainProvisionalTurn(server.id, "thread-1")
        lease.close()

        manager.releaseProvisionalTurn(server.id, "thread-1")
        advanceTimeBy(1)
        runCurrent()

        assertNull(manager.get(server.id))
    }

    private suspend fun initialize(transport: FakeTransport) {
        val initialize = transport.takeSentObject()
        assertEquals("initialize", initialize["method"]?.jsonPrimitive?.content)
        transport.respond(
            initialize.getValue("id").jsonPrimitive,
            buildJsonObject {
                put("userAgent", "codex-test")
                put("codexHome", "/tmp/codex")
                put("platformFamily", "unix")
                put("platformOs", "linux")
            },
        )
    }

    private fun FakeTransport.newClient(
        scope: kotlinx.coroutines.CoroutineScope,
        currentTimeSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    ) = CodexAppServerClient(
        transport = this,
        json = json,
        scope = scope,
        currentTimeSeconds = currentTimeSeconds,
    )

    private fun threadSession(threadId: String) = buildJsonObject {
        put("thread", buildJsonObject { put("id", threadId) })
        put("model", "gpt-5")
        put("modelProvider", "openai")
        put("cwd", "/workspace")
        put("approvalPolicy", "on-request")
        put("approvalsReviewer", "user")
        put("sandbox", "workspace-write")
    }

    private inner class FakeTransport(
        private var connectFailuresRemaining: Int = 0,
    ) : CodexRpcTransport {
        var incoming = Channel<String>(Channel.UNLIMITED)
            private set
        private val sent = Channel<String>(Channel.UNLIMITED)
        var connected = false
            private set
        private var disconnected = false
        var connectAttempts = 0
            private set
        var resumeAttempts = 0
            private set

        override suspend fun connect() {
            connectAttempts += 1
            if (connectFailuresRemaining > 0) {
                connectFailuresRemaining -= 1
                throw IllegalStateException("connect failed")
            }
            if (disconnected) incoming = Channel(Channel.UNLIMITED)
            disconnected = false
            connected = true
        }

        override suspend fun send(text: String) {
            check(connected)
            val message = json.parseToJsonElement(text).jsonObject
            if (message["method"]?.jsonPrimitive?.content == "thread/resume") {
                resumeAttempts += 1
            }
            sent.send(text)
        }

        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

        override fun close() {
            connected = false
        }

        fun disconnect() {
            disconnected = true
            incoming.close()
        }

        suspend fun takeSentObject(): JsonObject = json.parseToJsonElement(sent.receive()).jsonObject

        fun tryTakeSentObject(): JsonObject? = sent.tryReceive().getOrNull()?.let { text ->
            json.parseToJsonElement(text).jsonObject
        }

        suspend fun respond(id: JsonPrimitive, result: JsonElement) {
            incoming.send(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", id)
                        put("result", result)
                    },
                ),
            )
        }

        suspend fun respondError(id: JsonPrimitive, code: Long, message: String) {
            incoming.send(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", id)
                        put("error", buildJsonObject {
                            put("code", code)
                            put("message", message)
                        })
                    },
                ),
            )
        }
    }
}
