package dev.minios.ocremote.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.domain.model.McpConfigLoadState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun resolveMcpConfigLoadStateReturnsErrorWhenConfigReadFails() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmpFolder.newFile("server_repo_prefs.preferences_pb") },
        )

        val repository = ServerRepository(
            dataStore = dataStore,
            api = OpenCodeApi(HttpClient(OkHttp), json),
            json = json,
        )

        val result = repository.resolveMcpConfigLoadState(
            listOf(
                ServerRepository.McpConfigCandidateRead(
                    path = "/workspace/project/.opencode/config.json",
                    readResult = Result.failure(IllegalStateException("boom")),
                )
            )
        )

        assertTrue(result is McpConfigLoadState.Error)
        assertEquals(
            "/workspace/project/.opencode/config.json",
            (result as McpConfigLoadState.Error).filePath,
        )
    }
}
