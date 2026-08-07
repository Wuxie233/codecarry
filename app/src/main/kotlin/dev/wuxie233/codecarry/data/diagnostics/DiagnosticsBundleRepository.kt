package dev.wuxie233.codecarry.data.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val DIAGNOSTICS_BUNDLE_CONTENT_TYPE = "application/zip"
private const val DIAGNOSTICS_BUNDLE_PREFIX = "codecarry-diagnostics"
private const val BUNDLE_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

@Singleton
class DiagnosticsBundleRepository @Inject constructor(
    private val logRepository: DiagnosticsLogRepository,
) {
    fun createBundle(
        items: List<DiagnosticsLogItem>,
        nowMillis: Long = System.currentTimeMillis(),
    ): DiagnosticsUploadFile {
        require(items.isNotEmpty()) { "At least one diagnostics artifact is required." }
        val artifacts = items.map { item ->
            val file = logRepository.getArtifactFile(item)
            if (file == null || !file.isFile || !file.canRead()) {
                throw DiagnosticsBundleException.MissingArtifact()
            }
            BundledArtifact(item, file)
        }

        val directory = logRepository.bundleCacheDirectory
        ensureDirectory(directory)
        cleanupOldBundles(nowMillis)
        val bundleFile = File(directory, bundleFilename(nowMillis))

        try {
            ZipOutputStream(bundleFile.outputStream().buffered()).use { zip ->
                val usedNames = mutableSetOf<String>()
                artifacts.forEach { artifact ->
                    val entryName = artifact.item.safeEntryName(artifact.file, usedNames)
                    zip.putNextEntry(ZipEntry(entryName))
                    artifact.file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            bundleFile.delete()
            throw error
        }

        return DiagnosticsUploadFile(
            filename = bundleFile.name,
            bytes = bundleFile.readBytes(),
            contentType = DIAGNOSTICS_BUNDLE_CONTENT_TYPE,
        )
    }

    fun cleanupOldBundles(nowMillis: Long = System.currentTimeMillis()): Int {
        val directory = logRepository.bundleCacheDirectory
        if (!directory.exists()) return 0
        val cutoffMillis = nowMillis - BUNDLE_CACHE_MAX_AGE_MILLIS
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoffMillis }
            .count { it.delete() }
    }

    private fun bundleFilename(nowMillis: Long): String = "$DIAGNOSTICS_BUNDLE_PREFIX-${timestamp(nowMillis)}.zip"

    private fun timestamp(nowMillis: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(nowMillis))

    private fun DiagnosticsLogItem.safeEntryName(file: File, usedNames: MutableSet<String>): String {
        val extension = file.extension.sanitizeZipSegment(default = type.defaultFileExtension)
        val baseName = listOf(
            type.storageSegment,
            createdAtMillis.toString(),
            id,
        ).joinToString("-") { it.sanitizeZipSegment(default = "diagnostic") }
        return uniqueName("$baseName.$extension", usedNames)
    }

    private fun uniqueName(candidate: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(candidate)) return candidate
        val extension = candidate.substringAfterLast('.', missingDelimiterValue = "")
        val stem = candidate.substringBeforeLast('.')
        var index = 2
        while (true) {
            val name = if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension"
            if (usedNames.add(name)) return name
            index += 1
        }
    }

    private fun String.sanitizeZipSegment(default: String): String {
        val sanitized = lowercase(Locale.US)
            .map { character ->
                if (character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_') character else '-'
            }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
        return sanitized.ifBlank { default }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            error("Failed to create diagnostics bundle directory: ${directory.absolutePath}")
        }
    }
}

sealed class DiagnosticsBundleException(message: String) : IllegalStateException(DiagnosticsRedactor.redact(message)) {
    class MissingArtifact : DiagnosticsBundleException(
        "A selected diagnostics artifact is no longer available. Refresh diagnostics and try again.",
    )
}

private data class BundledArtifact(
    val item: DiagnosticsLogItem,
    val file: File,
)
