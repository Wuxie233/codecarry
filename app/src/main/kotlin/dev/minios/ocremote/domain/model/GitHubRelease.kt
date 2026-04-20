package dev.minios.ocremote.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<Asset> = emptyList(),
    @SerialName("html_url")
    val htmlUrl: String? = null,
) {
    @Serializable
    data class Asset(
        val name: String,
        @SerialName("browser_download_url")
        val browserDownloadUrl: String,
        @SerialName("content_type")
        val contentType: String? = null,
        val size: Long? = null,
    )
}
