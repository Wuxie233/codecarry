# Swipe-to-Archive Implementation Plan

**Goal:** Replace left-swipe = delete with left-swipe = archive (or restore in Archived space), and add a top-level Inbox / Archived segmented control as the primary entry point to archived sessions, backed by a 5s undo Snackbar.

**Architecture:**
- Introduce a new orthogonal concept `SessionScope { INBOX, ARCHIVED }` persisted in DataStore. `SessionFilter` keeps its state-based chips (WORKING / HAS_CHANGES / HAS_ERRORS / ALL) and **drops `ARCHIVED`**. The repository performs a one-shot migration: persisted `filter=ARCHIVED` from older builds is rewritten on first read to `scope=ARCHIVED, filter=ALL`.
- ViewModel exposes `setScope(...)`, a derived `archivedCount: StateFlow<Int>`, a `_undoState: Channel<UndoAction>` for snackbar events, and reuses the existing `archiveSession` / `restoreSession` server-call methods (which already exist at `SessionListViewModel.kt:583` and `:595`).
- The screen wraps the existing `Scaffold` with a `SnackbarHost`, slots a new `SessionScopeSegmentedControl` above the search/filter row, and rewires the per-row `SwipeToDismissBox` background + `confirmValueChange` to dispatch archive-or-restore based on current scope.

**Design:** [thoughts/shared/designs/2026-04-27-swipe-to-archive-design.md](../designs/2026-04-27-swipe-to-archive-design.md)

**Contract:** none (single-domain Android app — every task is `Domain: general`)

---

## Planner-stage decisions (gap-filling, locked)

These decisions are made now so implementers don't have to guess:

1. **Compose BOM version.** App uses `androidx.compose:compose-bom:2024.12.01` (`app/build.gradle.kts:91`), which ships Material3 1.3.x. `SingleChoiceSegmentedButtonRow` + `SegmentedButton` are stable in M3 1.2+. **No version bump task needed.**
2. **Migration semantics.** `SessionListPreferencesRepository.preferences` flow does the migration in-line during `map` — if the persisted `FILTER_KEY` string equals `"ARCHIVED"`, the flow emits `scope=ARCHIVED, filter=ALL` AND a side-effect `dataStore.edit` rewrites the persisted bytes (so subsequent reads are clean). Idempotent — running it twice is a no-op.
3. **`SessionFilter.ARCHIVED` enum constant.** Following the design literally, **remove** the `ARCHIVED` enum value. Existing tests that reference it (`SessionListViewModelTest.kt:17-26`) are migrated as part of Task 5.1. The migration in `SessionListPreferencesRepository` reads the raw string `"ARCHIVED"` (not `SessionFilter.valueOf`), so deleting the enum value does not break it.
4. **Snackbar duration.** 5 seconds (`SnackbarDuration.Long` ≈ 10s, `Short` ≈ 4s — neither is exactly 5s). Use a custom `LaunchedEffect { delay(5_000); snackbarHostState.currentSnackbarData?.dismiss() }` or pass `withDismissAction = true, duration = SnackbarDuration.Short` and accept 4s. **Decision:** use `SnackbarDuration.Short` (Material's intended duration for actionable snackbars). Document the trade-off in code comment.
5. **Filter row in Archived scope.** Follow design's leaning ("filter 行整体折叠 ... sort 控件保留"). The `SessionListTopControls` exposes a new `scope` parameter; when `scope == ARCHIVED`, the `FilterChip` row is fully hidden but the search + sort row stays.
6. **Icons.** Use `Icons.Default.Inbox` for Inbox and `Icons.Default.Archive` for Archived in the segmented control. For the swipe-restore background, use `Icons.Default.Unarchive`. All three are in `material-icons-extended` (already on classpath, `app/build.gradle.kts:97`).
7. **Undo channel buffer.** Use `Channel<UndoAction>(Channel.BUFFERED)` so a fast double-swipe never drops events — the screen collects sequentially and snackbar replacement is the natural Material behavior.
8. **Failure snackbar.** Design says "snackbar 显示 '归档失败,请重试',不显示撤销按钮". Implementer emits `UndoAction.Failure(message)` from the ViewModel catch block; screen renders it without an action label.
9. **Selection mode interaction with archived scope.** Multi-select delete still works in Archived scope (selecting archived sessions → deleting them is destructive but legitimate). No special-casing.
10. **`createNewSession` resets to Inbox.** It currently resets `_filter = ALL`. Extend it to also call `setScope(INBOX)` so a new session is always visible.

---

## Dependency Graph

```
Batch 1 (parallel, 3 tasks): foundation — independent files
  1.1 SessionScope enum + SessionListPreferences extension
  1.2 strings.xml additions (en + zh-rCN)
  1.3 UndoAction sealed type

Batch 2 (parallel, 1 task): repository wiring (depends on 1.1)
  2.1 SessionListPreferencesRepository.setScope + migration

Batch 3 (parallel, 2 tasks): ViewModel layer (depends on 2.1, 1.3)
  3.1 SessionListViewModel.setScope + archivedCount + scope-aware filtering
  3.2 SessionListViewModel.undoState channel + archive/restore wrap

Batch 4 (parallel, 2 tasks): UI components (depends on 1.1, 1.2)
  4.1 SessionScopeSegmentedControl (new file)
  4.2 SessionListTopControls.kt (scope-aware filter row hide)

Batch 5 (sequential, 1 task): screen integration (depends on 3.1, 3.2, 4.1, 4.2)
  5.1 SessionListScreen.kt — segmented control mount + swipe rewrite + snackbar

Batch 6 (parallel, 4 tasks): tests (depends on Batch 5)
  6.1 SessionListPreferencesRepositoryTest — migration
  6.2 SessionListViewModelTest — scope filtering, archivedCount, undo
  6.3 SessionRowSwipeTest (new) — swipe direction → callback dispatch
  6.4 SessionRowMenuActionsTest — keep green (no changes expected; verify only)
```

Total: ~13 micro-tasks across 6 batches. Batches 1, 3, 4, 6 each have 2-4 parallel tasks.

---

## Batch 1: Foundation (parallel — 3 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: Add `SessionScope` enum and `scope` field to `SessionListPreferences`

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferences.kt`
**Test:** none (data class — covered by repository tests in Task 6.1)
**Depends:** none
**Domain:** general

Replace the entire file with:

```kotlin
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
```

**Verify:** `./gradlew :app:compileDebugKotlin` (will fail — that's expected; the repository, ViewModel, screen, and tests still reference the old shape. Subsequent tasks fix them.)
**Commit:** `feat(prefs): introduce SessionScope and remove SessionFilter.ARCHIVED`

> **Note for executor:** This task intentionally breaks compilation. The dependent tasks in Batches 2-5 fix it. Run all of Batch 1 + Batch 2 + Batch 3 + Batch 4 + Batch 5 before declaring the build green.

---

### Task 1.2: Add new strings to en + zh-rCN

**File:** `app/src/main/res/values/strings.xml` AND `app/src/main/res/values-zh-rCN/strings.xml`
**Test:** none (resource file)
**Depends:** none
**Domain:** general

> Note: this is the only multi-file task. Both files are simple text inserts to the same logical string set, and they MUST stay in lockstep (a missing translation crashes Compose at runtime). Treat them as one atomic edit.

**In `app/src/main/res/values/strings.xml`** — append the following block immediately after line 123 (`<string name="sessions_filter_archived">Archived</string>`). Note: keep `sessions_filter_archived` in place for now; it becomes orphaned but removing it is unrelated cleanup that another task can do later.

```xml
    <!-- Swipe-to-archive: scope segmented control + undo snackbar -->
    <string name="sessions_scope_inbox">Inbox</string>
    <string name="sessions_scope_archived">Archived</string>
    <string name="sessions_scope_archived_with_count">Archived (%1$d)</string>
    <string name="sessions_archive_action">Archive</string>
    <string name="sessions_restore_action">Restore</string>
    <string name="sessions_undo_action">Undo</string>
    <string name="sessions_archive_success">Archived "%1$s"</string>
    <string name="sessions_restore_success">Restored "%1$s"</string>
    <string name="sessions_archive_failed">Failed to archive, please retry</string>
    <string name="sessions_restore_failed">Failed to restore, please retry</string>
    <string name="sessions_archived_empty">No archived sessions</string>
    <string name="sessions_archived_empty_hint">Sessions you archive will appear here</string>
```

**In `app/src/main/res/values-zh-rCN/strings.xml`** — append the same keys after the existing `sessions_filter_archived` line (around line 198):

```xml
    <!-- Swipe-to-archive: 顶部 scope 切换 + 撤销 snackbar -->
    <string name="sessions_scope_inbox">收件箱</string>
    <string name="sessions_scope_archived">归档</string>
    <string name="sessions_scope_archived_with_count">归档 (%1$d)</string>
    <string name="sessions_archive_action">归档</string>
    <string name="sessions_restore_action">还原</string>
    <string name="sessions_undo_action">撤销</string>
    <string name="sessions_archive_success">已归档 "%1$s"</string>
    <string name="sessions_restore_success">已还原 "%1$s"</string>
    <string name="sessions_archive_failed">归档失败,请重试</string>
    <string name="sessions_restore_failed">还原失败,请重试</string>
    <string name="sessions_archived_empty">暂无归档会话</string>
    <string name="sessions_archived_empty_hint">归档后的会话会出现在这里</string>
```

> Other locales (ru / de / es / fr / it / pt-BR / id / ja / ko / uk / tr / ar / pl) are intentionally skipped — they will be filled by the `lokit` workflow per project convention (see `~/.config/opencode/AGENTS.md` localization note). Missing keys in those locales fall back to the default `values/strings.xml` (English) at runtime.

**Verify:** `grep -c sessions_scope_inbox app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml` → both files report `1`.
**Commit:** `feat(i18n): add scope/undo strings for swipe-to-archive (en, zh-rCN)`

---

### Task 1.3: Define `UndoAction` sealed type

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/UndoAction.kt` (NEW)
**Test:** none (data type — covered by ViewModel tests in 6.2)
**Depends:** none
**Domain:** general

```kotlin
package dev.minios.ocremote.ui.screens.sessions

/**
 * Snackbar event emitted from [SessionListViewModel] after an archive / restore /
 * failure occurs. The screen collects these from `viewModel.undoState` and shows a
 * Snackbar; for [Archive] and [Restore] the snackbar exposes an Undo action that
 * calls the inverse VM method. [Failure] shows a non-actionable snackbar.
 */
internal sealed interface UndoAction {
    val sessionId: String?

    data class Archive(
        override val sessionId: String,
        val title: String,
    ) : UndoAction

    data class Restore(
        override val sessionId: String,
        val title: String,
    ) : UndoAction

    data class Failure(
        val messageResId: Int,
        override val sessionId: String? = null,
    ) : UndoAction
}
```

**Verify:** `./gradlew :app:compileDebugKotlin` builds this file in isolation — type-checks without referencing other to-be-built code.
**Commit:** `feat(sessions): add UndoAction event type for snackbar`

---

## Batch 2: Repository (parallel — 1 implementer, but listed for clarity)

**Depends on Batch 1.1** (needs `SessionScope` to compile).

### Task 2.1: Wire `scope` persistence + ARCHIVED-filter migration in repository

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepository.kt`
**Test:** extended in Task 6.1
**Depends:** 1.1
**Domain:** general

Replace the file with the version below. Key changes:
- New `SCOPE_KEY = stringPreferencesKey("scope")`.
- `preferences` flow does **inline migration**: if persisted filter string is `"ARCHIVED"`, emit `scope=ARCHIVED, filter=ALL` and fire-and-forget a `dataStore.edit { ... }` on a coroutine to rewrite. (Inline rewrite during `map` would block the flow; we use `migrationLatch` to ensure it happens at most once.)
- New `setScope(scope: SessionScope)` method.
- `setFilter` is unchanged.

```kotlin
package dev.minios.ocremote.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionListPreferencesRepository @Inject constructor(
    @SessionListDataStore private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val COLLAPSED_DIRS_KEY = stringSetPreferencesKey("collapsed_dirs")
        private val PINNED_DIRS_KEY = stringSetPreferencesKey("pinned_dirs")
        private val PINNED_DIRS_ORDER_KEY = stringPreferencesKey("pinned_dirs_order")
        private val HIDDEN_DIRS_KEY = stringSetPreferencesKey("hidden_dirs")
        private val SORT_KEY = stringPreferencesKey("sort")
        private val FILTER_KEY = stringPreferencesKey("filter")
        private val SCOPE_KEY = stringPreferencesKey("scope")
        private val UNREAD_MAIN_SESSION_IDS_KEY = stringSetPreferencesKey("unread_main_session_ids")

        // Old filter constant that no longer exists in the SessionFilter enum.
        private const val LEGACY_FILTER_ARCHIVED = "ARCHIVED"
    }

    /**
     * Best-effort one-shot writer: when the preferences flow first observes a legacy
     * `filter=ARCHIVED` row, we kick off a rewrite to `scope=ARCHIVED, filter=ALL`.
     * Subsequent observations are no-ops thanks to [migrationDone].
     *
     * We intentionally use a private supervisor scope rather than a coroutine
     * captured from a caller — DataStore's `edit { }` is suspending and we don't
     * want to block the [preferences] flow's downstream collectors.
     */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val migrationDone = AtomicBoolean(false)

    val preferences: Flow<SessionListPreferences> = dataStore.data.map { prefs ->
        val rawFilter = prefs[FILTER_KEY]
        val needsLegacyMigration = rawFilter == LEGACY_FILTER_ARCHIVED

        if (needsLegacyMigration && migrationDone.compareAndSet(false, true)) {
            migrationScope.launch {
                dataStore.edit { mutable ->
                    mutable[FILTER_KEY] = SessionFilter.ALL.name
                    mutable[SCOPE_KEY] = SessionScope.ARCHIVED.name
                }
            }
        }

        val collapsedDirs = prefs[COLLAPSED_DIRS_KEY] ?: emptySet()
        val pinnedDirsSet = prefs[PINNED_DIRS_KEY] ?: emptySet()
        val pinnedDirsOrder = prefs[PINNED_DIRS_ORDER_KEY] ?: ""
        val pinnedDirs = if (pinnedDirsOrder.isBlank()) {
            pinnedDirsSet.toList()
        } else {
            pinnedDirsOrder.split(",")
                .filter { it.isNotBlank() && it in pinnedDirsSet }
        }
        val sort = prefs[SORT_KEY]?.let {
            runCatching { SessionSort.valueOf(it) }.getOrNull()
        } ?: SessionSort.RECENT_UPDATED

        // The migrated filter is reported to UI immediately, even before the rewrite
        // lands on disk. This means the in-memory state is consistent with what the
        // user sees on screen on the very first frame.
        val filter = if (needsLegacyMigration) {
            SessionFilter.ALL
        } else {
            rawFilter?.let { runCatching { SessionFilter.valueOf(it) }.getOrNull() }
                ?: SessionFilter.ALL
        }

        val scope = if (needsLegacyMigration) {
            SessionScope.ARCHIVED
        } else {
            prefs[SCOPE_KEY]?.let { runCatching { SessionScope.valueOf(it) }.getOrNull() }
                ?: SessionScope.INBOX
        }

        val hiddenDirs = prefs[HIDDEN_DIRS_KEY] ?: emptySet()
        val unreadMainSessionIds = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
        SessionListPreferences(
            collapsedDirs = collapsedDirs,
            pinnedDirs = pinnedDirs,
            hiddenDirs = hiddenDirs,
            sort = sort,
            filter = filter,
            scope = scope,
            unreadMainSessionIds = unreadMainSessionIds,
        )
    }

    suspend fun setCollapsed(dir: String, collapsed: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[COLLAPSED_DIRS_KEY] ?: emptySet()
            prefs[COLLAPSED_DIRS_KEY] = if (collapsed) current + dir else current - dir
        }
    }

    suspend fun togglePinned(dir: String) {
        dataStore.edit { prefs ->
            val currentSet = prefs[PINNED_DIRS_KEY] ?: emptySet()
            val currentOrder = prefs[PINNED_DIRS_ORDER_KEY] ?: ""
            val currentList = if (currentOrder.isBlank()) {
                currentSet.toList()
            } else {
                currentOrder.split(",").filter { it.isNotBlank() && it in currentSet }
            }
            val newList = if (dir in currentSet) {
                currentList - dir
            } else {
                currentList + dir
            }
            prefs[PINNED_DIRS_KEY] = newList.toSet()
            prefs[PINNED_DIRS_ORDER_KEY] = newList.joinToString(",")
        }
    }

    suspend fun addPinned(dir: String): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val key = PINNED_DIRS_KEY
            val orderKey = PINNED_DIRS_ORDER_KEY
            val currentSet = prefs[key] ?: emptySet()
            val currentOrder = prefs[orderKey] ?: ""
            if (dir !in currentSet) {
                val newList = (currentOrder.split(",")
                    .filter { it.isNotBlank() && it in currentSet } + dir)
                    .distinct()
                prefs[key] = newList.toSet()
                prefs[orderKey] = newList.joinToString(",")
                changed = true
            }
        }
        return changed
    }

    suspend fun setSort(sort: SessionSort) {
        dataStore.edit { prefs ->
            prefs[SORT_KEY] = sort.name
        }
    }

    suspend fun setFilter(filter: SessionFilter) {
        dataStore.edit { prefs ->
            prefs[FILTER_KEY] = filter.name
        }
    }

    suspend fun setScope(scope: SessionScope) {
        dataStore.edit { prefs ->
            prefs[SCOPE_KEY] = scope.name
        }
    }

    suspend fun markMainSessionUnread(sessionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
            prefs[UNREAD_MAIN_SESSION_IDS_KEY] = current + sessionId
        }
    }

    suspend fun markMainSessionRead(sessionId: String) {
        dataStore.edit { prefs ->
            val current = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
            prefs[UNREAD_MAIN_SESSION_IDS_KEY] = current - sessionId
        }
    }

    suspend fun markMainSessionsRead(sessionIds: Collection<String>) {
        if (sessionIds.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[UNREAD_MAIN_SESSION_IDS_KEY] ?: emptySet()
            prefs[UNREAD_MAIN_SESSION_IDS_KEY] = current - sessionIds.toSet()
        }
    }

    suspend fun clearCollapsed() {
        dataStore.edit { prefs ->
            prefs[COLLAPSED_DIRS_KEY] = emptySet()
        }
    }

    suspend fun toggleHidden(dir: String) {
        dataStore.edit { prefs ->
            val current = prefs[HIDDEN_DIRS_KEY] ?: emptySet()
            prefs[HIDDEN_DIRS_KEY] = if (dir in current) current - dir else current + dir
        }
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin` — repository module compiles. (ViewModel and screen still broken; fixed in Batches 3-5.)
**Commit:** `feat(prefs): persist SessionScope and migrate legacy ARCHIVED filter`

---

## Batch 3: ViewModel layer (parallel — 2 implementers)

Both tasks edit the **same file** (`SessionListViewModel.kt`) and therefore CANNOT run in parallel. They are listed as 3.1 and 3.2 for logical clarity, but **the executor must run them sequentially** (3.1 first, then 3.2). Functionally they touch different sections of the file.

> **Coordination note:** Implementer of 3.1 leaves `archiveSession` / `restoreSession` UNCHANGED and adds the new state without touching them. Implementer of 3.2 then wraps those two methods to also emit on `_undoState`. Treat 3.1 → 3.2 as a hand-off.

### Task 3.1: Add `setScope`, `archivedCount`, scope-aware filtering

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
**Test:** extended in Task 6.2
**Depends:** 2.1
**Domain:** general

Apply the following edits:

**1.** Add import (top of file, after the existing `dev.minios.ocremote.data.preferences.SessionSort` import):

```kotlin
import dev.minios.ocremote.data.preferences.SessionScope
```

**2.** Extend `SessionListUiState` (around lines 53-75) — add a `scope` field and an `archivedCount` field:

```kotlin
data class SessionListUiState(
    val serverName: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,

    val activeConversations: List<ActiveConversationItem> = emptyList(),
    val groups: List<ProjectGroup> = emptyList(),
    val sessionGroups: List<ProjectSessionGroup> = emptyList(),

    val sort: SessionSort = SessionSort.RECENT_UPDATED,
    val filter: SessionFilter = SessionFilter.ALL,
    val scope: SessionScope = SessionScope.INBOX,
    val archivedCount: Int = 0,
    val searchQuery: String = "",
    val hasAnySessions: Boolean = false,
    val isFilteredEmpty: Boolean = false,

    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,

    val projects: List<Project> = emptyList(),

    val hiddenProjectCount: Int = 0,
    val showHiddenProjects: Boolean = false,
)
```

**3.** Update `matchesSessionFilter` (lines 136-145) — drop the `ARCHIVED` branch, since the enum no longer has that value:

```kotlin
internal fun matchesSessionFilter(item: SessionItem, filter: SessionFilter): Boolean {
    val session = item.session
    return when (filter) {
        SessionFilter.ALL -> !session.isArchived
        SessionFilter.WORKING -> !session.isArchived && item.status is SessionStatus.Busy
        SessionFilter.HAS_CHANGES -> !session.isArchived && ((session.summary?.additions ?: 0) + (session.summary?.deletions ?: 0) > 0)
        SessionFilter.HAS_ERRORS -> !session.isArchived && item.status is SessionStatus.Retry
    }
}
```

**4.** Add a helper for scope-based partitioning (place right after `matchesSessionFilter`, before `archiveableRootSessionIds`):

```kotlin
internal fun matchesScope(item: SessionItem, scope: SessionScope): Boolean {
    return when (scope) {
        SessionScope.INBOX -> !item.session.isArchived
        SessionScope.ARCHIVED -> item.session.isArchived
    }
}

internal fun matchesScopeAndFilter(
    item: SessionItem,
    scope: SessionScope,
    filter: SessionFilter,
): Boolean {
    if (!matchesScope(item, scope)) return false
    // In Archived scope, status filters are meaningless (the design forces filter=ALL).
    return when (scope) {
        SessionScope.ARCHIVED -> true
        SessionScope.INBOX -> matchesSessionFilter(item, filter)
    }
}
```

**5.** Inside `uiState` combine (around line 218-380), the filter logic (line 287-297) currently calls `matchesFilter(itemsById.getValue(session.id), filter)`. Replace `matchesFilter(...)` calls with the new scope-aware variant:

```kotlin
val filteredRoots = groupedRoots
    .filter { session ->
        matchesScopeAndFilter(itemsById.getValue(session.id), prefs.scope, filter)
    }
    .filter { session ->
        matchesSearch(
            session = session,
            directory = directory,
            projectName = projectName,
            query = searchQuery,
        )
    }
    .sortedWith(rootSessionComparator(prefs.sort))
```

**6.** Update child filter (line 301-302). Currently `matchesChildArchiveMode(child, filter)` reads child archive flag from `filter`. Replace with scope-driven version:

```kotlin
val subagentRowsByParent = filteredRoots.associate { root ->
    val childItems = (childBuckets[root.id] ?: emptyList())
        .filter { child -> matchesChildScope(child, prefs.scope) }
        .map { itemsById.getValue(it.id) }
        .sortedWith(sessionItemComparator(prefs.sort))
    root.id to partitionSubagentsByActivity(childItems)
}
```

**7.** Replace the now-unused `matchesChildArchiveMode` private method (line 762-767) with the scope-driven version:

```kotlin
private fun matchesChildScope(session: Session, scope: SessionScope): Boolean {
    return when (scope) {
        SessionScope.ARCHIVED -> session.isArchived
        SessionScope.INBOX -> !session.isArchived
    }
}
```

**8.** Compute `archivedCount` inside the combine block (after `serverScopedSessions` is computed, around line 253):

```kotlin
val archivedCount = serverScopedSessions.count { it.parentId == null && it.isArchived }
```

**9.** Pass `scope` and `archivedCount` into the returned `SessionListUiState` (around line 362-379):

```kotlin
SessionListUiState(
    serverName = serverName,
    isLoading = loading,
    error = error,
    activeConversations = activeConversations,
    groups = groups,
    sessionGroups = legacySessionGroups,
    sort = prefs.sort,
    filter = filter,
    scope = prefs.scope,
    archivedCount = archivedCount,
    searchQuery = searchQuery,
    hasAnySessions = emptyState.hasAnySessions,
    isFilteredEmpty = emptyState.isFilteredEmpty,
    selectedIds = validSelectedIds,
    isSelectionMode = validSelectedIds.isNotEmpty(),
    projects = projects,
    hiddenProjectCount = hiddenProjectCount,
    showHiddenProjects = showHiddenProjects,
)
```

**10.** Add the `setScope` method (place near `setFilter` around line 511):

```kotlin
fun setScope(scope: SessionScope) {
    viewModelScope.launch {
        preferencesRepo.setScope(scope)
    }
}
```

**11.** Update `createNewSession` (line 452-465) so creating a session always returns the user to Inbox + ALL filter. Replace its body:

```kotlin
fun createNewSession(directory: String? = null) {
    viewModelScope.launch {
        try {
            _filter.value = SessionFilter.ALL
            preferencesRepo.setScope(SessionScope.INBOX)
            val session = api.createSession(conn, directory = directory)
            eventReducer.setSessions(serverId, listOf(session))
            if (BuildConfig.DEBUG) Log.d(TAG, "Created new session: ${session.id}")
            _navigateToSession.tryEmit(session.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session", e)
            _error.value = e.message ?: "Failed to create session"
        }
    }
}
```

**12.** Remove the inline `private fun matchesFilter(...)` wrapper (line 758-760) — its only caller was the spot we just rewrote in step 5. Leave `matchesSessionFilter` as the public testable helper.

**Verify:**
- `./gradlew :app:compileDebugKotlin` — should compile if 1.1 and 2.1 are in.
- `./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.sessions.SessionListViewModelTest` — will FAIL because tests still reference `SessionFilter.ARCHIVED`. That's expected; Task 6.2 fixes them.

**Commit:** `feat(sessions): make filtering scope-aware and expose archivedCount`

---

### Task 3.2: Add `_undoState` channel and wrap archive/restore

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt` (continued from 3.1)
**Test:** extended in Task 6.2
**Depends:** 1.3, 3.1
**Domain:** general

Apply these edits on top of Task 3.1's changes:

**1.** Add imports (top of file):

```kotlin
import dev.minios.ocremote.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
```

**2.** Add a private channel + public flow near the other private state (around line 209, alongside `_navigateToSession`):

```kotlin
private val _undoState = Channel<UndoAction>(Channel.BUFFERED)
val undoState: kotlinx.coroutines.flow.Flow<UndoAction> = _undoState.receiveAsFlow()
```

**3.** Replace the existing `archiveSession` (line 583-593) and `restoreSession` (line 595-605) with versions that emit on the channel. The server calls themselves are unchanged:

```kotlin
fun archiveSession(sessionId: String) {
    viewModelScope.launch {
        val title = serverScopedSessions().firstOrNull { it.id == sessionId }?.title.orEmpty()
        try {
            api.archiveSession(conn, sessionId)
            loadSessions()
            _undoState.send(UndoAction.Archive(sessionId = sessionId, title = title))
        } catch (e: Exception) {
            logErrorCompat(TAG, "Failed to archive session $sessionId", e)
            _error.value = e.message ?: "Failed to archive session"
            _undoState.send(UndoAction.Failure(messageResId = R.string.sessions_archive_failed))
        }
    }
}

fun restoreSession(sessionId: String) {
    viewModelScope.launch {
        val title = serverScopedSessions().firstOrNull { it.id == sessionId }?.title.orEmpty()
        try {
            api.restoreSession(conn, sessionId)
            loadSessions()
            _undoState.send(UndoAction.Restore(sessionId = sessionId, title = title))
        } catch (e: Exception) {
            logErrorCompat(TAG, "Failed to restore session $sessionId", e)
            _error.value = e.message ?: "Failed to restore session"
            _undoState.send(UndoAction.Failure(messageResId = R.string.sessions_restore_failed))
        }
    }
}
```

> **Note on title capture:** we look up `title` BEFORE the api call so even if `loadSessions()` later removes the row from the in-memory list, the snackbar still has a useful label. Empty string falls back to the localized "Untitled session" in the screen layer.

**4.** Override `onCleared()` to close the channel (add at the end of the class, before the closing brace at line 816):

```kotlin
override fun onCleared() {
    super.onCleared()
    _undoState.close()
}
```

**Verify:**
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:testDebugUnitTest --tests *SessionListViewModelTest` — still failing (tests not yet updated); Task 6.2 fixes.

**Commit:** `feat(sessions): emit UndoAction events from archive/restore`

---

## Batch 4: UI components (parallel — 2 implementers)

Independent files. Run in parallel after Batch 1.

### Task 4.1: Create `SessionScopeSegmentedControl`

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionScopeSegmentedControl.kt` (NEW)
**Test:** none in this batch (covered indirectly by Task 6.3 swipe test infra and screen-level smoke)
**Depends:** 1.1, 1.2
**Domain:** general

```kotlin
package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.data.preferences.SessionScope

/**
 * Top-level switch between [SessionScope.INBOX] (active sessions) and
 * [SessionScope.ARCHIVED] (archive vault).
 *
 * - Inbox tab shows the Inbox icon and label.
 * - Archived tab shows the Archive icon and, when [archivedCount] > 0,
 *   appends the count in parentheses. When [archivedCount] == 0 we
 *   omit the count entirely (cleaner empty state).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScopeSegmentedControl(
    currentScope: SessionScope,
    archivedCount: Int,
    onScopeChange: (SessionScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(SessionScope.INBOX, SessionScope.ARCHIVED)

    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        options.forEachIndexed { index, scope ->
            val selected = scope == currentScope
            SegmentedButton(
                selected = selected,
                onClick = { onScopeChange(scope) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {
                    Icon(
                        imageVector = when (scope) {
                            SessionScope.INBOX -> Icons.Default.Inbox
                            SessionScope.ARCHIVED -> Icons.Default.Archive
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = when (scope) {
                                SessionScope.INBOX -> stringResource(R.string.sessions_scope_inbox)
                                SessionScope.ARCHIVED -> if (archivedCount > 0) {
                                    stringResource(R.string.sessions_scope_archived_with_count, archivedCount)
                                } else {
                                    stringResource(R.string.sessions_scope_archived)
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin` — file compiles in isolation.
**Commit:** `feat(sessions): add SessionScopeSegmentedControl composable`

---

### Task 4.2: Make `SessionListTopControls` scope-aware

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionListTopControls.kt`
**Test:** none (visual; covered by manual smoke)
**Depends:** 1.1, 1.2
**Domain:** general

Apply two changes:

**1.** Add a new parameter `scope: SessionScope` to the `SessionListTopControls` signature, after `filter`:

```kotlin
@Composable
fun SessionListTopControls(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sort: SessionSort,
    onSortChange: (SessionSort) -> Unit,
    filter: SessionFilter,
    onFilterChange: (SessionFilter) -> Unit,
    scope: SessionScope,
    modifier: Modifier = Modifier,
) {
```

(Add the import `import dev.minios.ocremote.data.preferences.SessionScope` at the top of the file.)

**2.** Wrap the existing filter chip Row (lines 179-207 in the current file) with a scope check — render it only when `scope == INBOX`:

```kotlin
        if (scope == SessionScope.INBOX) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(filterScrollState)
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilterChange(option) },
                        label = { Text(text = filterLabel(option)) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = if (isAmoled) Color.Black else colors.surface,
                            selectedContainerColor = colors.primaryContainer,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = filter == option,
                            borderColor = if (isAmoled) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.6f),
                            selectedBorderColor = if (isAmoled) colors.primaryContainer else colors.outlineVariant.copy(alpha = 0.3f),
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.dp,
                        ),
                    )
                }
            }
        }
```

Note `SessionFilter.entries` no longer contains `ARCHIVED` (removed in Task 1.1) — so `filterLabel(SessionFilter.ARCHIVED)` will not be invoked. Remove the now-stale line `SessionFilter.ARCHIVED -> stringResource(R.string.sessions_filter_archived)` from `filterLabel(...)` (currently at line 265):

```kotlin
@Composable
private fun filterLabel(filter: SessionFilter): String = when (filter) {
    SessionFilter.ALL -> stringResource(R.string.sessions_filter_all)
    SessionFilter.WORKING -> stringResource(R.string.sessions_filter_working)
    SessionFilter.HAS_CHANGES -> stringResource(R.string.sessions_filter_has_changes)
    SessionFilter.HAS_ERRORS -> stringResource(R.string.sessions_filter_has_errors)
}
```

**Verify:** `./gradlew :app:compileDebugKotlin` — module compiles. The unused `R.string.sessions_filter_archived` resource is left in `strings.xml` for now (orphan, harmless; lokit will sweep it later).
**Commit:** `feat(sessions): hide filter chips in Archived scope`

---

## Batch 5: Screen integration (sequential — 1 implementer)

This task touches the most code and depends on everything above. Run alone.

### Task 5.1: `SessionListScreen.kt` — segmented control + swipe rewrite + snackbar host

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
**Test:** Task 6.3 (swipe behavior)
**Depends:** 3.1, 3.2, 4.1, 4.2
**Domain:** general

Apply five distinct edits, in order:

#### 5.1.a — Imports (top of file, after the existing imports)

Add:

```kotlin
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberSnackbarHostState
import dev.minios.ocremote.data.preferences.SessionScope
import dev.minios.ocremote.ui.screens.sessions.components.SessionScopeSegmentedControl
```

> Compose's `SnackbarHostState` does not have a `rememberSnackbarHostState()` helper in M3 — use `remember { SnackbarHostState() }` instead. Drop that import. Final import set: just `SnackbarHost`, `SnackbarHostState`, `SnackbarResult`.

#### 5.1.b — Wire SnackbarHostState into the Scaffold

Inside `SessionListScreen` (around line 132, just after `val isAmoled = isAmoledTheme()`):

```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
```

Add `import androidx.compose.runtime.rememberCoroutineScope` to the imports if not already present.

Then wire it into the existing `Scaffold` (currently at line 167 with no `snackbarHost = { ... }`). Add a parameter immediately under `topBar = { ... }`:

```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            // ... existing top bar code unchanged ...
        },
        floatingActionButton = { /* unchanged */ }
    ) { padding ->
```

#### 5.1.c — Collect undoState and dispatch snackbars

Inside `SessionListScreen`, alongside the existing `LaunchedEffect(viewModel) { viewModel.navigateToSession ... }` (line 138), add a second LaunchedEffect:

```kotlin
    val archiveSuccessFmt = stringResource(R.string.sessions_archive_success)
    val restoreSuccessFmt = stringResource(R.string.sessions_restore_success)
    val undoLabel = stringResource(R.string.sessions_undo_action)
    val archiveFailedMsg = stringResource(R.string.sessions_archive_failed)
    val restoreFailedMsg = stringResource(R.string.sessions_restore_failed)
    val untitledLabel = stringResource(R.string.session_untitled)

    LaunchedEffect(viewModel) {
        viewModel.undoState.collect { event ->
            // Dismiss any in-flight snackbar so the new event always wins
            // (matches design's "后弹覆盖前弹" requirement).
            snackbarHostState.currentSnackbarData?.dismiss()

            when (event) {
                is UndoAction.Archive -> {
                    val displayTitle = event.title.ifBlank { untitledLabel }
                    val result = snackbarHostState.showSnackbar(
                        message = String.format(archiveSuccessFmt, displayTitle),
                        actionLabel = undoLabel,
                        withDismissAction = false,
                        duration = androidx.compose.material3.SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreSession(event.sessionId)
                    }
                }
                is UndoAction.Restore -> {
                    val displayTitle = event.title.ifBlank { untitledLabel }
                    val result = snackbarHostState.showSnackbar(
                        message = String.format(restoreSuccessFmt, displayTitle),
                        actionLabel = undoLabel,
                        withDismissAction = false,
                        duration = androidx.compose.material3.SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.archiveSession(event.sessionId)
                    }
                }
                is UndoAction.Failure -> {
                    val msg = when (event.messageResId) {
                        R.string.sessions_archive_failed -> archiveFailedMsg
                        R.string.sessions_restore_failed -> restoreFailedMsg
                        else -> archiveFailedMsg
                    }
                    snackbarHostState.showSnackbar(
                        message = msg,
                        actionLabel = null,
                        withDismissAction = true,
                        duration = androidx.compose.material3.SnackbarDuration.Short,
                    )
                }
            }
        }
    }
```

> **Why use `String.format`?** The strings `sessions_archive_success` / `sessions_restore_success` use `%1$s` placeholder — Compose's `stringResource(id, arg)` works at composition time but we're inside a `LaunchedEffect` (suspend context). Calling `stringResource` outside composition isn't safe, so we resolve the format string in composition (above the LaunchedEffect) and substitute via `String.format`.

#### 5.1.d — Mount SessionScopeSegmentedControl above SessionListTopControls

Replace the block at lines 321-330 (current `SessionListTopControls(...)` call inside the `else` branch of the empty-state when):

```kotlin
                        if (!uiState.isSelectionMode) {
                            SessionScopeSegmentedControl(
                                currentScope = uiState.scope,
                                archivedCount = uiState.archivedCount,
                                onScopeChange = viewModel::setScope,
                                modifier = Modifier.padding(top = 4.dp),
                            )

                            SessionListTopControls(
                                searchQuery = uiState.searchQuery,
                                onSearchQueryChange = viewModel::setSearchQuery,
                                sort = uiState.sort,
                                onSortChange = viewModel::setSort,
                                filter = uiState.filter,
                                onFilterChange = viewModel::setFilter,
                                scope = uiState.scope,
                            )
                        }
```

#### 5.1.e — Rewrite the per-row swipe behavior

This is the core UX change. Edit the `SessionRow` composable (currently at lines 1519-1854).

**Step 1:** Add a `currentScope: SessionScope` parameter to `SessionRow` (after `onDelete`):

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    item: SessionItem,
    projectName: String? = null,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    currentScope: SessionScope,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
```

**Step 2:** Rewrite the `dismissState` block (lines 1542-1557) so EndToStart triggers archive-or-restore depending on scope:

```kotlin
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onRename()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    when (currentScope) {
                        SessionScope.INBOX -> onArchive()
                        SessionScope.ARCHIVED -> onRestore()
                    }
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * 0.3f }
    )
```

**Step 3:** Rewrite the `backgroundContent` block (lines 1776-1848) so the EndToStart background reflects scope:

```kotlin
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection

            // StartToEnd background = rename (unchanged)
            val renameColor = MaterialTheme.colorScheme.primaryContainer
            // EndToStart background = scope-dependent
            val swipeLeftColor = when (currentScope) {
                SessionScope.INBOX -> MaterialTheme.colorScheme.tertiaryContainer
                SessionScope.ARCHIVED -> MaterialTheme.colorScheme.secondaryContainer
            }
            val swipeLeftIcon = when (currentScope) {
                SessionScope.INBOX -> Icons.Default.Archive
                SessionScope.ARCHIVED -> Icons.Default.Unarchive
            }
            val swipeLeftLabel = when (currentScope) {
                SessionScope.INBOX -> stringResource(R.string.sessions_archive_action)
                SessionScope.ARCHIVED -> stringResource(R.string.sessions_restore_action)
            }
            val swipeLeftTint = when (currentScope) {
                SessionScope.INBOX -> MaterialTheme.colorScheme.onTertiaryContainer
                SessionScope.ARCHIVED -> MaterialTheme.colorScheme.onSecondaryContainer
            }

            val bgColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> renameColor
                SwipeToDismissBoxValue.EndToStart -> swipeLeftColor
                else -> Color.Transparent
            }
            val iconTint = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onPrimaryContainer
                SwipeToDismissBoxValue.EndToStart -> swipeLeftTint
                else -> Color.Transparent
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CardDefaults.shape)
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    else -> Alignment.CenterEnd
                }
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.session_rename),
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                stringResource(R.string.session_rename),
                                style = MaterialTheme.typography.labelMedium,
                                color = iconTint
                            )
                        }
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                swipeLeftLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = iconTint
                            )
                            Icon(
                                swipeLeftIcon,
                                contentDescription = swipeLeftLabel,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }
        },
        enableDismissFromStartToEnd = !isSelectionMode,
        enableDismissFromEndToStart = !isSelectionMode
    ) {
        cardContent()
    }
```

**Step 4:** Plumb `currentScope` through `SessionRowWithSubagents` (lines 661-696):

```kotlin
@Composable
private fun SessionRowWithSubagents(
    item: SessionItem,
    subagents: SubagentRow,
    projectName: String?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    currentScope: SessionScope,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onSubagentClick: (sessionId: String) -> Unit,
) {
    // ... (rest unchanged)
        SessionRow(
            item = item,
            projectName = projectName,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            currentScope = currentScope,
            onClick = onClick,
            onLongClick = onLongClick,
            onRename = onRename,
            onArchive = onArchive,
            onRestore = onRestore,
            onDelete = onDelete,
        )
```

**Step 5:** At the call site of `SessionRowWithSubagents` (around line 437-473 inside the LazyColumn loop), pass the scope:

```kotlin
                                        SessionRowWithSubagents(
                                            item = item,
                                            subagents = group.subagentRowsByParent[item.session.id]
                                                ?: SubagentRow.EMPTY,
                                            projectName = dirLabel,
                                            isSelectionMode = uiState.isSelectionMode,
                                            isSelected = item.session.id in uiState.selectedIds,
                                            currentScope = uiState.scope,
                                            onClick = { ... unchanged ... },
                                            // ... rest unchanged ...
                                        )
```

#### 5.1.f — Update the filtered-empty / archived-empty UI

Currently the filtered-empty branch (line 340-368) only handles "user filtered everything out". Add a scope-aware empty state for archived. Replace the block:

```kotlin
                        if (uiState.isFilteredEmpty) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                            ) {
                                val isArchivedEmpty = uiState.scope == SessionScope.ARCHIVED && uiState.archivedCount == 0
                                Icon(
                                    imageVector = if (isArchivedEmpty) Icons.Default.Archive else Icons.Default.FilterList,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = if (isArchivedEmpty) {
                                        stringResource(R.string.sessions_archived_empty)
                                    } else {
                                        stringResource(R.string.sessions_filtered_empty)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = if (isArchivedEmpty) {
                                        stringResource(R.string.sessions_archived_empty_hint)
                                    } else {
                                        stringResource(R.string.sessions_filtered_empty_hint)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                if (!isArchivedEmpty) {
                                    Button(onClick = { viewModel.clearFilter() }) {
                                        Text(stringResource(R.string.sessions_clear_filter))
                                    }
                                }
                            }
                        } else {
                            // ... existing LazyColumn ... unchanged
                        }
```

**Verify:**
- `./gradlew :app:compileDebugKotlin` — full compile passes.
- `./gradlew :app:assembleDebug` — APK builds.
- Manual: launch app → top of session list shows segmented [Inbox] [Archived (N)]; left-swipe a row in Inbox → snackbar "Archived 'X' [Undo]"; tap Undo → row reappears.

**Commit:** `feat(sessions): swipe-left archives, scope segmented control, undo snackbar`

---

## Batch 6: Tests (parallel — 4 implementers)

Run after Batch 5 lands.

### Task 6.1: Migration test for repository

**File:** `app/src/test/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepositoryTest.kt`
**Test:** itself
**Depends:** 2.1
**Domain:** general

Append the following tests to the existing file. Also: the existing `setSort and setFilter are persisted correctly` test references `SessionFilter.HAS_ERRORS` which still exists — no change needed. But add an explicit `setScope` test and the migration test.

```kotlin
    @Test
    fun `setScope persists value to ARCHIVED then INBOX`() = testScope.runTest {
        val repo = createRepo()

        repo.setScope(SessionScope.ARCHIVED)
        assertEquals(SessionScope.ARCHIVED, repo.preferences.first().scope)

        repo.setScope(SessionScope.INBOX)
        assertEquals(SessionScope.INBOX, repo.preferences.first().scope)
    }

    @Test
    fun `default scope is INBOX`() = testScope.runTest {
        val repo = createRepo()
        assertEquals(SessionScope.INBOX, repo.preferences.first().scope)
    }

    @Test
    fun `legacy filter ARCHIVED migrates to scope ARCHIVED and filter ALL`() = testScope.runTest {
        // Seed the underlying DataStore directly with the legacy persisted string
        // before constructing the repository instance under test.
        val file = tmpFolder.newFile("legacy_session_list_prefs.preferences_pb")
        val seedDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { file },
        )
        seedDataStore.edit { mutable ->
            mutable[androidx.datastore.preferences.core.stringPreferencesKey("filter")] = "ARCHIVED"
        }
        // Build a repository pointing at the SAME file
        val repo = SessionListPreferencesRepository(
            dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { file },
            )
        )

        val migrated = repo.preferences.first()

        assertEquals(SessionScope.ARCHIVED, migrated.scope)
        assertEquals(SessionFilter.ALL, migrated.filter)
    }
```

> Add the import `import androidx.datastore.preferences.core.edit` if not already present.

**Verify:** `./gradlew :app:testDebugUnitTest --tests *SessionListPreferencesRepositoryTest`
**Commit:** `test(prefs): cover SessionScope persistence and legacy filter migration`

---

### Task 6.2: ViewModel tests — scope filtering + undo channel

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt`
**Test:** itself
**Depends:** 3.1, 3.2
**Domain:** general

The existing tests at lines 13-27 reference `SessionFilter.ARCHIVED` which no longer exists. Replace those two tests with new ones using `SessionScope`. Full updated file:

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.preferences.SessionFilter
import dev.minios.ocremote.data.preferences.SessionScope
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListViewModelTest {

    @Test
    fun archivedSessionsAreHiddenInInboxScope() {
        val item = SessionItem(session = testSession(id = "archived", archived = 1_000L), status = SessionStatus.Idle)

        assertFalse(matchesScopeAndFilter(item, SessionScope.INBOX, SessionFilter.ALL))
        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.ALL))
    }

    @Test
    fun activeSessionsAreHiddenInArchivedScope() {
        val item = SessionItem(session = testSession(id = "active", archived = null), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(item, SessionScope.INBOX, SessionFilter.ALL))
        assertFalse(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.ALL))
    }

    @Test
    fun statusFilterIsIgnoredInArchivedScope() {
        // An archived idle session would not match WORKING in inbox,
        // but in Archived scope status filters are forced to ALL.
        val item = SessionItem(session = testSession(id = "a", archived = 1_000L), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.WORKING))
        assertTrue(matchesScopeAndFilter(item, SessionScope.ARCHIVED, SessionFilter.HAS_ERRORS))
    }

    @Test
    fun inboxScopeStillRespectsStatusFilter() {
        val idleActive = SessionItem(session = testSession(id = "idle", archived = null), status = SessionStatus.Idle)

        assertTrue(matchesScopeAndFilter(idleActive, SessionScope.INBOX, SessionFilter.ALL))
        assertFalse(matchesScopeAndFilter(idleActive, SessionScope.INBOX, SessionFilter.WORKING))
    }

    @Test
    fun matchesScopeReportsArchivedFlagDirectly() {
        val active = SessionItem(session = testSession(id = "a", archived = null), status = SessionStatus.Idle)
        val archived = SessionItem(session = testSession(id = "b", archived = 1L), status = SessionStatus.Idle)

        assertTrue(matchesScope(active, SessionScope.INBOX))
        assertFalse(matchesScope(active, SessionScope.ARCHIVED))
        assertFalse(matchesScope(archived, SessionScope.INBOX))
        assertTrue(matchesScope(archived, SessionScope.ARCHIVED))
    }

    @Test
    fun archiveableRootSessionIdsOnlyIncludeActiveRootsInDirectory() {
        val ids = archiveableRootSessionIds(
            sessions = listOf(
                testSession(id = "root-active", directory = "/workspace/project", archived = null),
                testSession(id = "root-archived", directory = "/workspace/project", archived = 1_000L),
                testSession(id = "child-active", directory = "/workspace/project", parentId = "root-active", archived = null),
                testSession(id = "other-root", directory = "/workspace/other", archived = null),
            ),
            directory = "/workspace/project",
            normalizeDirectory = { it.trimEnd('/').ifEmpty { "/" } },
        )

        assertEquals(listOf("root-active"), ids)
    }

    @Test
    fun `deriveAllDirectories includes pinned empty directory`() {
        val directories = deriveAllDirectories(
            projectDirectories = emptyList(),
            rootSessionDirectories = emptyList(),
            pinnedDirectories = listOf("/workspace/empty-project"),
        )

        assertEquals(listOf("/workspace/empty-project"), directories)
    }

    @Test
    fun `deriveAllDirectories deduplicates pinned and active directories`() {
        val directories = deriveAllDirectories(
            projectDirectories = listOf("/workspace/project-a"),
            rootSessionDirectories = listOf("/workspace/project-b", "/workspace/project-a"),
            pinnedDirectories = listOf("/workspace/project-b", "/workspace/project-c"),
        )

        assertEquals(
            listOf("/workspace/project-a", "/workspace/project-b", "/workspace/project-c"),
            directories,
        )
    }

    @Test
    fun `pinned empty project group stays visible`() {
        val pinnedEmptyGroup = ProjectGroup(
            directory = "/workspace/empty-project",
            projectName = "empty-project",
            tildeDirectory = "~/empty-project",
            isPinned = true,
            isCollapsed = false,
            isHidden = false,
            sessionCount = 0,
            activeCount = 0,
            additionsSum = 0,
            deletionsSum = 0,
            sessions = emptyList(),
            subagentRowsByParent = emptyMap(),
        )
        val unpinnedEmptyGroup = pinnedEmptyGroup.copy(isPinned = false, directory = "/workspace/unpinned")

        assertTrue(isProjectGroupVisible(pinnedEmptyGroup, showHiddenProjects = false))
        assertFalse(isProjectGroupVisible(unpinnedEmptyGroup, showHiddenProjects = false))
    }

    @Test
    fun `pinDirectoryRefreshTargets keeps session refresh explicit after duplicate pin attempts`() {
        assertEquals(
            setOf(PinDirectoryRefreshTarget.PROJECTS, PinDirectoryRefreshTarget.SESSIONS),
            pinDirectoryRefreshTargets(changed = true),
        )
        assertEquals(
            setOf(PinDirectoryRefreshTarget.SESSIONS),
            pinDirectoryRefreshTargets(changed = false),
        )
    }

    private fun testSession(
        id: String,
        directory: String = "/workspace/project",
        parentId: String? = null,
        archived: Long? = null,
    ) = Session(
        id = id,
        directory = directory,
        parentId = parentId,
        time = Session.Time(
            created = 1L,
            updated = 1L,
            archived = archived,
        ),
    )
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests *SessionListViewModelTest` — all tests green.
**Commit:** `test(sessions): replace ARCHIVED-filter tests with scope-aware variants`

---

### Task 6.3: New `SessionRowSwipeTest`

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionRowSwipeTest.kt` (NEW)
**Test:** itself
**Depends:** 5.1
**Domain:** general

The actual `SessionRow` Composable is internal and uses Compose state; testing it via instrumentation tests is heavy. Instead, **extract a pure helper** during 5.1 and unit-test that. The helper expresses the swipe-direction → callback mapping:

> **Implementer note:** if Task 5.1 did NOT extract a helper, add this small helper in `SessionListScreen.kt` next to `sessionRowMenuActions`:
>
> ```kotlin
> internal fun resolveSwipeLeftAction(
>     scope: SessionScope,
>     onArchive: () -> Unit,
>     onRestore: () -> Unit,
> ) = when (scope) {
>     SessionScope.INBOX -> onArchive
>     SessionScope.ARCHIVED -> onRestore
> }
> ```
>
> And rewrite the `EndToStart` branch in `confirmValueChange` (Task 5.1 step 5.1.e Step 2) to call `resolveSwipeLeftAction(currentScope, onArchive, onRestore).invoke()` instead of an inline `when`. This makes the dispatch testable in pure JVM.

Then the test:

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.preferences.SessionScope
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRowSwipeTest {

    @Test
    fun `inbox swipe-left dispatches archive`() {
        var archived = 0
        var restored = 0
        val handler = resolveSwipeLeftAction(
            scope = SessionScope.INBOX,
            onArchive = { archived++ },
            onRestore = { restored++ },
        )
        handler.invoke()
        assertEquals(1, archived)
        assertEquals(0, restored)
    }

    @Test
    fun `archived swipe-left dispatches restore`() {
        var archived = 0
        var restored = 0
        val handler = resolveSwipeLeftAction(
            scope = SessionScope.ARCHIVED,
            onArchive = { archived++ },
            onRestore = { restored++ },
        )
        handler.invoke()
        assertEquals(0, archived)
        assertEquals(1, restored)
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests *SessionRowSwipeTest`
**Commit:** `test(sessions): cover scope-aware swipe-left dispatch`

---

### Task 6.4: Verify pre-existing tests still pass

**File:** none (verification task)
**Test:** existing
**Depends:** Batches 1-5
**Domain:** general

Run the existing suites that the design called out as "must not break":

```bash
./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.sessions.BuildActiveConversationsTest
./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.sessions.SessionListEmptyStateTest
./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.sessions.SessionRowMenuActionsTest
```

Then the full unit-test suite:

```bash
./gradlew :app:testDebugUnitTest
```

If any of `BuildActiveConversationsTest` / `SessionListEmptyStateTest` / `SessionRowMenuActionsTest` fail, **stop** — these tests are out of scope for change and a regression there means a bug was introduced upstream. Most likely cause: an import or signature change in `SessionListViewModel.kt` accidentally affected the helpers they exercise (`buildActiveConversations`, `partitionSubagentsByActivity`, `sessionRowMenuActions`).

**Verify:** all four commands above exit 0.
**Commit:** none (verification only — no file changes)

---

## Final smoke-test checklist (manual)

After all batches land:

1. Fresh install → opens in **Inbox** scope. Filter chips show ALL/Working/Has changes/Has errors (no Archived chip).
2. Long-press a row → multi-select mode → trash can deletes (regression check).
3. Left-swipe a row in Inbox → snackbar "Archived 'X' [Undo]" appears for ~4s; tap [Undo] → row returns; snackbar dismisses.
4. Tap **[Archived]** segment → list switches to archived sessions; filter chip row is HIDDEN; sort + search controls remain.
5. Left-swipe a row in Archived → snackbar "Restored 'X' [Undo]"; tap [Undo] → row returns to archived list.
6. Right-swipe in either scope → rename dialog (regression check).
7. Force-stop the app, manually corrupt DataStore (or simulate by reinstalling 1.6.x, archiving via old chip, then upgrading) → on first launch the migration kicks in: app opens in Archived scope with filter=ALL (visible to user as the segmented control's right tab being selected).
8. Disconnect network → swipe-archive → snackbar shows "Failed to archive, please retry" with no Undo button; row stays in Inbox.
9. Toggle [Inbox] [Archived] [Inbox] rapidly → no crashes; counts stay consistent.

---

## Rollback plan

If post-merge a critical bug surfaces:

1. Each batch corresponds to 1-3 commits; reverting Batches 5 + 6 brings the screen back to the old behavior while keeping the prefs/VM additions dormant (the new fields default to `INBOX` / `0`, behavior is identical to before).
2. Reverting Batch 2 alone leaves the migration off — fine, since `setScope` is the only writer of `SCOPE_KEY` and it's called only from the new UI.
3. Full revert: revert all six batches in reverse order. The migration is idempotent — re-applying after re-install does no harm.

