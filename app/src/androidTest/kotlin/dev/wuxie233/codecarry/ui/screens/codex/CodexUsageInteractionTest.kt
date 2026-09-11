package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexAccountUsageState
import dev.wuxie233.codecarry.data.codex.CodexUsageBucket
import dev.wuxie233.codecarry.data.codex.CodexUsageWindow
import java.text.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexUsageInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fastToggleChangesSelectionAndUnavailableIsDisabled() {
        rule.setContent { MaterialTheme {
            var selected by remember { mutableStateOf(false) }
            CodexFastChip(selected, available = !selected, pending = false, onClick = { selected = true })
        } }
        rule.onNodeWithTag("codex_fast_toggle").performClick().assertIsSelected().assertIsNotEnabled()
    }

    @Test fun quotaActionOpensDetailsWithDynamicWindowsAndRefresh() {
        var refreshes = 0
        val state = CodexAccountUsageState(
            buckets = listOf(CodexUsageBucket("codex", "Codex", CodexUsageWindow(25.0, 180, null), null, null, null)),
            isCurrent = true,
        )
        rule.setContent { MaterialTheme {
            var open by remember { mutableStateOf(false) }
            CodexUsageAction(state) { open = true }
            if (open) CodexUsageSheet(state, { refreshes++ }, { open = false })
        } }
        rule.onNodeWithTag("codex_usage_open").performClick()
        rule.onNodeWithText(context.getString(R.string.codex_usage_hours, 3L)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.codex_usage_remaining, NumberFormat.getPercentInstance().format(0.75))).assertIsDisplayed()
        rule.onNodeWithTag("codex_usage_refresh").performClick()
        rule.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun missingQuotaDoesNotRenderZeroAndFailureCanRefresh() {
        var refreshes = 0
        rule.setContent { MaterialTheme {
            CodexUsageContent(CodexAccountUsageState(errorMessage = "timeout"), { refreshes++ })
        } }
        rule.onNodeWithText(context.getString(R.string.codex_usage_failed)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.codex_usage_remaining, NumberFormat.getPercentInstance().format(0))).assertDoesNotExist()
        rule.onNodeWithTag("codex_usage_refresh").performClick()
        rule.runOnIdle { assertEquals(1, refreshes) }
    }
}
