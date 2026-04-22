package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun archiveSessionSendsArchivedTruePatch() = runTest {
        var capturedBody = ""
        val api = createApi(
            responseSession = testSession(archived = 1_000L),
            onRequest = { capturedBody = it }
        )

        val response = api.archiveSession(ServerConnection.from("http://127.0.0.1"), "ses-1")

        assertEquals(true, capturedBody.contains("\"archived\":true"))
        assertEquals(true, response.isArchived)
    }

    @Test
    fun restoreSessionSendsArchivedFalsePatch() = runTest {
        var capturedBody = ""
        val api = createApi(
            responseSession = testSession(archived = null),
            onRequest = { capturedBody = it }
        )

        val response = api.restoreSession(ServerConnection.from("http://127.0.0.1"), "ses-1")

        assertEquals(true, capturedBody.contains("\"archived\":false"))
        assertEquals(false, response.isArchived)
    }

    private fun createApi(
        responseSession: Session,
        onRequest: (String) -> Unit,
    ): OpenCodeApi {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/session/ses-1", request.url.encodedPath)
            onRequest(request.bodyText())
            respondJson(json.encodeToString(responseSession))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return OpenCodeApi(client, json)
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

    private fun HttpRequestData.bodyText(): String {
        val outgoing = body as OutgoingContent
        return when (outgoing) {
            is OutgoingContent.ByteArrayContent -> outgoing.bytes().toString(Charsets.UTF_8)
            else -> error("Unsupported request body type: ${outgoing::class.java.name}")
        }
    }

    private fun testSession(archived: Long?) = Session(
        id = "ses-1",
        directory = "/tmp/project",
        time = Session.Time(
            created = 1L,
            updated = 1L,
            archived = archived,
        ),
    )
}
