package dev.wuxie233.codecarry.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import dev.wuxie233.codecarry.data.dsh.DshAgentPresetEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w400dp-h800dp-mdpi")
class DshPresetPickerSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `roster shows fallback names descriptions default and disabled broken reason`() {
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                DshPresetPickerSheet(
                    presets = listOf(
                        DshAgentPresetEntry("standard", true),
                        DshAgentPresetEntry("custom", false, "Custom", "Build applications", "Missing plugin"),
                    ),
                    selectedId = null, loading = false, error = null,
                    onSelect = { selected = it }, onRefresh = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Host default").assertExists()
        compose.onNodeWithText("Custom").assertExists()
        compose.onNodeWithText("Build applications").assertExists()
        compose.onNodeWithText("Missing plugin", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("standard").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("standard", selected) }
    }

    @Test
    fun `load failure offers retry without an empty roster message`() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                DshPresetPickerSheet(
                    presets = emptyList(), selectedId = null, loading = false,
                    error = "Host unavailable", onSelect = {},
                    onRefresh = { retries++ }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Host unavailable").assertExists()
        compose.onNodeWithText("No presets available").assertDoesNotExist()
        compose.onNodeWithText("Refresh").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
