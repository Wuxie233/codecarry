package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
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
}
