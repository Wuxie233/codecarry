package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeApiForkTest {

    private val responseJson = """
        {
          "id": "ses_forked",
          "directory": "/home/user/projectA",
          "time": { "created": 1, "updated": 2, "archived": null }
        }
    """.trimIndent()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun newApi(captured: MutableList<HttpRequestData>): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel(responseJson),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    @Test
    fun `forkSession attaches encoded x-opencode-directory header when directory is supplied`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        val result: Session = api.forkSession(
            conn = conn,
            sessionId = "ses_source",
            directory = "/home/user/My Project",
        )

        assertEquals("ses_forked", result.id)
        assertEquals(1, captured.size)
        val request = captured.single()
        assertEquals("http://example.test:4096/session/ses_source/fork", request.url.toString())
        // Plain JVM unit tests use a mocked android.jar where android.net.Uri.encode is not executable;
        // assert the observable encoded fragment instead of calling Uri.encode for an exact expected value.
        val directoryHeader = request.headers["x-opencode-directory"]
        assertTrue(
            "Expected encoded directory header, was: $directoryHeader",
            directoryHeader?.contains("My%20Project") == true,
        )
    }

    @Test
    fun `forkSession omits x-opencode-directory header when directory is null`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        api.forkSession(conn = conn, sessionId = "ses_source", directory = null)

        assertEquals(1, captured.size)
        assertNull(captured.single().headers["x-opencode-directory"])
    }

    @Test
    fun `forkSession defaults directory to null and sends no header`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        api.forkSession(conn = conn, sessionId = "ses_source")

        assertEquals(1, captured.size)
        assertNull(captured.single().headers["x-opencode-directory"])
    }

    @Test
    fun `forkSession forwards messageID in body when supplied`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        api.forkSession(
            conn = conn,
            sessionId = "ses_source",
            messageId = "msg_123",
            directory = "/p",
        )

        val body = (captured.single().body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
            .bytes()
            .toString(Charsets.UTF_8)
        assert(body.contains("\"messageID\"")) { "messageID missing from body: $body" }
        assert(body.contains("msg_123")) { "messageID value missing from body: $body" }
    }
}
