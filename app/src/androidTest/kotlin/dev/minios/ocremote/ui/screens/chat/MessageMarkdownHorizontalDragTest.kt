package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageMarkdownHorizontalDragTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun wideAssistantParagraphCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = "→ ${"abcdefghijklmnopqrstuvwxyz".repeat(8)}",
                        textColor = Color.Black,
                        isUser = false,
                        modifier = Modifier
                            .testTag(MessageTag)
                            .width(220.dp)
                            .padding(8.dp),
                    )
                }
            }
        }

        val node = rule.onNodeWithTag(MessageTag)
        val before = node.captureToImage()
        node.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = node.captureToImage()

        assertTrue(
            "expected horizontal drag to visibly shift wide markdown content",
            changedPixels(before, after) > 250,
        )
    }

    private fun changedPixels(before: ImageBitmap, after: ImageBitmap): Int {
        val beforePixels = before.toPixelMap()
        val afterPixels = after.toPixelMap()
        var changed = 0
        for (x in 0 until minOf(before.width, after.width) step 4) {
            for (y in 0 until minOf(before.height, after.height) step 4) {
                if (beforePixels[x, y].toArgb() != afterPixels[x, y].toArgb()) {
                    changed++
                }
            }
        }
        return changed
    }

    private companion object {
        const val MessageTag = "wide-markdown-message"
    }
}
