package dev.minios.ocremote.data.preferences

enum class SessionSort {
    RECENT_UPDATED,
    CREATED_TIME,
    TITLE_ALPHA,
}

/**
 * Status-based filter chips applied within a single scope (Inbox).
 * The historical `ARCHIVED` value has been replaced by [SessionScope.ARCHIVED];
 * the repository migrates persisted "ARCHIVED" strings to scope=ARCHIVED, filter=ALL.
 */
enum class SessionFilter {
    ALL,
    WORKING,
    HAS_CHANGES,
    HAS_ERRORS,
}

/**
 * Top-level partition of the session list. Orthogonal to [SessionFilter]:
 * INBOX shows non-archived sessions, ARCHIVED shows archived ones.
 */
enum class SessionScope {
    INBOX,
    ARCHIVED,
}

data class SessionListPreferences(
    val collapsedDirs: Set<String>,
    val pinnedDirs: List<String>,
    val hiddenDirs: Set<String>,
    val sort: SessionSort,
    val filter: SessionFilter,
    val scope: SessionScope,
    val unreadMainSessionIds: Set<String>,
) {
    companion object {
        val DEFAULT = SessionListPreferences(
            collapsedDirs = emptySet(),
            pinnedDirs = emptyList(),
            hiddenDirs = emptySet(),
            sort = SessionSort.RECENT_UPDATED,
            filter = SessionFilter.ALL,
            scope = SessionScope.INBOX,
            unreadMainSessionIds = emptySet(),
        )
    }
}
