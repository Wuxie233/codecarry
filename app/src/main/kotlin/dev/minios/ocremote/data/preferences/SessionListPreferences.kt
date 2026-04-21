package dev.minios.ocremote.data.preferences

enum class SessionSort {
    RECENT_UPDATED,
    CREATED_TIME,
    TITLE_ALPHA,
}

enum class SessionFilter {
    ALL,
    WORKING,
    HAS_CHANGES,
    HAS_ERRORS,
    ARCHIVED,
}

data class SessionListPreferences(
    val collapsedDirs: Set<String>,
    val pinnedDirs: List<String>,
    val hiddenDirs: Set<String>,
    val sort: SessionSort,
    val filter: SessionFilter,
    val unreadMainSessionIds: Set<String>,
) {
    companion object {
        val DEFAULT = SessionListPreferences(
            collapsedDirs = emptySet(),
            pinnedDirs = emptyList(),
            hiddenDirs = emptySet(),
            sort = SessionSort.RECENT_UPDATED,
            filter = SessionFilter.ALL,
            unreadMainSessionIds = emptySet(),
        )
    }
}
