package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectGroupHeaderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun narrowLargeTextKeepsTitleAndEveryMetricVisible() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ProjectGroupHeader(
                            projectName = "Long project workspace",
                            tildeDirectory = "~/work/project",
                            sessionCount = 37,
                            activeCount = 5,
                            unreadCount = 3,
                            additions = 12,
                            deletions = 7,
                            isPinned = true,
                            isCollapsed = false,
                            isHidden = false,
                            onToggleCollapsed = {},
                            onTogglePinned = {},
                            onToggleHidden = {},
                            onNewSession = {},
                            onCopyPath = {},
                            onArchiveAll = {},
                        )
                    }
                }
            }
        }

        listOf("Long project workspace", "37", "5", "3", "+12", "-7").forEach { text ->
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }
    }
}
