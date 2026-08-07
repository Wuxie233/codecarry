package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionWorkspaceOverviewTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overviewKeepsRecentViewAndProjectControlsInScanOrder() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    SessionWorkspaceOverview(
                        recentWork = { Text("Recent") },
                        viewControl = { Text("Views") },
                        projectControls = { Text("Project controls") },
                    )
                }
            }
        }

        compose.onNodeWithTag("session_workspace_overview").assertWidthIsEqualTo(320.dp)
        val recent = compose.onNodeWithTag("session_workspace_recent").fetchSemanticsNode().boundsInRoot
        val view = compose.onNodeWithTag("session_workspace_view_control").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("session_workspace_project_controls").fetchSemanticsNode().boundsInRoot

        assertTrue(recent.bottom <= view.top)
        assertTrue(view.bottom <= controls.top)
    }

    @Test
    fun activityOverviewOmitsProjectControlsWithoutChangingNavigation() {
        compose.setContent {
            MaterialTheme {
                SessionWorkspaceOverview(
                    recentWork = null,
                    viewControl = { Text("Views") },
                    projectControls = null,
                )
            }
        }

        compose.onNodeWithTag("session_workspace_view_control").assertExists()
        compose.onNodeWithTag("session_workspace_recent").assertDoesNotExist()
        compose.onNodeWithTag("session_workspace_project_controls").assertDoesNotExist()
    }

    @Test
    fun overviewCapsReadableWidthInWideWindows() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(1_280.dp)) {
                    SessionWorkspaceOverview(
                        recentWork = null,
                        viewControl = { Text("Views") },
                        projectControls = null,
                    )
                }
            }
        }

        compose.onNodeWithTag("session_workspace_overview")
            .assertWidthIsEqualTo(SessionWorkspaceMaxContentWidth)
    }
}
