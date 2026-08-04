package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableLayoutTest {

    @Test
    fun narrowLabelColumnShrinksBelowLegacyFixedWidth() {
        val allocation = allocateMarkdownTableColumnWidths(
            naturalWidthsDp = listOf(88f, 196f),
            viewportWidthDp = 330f,
        )

        assertTrue(allocation.widthsDp[0] < 176f)
        assertTrue(allocation.widthsDp[0] >= 80f)
    }

    @Test
    fun longTextColumnExceedsLegacyFixedWidth() {
        val allocation = allocateMarkdownTableColumnWidths(
            naturalWidthsDp = listOf(96f, 242f),
            viewportWidthDp = 330f,
        )

        assertTrue(allocation.widthsDp[1] > 176f)
        assertTrue(allocation.widthsDp[1] <= 280f)
    }

    @Test
    fun realThreeColumnChinesePayloadKeepsOverflowWhenNaturalContentRequiresIt() {
        val payload = """
            | 模块 | 状态 | 说明 |
            | --- | --- | --- |
            | 连接 | 已连接 | 正在接收来自远程工作区的实时事件流 |
            | 会话 | 运行中 | 当前回复包含较长的中文上下文和工具调用结果 |
            | 权限 | 待确认 | 请确认是否允许访问项目目录中的相关文件 |
            | 输出 | 已完成 | 结果已经整理完成，可以继续查看后续内容 |
        """.trimIndent()
        val table = planMarkdownDocument(parseMarkdownDocument(payload).getOrThrow())
            .blocks.single().table!!
        val naturalWidthsDp = table.header.indices.map { columnIndex ->
            table.header.indices
                .map { rowIndex ->
                    val cell = if (rowIndex == 0) {
                        table.header[columnIndex]
                    } else {
                        table.rows[rowIndex - 1][columnIndex]
                    }
                    cell.length * 7f + 24f
                }
                .max()
        }
        val allocation = allocateMarkdownTableColumnWidths(naturalWidthsDp, viewportWidthDp = 330f)

        assertTrue(table.header.size == 3)
        assertTrue(allocation.totalWidthDp > 330f)
    }
}
