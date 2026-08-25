package dev.wuxie233.codecarry.ui.screens.home

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
