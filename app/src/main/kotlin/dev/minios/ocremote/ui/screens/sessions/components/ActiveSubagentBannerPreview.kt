package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.minios.ocremote.ui.theme.OpenCodeTheme

@Preview(showBackground = true)
@Composable
private fun ActiveSubagentBannerPreview() {
    OpenCodeTheme(darkTheme = false, dynamicColor = false) {
        ActiveSubagentBanner(
            items = listOf(
                ActiveSubagentItem(
                    sessionId = "sub-1",
                    title = "Trace session list flow",
                    agentName = "explore",
                    parentSessionId = "root-1",
                    parentTitle = "Session List polish",
                    projectName = "oc-remote",
                    status = SubagentStatus.BUSY,
                    updatedAt = System.currentTimeMillis() - 90_000,
                ),
                ActiveSubagentItem(
                    sessionId = "sub-2",
                    title = "Verify theme edge cases",
                    agentName = "oracle",
                    parentSessionId = "root-1",
                    parentTitle = "Session List polish",
                    projectName = "android-client",
                    status = SubagentStatus.RETRY,
                    updatedAt = System.currentTimeMillis() - 14 * 60_000,
                ),
                ActiveSubagentItem(
                    sessionId = "sub-3",
                    title = "Update strings",
                    agentName = null,
                    parentSessionId = "root-2",
                    parentTitle = "Localization sweep",
                    projectName = "locales",
                    status = SubagentStatus.IDLE,
                    updatedAt = System.currentTimeMillis() - 3 * 3_600_000,
                )
            ),
            onClick = {}
        )
    }
}
