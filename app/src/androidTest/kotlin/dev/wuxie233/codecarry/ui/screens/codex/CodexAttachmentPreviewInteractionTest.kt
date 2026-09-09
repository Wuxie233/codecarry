package dev.wuxie233.codecarry.ui.screens.codex

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexUserInput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CodexAttachmentPreviewInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val removed = mutableListOf<String>()
    private val closeDescription: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.close)

    @Test fun tappingImageOpensPreviewAndClosingPreservesAttachment() {
        showAttachments()
        rule.onNodeWithContentDescription("Screenshot", useUnmergedTree = true)
            .performTouchInput { click() }
        rule.onNodeWithContentDescription(closeDescription).assertIsDisplayed()
            .performTouchInput { click() }
        rule.onNodeWithContentDescription(closeDescription).assertDoesNotExist()
        rule.onNodeWithTag("codex_attachment:image").assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<String>(), removed) }
    }

    @Test fun tappingRemoveButtonOnlyRemovesItsAttachment() {
        showAttachments()
        rule.onNodeWithTag("codex_attachment_remove:image", useUnmergedTree = true)
            .performTouchInput { click() }
        rule.onNodeWithContentDescription(closeDescription).assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("image"), removed) }
    }

    @Test fun disabledAttachmentCannotBeRemovedByItsButton() {
        showAttachments(enabled = false)
        rule.onNodeWithTag("codex_attachment_remove:image", useUnmergedTree = true)
            .performTouchInput { click() }
        rule.onNodeWithText("Screenshot").performTouchInput { click() }
        rule.onNodeWithContentDescription(closeDescription).assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList<String>(), removed) }
    }

    private fun showAttachments(enabled: Boolean = true) {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        val attachments = listOf(
            CodexComposerAttachment("image", "Screenshot", CodexUserInput.Image("unused"), bytes),
            CodexComposerAttachment("other", "Other file", CodexUserInput.Mention("Other file", "/other")),
        )
        rule.setContent {
            MaterialTheme {
                CodexAttachmentChips(attachments, enabled, onRemove = { removed.add(it) })
            }
        }
    }
}
