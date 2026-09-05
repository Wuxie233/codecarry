package dev.wuxie233.codecarry.data.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CodexReadImageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `image reads use remote wire path and decode bytes`() = runTest {
        val wire = ImageWire()
        val client = CodexAppServerClient(wire, json, scope = backgroundScope)
        try {
            val connect = async { client.connect() }
            val init = wire.request()
            wire.incoming.send("""{"id":${init["id"]},"result":{"userAgent":"test"}}""")
            connect.await()
            wire.outgoing.receive()
            for (path in listOf("/repo/image name.png ", "C:\\repo\\image.png", "\\\\host\\share\\image.png")) {
                val read = async { client.readImageFile(path) }
                val request = wire.request()
                assertEquals("fs/readFile", request["method"]?.jsonPrimitive?.content)
                assertEquals(path, request["params"]?.jsonObject?.get("path")?.jsonPrimitive?.content)
                wire.result(request, buildJsonObject { put("dataBase64", "AAH+/w==") })
                assertArrayEquals(byteArrayOf(0, 1, -2, -1), read.await())
            }
            for (path in listOf("relative.png", "~/image.png", "C:image.png", "/bad\u0000.png")) {
                assertTrue(runCatching { client.readImageFile(path) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(wire.outgoing.tryReceive().isFailure)
        } finally { client.close() }
    }

    @Test fun `image read rejects malformed and oversized data and surfaces RPC errors`() = runTest {
        val wire = ImageWire()
        val client = CodexAppServerClient(wire, json, scope = backgroundScope)
        try {
            val connect = async { client.connect() }
            val init = wire.request()
            wire.incoming.send("""{"id":${init["id"]},"result":{"userAgent":"test"}}""")
            connect.await()
            wire.outgoing.receive()
            val oversized = "A".repeat(((16 * 1024 * 1024 + 2) / 3) * 4 + 4)
            for (result in listOf(
                JsonObject(emptyMap()),
                buildJsonObject { put("dataBase64", 123) },
                buildJsonObject { put("dataBase64", "invalid!") },
                buildJsonObject { put("dataBase64", oversized) },
            )) {
                val read = async { runCatching { client.readImageFile("/image.png") } }
                wire.result(wire.request(), result)
                assertTrue(read.await().isFailure)
            }
            for ((code, type) in listOf(
                -32000 to CodexRpcException::class.java,
                -32601 to CodexCapabilityUnavailableException::class.java,
            )) {
                val read = async { runCatching { client.readImageFile("/image.png") } }
                val request = wire.request()
                wire.incoming.send("""{"id":${request["id"]},"error":{"code":$code,"message":"Unavailable"}}""")
                assertTrue(type.isInstance(read.await().exceptionOrNull()))
            }
        } finally { client.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `unanswered image read becomes retryable failure and parent cancellation propagates`() = runTest {
        val wire = ImageWire()
        val client = CodexAppServerClient(wire, json, scope = backgroundScope)
        try {
            val connect = async { client.connect() }
            val init = wire.request()
            wire.incoming.send("""{"id":${init["id"]},"result":{"userAgent":"test"}}""")
            connect.await()
            wire.outgoing.receive()

            val read = async { runCatching { client.readImageFile("/unanswered.png") } }
            wire.request()
            advanceTimeBy(14_999)
            runCurrent()
            assertFalse(read.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(read.await().exceptionOrNull() is IOException)

            val parent = Job(coroutineContext[Job])
            val observed = CompletableDeferred<Throwable>()
            val child = CoroutineScope(coroutineContext + parent).launch {
                try {
                    client.readImageFile("/cancelled.png")
                    observed.complete(AssertionError("Cancelled read unexpectedly succeeded"))
                } catch (error: Throwable) {
                    observed.complete(error)
                    throw error
                }
            }
            wire.request()
            parent.cancelAndJoin()
            assertTrue(child.isCancelled)
            assertTrue(observed.await() is CancellationException)
        } finally { client.close() }
    }

    private inner class ImageWire : CodexRpcTransport {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val outgoing = Channel<String>(Channel.UNLIMITED)
        override suspend fun connect() = Unit
        override suspend fun send(text: String) { outgoing.send(text) }
        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
        override fun close() { incoming.close(); outgoing.close() }
        suspend fun request() = json.parseToJsonElement(outgoing.receive()).jsonObject
        suspend fun result(request: JsonObject, result: JsonObject) {
            incoming.send(buildJsonObject {
                put("id", request.getValue("id"))
                put("result", result)
            }.toString())
        }
    }
}
