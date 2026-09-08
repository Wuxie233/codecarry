package dev.wuxie233.codecarry.ui.screens.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageMarkdownSelectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectAllIncludesProseOnBothSidesOfCodeBlock() {
        val toolbar = CapturingTextToolbar()
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    MessageMarkdownContent(
                        markdown = "First paragraph\n\n```text\ncode owns its selection\n```\n\nLast paragraph",
                        textColor = MaterialTheme.colorScheme.onSurface,
                        isUser = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        rule.onNodeWithText("First paragraph").performTouchInput { longClick() }
        rule.waitUntil(5_000) { toolbar.selectAll != null }
        rule.runOnIdle { toolbar.selectAll!!.invoke() }
        rule.waitUntil(5_000) { toolbar.copy != null }
        rule.runOnIdle { toolbar.copy!!.invoke() }
        rule.runOnIdle {
            val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).coerceToText(rule.activity).toString()
            assertTrue(copied.contains("First paragraph"))
            assertTrue(copied.contains("Last paragraph"))
        }
    }

    private class CapturingTextToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var selectAll: (() -> Unit)? = null
        var copy: (() -> Unit)? = null
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            status = TextToolbarStatus.Shown
            selectAll = onSelectAllRequested
            copy = onCopyRequested
        }
    }
}
