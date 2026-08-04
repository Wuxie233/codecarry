package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R
import kotlin.math.roundToInt

private const val TABLE_MIN_COLUMN_WIDTH_DP = 80f
private const val TABLE_MAX_COLUMN_WIDTH_DP = 280f
private const val TABLE_CELL_HORIZONTAL_PADDING_DP = 24f

/** The measured width of each table column and the resulting content width, in dp. */
internal data class MarkdownTableColumnAllocation(
    val widthsDp: List<Float>,
) {
    val totalWidthDp: Float
        get() = widthsDp.sum()
}

/**
 * Clamps natural column widths and uses spare viewport width to make a small table easier to
 * scan. The active columns are stretched proportionally until either the viewport or the 280dp
 * per-column cap is reached.
 */
internal fun allocateMarkdownTableColumnWidths(
    naturalWidthsDp: List<Float>,
    viewportWidthDp: Float,
    minColumnWidthDp: Float = TABLE_MIN_COLUMN_WIDTH_DP,
    maxColumnWidthDp: Float = TABLE_MAX_COLUMN_WIDTH_DP,
): MarkdownTableColumnAllocation {
    if (naturalWidthsDp.isEmpty()) return MarkdownTableColumnAllocation(emptyList())

    val widths = naturalWidthsDp.map { it.coerceIn(minColumnWidthDp, maxColumnWidthDp) }.toMutableList()
    var remaining = (viewportWidthDp - widths.sum()).coerceAtLeast(0f)
    while (remaining > 0.01f) {
        val capacity = widths.map { (maxColumnWidthDp - it).coerceAtLeast(0f) }
        val activeCapacity = capacity.sum()
        if (activeCapacity <= 0.01f) break
        val growth = remaining.coerceAtMost(activeCapacity)
        capacity.forEachIndexed { index, available ->
            if (available > 0f) {
                widths[index] += growth * available / activeCapacity
            }
        }
        remaining -= growth
    }
    return MarkdownTableColumnAllocation(widths)
}

/**
 * A content-adaptive GFM table. The table owns its horizontal scroll so it can be rendered
 * outside text selection without relying on a parent to arbitrate drags.
 */
@Composable
internal fun MeasuredMarkdownTable(
    table: MarkdownRenderTable,
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
    scrollStateSink: ((ScrollState) -> Unit)? = null,
) {
    if (table.header.isEmpty()) {
        Text(text = "", style = textStyle, color = textColor)
        return
    }

    val header = table.header
    val rows = table.rows
    val scrollState = rememberScrollState()
    scrollStateSink?.invoke(scrollState)
    val tableShape = RoundedCornerShape(8.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val headerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val tableScrollsHorizontally = ChatOverflowPolicy.shouldUseHorizontalScroll(ChatOverflowContentKind.Table)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportWidthDp = maxWidth.value
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (tableScrollsHorizontally) Modifier.horizontalScroll(scrollState) else Modifier),
            ) {
                SubcomposeLayout(
                    modifier = Modifier
                        .clip(tableShape)
                        .border(BorderStroke(1.dp, borderColor), tableShape)
                        .background(surfaceColor),
                ) {
                    val density = this
                    val allRows = listOf(header) + rows
                    val columnCount = header.size
                    val naturalWidthsDp = List(columnCount) { columnIndex ->
                        val maxContentPx = allRows.indices.maxOf { rowIndex ->
                            val cell = allRows[rowIndex].getOrElse(columnIndex) { "" }
                            val cellStyle = if (rowIndex == 0) {
                                textStyle.copy(fontWeight = FontWeight.SemiBold)
                            } else {
                                textStyle
                            }
                            subcompose("natural-$rowIndex-$columnIndex") {
                                BasicText(text = cell, style = cellStyle)
                            }.first().measure(
                                Constraints(
                                    minWidth = 0,
                                    maxWidth = Constraints.Infinity,
                                    minHeight = 0,
                                    maxHeight = Constraints.Infinity,
                                ),
                            ).width
                        }
                        with(density) {
                            maxContentPx.toDp().value + TABLE_CELL_HORIZONTAL_PADDING_DP
                        }
                    }
                    val allocation = allocateMarkdownTableColumnWidths(naturalWidthsDp, viewportWidthDp)
                    val columnWidthsPx = allocation.widthsDp.map { with(density) { it.dp.toPx() } }
                    val tableWidthPx = columnWidthsPx.sum().roundToInt().coerceAtLeast(1)
                    val rowPlaceables = allRows.mapIndexed { rowIndex, row ->
                        subcompose("row-$rowIndex") {
                            Row(
                                modifier = Modifier
                                    .width(with(density) { tableWidthPx.toDp() })
                                    .background(if (rowIndex == 0) headerColor else surfaceColor)
                                    .drawBehind {
                                        if (rowIndex < allRows.lastIndex) {
                                            drawLine(
                                                color = dividerColor,
                                                start = androidx.compose.ui.geometry.Offset(0f, size.height - 0.5.dp.toPx()),
                                                end = androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5.dp.toPx()),
                                                strokeWidth = 1.dp.toPx(),
                                            )
                                        }
                                    },
                            ) {
                                repeat(columnCount) { columnIndex ->
                                    val cell = row.getOrElse(columnIndex) { "" }
                                    val cellStyle = if (rowIndex == 0) {
                                        textStyle.copy(fontWeight = FontWeight.SemiBold)
                                    } else {
                                        textStyle
                                    }
                                    Box(
                                        modifier = Modifier
                                            .width(with(density) { columnWidthsPx[columnIndex].toDp() })
                                            .drawBehind {
                                                if (columnIndex < columnCount - 1) {
                                                    drawLine(
                                                        color = dividerColor,
                                                        start = androidx.compose.ui.geometry.Offset(size.width - 0.5.dp.toPx(), 0f),
                                                        end = androidx.compose.ui.geometry.Offset(size.width - 0.5.dp.toPx(), size.height),
                                                        strokeWidth = 1.dp.toPx(),
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        Text(text = cell, style = cellStyle, color = textColor)
                                    }
                                }
                            }
                        }.first().measure(
                            Constraints.fixedWidth(tableWidthPx).copy(maxHeight = Constraints.Infinity),
                        )
                    }
                    val rowHeights = rowPlaceables.map { it.height }
                    val tableHeightPx = rowHeights.sum()
                    layout(tableWidthPx, tableHeightPx) {
                        var y = 0
                        rowPlaceables.forEach { rowPlaceable ->
                            rowPlaceable.placeRelative(0, y)
                            y += rowPlaceable.height
                        }
                    }
                }
            }
            if (scrollState.maxValue > 0) {
                Text(
                    text = stringResource(R.string.chat_table_scroll_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
