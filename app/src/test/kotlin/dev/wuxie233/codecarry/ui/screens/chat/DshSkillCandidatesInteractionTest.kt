package dev.wuxie233.codecarry.ui.screens.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DshSkillCandidatesInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val skill = DshSkillEntry("grill", "Discuss a plan", modelInvocable = true)

    @Test fun selectingCandidateOnlyEditsDraftAndBackDismisses() {
        val input = mutableStateOf(TextFieldValue("before /GR after", TextRange(10)))
        compose.setContent {
            MaterialTheme { Column {
                DshSkillCandidates(input.value, DshSkillCandidateState(true, skills = listOf(skill)), { input.value = it }, {})
                TextField(input.value, { input.value = it }, Modifier.testTag("draft"))
            } }
        }
        compose.onNodeWithText("Discuss a plan").assertIsDisplayed()
        compose.onNodeWithText("grill").performClick()
        compose.runOnIdle { assertEquals("before /grill  after", input.value.text) }
        compose.onNodeWithText("grill").assertDoesNotExist()
        compose.runOnIdle { input.value = TextFieldValue("/gr", TextRange(3)) }
        compose.onNodeWithText("grill").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("grill").assertDoesNotExist()
    }

    @Test fun deletingSlashAndImeCompositionHideRowsWithoutChangingDraft() {
        val input = mutableStateOf(TextFieldValue("/gr", TextRange(3), TextRange(1, 3)))
        compose.setContent {
            MaterialTheme { DshSkillCandidates(input.value,
                DshSkillCandidateState(true, skills = listOf(skill)), { input.value = it }, {}) }
        }
        compose.onNodeWithText("grill").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("/gr", input.value.text)
            input.value = input.value.copy(composition = null)
        }
        compose.onNodeWithText("grill").assertIsDisplayed()
        compose.runOnIdle { input.value = TextFieldValue("gr", TextRange(2)) }
        compose.onNodeWithText("grill").assertDoesNotExist()
        compose.runOnIdle { assertEquals("gr", input.value.text) }
    }

    @Test fun loadingFailureRetryNoMatchAndUnavailableHaveDistinctPresentation() {
        val input = mutableStateOf(TextFieldValue("/", TextRange(1)))
        val state = mutableStateOf(DshSkillCandidateState(true, loading = true))
        var retries = 0
        compose.setContent {
            MaterialTheme { DshSkillCandidates(input.value, state.value, { input.value = it }, { retries++ }) }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_skills_loading)).assertIsDisplayed()
        compose.runOnIdle { state.value = DshSkillCandidateState(true, failed = true) }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_skills_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = DshSkillCandidateState(true) }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_skills_no_match)).assertIsDisplayed()
        compose.runOnIdle { state.value = DshSkillCandidateState() }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_skills_no_match)).assertDoesNotExist()
    }
}
