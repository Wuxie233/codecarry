package dev.wuxie233.codecarry.ui.screens.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Differential expectations copied from the existing Codex skill composer, not the old DSH popup. */
@RunWith(RobolectricTestRunner::class)
class DshSkillCodexParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val skill = DshSkillEntry("to-spec", "Write a specification", modelInvocable = true)

    @Test fun separatorOmittingSearchWorksLikeCodex() {
        val state = DshSkillCandidateState(available = true, skills = listOf(skill))
        assertEquals(listOf(skill), state.matching("tospec"))
        assertEquals(listOf(skill), state.matching("specification"))
    }

    @Test fun chineseProseAndQueryCompositionWorkLikeCodex() {
        val text = "请用/tospec"
        val value = TextFieldValue(text, TextRange(text.length), TextRange(3, text.length))
        assertEquals("tospec", dshSkillTarget(value)?.query)
    }

    @Test fun candidatesOccupyComposerLayoutInsteadOfFloatingOverIt() {
        val value = mutableStateOf(TextFieldValue("/", TextRange(1)))
        val gate = ChatConfirmationGate()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatConfirmationGate provides gate) {
                    Column(Modifier.fillMaxWidth().testTag("composer-column")) {
                        DshSkillCandidates(value.value, DshSkillCandidateState(true, skills = listOf(skill)),
                            { value.value = it }, {}, onSelectSkill = { true })
                        TextField(value.value, { value.value = it }, Modifier.fillMaxWidth().testTag("draft"))
                    }
                }
            }
        }
        compose.onNodeWithText("to-spec").assertIsDisplayed()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        val row = compose.onNodeWithText("to-spec").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot
        assertTrue("Candidates should reserve space above the composer", row.bottom <= input.top)
    }
}
