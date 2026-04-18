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
    val sort: SessionSort,
    val filter: SessionFilter,
) {
    companion object {
        val DEFAULT = SessionListPreferences(
            collapsedDirs = emptySet(),
            pinnedDirs = emptyList(),
            sort = SessionSort.RECENT_UPDATED,
            filter = SessionFilter.ALL,
        )
    }
}
