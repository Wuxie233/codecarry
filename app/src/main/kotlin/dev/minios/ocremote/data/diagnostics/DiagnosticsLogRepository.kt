package dev.minios.ocremote.data.diagnostics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiagnosticsLogRepo"
private const val DIAGNOSTICS_DIRECTORY = "diagnostics"
private const val DIAGNOSTICS_BUNDLE_CACHE_DIRECTORY = "diagnostics-bundles"
private const val METADATA_FILE = "diagnostics_logs.json"
private const val RETENTION_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1000L
private const val RETENTION_NEWEST_KEEP_COUNT = 30

@Singleton
class DiagnosticsLogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val metadataSerializer = ListSerializer(DiagnosticsLogItem.serializer())

    val diagnosticsDirectory: File get() = File(context.filesDir, DIAGNOSTICS_DIRECTORY)
    val bundleCacheDirectory: File get() = File(context.cacheDir, DIAGNOSTICS_BUNDLE_CACHE_DIRECTORY)

    private val metadataFile: File get() = File(diagnosticsDirectory, METADATA_FILE)
    private var logs: MutableList<DiagnosticsLogItem>? = null

    fun createLog(
        type: DiagnosticsLogType,
        displayName: String,
        content: String,
        createdAtMillis: Long = System.currentTimeMillis(),
        sessionId: String? = null,
        serverName: String? = null,
        fileExtension: String = type.defaultFileExtension,
    ): DiagnosticsLogItem = createLog(
        type = type,
        displayName = displayName,
        content = content.toByteArray(Charsets.UTF_8),
        createdAtMillis = createdAtMillis,
        sessionId = sessionId,
        serverName = serverName,
        fileExtension = fileExtension,
    )

    fun createLog(
        type: DiagnosticsLogType,
        displayName: String,
        content: ByteArray,
        createdAtMillis: Long = System.currentTimeMillis(),
        sessionId: String? = null,
        serverName: String? = null,
        fileExtension: String = type.defaultFileExtension,
    ): DiagnosticsLogItem {
        require(displayName.isNotBlank()) { "Diagnostics log display name is required." }
        ensureDirectory(diagnosticsDirectory)

        val id = "diag_${createdAtMillis}_${UUID.randomUUID()}"
        val extension = fileExtension.sanitizedExtension(type.defaultFileExtension)
        val file = File(diagnosticsDirectory, "$id-${type.storageSegment}.$extension")
        file.writeBytes(content)

        val item = DiagnosticsLogItem(
            id = id,
            type = type,
            displayName = DiagnosticsRedactor.redact(displayName.trim()),
            createdAtMillis = createdAtMillis,
            sizeBytes = file.length(),
            relativePath = "$DIAGNOSTICS_DIRECTORY/${file.name}",
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            serverName = serverName?.trim()?.takeIf { it.isNotBlank() }?.let(DiagnosticsRedactor::redact),
        )

        val loaded = ensureLoaded()
        loaded.removeAll { it.id == item.id }
        loaded += item
        persist(loaded)
        return item
    }

    fun listLogs(): List<DiagnosticsLogItem> {
        val loaded = ensureLoaded()
        val available = loaded.filter { artifactFileOrNull(it)?.exists() == true }
        if (available.size != loaded.size) {
            logs = available.toMutableList()
            persist(available)
        }
        return available.sortedByDescending { it.createdAtMillis }
    }

    fun getArtifactFile(item: DiagnosticsLogItem): File? = artifactFileOrNull(item)?.takeIf { it.exists() }

    fun deleteLog(id: String): Boolean = deleteLogs(setOf(id)) > 0

    fun deleteLogs(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val loaded = ensureLoaded()
        val selected = loaded.filter { it.id in ids }
        selected.forEach { artifactFileOrNull(it)?.delete() }
        if (selected.isNotEmpty()) {
            loaded.removeAll { it.id in ids }
            persist(loaded)
        }
        return selected.size
    }

    fun cleanup(nowMillis: Long = System.currentTimeMillis()): Int {
        val available = listLogs()
        val newestIds = available
            .sortedByDescending { it.createdAtMillis }
            .take(RETENTION_NEWEST_KEEP_COUNT)
            .map { it.id }
            .toSet()
        val cutoffMillis = nowMillis - RETENTION_DAYS_MILLIS
        val expiredIds = available
            .filter { it.createdAtMillis < cutoffMillis && it.id !in newestIds }
            .map { it.id }
            .toSet()
        return deleteLogs(expiredIds)
    }

    fun clearBundleCache(): Boolean {
        if (!bundleCacheDirectory.exists()) return true
        return bundleCacheDirectory.deleteRecursively()
    }

    private fun ensureLoaded(): MutableList<DiagnosticsLogItem> {
        logs?.let { return it }
        val loaded = try {
            val content = metadataFile.takeIf { it.exists() }?.readText()
            if (content.isNullOrBlank()) {
                mutableListOf()
            } else {
                json.decodeFromString(metadataSerializer, content).toMutableList()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load diagnostics metadata, starting fresh: ${error.message}")
            mutableListOf()
        }
        logs = loaded
        return loaded
    }

    private fun persist(items: List<DiagnosticsLogItem>) {
        try {
            ensureDirectory(diagnosticsDirectory)
            metadataFile.writeText(json.encodeToString(metadataSerializer, items))
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist diagnostics metadata: ${error.message}")
        }
    }

    private fun artifactFileOrNull(item: DiagnosticsLogItem): File? {
        val file = File(context.filesDir, item.relativePath)
        val root = diagnosticsDirectory.canonicalFile
        val candidate = file.canonicalFile
        return candidate.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            error("Failed to create diagnostics directory: ${directory.absolutePath}")
        }
    }

    private fun String.sanitizedExtension(defaultExtension: String): String {
        val sanitized = trim().trimStart('.').filter { it.isLetterOrDigit() }
        return sanitized.ifBlank { defaultExtension }
    }
}
