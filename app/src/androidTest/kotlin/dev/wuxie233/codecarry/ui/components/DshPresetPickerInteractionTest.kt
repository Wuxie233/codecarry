package dev.wuxie233.codecarry.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.wuxie233.codecarry.data.dsh.DshAgentPresetEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DshPresetPickerInteractionTest {
    @get:Rule val rule = createComposeRule()

    private val presets = listOf(
        DshAgentPresetEntry("coding", "trusted", true, "Coding", "Implement features"),
        DshAgentPresetEntry("review", "trusted", false, "Review", "Inspect security"),
        DshAgentPresetEntry("broken", "trusted", false, "Unavailable", broken = "Missing model"),
    )

    @Test fun searchByDescriptionSelectsMatchingPreset() {
        val selections = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme {
                DshPresetPickerSheet(presets, "coding", false, null,
                    onSelect = { selections.add(it) }, onRefresh = {}, onDismiss = {})
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("security")
        rule.onNodeWithText("Coding").assertDoesNotExist()
        rule.onNodeWithText("Review").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("review"), selections) }
    }

    @Test fun brokenPresetCannotBeSelected() {
        val selections = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme {
                DshPresetPickerSheet(presets, "coding", false, null,
                    onSelect = { selections.add(it) }, onRefresh = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Unavailable").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(emptyList<String?>(), selections) }
    }

    @Test fun selectionInFlightDisablesFurtherSubmissions() {
        val selecting = mutableStateOf(false)
        val selections = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme {
                DshPresetPickerSheet(presets, "coding", false, null, selecting = selecting.value,
                    onSelect = { selections.add(it); selecting.value = true },
                    onRefresh = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Review").assertIsNotEnabled()
        rule.onNodeWithText("Coding").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf("review"), selections) }
    }

    @Test fun runningSessionShowsReasonAndDisablesPresetChanges() {
        rule.setContent {
            MaterialTheme {
                DshPresetPickerSheet(presets, "coding", false, null,
                    enabled = false, disabledReason = "Wait for the current turn",
                    onSelect = { error("Running session must not select a preset") },
                    onRefresh = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Wait for the current turn").assertIsDisplayed()
        rule.onNodeWithText("Review").assertIsNotEnabled()
    }
}
