package dev.wuxie233.codecarry.data.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CodexDirectoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `remote paths preserve roots and do not assume Android filesystem`() {
        assertNull(codexDirectoryParent("/"))
        assertEquals("/", codexDirectoryParent("/repo"))
        assertEquals("/repo", codexDirectoryParent("/repo/app/"))
        assertNull(codexDirectoryParent("C:\\"))
        assertEquals("C:\\", codexDirectoryParent("C:\\repo"))
        assertEquals("C:\\repo", codexDirectoryParent("C:/repo/app"))
        assertNull(codexDirectoryParent("\\\\host\\share"))
        assertEquals("\\\\host\\share", codexDirectoryParent("\\\\host\\share\\repo"))
        assertEquals("//host/share", codexDirectoryParent("//host/share/repo"))
        assertNull(normalizeCodexDirectoryPath("relative/repo"))
        assertNull(normalizeCodexDirectoryPath("C:repo"))
        assertNull(normalizeCodexDirectoryPath("~/repo"))
        assertEquals("/repo/link/..", normalizeCodexDirectoryPath("/repo/link/.."))
    }

    @Test fun `listing accepts empty directories and filters files`() {
        assertTrue(parseCodexDirectoryListing("/", json.parseToJsonElement("""{"entries":[]}""").jsonObject).directories.isEmpty())
        val listing = parseCodexDirectoryListing("C:\\", json.parseToJsonElement("""{"entries":[{"fileName":"z","isDirectory":true,"isFile":false},{"fileName":"a.txt","isDirectory":false,"isFile":true},{"fileName":"A","isDirectory":true,"isFile":false}]}""").jsonObject)
        assertEquals(listOf("A", "z"), listing.directories.map { it.name })
        assertEquals("C:\\A", listing.directories.first().path)
        assertNull(listing.parentPath)
        assertThrows(IllegalStateException::class.java) { parseCodexDirectoryListing("/", JsonObject(emptyMap())) }
        assertThrows(IllegalArgumentException::class.java) {
            parseCodexDirectoryListing("/", json.parseToJsonElement("""{"entries":[{"fileName":"../escape","isDirectory":true}]}""").jsonObject)
        }
    }

    @Test fun `wire directory request uses absolute remote path and surfaces permission and capability errors`() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val outgoing = Channel<String>(Channel.UNLIMITED)
        val transport = object : CodexRpcTransport {
            override suspend fun connect() = Unit
            override suspend fun send(text: String) { outgoing.send(text) }
            override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()
            override fun close() { incoming.close(); outgoing.close() }
        }
        val client = CodexAppServerClient(transport, json, scope = backgroundScope)
        try {
            val connect = async { client.connect() }
            val init = json.parseToJsonElement(outgoing.receive()).jsonObject
            incoming.send("""{"id":${init["id"]},"result":{"userAgent":"test","codexHome":"/home/test/.codex","platformFamily":"unix","platformOs":"linux"}}""")
            connect.await()
            outgoing.receive()
            assertEquals("/home/test/.codex", client.defaultDirectory())
            assertEquals("/repo", client.defaultDirectory("/repo"))
            val read = async { client.readDirectory("/") }
            val request = json.parseToJsonElement(outgoing.receive()).jsonObject
            assertEquals("fs/readDirectory", request["method"]?.jsonPrimitive?.content)
            assertEquals("/", request["params"]?.jsonObject?.get("path")?.jsonPrimitive?.content)
            incoming.send("""{"id":${request["id"]},"result":{"entries":[]}}""")
            assertTrue(read.await().directories.isEmpty())
            for ((code, expected) in listOf(-32000 to CodexRpcException::class.java, -32601 to CodexCapabilityUnavailableException::class.java)) {
                val failed = async { runCatching { client.readDirectory("/private") } }
                val pending = json.parseToJsonElement(outgoing.receive()).jsonObject
                incoming.send("""{"id":${pending["id"]},"error":{"code":$code,"message":"Permission denied or method unavailable"}}""")
                assertTrue(expected.isInstance(failed.await().exceptionOrNull()))
            }
            assertTrue(runCatching { client.readDirectory("relative") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(outgoing.tryReceive().isFailure)
        } finally { client.close() }
    }
}
