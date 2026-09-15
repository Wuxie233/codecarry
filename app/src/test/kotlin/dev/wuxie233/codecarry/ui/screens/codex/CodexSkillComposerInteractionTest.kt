package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.wuxie233.codecarry.data.codex.CodexSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class CodexSkillComposerInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun movingCursorIntoDraftOffersSkillAndKeepsSurroundingText() {
        val input = mutableStateOf(TextFieldValue("Before  after"))
        var selected: CodexSkill? = null
        compose.setContent {
            MaterialTheme {
                Column {
                    codexSkillCompletionTarget(input.value)?.let { target ->
                        CodexSlashSkillList(target.query,
                            listOf(CodexSkill("to-spec", "Write a spec", null, "/skills/to-spec", true)),
                            false, null, true, {}, {
                                selected = it
                                input.value = target.consume(input.value)
                            })
                    }
                    CodexComposerTextField(input.value, { input.value = it }, "Message", Modifier.testTag("composer"))
                }
            }
        }
        val field = compose.onNodeWithTag("composer")
        field.performClick()
        field.performTextInputSelection(TextRange(7))
        field.performTextInput("/tospec")
        compose.onNodeWithText("to-spec").assertIsDisplayed().performClick()
        field.assertTextEquals("Before  after")
        compose.runOnIdle {
            assertEquals(TextRange(7), input.value.selection)
            assertEquals("/skills/to-spec", selected?.path)
        }
    }

    @Test
    fun urlsPathsAndSelectedTextAreNotSkillQueries() {
        for (text in listOf("https://host/review", "/workspace/src", "./review", "foo/review", "/README.md")) {
            assertNull(text, codexSkillCompletionTarget(TextFieldValue(text, TextRange(text.length))))
        }
        assertNull(codexSkillCompletionTarget(TextFieldValue("/review", TextRange(1, 4))))
        assertNull(codexSkillCompletionTarget(TextFieldValue("/review", TextRange(0))))
    }

    @Test
    fun chineseProseAndImeCompositionKeepAnEditableQuery() {
        val input = TextFieldValue("请用/tospec", TextRange(9), TextRange(3, 9))
        val target = requireNotNull(codexSkillCompletionTarget(input))
        assertEquals("tospec", target.query)
        assertEquals(TextFieldValue("请用", TextRange(2)), target.consume(input))
        assertNull(codexSkillCompletionTarget(input.copy(composition = TextRange(0, 9))))
    }
}
