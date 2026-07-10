package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal enum class ChatOverflowContentKind {
    MarkdownParagraph,
    PlainText,
    CodeBlock,
    Table,
    WebViewProse,
    WebViewStructuredBlock,
}

internal enum class ChatOverflowTreatment {
    Wrap,
    HorizontalScroll,
}

internal object ChatOverflowPolicy {
    internal const val WideAsciiThreshold = 28
    internal const val WebViewHorizontalScrollClass = "markdown-horizontal-scroll"
    internal const val WebViewTableWrapperClass = "table-scroll"

    private const val WebViewKatexDisplayClass = "katex-display"
    private val markdownFileLinkTarget = Regex("""\]\(/[^\s)]+:[0-9]+\)""")
    private val webViewStructuredScrollSelectors = listOf(
        "pre",
        ".${WebViewTableWrapperClass}",
        ".${WebViewKatexDisplayClass}",
    )

    internal fun resolve(
        kind: ChatOverflowContentKind,
        text: String = "",
        codeWordWrap: Boolean = false,
    ): ChatOverflowTreatment {
        return when (kind) {
            ChatOverflowContentKind.MarkdownParagraph,
            ChatOverflowContentKind.PlainText,
            -> if (containsWideAsciiToken(text)) {
                ChatOverflowTreatment.HorizontalScroll
            } else {
                ChatOverflowTreatment.Wrap
            }

            ChatOverflowContentKind.CodeBlock -> if (codeWordWrap) {
                ChatOverflowTreatment.Wrap
            } else {
                ChatOverflowTreatment.HorizontalScroll
            }

            ChatOverflowContentKind.Table,
            ChatOverflowContentKind.WebViewStructuredBlock,
            -> ChatOverflowTreatment.HorizontalScroll

            ChatOverflowContentKind.WebViewProse -> ChatOverflowTreatment.Wrap
        }
    }

    internal fun shouldUseHorizontalScroll(
        kind: ChatOverflowContentKind,
        text: String = "",
        codeWordWrap: Boolean = false,
    ): Boolean {
        return resolve(kind = kind, text = text, codeWordWrap = codeWordWrap) == ChatOverflowTreatment.HorizontalScroll
    }

    internal fun containsWideAsciiToken(text: String, threshold: Int = WideAsciiThreshold): Boolean {
        val sanitized = markdownFileLinkTarget.replace(text, "](/link)")
        var run = 0
        sanitized.forEach { char ->
            run = if (char.code in 0x21..0x7E) run + 1 else 0
            if (run >= threshold) return true
        }
        return false
    }

    internal fun webViewOverflowClass(kind: ChatOverflowContentKind): String? {
        return when (resolve(kind = kind)) {
            ChatOverflowTreatment.HorizontalScroll -> WebViewHorizontalScrollClass
            ChatOverflowTreatment.Wrap -> null
        }
    }

    internal fun webViewStructuredScrollSelector(): String {
        return webViewStructuredScrollSelectors.joinToString(", ")
    }

    internal fun webViewTableWrapperClasses(): String {
        return listOfNotNull(
            WebViewTableWrapperClass,
            webViewOverflowClass(ChatOverflowContentKind.Table),
        ).joinToString(" ")
    }
}

@Composable
internal fun Modifier.chatCodeOverflow(codeWordWrap: Boolean): Modifier {
    return if (ChatOverflowPolicy.shouldUseHorizontalScroll(
            kind = ChatOverflowContentKind.CodeBlock,
            codeWordWrap = codeWordWrap,
        )
    ) {
        horizontalScroll(rememberScrollState())
    } else {
        this
    }
}
