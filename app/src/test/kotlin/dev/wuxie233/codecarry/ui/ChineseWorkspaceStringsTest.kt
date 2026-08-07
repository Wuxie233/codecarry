package dev.wuxie233.codecarry.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChineseWorkspaceStringsTest {

    @Test
    fun `simplified Chinese covers the OpenCode workspace strings`() {
        val chinese = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val requiredKeys = listOf(
            "sessions_view_activity",
            "sessions_view_activity_count",
            "sessions_view_projects",
            "sessions_recent_work",
            "sessions_recent_status_retry",
            "sessions_activity_empty",
            "sessions_activity_group_pending",
            "sessions_activity_group_running",
            "sessions_activity_group_unread",
            "sessions_activity_status_question",
            "sessions_activity_status_permission",
            "sessions_activity_status_retry",
            "sessions_activity_status_running",
            "sessions_activity_status_unread",
            "sessions_activity_filter_all",
            "sessions_activity_filter_pending",
            "sessions_activity_filter_running",
            "sessions_activity_filter_unread",
            "sessions_activity_filter_retry",
            "chat_subagents_title",
            "chat_subagents_open_count",
            "chat_subagents_close",
            "chat_subagents_running",
            "chat_subagents_history",
            "chat_subagents_search_history",
            "chat_subagents_running_empty",
            "chat_subagents_history_empty",
            "chat_subagents_directory_unknown",
            "chat_subagents_open",
            "chat_subagents_status_running",
            "chat_subagents_status_awaiting",
            "chat_subagents_status_retrying",
            "chat_subagents_status_completed",
        )

        requiredKeys.forEach { key ->
            assertTrue("Missing Simplified Chinese resource: $key", chinese.contains("name=\"$key\""))
        }
    }
}
