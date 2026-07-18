package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import dev.minios.ocremote.ui.screens.sessions.SessionActivityFilter
import dev.minios.ocremote.ui.screens.sessions.SessionActivityGroup
import dev.minios.ocremote.ui.screens.sessions.SessionActivityGroupKind
import dev.minios.ocremote.ui.screens.sessions.SessionActivityItem
import dev.minios.ocremote.ui.screens.sessions.SessionActivityKind
import dev.minios.ocremote.ui.screens.sessions.SessionActivityQueue
import dev.minios.ocremote.ui.screens.sessions.SessionActivitySignals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionActivityQueueTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeActivityQueueScrollsToLastItem() {
        val items = (0 until 200).map(::activityItem)
        val queue = SessionActivityQueue(
            items = items,
            groups = listOf(
                SessionActivityGroup(
                    kind = SessionActivityGroupKind.RUNNING,
                    items = items,
                    signalCount = items.size,
                ),
            ),
            totalSessionCount = items.size,
            pendingSessionCount = 0,
            sessionCountsByKind = mapOf(SessionActivityKind.BUSY to items.size),
            signalCountsByKind = mapOf(SessionActivityKind.BUSY to items.size),
        )

        compose.setContent {
            MaterialTheme {
                SessionActivityQueueView(
                    queue = queue,
                    filter = SessionActivityFilter.ALL,
                    onFilterChange = {},
                    onSessionClick = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("session_activity_queue").performScrollToNode(hasText("Activity 199"))
        compose.onNodeWithText("Activity 199").assertExists()
    }

    private fun activityItem(index: Int) = SessionActivityItem(
        sessionId = "session-$index",
        directory = "/workspace/project-$index",
        title = "Activity $index",
        projectName = "Project $index",
        primaryKind = SessionActivityKind.BUSY,
        signals = SessionActivitySignals(isBusy = true),
        updatedAt = index.toLong(),
    )
}
