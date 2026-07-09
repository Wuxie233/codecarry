package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
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

    @Test
    fun assistantParagraphWithMediumInlineCodeTokensCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = """
                            已完成安装和验证。

                            `opencode debug skill` 已能列出全部 10 个新 skill；每个目录都只有 1 个 `SKILL.md`，且 frontmatter `name` 和目录名匹配。最后确认 `debug skill` 总计输出 80 个 skill，stderr 为空。没有改 `opencode.json` 或 `oh-my-openagent.json`，因为 OpenCode 本来就会扫描 `~/.config/opencode/skills/**/SKILL.md`。
                        """.trimIndent(),
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
        val changed = changedPixels(before, after)

        assertTrue(
            "expected horizontal drag to shift assistant markdown with medium inline-code tokens, changed=$changed",
            changed > 250,
        )
    }

    @Test
    fun wideReasoningBlockCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .testTag(ReasoningTag)
                        .width(220.dp),
                ) {
                    ReasoningBlock("→ ${"abcdefghijklmnopqrstuvwxyz".repeat(8)}")
                }
            }
        }

        val node = rule.onNodeWithTag(ReasoningTag)
        val before = node.captureToImage()
        rule.onNodeWithTag(WidePlainTextTag).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = node.captureToImage()
        val changed = changedPixels(before, after)

        assertTrue(
            "expected horizontal drag to visibly shift wide reasoning content, changed=$changed",
            changed > 250,
        )
    }

    @Test
    fun wideCodeBlockCanBeDraggedHorizontally() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChatFontSize provides "medium",
                    LocalCodeWordWrap provides false,
                ) {
                    MessageMarkdownContent(
                        markdown = """
                            Real response:

                            ```text
                            /root/CODE/hackathon/pulse/docs/design-system.md -> ${"0123456789abcdef".repeat(12)}
                            ```
                        """.trimIndent(),
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
        val changed = changedPixels(before, after)

        assertTrue(
            "expected horizontal drag to visibly shift wide code block content, changed=$changed",
            changed > 250,
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
        const val ReasoningTag = "wide-reasoning-message"
    }
}
