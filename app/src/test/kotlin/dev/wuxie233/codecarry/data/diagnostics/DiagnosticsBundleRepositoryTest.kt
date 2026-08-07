package dev.wuxie233.codecarry.data.diagnostics

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class DiagnosticsBundleRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `one selected artifact creates one-entry zip upload file`() {
        val fixture = newRepository()
        val item = fixture.logRepository.createLog(
            type = DiagnosticsLogType.APP_EVENT,
            displayName = "App Events",
            content = "started",
            createdAtMillis = 1000L,
        )

        val uploadFile = fixture.bundleRepository.createBundle(listOf(item), nowMillis = 1000L)
        val entries = uploadFile.zipEntries()

        assertEquals("codecarry-diagnostics-19700101-000001.zip", uploadFile.filename)
        assertEquals("application/zip", uploadFile.contentType)
        assertEquals(listOf("app-event-1000-${item.id}.log"), entries)
        assertEquals(1, fixture.logRepository.bundleCacheDirectory.listFiles().orEmpty().size)
        assertTrue(File(fixture.logRepository.bundleCacheDirectory, uploadFile.filename).exists())
    }

    @Test
    fun `three selected artifacts create three safe unique zip entries`() {
        val fixture = newRepository()
        val first = fixture.logRepository.createLog(
            type = DiagnosticsLogType.APP_EVENT,
            displayName = "Duplicate / 名称",
            content = "app",
            createdAtMillis = 1000L,
            fileExtension = "log file",
        )
        val second = fixture.logRepository.createLog(
            type = DiagnosticsLogType.SESSION_DIAGNOSTIC,
            displayName = "Duplicate / 名称",
            content = "session",
            createdAtMillis = 2000L,
            fileExtension = "json/unsafe",
        )
        val third = fixture.logRepository.createLog(
            type = DiagnosticsLogType.NETWORK_DIAGNOSTIC,
            displayName = "Duplicate / 名称",
            content = "network",
            createdAtMillis = 3000L,
            fileExtension = "zip!",
        )

        val uploadFile = fixture.bundleRepository.createBundle(listOf(first, second, third), nowMillis = 2000L)
        val entries = uploadFile.zipEntries()

        assertEquals(3, entries.size)
        assertEquals(entries.toSet().size, entries.size)
        assertTrue(entries.all { it.matches(Regex("[a-z0-9-]+-[0-9]+-diag_[a-z0-9_-]+\\.[a-z0-9-]+")) })
        assertTrue(entries.none { it.contains('/') || it.contains(' ') })
    }

    @Test
    fun `missing selected artifact fails before producing partial upload file`() {
        val fixture = newRepository()
        val existing = fixture.logRepository.createLog(
            type = DiagnosticsLogType.APP_EVENT,
            displayName = "Existing",
            content = "existing",
            createdAtMillis = 1000L,
        )
        val missing = fixture.logRepository.createLog(
            type = DiagnosticsLogType.NETWORK_DIAGNOSTIC,
            displayName = "Authorization: Bearer leaked-token password=leaked-password",
            content = "missing",
            createdAtMillis = 2000L,
        )
        assertTrue(fixture.logRepository.getArtifactFile(missing)?.delete() == true)

        val error = runCatching {
            fixture.bundleRepository.createBundle(listOf(existing, missing), nowMillis = 3000L)
        }.exceptionOrNull()

        assertTrue(error is DiagnosticsBundleException.MissingArtifact)
        val message = error?.message.orEmpty()
        assertFalse(message.contains("leaked-token"))
        assertFalse(message.contains("leaked-password"))
        assertEquals(emptyList<File>(), fixture.logRepository.bundleCacheDirectory.listFiles().orEmpty().toList())
    }

    @Test
    fun `cleanup removes bundle cache files older than twenty four hours`() {
        val fixture = newRepository()
        val directory = fixture.logRepository.bundleCacheDirectory
        assertTrue(directory.mkdirs())
        val oldBundle = File(directory, "old.zip").apply {
            writeText("old")
            setLastModified(1_000L)
        }
        val freshBundle = File(directory, "fresh.zip").apply {
            writeText("fresh")
            setLastModified(2_000L + 24L * 60L * 60L * 1000L)
        }

        val deleted = fixture.bundleRepository.cleanupOldBundles(nowMillis = 2_000L + 25L * 60L * 60L * 1000L)

        assertEquals(1, deleted)
        assertFalse(oldBundle.exists())
        assertTrue(freshBundle.exists())
    }

    private fun DiagnosticsUploadFile.zipEntries(): List<String> {
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun newRepository(): RepositoryFixture {
        val filesDir = tmpFolder.newFolder("files-${System.nanoTime()}")
        val cacheDir = tmpFolder.newFolder("cache-${System.nanoTime()}")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
        }
        val logRepository = DiagnosticsLogRepository(context)
        return RepositoryFixture(
            logRepository = logRepository,
            bundleRepository = DiagnosticsBundleRepository(logRepository),
        )
    }

    private data class RepositoryFixture(
        val logRepository: DiagnosticsLogRepository,
        val bundleRepository: DiagnosticsBundleRepository,
    )
}
