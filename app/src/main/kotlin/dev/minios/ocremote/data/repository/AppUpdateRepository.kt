package dev.minios.ocremote.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.domain.model.GitHubRelease
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepository @Inject constructor(
    private val httpClient: HttpClient,
    @ApplicationContext private val context: Context,
) {
    suspend fun fetchLatestRelease(
        apiUrl: String = AppUpdateLogic.DEFAULT_LATEST_RELEASE_API_URL,
    ): GitHubRelease {
        return httpClient.get(apiUrl).body()
    }

    suspend fun downloadApkAsset(
        asset: GitHubRelease.Asset,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): File {
        val updatesDirectory = File(context.cacheDir, "app-updates")
        if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
            error("Failed to create cache directory: ${updatesDirectory.absolutePath}")
        }

        val targetFile = File(updatesDirectory, asset.name)
        val response = httpClient.get(asset.browserDownloadUrl)
        val totalBytes = response.contentLength()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8 * 1024)
        var downloadedBytes = 0L

        targetFile.outputStream().buffered().use { output ->
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read > 0) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    onProgress(downloadedBytes, totalBytes)
                }
            }
        }
        return targetFile
    }
}
