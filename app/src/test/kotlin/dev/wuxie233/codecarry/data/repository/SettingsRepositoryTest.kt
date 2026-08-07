package dev.wuxie233.codecarry.data.repository

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createRepo(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings-${System.nanoTime()}.preferences_pb") },
        )
        val context = object : ContextWrapper(null) {}
        return SettingsRepository(dataStore, context)
    }

    @Test
    fun `diagnostics upload defaults are empty`() = testScope.runTest {
        val repo = createRepo()

        assertEquals("", repo.diagnosticsUploadUrl.first())
        assertEquals("", repo.diagnosticsUploadToken.first())
    }

    @Test
    fun `diagnostics upload setters trim and persist`() = testScope.runTest {
        val repo = createRepo()

        repo.setDiagnosticsUploadUrl("  https://example.com/upload  ")
        repo.setDiagnosticsUploadToken("  token-value  ")

        assertEquals("https://example.com/upload", repo.diagnosticsUploadUrl.first())
        assertEquals("token-value", repo.diagnosticsUploadToken.first())
    }
}
