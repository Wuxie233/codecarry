package dev.wuxie233.codecarry.ui.screens.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.wuxie233.codecarry.data.repository.AppUpdateRepository
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private fun createSettingsRepository(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings-${System.nanoTime()}.preferences_pb") },
        )
        val context = object : ContextWrapper(null) {}
        return SettingsRepository(dataStore, context)
    }

    private fun createViewModel(settingsRepository: SettingsRepository): SettingsViewModel {
        val appUpdateRepository = AppUpdateRepository(
            httpClient = HttpClient(MockEngine { throw AssertionError("unexpected app update request") }),
            context = object : ContextWrapper(null) {},
        )
        return SettingsViewModel(
            settingsRepository = settingsRepository,
            appUpdateRepository = appUpdateRepository,
        )
    }

    @Test
    fun `diagnostics upload state flows start empty and setters persist trimmed values`() = testScope.runTest {
        val settingsRepository = createSettingsRepository()
        val viewModel = createViewModel(settingsRepository)

        assertEquals("", viewModel.diagnosticsUploadUrl.first())
        assertEquals("", viewModel.diagnosticsUploadToken.first())

        viewModel.setDiagnosticsUploadUrl("  https://example.com/upload  ")
        viewModel.setDiagnosticsUploadToken("  token-value  ")
        advanceUntilIdle()

        assertEquals("https://example.com/upload", viewModel.diagnosticsUploadUrl.first())
        assertEquals("token-value", viewModel.diagnosticsUploadToken.first())
    }

    class MainDispatcherRule(
        private val dispatcher: TestDispatcher,
    ) : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
}
