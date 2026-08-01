package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class ChatMarkdownRendererGestureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun measuredTableInProductionMessageHierarchyRespondsToHorizontalDrag() {
        lateinit var scrollState: androidx.compose.foundation.ScrollState
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    item {
                        ProductionShapedAssistantBubble {
                            MeasuredMarkdownTable(
                                rawTable = TABLE_MARKDOWN,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                textColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("measured-table"),
                                scrollStateSink = { scrollState = it },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val maxValue = scrollState.maxValue
        assertTrue("measured table must overflow in the message hierarchy", maxValue > 0)
        val initialValue = scrollState.value

        compose.onNodeWithTag("measured-table").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        println(
            "ChatMarkdownRendererGesture productionHierarchy " +
                "maxValue=$maxValue postDragValue=${scrollState.value}",
        )
        assertTrue("table horizontal scroll did not move", scrollState.value > initialValue)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ProductionShapedAssistantBubble(content: @Composable () -> Unit) {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    content()
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(14.dp)
                        .combinedClickable(onClick = {}, onDoubleClick = {}, onLongClick = {}),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(14.dp)
                        .combinedClickable(onClick = {}, onDoubleClick = {}, onLongClick = {}),
                )
            }
        }
    }

    private companion object {
        const val TABLE_MARKDOWN = """
            | 模块 | 状态 | 说明 | 来源 | 时间 |
            | --- | --- | --- | --- | --- |
            | 连接 | 正常 | 已连接服务器 | 远程工作区 | 刚刚 |
            | 会话 | 运行中 | 正在处理请求 | 当前会话 | 1 秒前 |
            | 输出 | 完成 | 可以查看结果 | 工具调用 | 2 秒前 |
        """
    }
}
