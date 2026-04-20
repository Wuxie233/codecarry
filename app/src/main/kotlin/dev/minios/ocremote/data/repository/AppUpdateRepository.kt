package dev.minios.ocremote.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.domain.model.GitHubRelease
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
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

    suspend fun downloadApkAsset(asset: GitHubRelease.Asset): File {
        val updatesDirectory = File(context.cacheDir, "app-updates")
        if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
            error("Failed to create cache directory: ${updatesDirectory.absolutePath}")
        }

        val targetFile = File(updatesDirectory, asset.name)
        val apkBytes: ByteArray = httpClient.get(asset.browserDownloadUrl).body()
        targetFile.writeBytes(apkBytes)
        return targetFile
    }
}
