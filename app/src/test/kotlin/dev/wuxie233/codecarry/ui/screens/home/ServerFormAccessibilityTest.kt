package dev.wuxie233.codecarry.ui.screens.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerFormAccessibilityTest {

    @Test
    fun serverDialog_textFieldsHaveExplicitContentDescriptionsDerivedFromLabels() {
        val source = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/home/ServerDialog.kt").readText()

        listOf("nameLabel", "urlLabel", "usernameLabel", "passwordLabel", "tokenLabel").forEach { labelVariable ->
            assertTrue(
                "$labelVariable should be reused as an explicit text-field contentDescription",
                source.contains("contentDescription = $labelVariable"),
            )
        }
        assertTrue(source.contains("saveServerDescription"))
    }

    @Test
    fun settingsLocalServerFieldsHaveExplicitContentDescriptionsDerivedFromLabels() {
        val source = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/settings/SettingsScreen.kt").readText()

        listOf(
            "localServerUsernameLabel",
            "localServerPasswordLabel",
            "proxyUrlLabel",
            "noProxyLabel",
            "startupTimeoutLabel",
        ).forEach { labelVariable ->
            assertTrue(
                "$labelVariable should be reused as an explicit settings field contentDescription",
                source.contains("contentDescription = $labelVariable"),
            )
        }
        assertTrue(source.contains("localLaunchSaveDescription"))
    }

    @Test
    fun connectionErrorsExposePersistentInlineRetryAffordance() {
        val homeSource = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/home/HomeScreen.kt").readText()
        val providersSource = File("src/main/kotlin/dev/wuxie233/codecarry/ui/screens/server/ServerProvidersScreen.kt").readText()

        assertTrue(providersSource.contains("contentDescription = apiKeyLabel"))
        assertTrue(providersSource.contains("contentDescription = oauthCodeLabel"))
        assertTrue(homeSource.contains("retryConnectDescription"))
        assertTrue(homeSource.contains("onClick = onConnect"))
        assertTrue(providersSource.contains("ServerSettingsErrorRegion"))
        assertTrue(providersSource.contains("onRetry = viewModel::loadProviders"))
        assertTrue(providersSource.contains("stringResource(R.string.retry)"))
    }
}
