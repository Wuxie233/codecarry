package dev.wuxie233.codecarry.data.repository

import dev.wuxie233.codecarry.domain.model.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateLogicTest {

    @Test
    fun normalizeReleaseTagStripsLeadingVAndWhitespace() {
        assertEquals("1.6.15", AppUpdateLogic.normalizeReleaseTag("  v1.6.15  "))
    }

    @Test
    fun normalizeReleaseTagReturnsNullForBlankInput() {
        assertNull(AppUpdateLogic.normalizeReleaseTag("   "))
    }

    @Test
    fun isRemoteVersionNewerReturnsTrueWhenRemoteVersionIsHigher() {
        assertTrue(AppUpdateLogic.isRemoteVersionNewer(currentVersion = "1.6.14", remoteTag = "v1.6.15"))
    }

    @Test
    fun isRemoteVersionNewerReturnsFalseWhenVersionsAreEqual() {
        assertFalse(AppUpdateLogic.isRemoteVersionNewer(currentVersion = "1.6.14", remoteTag = "1.6.14"))
    }

    @Test
    fun selectApkAssetPrefersDebugApkWhenRequested() {
        val releaseAsset = asset(name = "codecarry-release.apk")
        val debugAsset = asset(name = "codecarry-debug.apk")

        val selected = AppUpdateLogic.selectApkAsset(
            assets = listOf(
                asset(name = "checksums.txt"),
                releaseAsset,
                debugAsset,
            ),
            preferDebug = true,
        )

        assertEquals(debugAsset, selected)
    }

    @Test
    fun selectApkAssetPrefersNonDebugApkByDefault() {
        val debugAsset = asset(name = "codecarry-debug.apk")
        val releaseAsset = asset(name = "codecarry-release.apk")

        val selected = AppUpdateLogic.selectApkAsset(
            assets = listOf(debugAsset, releaseAsset),
            preferDebug = false,
        )

        assertEquals(releaseAsset, selected)
    }

    @Test
    fun resolveLatestReleaseApiUrlUsesOverrideOnlyForDebugBuilds() {
        val overrideUrl = "https://example.test/releases/latest"

        assertEquals(
            overrideUrl,
            AppUpdateLogic.resolveLatestReleaseApiUrl(
                debugOverrideUrl = overrideUrl,
                isDebugBuild = true,
            )
        )
        assertEquals(
            AppUpdateLogic.DEFAULT_LATEST_RELEASE_API_URL,
            AppUpdateLogic.resolveLatestReleaseApiUrl(
                debugOverrideUrl = overrideUrl,
                isDebugBuild = false,
            )
        )
        assertEquals(
            AppUpdateLogic.DEFAULT_LATEST_RELEASE_API_URL,
            AppUpdateLogic.resolveLatestReleaseApiUrl(
                debugOverrideUrl = "   ",
                isDebugBuild = true,
            )
        )
    }

    private fun asset(name: String): GitHubRelease.Asset {
        return GitHubRelease.Asset(
            name = name,
            browserDownloadUrl = "https://example.test/$name",
        )
    }
}
