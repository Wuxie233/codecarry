package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.ImeAction
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class CodexComposerInputTest {
    @get:Rule val rule = createComposeRule()

    @Test fun enterKeepsDraftAndInsertsNewline() {
        val draft = mutableStateOf("")
        rule.setContent {
            MaterialTheme {
                CodexComposerTextField(draft.value, { draft.value = it }, "Message", Modifier.testTag("composer"))
            }
        }
        val field = rule.onNodeWithTag("composer")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
        field.performClick().performTextInput("first line")
        field.performKeyInput { pressKey(Key.Enter) }
        field.performTextInput("second line")
        field.assertTextEquals("first line\nsecond line")
    }
}
