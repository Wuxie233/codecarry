package dev.wuxie233.codecarry.data.api

import android.net.Uri
import dev.wuxie233.codecarry.domain.model.SessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeApiSessionStatusTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun newApi(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): OpenCodeApi {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    @Test
    fun `getSessionStatuses parses busy and retry from the status map and ignores the optional action block`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi { request ->
            captured += request
            assertEquals("/session/status", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            respondJson(
                """
                {
                  "ses-busy": {"type": "busy"},
                  "ses-retry": {
                    "type": "retry",
                    "attempt": 3,
                    "message": "rate limited, retrying",
                    "next": 1700000000,
                    "action": {"reason": "x", "provider": "y", "title": "t", "message": "m", "label": "l"}
                  }
                }
                """.trimIndent(),
            )
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getSessionStatuses(conn, directory = null)

        assertEquals(SessionStatus.Busy, result["ses-busy"])
        assertEquals(
            SessionStatus.Retry(attempt = 3, message = "rate limited, retrying", next = 1_700_000_000L),
            result["ses-retry"],
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `getSessionStatuses returns empty map for an empty snapshot`() = runBlocking {
        val api = newApi { _ -> respondJson("{}") }
        val conn = ServerConnection.from("http://example.test:4096")

        assertTrue(api.getSessionStatuses(conn, directory = null).isEmpty())
    }

    @Test
    fun `getSessionStatuses maps explicit idle and unknown status types to Idle for forward compatibility`() = runBlocking {
        val api = newApi { _ ->
            respondJson("""{"ses-idle": {"type": "idle"}, "ses-future": {"type": "reconnecting"}}""")
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getSessionStatuses(conn, directory = null)

        assertEquals(SessionStatus.Idle, result["ses-idle"])
        assertEquals(SessionStatus.Idle, result["ses-future"])
    }

    @Test
    fun `retrySessionNow posts authenticated encoded-directory request and decodes boolean response bodies`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val responseBodies = ArrayDeque(listOf("true", "false"))
        val api = newApi { request ->
            captured += request
            respondJson(responseBodies.removeFirst())
        }
        val conn = ServerConnection.from(
            url = "http://example.test:4096",
            username = "opencode-user",
            password = "secret",
        )
        val directory = "/workspace/project name/项目"

        val firstResult = api.retrySessionNow(conn, "ses-retry", directory)
        val secondResult = api.retrySessionNow(conn, "ses-retry", directory)

        assertTrue(firstResult)
        assertFalse(secondResult)
        assertEquals(2, captured.size)
        captured.forEach { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/session/ses-retry/retry", request.url.encodedPath)
            assertEquals(conn.authHeader, request.headers[HttpHeaders.Authorization])
            assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("Basic "))
            assertEquals(Uri.encode(directory), request.headers["x-opencode-directory"])
        }
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
