package dev.wuxie233.codecarry.ui.screens.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DshSkillCandidatesInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val skill = DshSkillEntry("grill", "Discuss a plan", modelInvocable = true)

    @Test fun compactCommandConfirmsWithoutEditingDraftAndStaysSeparateFromSkill() {
        val original = TextFieldValue("draft /CO suffix", TextRange(9))
        val input = mutableStateOf(original)
        val state = mutableStateOf(DshSkillCandidateState(true,
            skills = listOf(DshSkillEntry("compact", "A skill", modelInvocable = true))))
        var sends = 0
        val selected = mutableListOf<String>()
        val gate = ChatConfirmationGate()
        setContentWithGate(gate) {
            MaterialTheme { DshSkillCandidates(input.value, state.value, { input.value = it }, {},
                onCompact = { sends++ }, onSelectSkill = { selected.add(it.name); true }) }
        }
        compose.onNodeWithText("/compact").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(commands = listOf(
            dev.wuxie233.codecarry.data.dsh.DshCommandDescriptor("compact", "Compact history"))) }
        compose.onNodeWithText("compact").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("/compact").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("compact").fetchSemanticsNode().boundsInRoot.top)
        compose.runOnIdle { gate.acquire("rewind") }
        compose.onNodeWithText("/compact").assertDoesNotExist()
        compose.runOnIdle { gate.release("rewind") }
        compose.onNodeWithText("/compact").performClick()
        compose.onNodeWithText("compact").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(original, input.value); assertEquals(0, sends); assertTrue(selected.isEmpty())
            assertFalse(gate.acquire("rewind"))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(original, input.value); assertEquals(0, sends) }
        compose.runOnIdle { input.value = TextFieldValue("draft /C suffix", TextRange(8)) }
        compose.onNodeWithText("/compact").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_compact_confirm)).performClick()
        compose.runOnIdle { assertEquals("draft /C suffix", input.value.text); assertEquals(1, sends)
            input.value = TextFieldValue("/com", TextRange(4)) }
        compose.onNodeWithText("compact").performClick()
        compose.runOnIdle {
            assertEquals("", input.value.text); assertEquals(1, sends)
            assertEquals(listOf("compact"), selected)
        }
    }

    @Test fun selectionBecomesRemovableChipKeepsSurroundingsAndDoesNotSend() {
        val input = mutableStateOf(TextFieldValue("before /GR after", TextRange(10)))
        val selected = mutableStateOf(emptyList<String>())
        setContentWithGate {
            MaterialTheme { Column {
                DshSelectedSkillChips(selected.value, true) { name -> selected.value = selected.value - name }
                DshSkillCandidates(input.value, DshSkillCandidateState(true, skills = listOf(skill)),
                    { input.value = it }, {}, onSelectSkill = { candidate ->
                        selected.value = (selected.value + candidate.name).distinct(); true
                    })
                TextField(input.value, { input.value = it }, Modifier.testTag("draft"))
            } }
        }
        compose.onNodeWithText("Discuss a plan").assertIsDisplayed()
        compose.onNodeWithText("grill").performClick()
        compose.runOnIdle { assertEquals("before  after", input.value.text); assertEquals(TextRange(7), input.value.selection) }
        compose.onNodeWithTag("dsh_skill:grill").assertIsDisplayed()
        compose.onNodeWithTag("skill-candidate-panel").assertDoesNotExist()
        compose.onNodeWithTag("dsh_skill_remove:grill").performClick()
        compose.runOnIdle { assertTrue(selected.value.isEmpty()); assertEquals("before  after", input.value.text) }
        compose.runOnIdle { input.value = TextFieldValue("/gr", TextRange(3)) }
        compose.onNodeWithText("grill").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("skill-candidate-panel").assertDoesNotExist()
    }

    @Test fun rejectedSelectionOrDisabledComposerPreservesQuery() {
        val input = mutableStateOf(TextFieldValue("/gr", TextRange(3)))
        val enabled = mutableStateOf(true)
        var attempts = 0
        setContentWithGate {
            MaterialTheme { DshSkillCandidates(input.value,
                DshSkillCandidateState(true, skills = listOf(skill)), { input.value = it }, {},
                onSelectSkill = { attempts++; false }, enabled = enabled.value) }
        }
        compose.onNodeWithText("grill").performClick()
        compose.runOnIdle { assertEquals("/gr", input.value.text); assertEquals(1, attempts); enabled.value = false }
        compose.onNodeWithText("grill").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("/gr", input.value.text); assertEquals(1, attempts) }
    }

    @Test fun onlyUnrelatedImeCompositionHidesRows() {
        val input = mutableStateOf(TextFieldValue("before /gr", TextRange(10), TextRange(0, 6)))
        setContentWithGate {
            MaterialTheme { DshSkillCandidates(input.value,
                DshSkillCandidateState(true, skills = listOf(skill)), { input.value = it }, {},
                onSelectSkill = { true }) }
        }
        compose.onNodeWithText("grill").assertDoesNotExist()
        compose.runOnIdle { input.value = input.value.copy(composition = TextRange(8, 10)) }
        compose.onNodeWithText("grill").assertIsDisplayed()
        compose.runOnIdle { input.value = TextFieldValue("gr", TextRange(2)) }
        compose.onNodeWithText("grill").assertDoesNotExist()
        compose.runOnIdle { assertEquals("gr", input.value.text) }
    }

    @Test fun loadingFailureRetryNoMatchAndUnavailableHaveDistinctPresentation() {
        val input = mutableStateOf(TextFieldValue("/", TextRange(1)))
        val state = mutableStateOf(DshSkillCandidateState(true, loading = true))
        var retries = 0
        setContentWithGate {
            MaterialTheme { DshSkillCandidates(input.value, state.value, { input.value = it },
                { retries++ }, onSelectSkill = { true }) }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        compose.runOnIdle { state.value = DshSkillCandidateState(true, failed = true) }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_skills_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = DshSkillCandidateState(true) }
        compose.onNodeWithText(compose.activity.getString(R.string.dsh_skills_no_match)).assertIsDisplayed()
        compose.runOnIdle { state.value = DshSkillCandidateState() }
        compose.onNodeWithTag("skill-candidate-panel").assertDoesNotExist()
    }

    @Test fun compactRequiresAnExplicitConfirmationOwner() {
        assertThrows(IllegalStateException::class.java) {
            compose.setContent {
                DshSkillCandidates(TextFieldValue("/co", TextRange(3)), DshSkillCandidateState(true), {}, {},
                    onSelectSkill = { true })
            }
            compose.waitForIdle()
        }
    }

    @Test fun rewindRequiresAnExplicitConfirmationOwner() {
        assertThrows(IllegalStateException::class.java) {
            compose.setContent { DshRewindAction("message", false, false) {} }
            compose.waitForIdle()
        }
    }

    private fun setContentWithGate(
        gate: ChatConfirmationGate = ChatConfirmationGate(),
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalChatConfirmationGate provides gate, content = content)
        }
    }
}
