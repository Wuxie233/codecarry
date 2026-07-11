package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatOverflowPolicyTest {
    @Test
    fun `bare http urls wrap in markdown paragraphs`() {
        assertMarkdownParagraphWraps(
            "v1.7.42 released:\n\nhttps://github.com/Wuxie233/oc-remote/releases/tag/v1.7.42",
        )
        assertMarkdownParagraphWraps(
            "See (http://example.com/${"release-segment/".repeat(4)}notes), then continue.",
        )
    }

    @Test
    fun `bare http urls wrap in plain text`() {
        assertWraps(
            kind = ChatOverflowContentKind.PlainText,
            text = "http://example.com/${"release-segment/".repeat(4)}notes",
        )
        assertWraps(
            kind = ChatOverflowContentKind.PlainText,
            text = "https://github.com/Wuxie233/oc-remote/releases/tag/v1.7.42",
        )
    }

    @Test
    fun `markdown links with long targets wrap`() {
        assertMarkdownParagraphWraps(
            "[Release notes](https://github.com/Wuxie233/oc-remote/releases/tag/v1.7.42)",
        )
    }

    @Test
    fun `markdown links with optional titles wrap`() {
        val longTitle = "abcdefghijklmnopqrstuvwxyz0123456789"

        assertMarkdownParagraphWraps("[Release](https://example.com \"$longTitle\")")
        assertMarkdownParagraphWraps("[Release](https://example.com '$longTitle')")
        assertMarkdownParagraphWraps("[Release](https://example.com ($longTitle))")
        assertMarkdownParagraphWraps("[Release](<https://example.com/releases/current> \"$longTitle\")")
    }

    @Test
    fun `markdown file links remain wrapped`() {
        assertMarkdownParagraphWraps(
            "[DispatchAsync](/root/CODE/RimWorld/RimWorldMod_RimWorldAI/RimWorldMCP/McpCommandQueue.cs:72)",
        )
    }

    @Test
    fun `docker image references inside ordinary prose wrap`() {
        val text = "部署数据库时请使用 registry.example.com/team/postgres:18-alpine 镜像，完成后继续检查服务状态。"

        assertMarkdownParagraphWraps(text)
        assertWraps(kind = ChatOverflowContentKind.PlainText, text = text)
    }

    @Test
    fun `version tokens inside ordinary prose wrap`() {
        val text = "当前版本是 1.151-regression-fix-202607111622，请更新后重新打开会话。"

        assertMarkdownParagraphWraps(text)
        assertWraps(kind = ChatOverflowContentKind.PlainText, text = text)
    }

    @Test
    fun `non link wide ascii tokens remain horizontally scrollable`() {
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = "sha256:${"0123456789abcdef".repeat(4)}",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = "https://example.com/release ${"abcdef0123456789".repeat(4)}",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = "[Release](https://example.com/release) ${"abcdef0123456789".repeat(4)}",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = "[Release](https://example.com \"hidden title\") ${"abcdef0123456789".repeat(4)}",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.MarkdownParagraph,
                text = "[Release](https://example.com 'hidden title'),${"abcdef0123456789".repeat(4)}",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.PlainText,
                text = "https://example.com/release ${"abcdef0123456789".repeat(4)}",
            ),
        )
        assertEquals(
            ChatOverflowTreatment.HorizontalScroll,
            ChatOverflowPolicy.resolve(
                kind = ChatOverflowContentKind.PlainText,
                text = "https://example.com/release,${"abcdef0123456789".repeat(4)}",
            ),
        )
    }

    private fun assertMarkdownParagraphWraps(text: String) {
        assertWraps(kind = ChatOverflowContentKind.MarkdownParagraph, text = text)
    }

    private fun assertWraps(kind: ChatOverflowContentKind, text: String) {
        assertEquals(
            ChatOverflowTreatment.Wrap,
            ChatOverflowPolicy.resolve(
                kind = kind,
                text = text,
            ),
        )
    }
}
