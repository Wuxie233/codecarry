package dev.minios.ocremote.ui.screens.roundtable

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.repository.SettingsRepository
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
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLEncoder
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class RoundtableCenterViewModelTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)
    private val viewModels = mutableListOf<RoundtableCenterViewModel>()
    private val collectJobs = mutableListOf<Job>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `roundtable center lifecycle never calls session store`() = runTest(dispatcher) {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val service = FakeRoundtableService()
        val vm = RoundtableCenterViewModel(
            savedStateHandle = savedStateHandle(),
            api = piApi(requests, service),
            settingsRepository = settingsRepository(),
        ).also { viewModels.add(it) }
        collectJobs += backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { }
        }
        advanceUntilIdle()

        vm.createRoundtable()
        vm.createRoundtable()
        advanceUntilIdle()
        vm.setFilter(RoundtableFilter.All)
        advanceUntilIdle()

        val createdState = vm.uiState.first { it.items.size == 2 }
        assertEquals(2, createdState.items.count { it.status == dev.minios.ocremote.domain.model.Roundtable.Status.Running })

        val firstId = createdState.items.first().id
        vm.archiveRoundtable(firstId)
        advanceUntilIdle()

        val archivedState = vm.uiState.first { state ->
            state.items.any { it.id == firstId && it.status == dev.minios.ocremote.domain.model.Roundtable.Status.Archived }
        }
        assertEquals(1, archivedState.items.count { it.status == dev.minios.ocremote.domain.model.Roundtable.Status.Running })

        val remainingId = archivedState.items.first { it.id != firstId }.id
        vm.deleteRoundtable(remainingId)
        advanceUntilIdle()

        val deletedState = vm.uiState.first { it.items.map { item -> item.id } == listOf(firstId) }
        assertEquals(listOf(firstId), deletedState.items.map { it.id })
        assertEquals(0, requests.count { it.url.encodedPath.startsWith("/session") })
        assertFalse(requests.any { it.url.encodedPath == "/session" })
        assertTrue(requests.any { it.method == HttpMethod.Delete && it.url.encodedPath == "/roundtables/$remainingId" })
    }

    private fun piApi(requests: MutableList<HttpRequestData>, service: FakeRoundtableService): PiApi {
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath == "/roundtables" && request.method == HttpMethod.Get -> respondJson(service.list())
                request.url.encodedPath == "/roundtables" && request.method == HttpMethod.Post -> respondJson(service.create())
                request.url.encodedPath.endsWith("/archive") && request.method == HttpMethod.Post -> respondJson(service.archive(request.url.encodedPath.substringAfter("/roundtables/").substringBefore("/archive")))
                request.url.encodedPath.startsWith("/roundtables/") && request.method == HttpMethod.Delete -> respondJson(service.delete(request.url.encodedPath.substringAfterLast('/')))
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return PiApi(client, json)
    }

    private fun savedStateHandle(): SavedStateHandle = SavedStateHandle(
        mapOf(
            "serverUrl" to encode("https://pi.example.test"),
            "token" to encode("pi-token"),
            "serverName" to encode("Pi Test"),
            "serverId" to encode("srv-pi"),
        )
    )

    private fun settingsRepository(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("roundtable-settings-${System.nanoTime()}.preferences_pb") },
        )
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmpFolder.root
        }
        return SettingsRepository(dataStore, context)
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = ByteReadChannel(content),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private class FakeRoundtableService {
        private val items = linkedMapOf<String, String>()
        private var next = 0

        fun list(): String = items.entries.joinToString(prefix = "{\"items\":[", postfix = "]}") { (id, status) -> item(id, status) }

        fun create(): String {
            next += 1
            val id = "round-$next"
            items[id] = "running"
            return item(id, "running")
        }

        fun archive(id: String): String {
            items[id] = "archived"
            return item(id, "archived")
        }

        fun delete(id: String): String {
            items.remove(id)
            return "{\"protocolVersion\":1,\"id\":\"$id\",\"deleted\":true}"
        }

        private fun item(id: String, status: String): String = """
            {"id":"$id","roundId":"$id","topic":"Topic $id","status":"$status","roundCount":1,"createdAt":"2026-06-02T00:00:00Z","updatedAt":"2026-06-02T00:00:0${id.takeLast(1)}Z","roster":[{"id":"ada","name":"Ada","role":"persona","colorSeed":"persona-ada"},{"id":"curie","name":"Curie","role":"persona","colorSeed":"persona-curie"}]}
        """.trimIndent()
    }
}
