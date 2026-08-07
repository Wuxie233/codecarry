package dev.wuxie233.codecarry.ui.screens.home

import dev.wuxie233.codecarry.data.api.PiStackApiErrorKind
import dev.wuxie233.codecarry.data.api.PiStackApiException
import dev.wuxie233.codecarry.data.api.ProviderCatalogResponse
import dev.wuxie233.codecarry.data.api.ProviderInfo
import dev.wuxie233.codecarry.data.api.ProviderModel
import dev.wuxie233.codecarry.data.api.ProvidersResponse
import dev.wuxie233.codecarry.domain.model.ServerType
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {

    @Test
    fun `connecting server keeps disconnect action available`() {
        assertTrue(shouldShowServerDisconnect(isConnected = false, isConnecting = true))
        assertTrue(shouldShowServerDisconnect(isConnected = true, isConnecting = false))
        assertFalse(shouldShowServerDisconnect(isConnected = false, isConnecting = false))
    }

    @Test
    fun `connection service intent contains only server identity`() {
        assertEquals(setOf("server_id"), serverConnectionIntentExtraKeys)
    }

    @Test
    fun `Codex endpoints require TLS outside loopback`() {
        assertEquals("wss://codex.example.com", validateAndNormalizeUrl("codex.example.com", ServerType.CODEX))
        assertEquals("ws://127.0.0.1:8765", validateAndNormalizeUrl("ws://127.0.0.1:8765", ServerType.CODEX))
        assertEquals("ws://localhost:8765", validateAndNormalizeUrl("ws://localhost:8765", ServerType.CODEX))
        assertEquals(null, validateAndNormalizeUrl("ws://192.168.1.20:8765", ServerType.CODEX))
    }

    @Test
    fun `Pi Stack origin URLs use control endpoint while custom paths are preserved`() {
        assertEquals(
            "https://pi.example.test/control",
            validateAndNormalizeUrl("https://pi.example.test/", ServerType.PI_STACK),
        )
        assertEquals(
            "https://pi.example.test/custom-control",
            validateAndNormalizeUrl("https://pi.example.test/custom-control/", ServerType.PI_STACK),
        )
    }

    @Test
    fun `health check auth failure surfaces authentication error instead of generic not responding`() {
        val authError = PiStackApiException(
            kind = PiStackApiErrorKind.Auth,
            message = "Bearer authentication failed.",
            status = 401,
            code = "auth_invalid",
        )

        assertEquals(
            "Authentication failed. Check the access token.",
            healthCheckErrorMessage(authError),
        )
    }

    @Test
    fun `health check protocol failure surfaces protocol message`() {
        val protocolError = PiStackApiException(
            kind = PiStackApiErrorKind.Protocol,
            message = "capabilities protocolVersion is not 1",
        )

        assertEquals(
            "capabilities protocolVersion is not 1",
            healthCheckErrorMessage(protocolError),
        )
    }

    @Test
    fun `health check transport failure keeps generic not responding message`() {
        assertEquals("Server is not responding", healthCheckErrorMessage(IOException("timeout")))
        assertEquals("Server is not responding", healthCheckErrorMessage(null))
    }

    @Test
    fun `returns true when provider response already has models`() {
        val response = ProvidersResponse(
            providers = listOf(
                providerInfo(
                    id = "openai",
                    name = "OpenAI",
                    models = mapOf(
                        "gpt-5" to providerModel("gpt-5", "GPT-5")
                    )
                )
            )
        )

        assertTrue(hasServerSettingsAccess(response))
    }

    @Test
    fun `returns true when custom provider exists without published models`() {
        val response = ProvidersResponse(
            providers = listOf(
                providerInfo(
                    id = "openai-compatible",
                    name = "OpenAI Compatible",
                    source = "custom",
                )
            )
        )

        assertTrue(hasServerSettingsAccess(response))
    }

    @Test
    fun `returns true when provider catalog exposes custom provider after config providers are empty`() {
        val response = ProvidersResponse(providers = emptyList())
        val catalog = ProviderCatalogResponse(
            all = listOf(
                providerInfo(
                    id = "openai-compatible",
                    name = "OpenAI Compatible",
                    source = "custom",
                )
            ),
            connected = listOf("openai-compatible")
        )

        assertTrue(hasServerSettingsAccess(response, catalog))
    }

    @Test
    fun `returns false when neither providers response nor catalog expose settings data`() {
        assertFalse(
            hasServerSettingsAccess(
                ProvidersResponse(providers = emptyList()),
                ProviderCatalogResponse(all = emptyList())
            )
        )
    }

    private fun providerInfo(
        id: String,
        name: String,
        source: String = "",
        models: Map<String, ProviderModel> = emptyMap(),
    ) = ProviderInfo(
        id = id,
        name = name,
        source = source,
        models = models,
    )

    private fun providerModel(id: String, name: String) = ProviderModel(
        id = id,
        providerId = "",
        name = name,
    )
}
