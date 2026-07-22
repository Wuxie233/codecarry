package dev.minios.ocremote.data.transport

import dev.minios.ocremote.data.api.PiStackApi
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PiStackTransportTest {
    @Test
    fun `factory creates server bound transport with capability probe`() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            respond(
                content = ByteReadChannel("""
                    {"protocolVersion":1,"worker":{"generation":"g","epoch":1,"startedAt":"now","active":true},
                     "data":{"protocolVersion":1,"permissions":{"supported":false,"pending":[]},
                     "runtime":{"prompt":true,"abort":true,"retry":false,"sessionPatch":[]},
                     "questions":{"reply":true,"reject":true},"ensemble":{"projections":true,"commands":true,"tools":[]}}}
                """.trimIndent()),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json(json) } }
        val factory = PiStackTransportFactory(PiStackApi(client, json))

        val result = factory.create(
            ServerConfig(id = "pi-stack", type = ServerType.PI_STACK, url = "https://pi.test", token = "token")
        ).probe()

        assertEquals("g", result.worker.generation)
        assertEquals(listOf("/control/v1/capabilities"), requests)
    }
}
