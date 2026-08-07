package dev.wuxie233.codecarry.data.repository

import dev.wuxie233.codecarry.domain.model.GitHubRelease

object AppUpdateLogic {
    const val DEFAULT_LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/Wuxie233/oc-remote/releases/latest"

    fun normalizeReleaseTag(tag: String?): String? {
        val normalized = tag
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.trim()

        return normalized?.takeIf { it.isNotEmpty() }
    }

    fun isRemoteVersionNewer(currentVersion: String, remoteTag: String): Boolean {
        val currentParts = versionParts(normalizeReleaseTag(currentVersion))
        val remoteParts = versionParts(normalizeReleaseTag(remoteTag))
        val maxLength = maxOf(currentParts.size, remoteParts.size)

        for (index in 0 until maxLength) {
            val current = currentParts.getOrElse(index) { 0 }
            val remote = remoteParts.getOrElse(index) { 0 }

            if (remote != current) {
                return remote > current
            }
        }

        return false
    }

    fun selectApkAsset(
        assets: List<GitHubRelease.Asset>,
        preferDebug: Boolean,
    ): GitHubRelease.Asset? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) {
            return null
        }

        val debugAsset = apkAssets.firstOrNull { isDebugApkName(it.name) }
        val releaseAsset = apkAssets.firstOrNull { !isDebugApkName(it.name) }

        return if (preferDebug) {
            debugAsset ?: releaseAsset ?: apkAssets.first()
        } else {
            releaseAsset ?: debugAsset ?: apkAssets.first()
        }
    }

    fun resolveLatestReleaseApiUrl(
        debugOverrideUrl: String?,
        isDebugBuild: Boolean,
        defaultUrl: String = DEFAULT_LATEST_RELEASE_API_URL,
    ): String {
        val overrideUrl = debugOverrideUrl?.trim().orEmpty()
        return if (isDebugBuild && overrideUrl.isNotEmpty()) overrideUrl else defaultUrl
    }

    private fun versionParts(version: String?): List<Int> {
        if (version.isNullOrBlank()) {
            return emptyList()
        }

        return VERSION_SPLIT_REGEX
            .split(version)
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
    }

    private fun isDebugApkName(name: String): Boolean {
        return name.contains("debug", ignoreCase = true)
    }

    private val VERSION_SPLIT_REGEX = Regex("[^0-9]+")
}
