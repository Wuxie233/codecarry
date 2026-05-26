package dev.minios.ocremote.data.diagnostics

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsLogRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `create and list stores diagnostics artifacts in app private files directory`() {
        val fixture = newRepository()

        val item = fixture.repository.createLog(
            type = DiagnosticsLogType.APP_EVENT,
            displayName = "App events",
            content = "started",
            createdAtMillis = 1000L,
            serverName = "Local server",
        )

        val listed = fixture.repository.listLogs()
        val artifact = fixture.repository.getArtifactFile(item)

        assertEquals(listOf(item), listed)
        assertNotNull(artifact)
        assertEquals("started", artifact?.readText())
        assertTrue(artifact?.absolutePath.orEmpty().startsWith(File(fixture.filesDir, "diagnostics").absolutePath))
        assertFalse(artifact?.absolutePath.orEmpty().startsWith(fixture.cacheDir.absolutePath))
        assertEquals("diagnostics-bundles", fixture.repository.bundleCacheDirectory.name)
        assertEquals(File(fixture.cacheDir, "diagnostics-bundles"), fixture.repository.bundleCacheDirectory)
    }

    @Test
    fun `metadata survives repository recreation`() {
        val fixture = newRepository()
        val firstRepository = fixture.repository
        val item = firstRepository.createLog(
            type = DiagnosticsLogType.SESSION_DIAGNOSTIC,
            displayName = "Session diagnostic",
            content = "{\"session\":true}",
            createdAtMillis = 2000L,
            sessionId = "ses_123",
            serverName = "Work server",
        )

        val recreated = DiagnosticsLogRepository(fixture.context)
        val listed = recreated.listLogs()

        assertEquals(1, listed.size)
        assertEquals(item, listed.single())
        assertEquals("{\"session\":true}", recreated.getArtifactFile(listed.single())?.readText())
    }

    @Test
    fun `delete removes selected artifact immediately`() {
        val fixture = newRepository()
        val keep = fixture.repository.createLog(
            type = DiagnosticsLogType.APP_EVENT,
            displayName = "Keep",
            content = "keep",
            createdAtMillis = 1000L,
        )
        val remove = fixture.repository.createLog(
            type = DiagnosticsLogType.NETWORK_DIAGNOSTIC,
            displayName = "Remove",
            content = "remove",
            createdAtMillis = 2000L,
        )
        val removeFile = fixture.repository.getArtifactFile(remove)

        assertTrue(fixture.repository.deleteLog(remove.id))

        assertFalse(removeFile?.exists() == true)
        assertEquals(listOf(keep), fixture.repository.listLogs())
    }

    @Test
    fun `cleanup deletes only artifacts older than seven days and outside newest thirty`() {
        val fixture = newRepository()
        val now = 30L * 24L * 60L * 60L * 1000L
        val oneHourMillis = 60L * 60L * 1000L
        val recentItems = (0 until 31).map { index ->
            fixture.repository.createLog(
                type = DiagnosticsLogType.APP_EVENT,
                displayName = "Recent $index",
                content = "recent-$index",
                createdAtMillis = now - index * oneHourMillis,
            )
        }
        val oldItems = (0 until 35).map { index ->
            fixture.repository.createLog(
                type = DiagnosticsLogType.SESSION_DIAGNOSTIC,
                displayName = "Old $index",
                content = "old-$index",
                createdAtMillis = now - 8L * 24L * 60L * 60L * 1000L - index * oneHourMillis,
            )
        }

        val deletedCount = fixture.repository.cleanup(nowMillis = now)
        val remaining = fixture.repository.listLogs()
        val remainingIds = remaining.map { it.id }.toSet()

        assertEquals(35, deletedCount)
        assertTrue(recentItems.all { it.id in remainingIds })
        assertTrue(oldItems.none { it.id in remainingIds })
    }

    @Test
    fun `cleanup keeps newest thirty even when they are older than seven days`() {
        val fixture = newRepository()
        val now = 20L * 24L * 60L * 60L * 1000L
        val oneHourMillis = 60L * 60L * 1000L
        val oldItems = (0 until 35).map { index ->
            fixture.repository.createLog(
                type = DiagnosticsLogType.SESSION_DIAGNOSTIC,
                displayName = "Old $index",
                content = "old-$index",
                createdAtMillis = now - 8L * 24L * 60L * 60L * 1000L - index * oneHourMillis,
            )
        }

        val deletedCount = fixture.repository.cleanup(nowMillis = now)
        val remaining = fixture.repository.listLogs()

        assertEquals(5, deletedCount)
        assertEquals(oldItems.take(30).map { it.id }, remaining.map { it.id })
    }

    @Test
    fun `missing artifact file is pruned from list without throwing`() {
        val fixture = newRepository()
        val missing = fixture.repository.createLog(
            type = DiagnosticsLogType.NETWORK_DIAGNOSTIC,
            displayName = "Network diagnostic",
            content = "network",
            createdAtMillis = 1000L,
        )
        val artifact = fixture.repository.getArtifactFile(missing)
        assertTrue(artifact?.delete() == true)

        val listed = fixture.repository.listLogs()
        val recreated = DiagnosticsLogRepository(fixture.context)

        assertEquals(emptyList<DiagnosticsLogItem>(), listed)
        assertEquals(emptyList<DiagnosticsLogItem>(), recreated.listLogs())
        assertNull(recreated.getArtifactFile(missing))
    }

    @Test
    fun `diagnostics log metadata redacts token authorization bearer and password values`() {
        val fixture = newRepository()

        val item = fixture.repository.createLog(
            type = DiagnosticsLogType.APP_EVENT,
            displayName = "Authorization: Bearer display-token password=display-password",
            content = "content may be generated by later tasks",
            createdAtMillis = 1000L,
            serverName = "token=server-token",
        )

        assertFalse(item.displayName.contains("display-token"))
        assertFalse(item.displayName.contains("display-password"))
        assertFalse(item.serverName.orEmpty().contains("server-token"))
        assertTrue(item.displayName.contains("<redacted>"))
    }

    @Test
    fun `diagnostics storage does not request broad storage permissions`() {
        val sourceRoot = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val forbiddenStoragePermissions = listOf(
            "READ_" + "EXTERNAL_STORAGE",
            "WRITE_" + "EXTERNAL_STORAGE",
            "MANAGE_" + "EXTERNAL_STORAGE",
            "requestLegacy" + "ExternalStorage",
        )

        val matches = sourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .flatMap { file ->
                forbiddenStoragePermissions.filter { token -> file.readText().contains(token) }
                    .map { token -> "${file.path}:$token" }
            }
            .toList()

        assertEquals(emptyList<String>(), matches)
    }

    private fun newRepository(): RepositoryFixture {
        val filesDir = tmpFolder.newFolder("files-${System.nanoTime()}")
        val cacheDir = tmpFolder.newFolder("cache-${System.nanoTime()}")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
        }
        return RepositoryFixture(
            repository = DiagnosticsLogRepository(context),
            context = context,
            filesDir = filesDir,
            cacheDir = cacheDir,
        )
    }

    private data class RepositoryFixture(
        val repository: DiagnosticsLogRepository,
        val context: ContextWrapper,
        val filesDir: File,
        val cacheDir: File,
    )
}
