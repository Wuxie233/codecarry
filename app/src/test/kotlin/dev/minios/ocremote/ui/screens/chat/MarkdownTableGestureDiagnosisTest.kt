package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class MarkdownTableGestureDiagnosisTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tableAlone_at288dp_hasScrollRange_andRespondsToHorizontalDrag() =
        assertMeasurement(measureTable(288, insideSelectionContainer = false))

    @Test
    fun tableAlone_at330dp_hasScrollRange_andRespondsToHorizontalDrag() =
        assertMeasurement(measureTable(330, insideSelectionContainer = false))

    @Test
    fun tableAlone_at360dp_hasScrollRange_andRespondsToHorizontalDrag() =
        assertMeasurement(measureTable(360, insideSelectionContainer = false))

    @Test
    fun selectionContainerDisableSelection_at288dp_hasScrollRange_andRespondsToHorizontalDrag() =
        assertMeasurement(measureTable(288, insideSelectionContainer = true))

    @Test
    fun selectionContainerDisableSelection_at330dp_hasScrollRange_andRespondsToHorizontalDrag() =
        assertMeasurement(measureTable(330, insideSelectionContainer = true))

    @Test
    fun selectionContainerDisableSelection_at360dp_hasScrollRange_andRespondsToHorizontalDrag() =
        assertMeasurement(measureTable(360, insideSelectionContainer = true))

    private fun measureTable(widthDp: Int, insideSelectionContainer: Boolean): Measurement {
        val viewportTag = "markdown-table-viewport-$widthDp-${insideSelectionContainer}"
        val contentTag = "markdown-table-content-$widthDp-${insideSelectionContainer}"
        lateinit var scrollState: androidx.compose.foundation.ScrollState
        var measuredContentWidth = -1

        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp)) {
                    if (insideSelectionContainer) {
                        SelectionContainer {
                            DisableSelection {
                                DiagnosticTable(
                                    scrollStateTag = contentTag,
                                    viewportTag = viewportTag,
                                    stateSink = { scrollState = it },
                                    contentWidthSink = { measuredContentWidth = it },
                                )
                            }
                        }
                    } else {
                        DiagnosticTable(
                            scrollStateTag = contentTag,
                            viewportTag = viewportTag,
                            stateSink = { scrollState = it },
                            contentWidthSink = { measuredContentWidth = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val viewportWidth = compose.onNodeWithTag(viewportTag)
            .fetchSemanticsNode().boundsInRoot.width.roundToInt()
        val contentWidth = measuredContentWidth
        val maxValue = scrollState.maxValue
        val expectedMaxValue = contentWidth - viewportWidth
        assertEquals(
            "content must be wider than viewport at ${widthDp}dp",
            expectedMaxValue,
            maxValue,
        )

        compose.onNodeWithTag(viewportTag).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val postDragValue = scrollState.value
        println(
            "MarkdownTableGestureDiagnosis width=${widthDp}dp selection=$insideSelectionContainer " +
                "viewportPx=$viewportWidth contentPx=$contentWidth maxValue=$maxValue postDragValue=$postDragValue",
        )
        return Measurement(widthDp, viewportWidth, contentWidth, expectedMaxValue, maxValue, postDragValue)
    }

    private fun assertMeasurement(measurement: Measurement) {
        assertTrue(
            "table at ${measurement.widthDp}dp must overflow, measured maxValue=${measurement.maxValue}",
            measurement.maxValue > 0,
        )
        assertEquals(
            "maxValue must equal content width minus viewport width at ${measurement.widthDp}dp",
            measurement.expectedMaxValue,
            measurement.maxValue,
        )
        assertTrue(
            "table at ${measurement.widthDp}dp did not move after swipe, value=${measurement.postDragValue}",
            measurement.postDragValue > 0,
        )
    }

    @Composable
    private fun DiagnosticTable(
        scrollStateTag: String,
        viewportTag: String,
        stateSink: (androidx.compose.foundation.ScrollState) -> Unit,
        contentWidthSink: (Int) -> Unit,
    ) {
        val parsed = remember(TABLE_MARKDOWN) { parseMarkdownTable(TABLE_MARKDOWN) }
            ?: error("diagnostic payload must parse as a GFM table")
        val (header, rows) = parsed
        val scrollState = rememberScrollState()
        stateSink(scrollState)
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(viewportTag)
                    .horizontalScroll(scrollState),
            ) {
                Column(
                    modifier = Modifier
                        .testTag(scrollStateTag)
                        .onSizeChanged { contentWidthSink(it.width) }
                        .clip(RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, dividerColor), RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    DiagnosticTableRow(header, isHeader = true, dividerColor = dividerColor)
                    HorizontalDivider(color = dividerColor)
                    rows.forEachIndexed { index, row ->
                        DiagnosticTableRow(row, isHeader = false, dividerColor = dividerColor)
                        if (index != rows.lastIndex) {
                            HorizontalDivider(color = dividerColor)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DiagnosticTableRow(
        cells: List<String>,
        isHeader: Boolean,
        dividerColor: Color,
    ) {
        Row(
            modifier = Modifier
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            repeat(3) { index ->
                if (index > 0) {
                    VerticalDivider(color = dividerColor, modifier = Modifier.fillMaxHeight())
                }
                Text(
                    text = cells.getOrElse(index) { "" },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .width(176.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }

    private data class Measurement(
        val widthDp: Int,
        val viewportWidthPx: Int,
        val contentWidthPx: Int,
        val expectedMaxValue: Int,
        val maxValue: Int,
        val postDragValue: Int,
    )

    private companion object {
        const val TABLE_MARKDOWN = """
            | 项目 | 状态 | 说明 |
            | --- | --- | --- |
            | 连接 | 正常 | 已连接到服务器 |
            | 会话 | 运行中 | 正在处理请求 |
            | 输出 | 增量 | 内容持续更新 |
            | 结果 | 完成 | 可以查看详情 |
        """
    }
}
