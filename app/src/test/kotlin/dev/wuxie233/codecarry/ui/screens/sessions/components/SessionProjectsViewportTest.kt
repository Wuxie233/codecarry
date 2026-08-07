package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionProjectsViewportTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeProjectListScrollsToLastProject() {
        compose.setContent {
            MaterialTheme {
                SessionProjectsViewport(modifier = Modifier) {
                    items(100, key = { "project-$it" }) { index ->
                        Text("Project $index")
                    }
                }
            }
        }

        compose.onNodeWithTag("session_projects_queue").performScrollToNode(hasText("Project 99"))
        compose.onNodeWithText("Project 99").assertExists()
    }

    @Test
    fun contentWidthFillsNarrowWindowsAndCapsWideWindows() {
        assertEquals(360.dp, sessionWorkspaceContentWidth(360.dp))
        assertEquals(SessionWorkspaceMaxContentWidth, sessionWorkspaceContentWidth(1_280.dp))

        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(240.dp)) {
                    SessionProjectsViewport(modifier = Modifier.fillMaxSize()) {
                        item { Text("Project") }
                    }
                }
            }
        }

        compose.onNodeWithTag("session_projects_queue").assertWidthIsEqualTo(320.dp)
    }
}
