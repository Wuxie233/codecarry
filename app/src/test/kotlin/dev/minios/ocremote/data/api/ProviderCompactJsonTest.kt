package dev.minios.ocremote.data.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCompactJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `providers response with no providers defaults to empty list`() {
        val response = json.decodeFromString(ProvidersResponse.serializer(), """{}""")

        assertTrue(response.providers.isEmpty())
        assertTrue(response.default.isEmpty())
    }

    @Test
    fun `provider with only id decodes with empty name and models`() {
        val provider = json.decodeFromString(ProviderInfo.serializer(), """{"id":"openai"}""")

        assertEquals("openai", provider.id)
        assertEquals("", provider.name)
        assertTrue(provider.models.isEmpty())
    }

    @Test
    fun `provider model with only id decodes with empty name`() {
        val model = json.decodeFromString(ProviderModel.serializer(), """{"id":"gpt-5"}""")

        assertEquals("gpt-5", model.id)
        assertEquals("", model.name)
    }

    @Test
    fun `full provider response keeps provided names and models`() {
        val response = json.decodeFromString(
            ProvidersResponse.serializer(),
            """
            {
              "providers": [
                {
                  "id": "openai",
                  "name": "OpenAI",
                  "models": {
                    "gpt-5": {
                      "id": "gpt-5",
                      "name": "GPT-5"
                    }
                  }
                }
              ],
              "default": { "providerID": "openai", "modelID": "gpt-5" }
            }
            """.trimIndent()
        )

        val provider = response.providers.single()
        val model = provider.models.getValue("gpt-5")
        assertEquals("OpenAI", provider.name)
        assertEquals("GPT-5", model.name)
        assertEquals("gpt-5", response.default["modelID"])
    }

    @Test(expected = SerializationException::class)
    fun `provider id remains required`() {
        json.decodeFromString(ProviderInfo.serializer(), """{"name":"OpenAI"}""")
    }

    @Test(expected = SerializationException::class)
    fun `provider model id remains required`() {
        json.decodeFromString(ProviderModel.serializer(), """{"name":"GPT-5"}""")
    }
}
