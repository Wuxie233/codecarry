package dev.wuxie233.codecarry.ui.screens.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.data.codex.CodexSkill
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import dev.wuxie233.codecarry.ui.screens.codex.CodexSlashSkillList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Compose rendering of the shared control, not an illustration or a phone screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkillCandidateVisualParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val skills = listOf(
        DshSkillEntry("to-spec", "Turn a conversation into a buildable spec", modelInvocable = true),
        DshSkillEntry("grill-with-docs", "Clarify requirements and retain decisions", modelInvocable = true),
        DshSkillEntry("code-review", "Review changes against standards and spec", modelInvocable = true),
    )
    private val draft = TextFieldValue("/", TextRange(1))

    @Test fun dshCandidatesUseComposerWidthAndSupportRealScrolling() {
        val catalog = androidx.compose.runtime.mutableStateOf(skills)
        render {
            DshSkillCandidates(draft, DshSkillCandidateState(true, skills = catalog.value), {}, {}, onSelectSkill = { true })
        }
        assertInline()
        capture("dsh-skill-candidates.png")
        compose.runOnIdle {
            catalog.value = skills + (1..30).map { DshSkillEntry("skill-$it", "Description $it", modelInvocable = true) }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("skill-30"))
        compose.onNodeWithText("skill-30").assertIsDisplayed()
    }

    @Test fun codexReferenceUsesTheSameComposerWidthAndPanel() {
        render {
            CodexSlashSkillList("", skills.map { CodexSkill(it.name, it.description, null, "/skills/${it.name}", true) },
                false, null, true, {}, {})
        }
        assertInline()
        capture("codex-skill-candidates.png")
    }

    @Test fun dshSelectedChipsStayOutsideTheUnchangedMessageBody() {
        val value = TextFieldValue("Please review this change.")
        compose.setContent {
            dev.wuxie233.codecarry.ui.theme.OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp).testTag("capture")) {
                    DshSelectedSkillChips(listOf("to-spec", "code-review"), true, {})
                    OutlinedTextField(value, {}, Modifier.fillMaxWidth().testTag("draft"))
                }
            }
        }
        compose.onNodeWithTag("draft").assertTextEquals("Please review this change.")
        compose.onNodeWithTag("dsh_skill:to-spec").assertIsDisplayed()
        compose.onNodeWithTag("dsh_skill:code-review").assertIsDisplayed()
        capture("dsh-selected-skills.png")
    }

    private fun render(panel: @Composable () -> Unit) {
        compose.setContent {
            dev.wuxie233.codecarry.ui.theme.OpenCodeTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalChatConfirmationGate provides ChatConfirmationGate()) {
                    Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp).testTag("capture")) {
                        panel()
                        OutlinedTextField(draft, {}, Modifier.fillMaxWidth().testTag("draft"))
                    }
                }
            }
        }
    }

    private fun assertInline() {
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        val panel = compose.onNodeWithTag("skill-candidate-panel").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot
        assertEquals(input.width, panel.width, 1f)
        assertTrue(panel.bottom <= input.top)
    }

    private fun capture(name: String) {
        // Optional local artifacts; CI only needs the actual geometry and interaction assertions.
        val directory = System.getenv("CODECARRY_SKILL_SCREENSHOT_DIR") ?: return
        val output = File(directory, name)
        output.parentFile.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}
