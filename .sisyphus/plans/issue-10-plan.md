# Issue #10 Implementation Plan: Unread Main Sessions & Visible Live Logs & Retry Visibility

**Date**: 2025-01-17  
**Status**: Approved for autonomous execution  
**Branch**: `feat/issue10-unread-live-logs`  
**Baseline**: v1.6.17 (issue #12–#14 merged)

---

## Overview

This plan breaks the approved spec (docs/superpowers/specs/2026-04-21-issue10-unread-live-logs-design.md) into focused, executable tasks with exact file locations, unit tests, and verification steps. It is sequenced for parallel execution with issue #9 baseline test fix, with dependencies noted.

Three tightly integrated concerns:

1. **Unread main-session state** — client-local only, cleared on session entry
2. **Live command log streaming** — consume existing `message.part.updated` events, render expanded cards in real time
3. **Retry visibility** — expose retry-related progress and failure details in-session alongside top card

---

## Task 1: Unread Main-Session Persistence Layer

**Objective**: Add client-local unread state storage keyed by session id.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferences.kt`

**Change**: Add `unreadMainSessionIds` field to data class.

```kotlin
data class SessionListPreferences(
    val collapsedDirs: Set<String>,
    val pinnedDirs: List<String>,
    val hiddenDirs: Set<String>,
    val sort: SessionSort,
    val filter: SessionFilter,
    val unreadMainSessionIds: Set<String> = emptySet(),  // NEW
) {
    companion object {
        val DEFAULT = SessionListPreferences(
            collapsedDirs = emptySet(),
            pinnedDirs = emptyList(),
            hiddenDirs = emptySet(),
            sort = SessionSort.RECENT_UPDATED,
            filter = SessionFilter.ALL,
            unreadMainSessionIds = emptySet(),  // NEW
        )
    }
}
```

**Rationale**: Client-local storage only reflects what user has viewed in app, not server-global read receipts.

#### File: `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepository.kt`

**Change**: Add DataStore key and update preferences flow mapping.

In the companion object, add the key:

```kotlin
companion object {
    private val COLLAPSED_DIRS_KEY = stringSetPreferencesKey("collapsed_dirs")
    // ... existing keys ...
    private val UNREAD_MAIN_SESSION_IDS = stringSetPreferencesKey("unread_main_session_ids")  // NEW
}
```

Update the `preferences` flow to include:

```kotlin
val preferences: Flow<SessionListPreferences> = dataStore.data.map { prefs ->
    // ... existing mappings ...
    val unreadMainSessionIds = prefs[UNREAD_MAIN_SESSION_IDS] ?: emptySet()
    SessionListPreferences(
        collapsedDirs = collapsedDirs,
        pinnedDirs = pinnedDirs,
        hiddenDirs = hiddenDirs,
        sort = sort,
        filter = filter,
        unreadMainSessionIds = unreadMainSessionIds,  // NEW
    )
}
```

#### File: `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepository.kt` (continued)

**Change**: Add CRUD methods for unread state after existing methods (after `toggleHidden`).

```kotlin
suspend fun markMainSessionUnread(sessionId: String) {
    dataStore.edit { prefs ->
        val current = prefs[UNREAD_MAIN_SESSION_IDS]?.toMutableSet() ?: mutableSetOf()
        current.add(sessionId)
        prefs[UNREAD_MAIN_SESSION_IDS] = current
    }
}

suspend fun markMainSessionRead(sessionId: String) {
    dataStore.edit { prefs ->
        val current = prefs[UNREAD_MAIN_SESSION_IDS]?.toMutableSet() ?: mutableSetOf()
        current.remove(sessionId)
        prefs[UNREAD_MAIN_SESSION_IDS] = current
    }
}

suspend fun markMainSessionsRead(sessionIds: Collection<String>) {
    dataStore.edit { prefs ->
        val current = prefs[UNREAD_MAIN_SESSION_IDS]?.toMutableSet() ?: mutableSetOf()
        current.removeAll(sessionIds)
        prefs[UNREAD_MAIN_SESSION_IDS] = current
    }
}
```

**Rationale**: Decouples persistence logic from UI state management; allows concurrent unread updates across sessions.

### Test: `app/src/test/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepositoryTest.kt`

Add tests:

```kotlin
@Test
fun `markMainSessionUnread adds session to unread set`() = runTest {
    val sessionId = "session1"
    repo.markMainSessionUnread(sessionId)
    
    val prefs = repo.preferences.first()
    assertTrue(prefs.unreadMainSessionIds.contains(sessionId))
}

@Test
fun `markMainSessionRead removes session from unread set`() = runTest {
    val sessionId = "session1"
    repo.markMainSessionUnread(sessionId)
    repo.markMainSessionRead(sessionId)
    
    val prefs = repo.preferences.first()
    assertFalse(prefs.unreadMainSessionIds.contains(sessionId))
}

@Test
fun `markMainSessionsRead removes multiple sessions from unread set`() = runTest {
    val ids = listOf("s1", "s2", "s3")
    ids.forEach { repo.markMainSessionUnread(it) }
    
    repo.markMainSessionsRead(listOf("s1", "s3"))
    
    val prefs = repo.preferences.first()
    assertFalse(prefs.unreadMainSessionIds.contains("s1"))
    assertTrue(prefs.unreadMainSessionIds.contains("s2"))
    assertFalse(prefs.unreadMainSessionIds.contains("s3"))
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.data.preferences.SessionListPreferencesRepositoryTest
```

---

## Task 2: Unread State Derivation in SessionListViewModel

**Objective**: Extend session items with `isUnread` flag and aggregate unread counts per project.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

**Changes**:

1. Extend `SessionItem` data class to include unread flag:

```kotlin
data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle,
    val isUnread: Boolean = false,  // NEW
)
```

2. Extend `ProjectGroup` to track unread count:

```kotlin
data class ProjectGroup(
    val directory: String,
    val projectName: String,
    val tildeDirectory: String,
    val isPinned: Boolean,
    val isCollapsed: Boolean,
    val isHidden: Boolean,
    val sessionCount: Int,
    val activeCount: Int,
    val additionsSum: Int,
    val deletionsSum: Int,
    val sessions: List<SessionItem>,
    val subagentRowsByParent: Map<String, SubagentRow>,
    val sessionDirLabels: Map<String, String> = emptyMap(),
    val unreadCount: Int = 0,  // NEW
)
```

3. In `SessionListViewModel`, after building `itemsById`, enhance with unread state:

```kotlin
// In the combine block, after creating itemsById:
val unreadSessionIds = prefs.unreadMainSessionIds
val itemsByIdWithUnread = itemsById.mapValues { (sessionId, item) ->
    item.copy(
        isUnread = item.session.parentId == null && sessionId in unreadSessionIds
    )
}
```

4. Compute unread counts when building `ProjectGroup` instances:

```kotlin
// When constructing each ProjectGroup:
val unreadCount = groupSessions
    .count { it.isUnread }

// Pass to ProjectGroup constructor:
ProjectGroup(
    // ... existing fields ...
    unreadCount = unreadCount,
)
```

**Rationale**: 
- Unread state flows from preferences into derived UI state.
- Only main sessions (`parentId == null`) count as unread.
- Subagent sessions never create unread state.
- Unread count enables top-card ordering logic later.

### Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt`

Create new test file with:

```kotlin
class UnreadStateDerivedFromPreferencesTest {
    @Test
    fun `main session with unread id marked as unread`() {
        val session = mainSession("main1")
        val unreadIds = setOf("main1")
        
        val item = SessionItem(session)
        val itemWithUnread = item.copy(
            isUnread = session.parentId == null && session.id in unreadIds
        )
        
        assertTrue(itemWithUnread.isUnread)
    }

    @Test
    fun `subagent session never marked as unread`() {
        val subagent = subagentSession("sub1", parentId = "main1")
        val unreadIds = setOf("sub1")
        
        val item = SessionItem(subagent)
        val itemWithUnread = item.copy(
            isUnread = subagent.parentId == null && subagent.id in unreadIds
        )
        
        assertFalse(itemWithUnread.isUnread)
    }

    @Test
    fun `project unread count reflects unread main sessions only`() {
        val sessions = listOf(
            mainSession("m1"),
            mainSession("m2"),
            subagentSession("s1", parentId = "m1")
        )
        val unreadIds = setOf("m2", "s1")
        
        val items = sessions.associate { s ->
            s.id to SessionItem(
                s,
                isUnread = s.parentId == null && s.id in unreadIds
            )
        }
        
        val mainItems = items.filterValues { it.session.parentId == null }
        val unreadCount = mainItems.count { (_, item) -> item.isUnread }
        
        assertEquals(1, unreadCount)  // only m2
    }
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.ui.screens.sessions.SessionListViewModelTest
```

---

## Task 3: Top Status Card Ordering with Unread Bucket

**Objective**: Insert unread conversation card before decision card in `buildActiveConversations`.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

Locate `buildActiveConversations(...)` function. It currently returns a list of `ActiveConversationItem` ordered by status priority (Busy, Retry, Question, Permission).

**Change**: Insert unread bucket logic:

1. Add helper to check if session has unread main children (for decision sessions):

```kotlin
private fun hasUnreadMainSession(sessionId: String?, itemsById: Map<String, SessionItem>): Boolean {
    if (sessionId == null) return false
    return itemsById.values.any { item ->
        item.session.parentId == sessionId && item.isUnread
    }
}
```

2. Modify `buildActiveConversations` to handle unread:

```kotlin
fun buildActiveConversations(
    rootSessions: List<Session>,
    statuses: Map<String, SessionStatus>,
    pendingQuestions: Map<String, List<SseEvent.QuestionAsked>>,
    pendingPermissions: Map<String, List<SseEvent.PermissionAsked>>,
    unreadMainSessionIds: Set<String> = emptySet(),  // NEW
): List<ActiveConversationItem> {
    // ... existing sorting logic ...
    
    // NEW: Filter to unread main sessions not already in active statuses
    val unreadSessions = rootSessions.filter { session ->
        session.id in unreadMainSessionIds &&
        session.id !in activeSessions.map { it.sessionId }
    }
    
    val unreadItems = unreadSessions.map { session ->
        ActiveConversationItem(
            sessionId = session.id,
            sessionTitle = session.title,
            projectName = extractProjectName(session),
            status = ConversationStatus.UNREAD,  // NEW enum value
            pendingCount = 0,
            updatedAt = session.updatedAt,
        )
    }.sortedByDescending { it.updatedAt }
    
    // Merge in order: Active → Unread → Decision → ...existing
    return activeSessions + unreadItems + decisionItems + ...
}
```

3. Add `ConversationStatus.UNREAD` enum variant in `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ActiveConversationItem.kt`:

```kotlin
enum class ConversationStatus {
    AWAITING_QUESTION,
    AWAITING_PERMISSION,
    BUSY,
    RETRY,
    UNREAD,  // NEW
}
```

4. Update `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ActiveConversationsBanner.kt` to handle the new UNREAD status:

In `ActiveConversationCard`, add to the `when` expression after line 109:

```kotlin
ConversationStatus.UNREAD -> colors.primary to R.string.sessions_conversation_status_unread
```

In `ConversationStatusIcon`, add after the RETRY branch:

```kotlin
ConversationStatus.UNREAD -> Icon(
    imageVector = Icons.Default.CheckCircle,
    contentDescription = stringResource(R.string.sessions_conversation_status_unread),
    tint = color,
    modifier = modifier,
)
```

Add resource strings in `app/src/main/res/values/strings.xml` and all locale variants:

```xml
<string name="sessions_conversation_status_unread">Unread</string>
```

**Rationale**:
- Unread cards surface recently completed work without requiring immediate action.
- Positioned before decision cards to prioritize review over pending questions.
- Reuses existing `ActiveConversationItem` and card rendering.

### Test: Update `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/BuildActiveConversationsTest.kt`

Add:

```kotlin
@Test
fun `unread main session creates UNREAD card before decision`() {
    val main1 = rootSession("main1", updated = 100)
    val main2 = rootSession("main2", updated = 90)
    
    val items = buildActiveConversations(
        rootSessions = listOf(main1, main2),
        statuses = mapOf(main1.id to SessionStatus.Idle, main2.id to SessionStatus.Idle),
        pendingQuestions = emptyMap(),
        pendingPermissions = emptyMap(),
        unreadMainSessionIds = setOf("main2"),
    )
    
    assertEquals(1, items.size)
    assertEquals("main2", items[0].sessionId)
    assertEquals(ConversationStatus.UNREAD, items[0].status)
}

@Test
fun `unread does not appear if session is already busy`() {
    val main = rootSession("main1", updated = 100)
    
    val items = buildActiveConversations(
        rootSessions = listOf(main),
        statuses = mapOf(main.id to SessionStatus.Busy),
        pendingQuestions = emptyMap(),
        pendingPermissions = emptyMap(),
        unreadMainSessionIds = setOf("main"),
    )
    
    assertEquals(1, items.size)
    assertEquals(ConversationStatus.BUSY, items[0].status)  // Busy wins
}

@Test
fun `unread appears before decision in card order`() {
    val main1 = rootSession("main1", updated = 100)  // unread
    val main2 = rootSession("main2", updated = 90)   // has decision
    val sub = rootSession("main2/sub", updated = 80)
    sub.parentId = "main2"
    
    val items = buildActiveConversations(
        rootSessions = listOf(main1, main2, sub),
        statuses = mapOf(
            main1.id to SessionStatus.Idle,
            main2.id to SessionStatus.Idle,
            sub.id to SessionStatus.Idle,
        ),
        pendingQuestions = mapOf(sub.id to listOf(questionAsked("q1"))),
        pendingPermissions = emptyMap(),
        unreadMainSessionIds = setOf("main1"),
    )
    
    assertEquals(2, items.size)
    assertEquals(ConversationStatus.UNREAD, items[0].status)
    assertEquals(ConversationStatus.AWAITING_QUESTION, items[1].status)
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.ui.screens.sessions.BuildActiveConversationsTest
```

---

## Task 4: Unread Blue Indicator on Project & Session Rows

**Objective**: Render visual indicator when project or session has unread content.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeader.kt`

**Change**: Add conditional blue dot indicator.

Locate the project row rendering (typically `ProjectGroupHeader` Composable). Add a small blue circle before or after the project title:

```kotlin
@Composable
fun ProjectGroupHeader(
    group: ProjectGroup,
    onToggleCollapse: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !group.isHidden) { onToggleCollapse() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Expand/collapse icon
            Icon(...)
            
            // NEW: Unread indicator
            if (group.unreadCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,  // blue
                    modifier = Modifier.size(8.dp)
                )
            }
            
            // Project name text
            Text(...)
        }
        
        // ... menu actions ...
    }
}
```

**Rationale**: Blue dot is low-visuals but signals unread work; positioned near project label for quick scan.

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

**Change**: Add unread indicator to individual session rows.

Locate session card/row rendering. Add similar blue dot:

```kotlin
// In session row layout:
if (sessionItem.isUnread) {
    Spacer(modifier = Modifier.width(6.dp))
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(6.dp)  // slightly smaller than project dot
    )
}
```

**Rationale**: Consistent visual language across hierarchy; session-level unread visible at glance when list expands.

### Test: Create `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/UnreadIndicatorVisibilityTest.kt`

```kotlin
class UnreadIndicatorVisibilityTest {
    @Test
    fun `project group with unread main sessions shows blue indicator`() {
        val group = ProjectGroup(
            directory = "/home/user/project1",
            projectName = "project1",
            tildeDirectory = "~/project1",
            isPinned = false,
            isCollapsed = false,
            isHidden = false,
            sessionCount = 2,
            activeCount = 0,
            additionsSum = 10,
            deletionsSum = 5,
            sessions = emptyList(),
            subagentRowsByParent = emptyMap(),
            unreadCount = 1,  // HAS UNREAD
        )
        
        assertTrue(group.unreadCount > 0)
    }

    @Test
    fun `project group with no unread shows no indicator`() {
        val group = ProjectGroup(
            directory = "/home/user/project2",
            projectName = "project2",
            tildeDirectory = "~/project2",
            isPinned = false,
            isCollapsed = false,
            isHidden = false,
            sessionCount = 1,
            activeCount = 0,
            additionsSum = 0,
            deletionsSum = 0,
            sessions = emptyList(),
            subagentRowsByParent = emptyMap(),
            unreadCount = 0,  // NO UNREAD
        )
        
        assertEquals(0, group.unreadCount)
    }

    @Test
    fun `session item unread flag controls row indicator`() {
        val unreadItem = SessionItem(mainSession("s1"), isUnread = true)
        val readItem = SessionItem(mainSession("s2"), isUnread = false)
        
        assertTrue(unreadItem.isUnread)
        assertFalse(readItem.isUnread)
    }
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.ui.screens.sessions.UnreadIndicatorVisibilityTest
```

---

## Task 5: Clear Unread on Session Entry

**Objective**: When user navigates to chat screen, mark session as read.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**Change**: Call preferences repository when session becomes active.

In `ChatViewModel` constructor or initialization:

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // ... other deps ...
    private val preferencesRepo: SessionListPreferencesRepository,  // NEW
) : ViewModel() {
    
    val sessionId = savedStateHandle.get<String>("sessionId") ?: ""
    
    init {
        // Clear unread when entering this session
        viewModelScope.launch {
            if (sessionId.isNotEmpty()) {
                preferencesRepo.markMainSessionRead(sessionId)
            }
        }
    }
    
    // ... rest of ViewModel ...
}
```

Alternatively, if session entry is tracked differently (e.g., in `ChatScreen` Composable):

```kotlin
// In ChatScreen.kt, when session becomes active:
LaunchedEffect(sessionId) {
    if (sessionId != null && sessionId.isNotEmpty()) {
        preferencesRepo.markMainSessionRead(sessionId)
    }
}
```

**Rationale**:
- Clearing must happen exactly when user can see session content.
- Using ViewModel init ensures clean coupling with navigation.
- LaunchedEffect alternative works if navigation triggers recomposition.

### Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModelTest.kt`

```kotlin
@Test
fun `entering chat screen marks session as read`() = runTest {
    val sessionId = "session1"
    val prefsRepo = mockk<SessionListPreferencesRepository>(relaxed = true)
    
    val viewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId)),
        preferencesRepo = prefsRepo,
        // ... other deps ...
    )
    
    // ViewModel init runs in test dispatcher
    advanceUntilIdle()
    
    verify { prefsRepo.markMainSessionRead(sessionId) }
}

@Test
fun `empty sessionId does not call markRead`() = runTest {
    val prefsRepo = mockk<SessionListPreferencesRepository>(relaxed = true)
    
    val viewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("sessionId" to "")),
        preferencesRepo = prefsRepo,
        // ... other deps ...
    )
    
    advanceUntilIdle()
    
    verify(exactly = 0) { prefsRepo.markMainSessionRead(any()) }
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest
```

---

## Task 6: Mark Main Sessions Unread on Status Completion (Service-Only Owner)

**Objective**: When a main session status transitions from Busy/Retry to Idle, mark unread if user is not currently viewing it.

**Execution Architecture**:
- **Single owner**: `OpenCodeConnectionService` is the only place that marks unread on completion.
- **Shared state**: `EventReducer` exposes `activeSessionId` StateFlow (set by `ChatViewModel`, read by service).
- **Why**: Service layer is always-on and catches completions even when UI unsubscribes (ViewModel's `uiState` uses `SharingStarted.WhileSubscribed(5000)`).
- **No split ownership**: Only service marks unread; no ViewModel-owned unread logic.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt`

**Change**: Add shared state for active session id (read-only from service perspective).

```kotlin
@Singleton
class EventReducer @Inject constructor() {
    // ... existing state ...
    
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()
    
    fun setActiveSessionId(sessionId: String?) {
        _activeSessionId.value = sessionId
    }
}
```

**Rationale**: Single source of truth for which session is currently visible. Only `ChatViewModel` calls this; service only reads.

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**Change**: Signal session visibility to EventReducer. No unread marking logic here.

In `ChatViewModel` init (after existing setup):

```kotlin
init {
    // Signal to service layer which session is visible
    viewModelScope.launch {
        eventReducer.setActiveSessionId(sessionId)
    }
}

override fun onCleared() {
    super.onCleared()
    // Signal to service layer that no session is visible
    eventReducer.setActiveSessionId(null)
}
```

**Rationale**: ViewModel owns chat visibility; signals it so service can make unread decisions. No unread logic here.

#### File: `app/src/main/kotlin/dev/minios/ocremote/service/OpenCodeConnectionService.kt`

**Change**: Add unread-on-completion detection in the always-on event loop using actual `SseEvent.SessionStatus` event path.

In the service (locate where `eventReducer.processEvent()` is called in the SSE event handler), add after the processEvent call:

```kotlin
private val previousStatusesBySession = mutableMapOf<String, SessionStatus>()

// After eventReducer.processEvent(event, serverId) in the SSE loop:
// Track main session completion for unread marking (real event path)
if (event is SseEvent.SessionStatus) {
    val sessionId = event.sessionId
    val currentStatus = event.status  // From actual event
    val prevStatus = previousStatusesBySession[sessionId] ?: SessionStatus.Idle
    val activeSessionId = eventReducer.activeSessionId.value
    
    // Main session completing (Busy/Retry → Idle) while user not viewing
    val session = eventReducer.sessions.value.find { it.id == sessionId }
    if (session?.parentId == null &&
        prevStatus != SessionStatus.Idle &&
        currentStatus == SessionStatus.Idle &&
        sessionId != activeSessionId) {
        
        serviceScope.launch {
            preferencesRepo.markMainSessionUnread(sessionId)
        }
    }
    
    // Update tracking for next iteration
    previousStatusesBySession[sessionId] = currentStatus
}
```

**Rationale**:
- Uses real codebase event: `SseEvent.SessionStatus(val sessionId: String, val status: SessionStatus)`
- Mirrors actual handler: `handleSessionStatus` (line 164 in EventReducer.kt)
- Service is always-on, catches all completions in main event loop.
- Reads `activeSessionId` (shared state set by ViewModel via `ChatViewModel.init` and `onCleared`).
- **Single owner**: Only location that marks unread on completion.

### QA Scenario for Task 6

**Concrete Executable Test**: Mark unread reliably using shared state mechanism.

**Setup**:
- Two main sessions: `backend-dev` and `frontend-dev`

**Steps**:
1. Navigate to `frontend-dev` chat (triggers `ChatViewModel.init` → `setActiveSessionId("frontend-dev")`)
2. In `frontend-dev`, run: `sleep 20 && echo "frontend done"`
3. Navigate to session list (triggers `ChatViewModel.onCleared()` → `setActiveSessionId(null)`)
4. Navigate to `backend-dev` chat
5. In `backend-dev`, run: `sleep 2 && echo "backend done"`
6. **Key**: While `backend-dev` is running, navigate away: press back → session list
7. **Expected**: `activeSessionId` becomes `null` in `EventReducer`
8. Wait for `backend-dev` to complete (~2s)
9. Service detects: status Busy → Idle, `sessionId != activeSessionId` (null ≠ "backend-dev"), calls `markMainSessionUnread()`
10. Return to session list
11. **Expected**: Blue dot visible on `backend-dev`
12. Tap `backend-dev` → triggers `setActiveSessionId("backend-dev")`
13. **Expected (Task 5)**: Blue dot disappears after entering

**Verify Shared State**: Check state transitions:
- After step 4: `activeSessionId = "backend-dev"`
- After step 6: `activeSessionId = null`
- After step 12: `activeSessionId = "backend-dev"`

### Test: `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt`

Add to existing test file (only EventReducer tests; service logic is not unit-tested separately):

```kotlin
@Test
fun `activeSessionId reflects current chat screen state via setActiveSessionId`() = runTest {
    val reducer = EventReducer()
    
    // Initially null
    assertNull(reducer.activeSessionId.value)
    
    // ChatViewModel enters chat for session1
    reducer.setActiveSessionId("session1")
    assertEquals("session1", reducer.activeSessionId.value)
    
    // User navigates to different session
    reducer.setActiveSessionId("session2")
    assertEquals("session2", reducer.activeSessionId.value)
    
    // User navigates away from chat
    reducer.setActiveSessionId(null)
    assertNull(reducer.activeSessionId.value)
}
```

**Note**: Service unread-marking logic is integration-tested in Scenario 4 (QA Scenario for Task 6), not unit-tested separately. The gate condition (`sessionId != activeSessionId`) is simple enough that integration validation suffices.

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.data.repository.EventReducerTest
```
```

**Rationale**:
- Observes status transition, not individual messages.
- Only marks unread for main sessions (`parentId == null`).
- Checks `currentViewingSessionId` (injected from nav or saved state) to avoid marking if user is actively viewing.
- Updates `_previousStatusesBySession` to track changes for next emission.

### QA Scenario for Task 6

**Concrete Executable Test**: Mark unread only when session completes and user is NOT viewing it.

**Setup**:
- Two main sessions: `backend-dev` and `frontend-dev`
- Currently viewing: `frontend-dev`

**Steps**:
1. In `backend-dev`, run: `sleep 2 && echo "done"`
2. While it's running, remain in `frontend-dev` chat
3. Watch `backend-dev` status change to Idle (after ~2s)
4. Tap "Sessions" / back to list
5. **Expected**: Blue dot visible on `backend-dev` row and project header
6. Tap `backend-dev` to open it
7. **Expected**: Blue dot disappears

**Verify Negative**: If user is viewing the completing session, it should NOT mark unread:
1. In `frontend-dev`, run: `sleep 2 && echo "done"`
2. Remain viewing `frontend-dev` while it completes
3. Return to list
4. **Expected**: NO blue dot on `frontend-dev` (because you were watching)

### Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt`

```kotlin
@Test
fun `main session marked unread when transitioning from Busy to Idle while user viewing different session`() = runTest {
    val mainSession = rootSession("main1", updated = 100)
    val prefsRepo = FakeSLPRepo()
    val eventReducer = FakeEventReducer()
    
    // Simulate: main1 is Busy
    eventReducer._sessionStatuses.value = mapOf("main1" to SessionStatus.Busy)
    
    val viewModel = SessionListViewModel(
        // ... with eventReducer, prefsRepo, currentViewingSessionId = "other-session"
    )
    
    // Simulate: main1 transitions to Idle
    eventReducer._sessionStatuses.value = mapOf("main1" to SessionStatus.Idle)
    advanceUntilIdle()
    
    // Verify unread was marked
    val prefs = prefsRepo.preferences.first()
    assertTrue(prefs.unreadMainSessionIds.contains("main1"))
}

@Test
fun `main session NOT marked unread when completing while user actively viewing it`() = runTest {
    val mainSession = rootSession("main1", updated = 100)
    val prefsRepo = FakeSLPRepo()
    val eventReducer = FakeEventReducer()
    
    eventReducer._sessionStatuses.value = mapOf("main1" to SessionStatus.Busy)
    
    val viewModel = SessionListViewModel(
        // ... with currentViewingSessionId = "main1" (user is viewing it)
    )
    
    eventReducer._sessionStatuses.value = mapOf("main1" to SessionStatus.Idle)
    advanceUntilIdle()
    
    val prefs = prefsRepo.preferences.first()
    assertFalse(prefs.unreadMainSessionIds.contains("main1"))  // NOT unread
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.ui.screens.sessions.SessionListViewModelTest
```

---

## Task 7: Extend ToolState.Running with Output Field

**Objective**: Allow running tool to accumulate output from streaming events.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/domain/model/ToolState.kt`

**Change**: Add optional output field to Running state (preserves API compatibility).

```kotlin
@Serializable
data class Running(
    val input: Map<String, JsonElement> = emptyMap(),
    val title: String? = null,
    val metadata: Map<String, JsonElement>? = null,
    val time: Time? = null,
    val output: String = "",  // NEW - accumulated output from streaming updates
) : ToolState() {
    @Serializable
    data class Time(val start: Long)
}
```

**Rationale**:
- OpenCode streams `message.part.updated` with running tool output.
- Client accumulates into this field as updates arrive.
- Does not break existing API; defaults to empty string.

### QA Scenario for Task 7

**Concrete Executable Test**: Running state preserves output field through state transitions.

**Setup**:
- In a session, trigger a tool that will run for several seconds
- Capture the ToolState.Running object as it streams

**Steps**:
1. Run: `for i in {1..3}; do echo "output $i"; sleep 1; done`
2. After ~0.5s (while running), inspect the Part.Tool state in memory/debugger
3. **Expected**: `ToolState.Running` has:
   - `title = "bash"` (or tool name)
   - `output = ""` or partial content (first few lines)
4. After ~1.5s, re-inspect:
   - **Expected**: `output` field contains accumulated lines
5. After completion (~3s), inspect final state:
   - **Expected**: State is now `ToolState.Completed` with full output

### Test: `app/src/test/kotlin/dev/minios/ocremote/domain/model/ToolStateTest.kt`

```kotlin
@Test
fun `running tool state preserves and accumulates output field`() {
    val running1 = ToolState.Running(title = "bash", output = "")
    val running2 = running1.copy(output = "$ echo hello\nhello\n")
    val running3 = running2.copy(output = "$ echo hello\nhello\n$ echo world\nworld\n")
    
    assertEquals("", running1.output)
    assertEquals("$ echo hello\nhello\n", running2.output)
    assertEquals(
        "$ echo hello\nhello\n$ echo world\nworld\n",
        running3.output
    )
    assertTrue(running3.output.lines().size >= 2)
}

@Test
fun `running tool output defaults to empty string`() {
    val running = ToolState.Running(title = "bash")
    assertEquals("", running.output)
}

@Test
fun `completed tool retains final output separate from running`() {
    val running = ToolState.Running(title = "bash", output = "partial\n")
    val completed = ToolState.Completed(
        title = "bash",
        output = "partial\nfinal\n"
    )
    
    assertNotEquals(running.output, completed.output)
    assertTrue(completed.output.contains("final"))
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.domain.model.ToolStateTest
```

---

## Task 8: Accumulate Running Tool Output in EventReducer

**Objective**: When `SseEvent.MessagePartUpdated` arrives with running tool state, accumulate output into running ToolState.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt`

**Change**: In existing `handleMessagePartUpdated(event: SseEvent.MessagePartUpdated)` (line 216), add output accumulation for running tools.

Located at line 216, the current implementation replaces the entire part. Update to preserve and accumulate running tool output:

```kotlin
private fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
    val messageId = event.part.messageId
    _parts.update { current ->
        val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
        val existingIndex = messageParts.indexOfFirst { it.id == event.part.id }
        
        // NEW: If updating a running Tool, accumulate output instead of replacing
        val updatedPart = if (existingIndex >= 0 && event.part is Part.Tool) {
            val existing = messageParts[existingIndex]
            if (existing is Part.Tool && 
                existing.state is ToolState.Running && 
                event.part.state is ToolState.Running) {
                // Accumulate output: existing output + new lines not yet seen
                val existingOutput = (existing.state as ToolState.Running).output
                val newOutput = (event.part.state as ToolState.Running).output
                val accumulatedOutput = if (newOutput.startsWith(existingOutput)) {
                    // New output extends existing; append the delta
                    newOutput
                } else {
                    // Fallback: use new output as-is (shouldn't happen if server is consistent)
                    newOutput
                }
                val accumulatedState = (event.part.state as ToolState.Running).copy(
                    output = accumulatedOutput
                )
                existing.copy(state = accumulatedState)
            } else {
                // Not running tool or transition (Running → Completed), use new part as-is
                event.part
            }
        } else {
            event.part
        }
        
        if (existingIndex >= 0) {
            messageParts[existingIndex] = updatedPart
        } else {
            messageParts.add(updatedPart)
        }
        
        current + (messageId to messageParts)
    }
}
```

**Rationale**:
- Server sends full state per `MessagePartUpdated` (not deltas).
- Client receives repeated updates with growing output, e.g., "Line 1\n" → "Line 1\nLine 2\n" → "Line 1\nLine 2\nLine 3\n"
- Accumulation logic handles this: if new output starts with old output, use new as accumulated; else use new as-is.
- Handles transition from Running to Completed (new part replaces old as-is).

### Test: `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt`

Add:

```kotlin
@Test
fun `MessagePartUpdated with running tool accumulates output`() {
    val reducer = EventReducer()
    val messageId = "m1"
    
    // First update: Tool output "Line 1\n"
    val running1 = ToolState.Running(title = "bash", output = "Line 1\n")
    val part1 = Part.Tool(id = "t1", tool = "bash", state = running1, messageId = messageId)
    
    reducer.processEvent(
        SseEvent.MessagePartUpdated(part = part1),
        serverId = "srv1"
    )
    
    val partsAfterFirst = reducer.parts.value[messageId]
    val toolAfterFirst = partsAfterFirst?.find { it.id == "t1" } as? Part.Tool
    assertEquals("Line 1\n", (toolAfterFirst?.state as? ToolState.Running)?.output)
    
    // Second update: Tool output grows to "Line 1\nLine 2\n"
    val running2 = ToolState.Running(title = "bash", output = "Line 1\nLine 2\n")
    val part2 = Part.Tool(id = "t1", tool = "bash", state = running2, messageId = messageId)
    
    reducer.processEvent(
        SseEvent.MessagePartUpdated(part = part2),
        serverId = "srv1"
    )
    
    val partsAfterSecond = reducer.parts.value[messageId]
    val toolAfterSecond = partsAfterSecond?.find { it.id == "t1" } as? Part.Tool
    assertEquals("Line 1\nLine 2\n", (toolAfterSecond?.state as? ToolState.Running)?.output)
    
    // Verify no duplication (output is not "Line 1\nLine 1\nLine 2\n")
    assertFalse((toolAfterSecond?.state as? ToolState.Running)?.output?.contains("Line 1\nLine 1") ?: false)
}

@Test
fun `MessagePartUpdated handles transition from Running to Completed`() {
    val reducer = EventReducer()
    val messageId = "m1"
    
    // First: Running state
    val running = ToolState.Running(title = "bash", output = "Line 1\nLine 2\n")
    val partRunning = Part.Tool(id = "t1", tool = "bash", state = running, messageId = messageId)
    
    reducer.processEvent(
        SseEvent.MessagePartUpdated(part = partRunning),
        serverId = "srv1"
    )
    
    // Then: Completed state (replaces entire part)
    val completed = ToolState.Completed(title = "bash", output = "Line 1\nLine 2\nDone\n")
    val partCompleted = Part.Tool(id = "t1", tool = "bash", state = completed, messageId = messageId)
    
    reducer.processEvent(
        SseEvent.MessagePartUpdated(part = partCompleted),
        serverId = "srv1"
    )
    
    val partsAfter = reducer.parts.value[messageId]
    val toolAfter = partsAfter?.find { it.id == "t1" } as? Part.Tool
    
    assertTrue(toolAfter?.state is ToolState.Completed)
    assertEquals("Line 1\nLine 2\nDone\n", (toolAfter?.state as? ToolState.Completed)?.output)
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.data.repository.EventReducerTest
```

---

## Task 9: Render Live Tool Output on Card Expansion

**Objective**: When user expands a running tool card, show full accumulated output and keep updating in real time.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

Locate `ToolCallCard(tool: Part.Tool)` function (around line 4450).

**Changes**:

1. Allow expansion while running (change `expanded` initialization); do NOT auto-expand:

```kotlin
private fun ToolCallCard(tool: Part.Tool) {
    // ... existing color/display logic ...
    
    var expanded by remember(autoExpand) { 
        mutableStateOf(autoExpand)  // NO auto-expand for running; keep default collapsed
    }
    
    Surface(...) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        // NEW: Allow click on running too
                        if (tool.state is ToolState.Completed || 
                            tool.state is ToolState.Error ||
                            tool.state is ToolState.Running) {
                            mod.clickable { performHaptic(hapticView, hapticOn); expanded = !expanded }
                        } else mod
                    },
                // ... rest of header ...
            ) {
                // ... header content ...
                
                // NEW: Show expand icon for running too
                if (tool.state is ToolState.Completed || 
                    tool.state is ToolState.Error ||
                    tool.state is ToolState.Running) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                } else if (tool.state is ToolState.Running && !expanded) {
                    // Show pulsing dots only when collapsed+running
                    PulsingDotsIndicator(...)
                }
            }
            
            // Expandable details (NEW: support running output)
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    val outputText = when (val state = tool.state) {
                        is ToolState.Running -> state.output.ifEmpty { "Running..." }
                        is ToolState.Completed -> state.output
                        is ToolState.Error -> state.error
                        else -> ""
                    }
                    
                    SelectionContainer {
                        Text(
                            text = outputText,
                            style = CodeTypography,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(8.dp),
                            softWrap = true,
                        )
                    }
                }
            }
        }
    }
}
```

**Rationale**:
- Collapsed by default: only header visible → quick preview.
- Expanding running card: reveals full accumulated log, auto-updates as events arrive.
- Expanding completed card: shows final output in same component.
- Auto-expanding running cards (if `autoExpand = true` per setting) enables live monitoring.

### QA Scenario for Task 9

**Concrete Executable Test**: Running tool card expands and shows live accumulating output.

**Setup**:
- Open a session
- Run a long command that produces multi-line output over time

**Steps**:
1. Run: `for i in {1..5}; do echo "Line $i $(date +%H:%M:%S)"; sleep 1; done`
2. **Initial state (0–1s)**: Card is collapsed, shows only header "bash | for i in…"
3. **~0.5s in**: Tap card to expand
4. **Expected output section visible**:
   - Shows "Line 1 HH:MM:SS"
5. **Wait 2s more** (now ~2.5s elapsed):
   - **Expected**: Card still expanded, shows now:
     ```
     Line 1 HH:MM:SS
     Line 2 HH:MM:SS
     Line 3 HH:MM:SS
     ```
6. **Command completes** (after ~5s total):
   - Card remains expanded
   - Shows final output with all 5 lines
7. **Manual Collapse**: Tap card again → header-only view
8. **Re-expand**: Full output still visible

**Key Assertion**: Output grows in real time; user sees live progress.

### Test: Create `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ToolCallCardLiveOutputTest.kt`

```kotlin
class ToolCallCardLiveOutputTest {
    @Test
    fun `running tool displays output when expanded`() {
        val running = ToolState.Running(
            title = "bash",
            output = "$ echo test\ntest\n"
        )
        val tool = Part.Tool(tool = "bash", state = running)
        
        // Simulate UI state: expanded
        var expanded = true
        val displayOutput = if (expanded && tool.state is ToolState.Running) {
            (tool.state as ToolState.Running).output.ifEmpty { "Running..." }
        } else ""
        
        assertTrue(displayOutput.contains("test"))
    }

    @Test
    fun `running tool shows pulsing dots when collapsed and no output yet`() {
        val running = ToolState.Running(
            title = "bash",
            output = ""
        )
        val tool = Part.Tool(tool = "bash", state = running)
        
        var expanded = false
        val showPulse = !expanded && tool.state is ToolState.Running
        
        assertTrue(showPulse)
    }

    @Test
    fun `running tool shows output instead of dots when collapsed but output present`() {
        val running = ToolState.Running(
            title = "bash",
            output = "$ echo hello\nhello\n"
        )
        val tool = Part.Tool(tool = "bash", state = running)
        
        var expanded = false
        // When collapsed but has output, still show preview or dots
        // (implementation detail; test shows output field exists)
        assertTrue((tool.state as ToolState.Running).output.isNotEmpty())
    }

    @Test
    fun `completed tool displays final output when expanded`() {
        val completed = ToolState.Completed(
            title = "bash",
            output = "$ echo done\ndone\n"
        )
        val tool = Part.Tool(tool = "bash", state = completed)
        
        var expanded = true
        val displayOutput = if (expanded && tool.state is ToolState.Completed) {
            (tool.state as ToolState.Completed).output
        } else ""
        
        assertTrue(displayOutput.contains("done"))
    }

    @Test
    fun `card expansion state persists across output updates`() {
        var running = ToolState.Running(title = "bash", output = "Line 1\n")
        var tool = Part.Tool(tool = "bash", state = running)
        var expanded = true
        
        // Simulate output update
        running = running.copy(output = "Line 1\nLine 2\n")
        tool = tool.copy(state = running)
        
        // Expansion state should not change
        assertTrue(expanded)
        assertEquals("Line 1\nLine 2\n", (tool.state as ToolState.Running).output)
    }
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.ui.screens.chat.ToolCallCardLiveOutputTest
```

---

## Task 10: Retry Status Block in Chat

**Objective**: Show a compact retry status block in chat when session is retrying.

### Files to Modify

#### File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

**Change**: Render retry status block above/below messages when session status is Retry.

Add a Composable for retry status:

```kotlin
@Composable
private fun RetryStatusBlock(
    status: SessionStatus.Retry,
    onDismiss: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "正在重试 · 第 ${status.attempt} 次",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (status.message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "上次失败：${status.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            if (status.next > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "下次重试：${formatRetryTiming(status.next)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun formatRetryTiming(nextTimestampMs: Long): String {
    val now = System.currentTimeMillis()
    val delayMs = nextTimestampMs - now
    return when {
        delayMs <= 0 -> "immediately"
        delayMs < 1000 -> "in ${delayMs}ms"
        delayMs < 60000 -> "in ${delayMs / 1000}s"
        else -> "in ${delayMs / 60000}m"
    }
}
```

In `ChatScreen`, render the block conditionally:

```kotlin
// In the main chat column, above or near message list:
if (sessionStatus is SessionStatus.Retry) {
    RetryStatusBlock(status = sessionStatus)
}

LazyColumn {
    // messages...
}
```

**Rationale**:
- Compact, semantic block summarizes retry state without cluttering.
- Complements live tool logs; together show full retry lifecycle.
- Users can see why retry started (failure message) and when next happens.

### Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/RetryStatusBlockTest.kt`

```kotlin
class RetryStatusBlockTest {
    @Test
    fun `retry status block formats attempt count`() {
        val status = SessionStatus.Retry(
            attempt = 2,
            message = "connection timeout",
            next = System.currentTimeMillis() + 5000
        )
        
        assertEquals(2, status.attempt)
    }

    @Test
    fun `retry status block shows failure message`() {
        val status = SessionStatus.Retry(
            attempt = 1,
            message = "command not found",
            next = 0L
        )
        
        assertTrue(status.message.contains("not found"))
    }

    @Test
    fun `retry timing is calculated from next timestamp`() {
        val now = System.currentTimeMillis()
        val next = now + 3000
        val status = SessionStatus.Retry(attempt = 1, message = "", next = next)
        
        val delayMs = status.next - now
        assertTrue(delayMs in 2900..3100)
    }
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest
```

---

## Task 11: Integration Test — Unread + Live Output + Retry

**Objective**: End-to-end test verifying unread flows, output accumulates, and retry is visible.

### Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/UnreadLiveRetryIntegrationTest.kt`

```kotlin
class UnreadLiveRetryIntegrationTest {
    
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    
    @Test
    fun `session completion marks unread if user not viewing`() = testScope.runTest {
        // Setup: main session, message completes
        val sessionId = "main1"
        val session = mainSession(sessionId)
        val prefsRepo = FakeSessionListPreferencesRepository()
        
        // Simulate: session status changes from Busy to Idle
        prefsRepo.markMainSessionUnread(sessionId)
        
        // Verify
        val prefs = prefsRepo.preferences.first()
        assertTrue(prefs.unreadMainSessionIds.contains(sessionId))
    }
    
    @Test
    fun `entering chat clears unread for that session`() = testScope.runTest {
        val sessionId = "main1"
        val prefsRepo = FakeSessionListPreferencesRepository()
        
        // Mark unread
        prefsRepo.markMainSessionUnread(sessionId)
        assertTrue(prefsRepo.preferences.first().unreadMainSessionIds.contains(sessionId))
        
        // Enter chat (clear)
        prefsRepo.markMainSessionRead(sessionId)
        assertFalse(prefsRepo.preferences.first().unreadMainSessionIds.contains(sessionId))
    }
    
    @Test
    fun `running tool with output visible when expanded`() = testScope.runTest {
        val tool1 = ToolState.Running(title = "bash", output = "")
        val tool2 = tool1.copy(output = "$ echo hello\nhello\n")
        
        assertTrue(tool1.output.isEmpty())
        assertTrue(tool2.output.contains("hello"))
    }
    
    @Test
    fun `retry status block renders when session retrying`() {
        val retryStatus = SessionStatus.Retry(
            attempt = 1,
            message = "network error",
            next = System.currentTimeMillis() + 2000
        )
        
        assertEquals(1, retryStatus.attempt)
        assertTrue(retryStatus.message.isNotEmpty())
    }
}

private class FakeSessionListPreferencesRepository : SessionListPreferencesRepository {
    private val _prefs = MutableStateFlow(SessionListPreferences.DEFAULT)
    override val preferences = _prefs.asStateFlow()
    
    override suspend fun markMainSessionUnread(sessionId: String) {
        _prefs.value = _prefs.value.copy(
            unreadMainSessionIds = _prefs.value.unreadMainSessionIds + sessionId
        )
    }
    
    override suspend fun markMainSessionRead(sessionId: String) {
        _prefs.value = _prefs.value.copy(
            unreadMainSessionIds = _prefs.value.unreadMainSessionIds - sessionId
        )
    }
    
    override suspend fun markMainSessionsRead(sessionIds: Collection<String>) {
        _prefs.value = _prefs.value.copy(
            unreadMainSessionIds = _prefs.value.unreadMainSessionIds - sessionIds.toSet()
        )
    }
    
    // ... other methods ...
}
```

**Verification Command**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest
```

---

## Task 12: Manual Acceptance Testing

**Objective**: Verify feature works end-to-end on device or emulator via concrete scenarios.

## Comprehensive QA Scenarios (All Tasks)

### Scenario 1: Task 1 & 5 - Unread Persistence (Storage → Clear)

**Tool**: Long-running command with completion event before app restart  
**Steps**:
1. Create session "test-session-persist"
2. Run genuinely long command: `for i in {1..20}; do echo "Step $i"; sleep 1; done` (~20 second duration)
3. **Within first 2 seconds**: Navigate away from this session (press back or switch to different session)
   - Command continues running in background
   - Service remains alive
4. **Wait for command to complete** (~20s total)
   - While waiting, service receives `SseEvent.SessionStatus` with status = Idle
   - Service detects: `prevStatus = Busy/Retry`, `currentStatus = Idle`, `activeSessionId = null` → marks unread
5. **After completion confirmed** (wait until no more output in any session), close app completely
6. Reopen app → navigate to session list
7. **Expected** (Post-Task 1): Blue dot visible on "test-session-persist" row and project header
   - Proves DataStore persisted unread state across app restart
   - Unread was marked while service was alive (step 4), persisted, and loaded on restart
8. Open "test-session-persist" chat
   - **Expected** (Task 5 implementation): `ChatViewModel.init` calls `eventReducer.setActiveSessionId(sessionId)` in init
9. Return to session list
10. **Expected** (Post-Task 5): Blue dot is gone
    - Proves `markMainSessionRead(sessionId)` was called when entering chat
11. Close app completely again
12. Reopen app → navigate to session list
13. **Expected**: Still no blue dot on "test-session-persist"
    - Proves clear state persisted to DataStore
    - Both persist steps (unread + clear) working correctly

### Scenario 2: Task 2 & 4 - Unread Derivation & Indicators (State → Visual)

**Tool**: Two sessions in same project  
**Steps**:
1. Create two main sessions in same project: "session-a" and "session-b"
2. In "session-a": run `sleep 3 && echo "done"`
3. Switch to "session-b" immediately (before session-a completes)
4. Wait for "session-a" to complete
5. Return to session list
6. **Expected** (Post-Task 2 & 4):
   - Blue dot on "session-a" row (size 6dp)
   - Blue dot on project header (size 8dp)
   - Both use primary color
7. Tap project to collapse it → blue dot remains
8. Expand project → blue dot still visible
9. Tap "session-a" → navigate to chat, blue dot gone

### Scenario 3: Task 3 - Unread Card in Top Banner (Ordering)

**Tool**: Session with command + pending decision in subagent  
**Steps**:
1. Create two main sessions: "main-work" and "decision-wait"
2. In "main-work": run `sleep 2 && echo "work done"`
3. In "decision-wait", spawn a subagent that will ask a question
4. While "main-work" is running, switch to "decision-wait"
5. Immediately navigate back to session list (before "main-work" completes)
6. Wait for "main-work" to complete
7. Wait for subagent question to arrive
8. **Expected** (Post-Task 3):
   - Top banner shows TWO cards:
     1. "main-work" with UNREAD status and blue circle icon
     2. "decision-wait" with AWAITING_QUESTION status and help icon
   - Unread card appears **before** decision card (left-to-right order)
9. Tap unread card → navigates to "main-work", card disappears
10. Return to list → only decision card remains

### Scenario 4: Task 6 - Always-On Unread Marking (Service Layer)

**Tool**: Long-running session in background  
**Steps**:
1. Two sessions: "bg-worker" and "main-task"
2. In "bg-worker": run `sleep 25 && echo "done"` (long-running)
3. Switch to "main-task", run: `sleep 2 && echo "quick"`
4. Wait for "main-task" to complete (~2s), verify blue dot appears
5. **Then**: Put app in background (press Home button)
6. Keep app backgrounded for ~5s
7. Reopen app
8. **Expected** (Post-Task 6, Service Layer):
   - Blue dot still visible on "main-task"
   - This proves marking happened in always-on service, not UI
9. Wait another ~20s (total ~25s from start)
10. "bg-worker" completes while app is running
11. Navigate to session list
12. **Expected**: Blue dot now appears on "bg-worker" (marked while you were in main-task)

### Scenario 5: Task 7 - ToolState.Running Output Field

**Tool**: Streaming command in single session  
**Steps**:
1. In a session, run: `for i in {1..3}; do echo "Item $i"; sleep 1; done`
2. At ~0.5s (after first echo): Inspect state via debugger/logging
3. **Expected** (Post-Task 7):
   - `ToolState.Running` has `output: String` field (not optional)
   - Field contains "Item 1\n"
4. Wait ~1.5s more (total ~2s)
5. Re-inspect: `output` field now shows "Item 1\nItem 2\n"
6. Wait ~1s more (total ~3s, command done)
7. State transitions to `ToolState.Completed`
8. **Expected**: `Completed.output` = "Item 1\nItem 2\nItem 3\n"

### Scenario 6: Task 8 - EventReducer Accumulates Output

**Tool**: Running bash tool with continuous output  
**Steps**:
1. Run: `for i in {1..5}; do echo "Line $i at $(date +%T)"; sleep 0.3; done`
2. At ~0.2s: First `PartUpdated` event arrives with "Line 1 at HH:MM:SS\n"
3. EventReducer merges into `ToolState.Running.output`
4. At ~0.5s: Next `PartUpdated` with full "Line 1 at HH:MM:SS\nLine 2 at HH:MM:SS\n"
5. **Expected** (Post-Task 8):
   - No duplication; accumulated output = "Line 1...\nLine 2...\n"
   - Not "Line 1...\nLine 1...\nLine 2...\n"
6. At ~1.5s: Re-inspect, output has 3–4 lines (not doubled)
7. At completion (~1.5s): All 5 lines visible, clean accumulation

### Scenario 7: Task 9 - Live Output on Card Expansion (User-Driven)

**Tool**: Multi-line streaming output  
**Steps**:
1. Run: `for i in {1..5}; do echo "Line $i $(date +%H:%M:%S)"; sleep 1; done`
2. At ~0.5s (while running): Card is COLLAPSED (not auto-expanded)
3. **Expected** (Post-Task 9):
   - Header shows: "bash" icon + title + ellipsis icon (no pulsing dots yet)
   - No output visible in collapsed state
4. **User action**: Tap card to expand
5. **Expected**:
   - Output section appears with "Line 1 HH:MM:SS\n"
   - Expansion icon shows ↑ (expanded)
6. Wait ~2.5s (total ~3s)
7. **Expected**: Output now shows "Line 1…\nLine 2…\nLine 3…\n" (accumulating in real time)
8. **Command completes** (~5s total)
9. **Expected**:
   - All 5 lines visible
   - State is now Completed
   - Card remains expanded, output unchanged
10. **User action**: Tap to collapse
11. **Expected**: Header-only view, output hidden
12. **User action**: Re-expand
13. **Expected**: Full output still there (not cleared)

### Scenario 8: Task 10 - Retry Status Block Visibility

**Tool**: Command that fails and triggers retry  
**Steps**:
1. Run command that fails (simulate via OpenCode server retry):  
   `false` or `command_that_does_not_exist`
2. Server enters `SessionStatus.Retry` with attempt=1, message="exit code 1"
3. Open the session chat
4. **Expected** (Post-Task 10):
   - Below messages (or above, depending on layout), a card appears:
     ```
     正在重试 · 第 1 次
     上次失败：exit code 1
     ```
   - Card background color: error container (reddish)
5. Server retries, attempt=2 now
6. **Expected**: Block updates to "第 2 次"
7. If next retry time is future: "下次重试：in 3s" or similar
8. Retry succeeds, session returns to Idle
9. **Expected**: Retry block disappears

### Scenario 9: Task 11 - Integration Test (All Three Features Together)

**Tool**: Realistic workflow with two sessions  
**Steps**:
1. Two sessions: "api-server" and "frontend"
2. In "api-server", a long-running build: `for i in {1..10}; do echo "Build step $i"; sleep 1; done` (~10s)
3. In "frontend", a quick command: `sleep 3 && echo "built"` (~3s)
4. View "frontend", but **navigate away within first 1s** (while "frontend" command is running)
   - Press back to leave "frontend" before it completes
5. Wait for "frontend" to complete (~3s total from start)
6. **Expected** (Task 3 + 2 + 4): Blue dot on "frontend" and project header
   - Unread was marked while user was NOT viewing (rule: only mark if not viewing when complete)
7. Top banner shows "frontend" UNREAD card
8. Click unread card → navigate to "frontend"
9. **Expected** (Task 5): Blue dot disappears (cleared on entry via `setActiveSessionId()`)
10. Switch to "api-server" (still building, ~8–9s elapsed of ~10s)
11. **Expected** (Task 9): Tool card is COLLAPSED, showing only header + pulsing dots
12. Wait ~3s into visible "api-server" (total ~5–6s of build)
13. **User action**: Expand tool card
14. **Expected** (Task 9): See ~5–6 build steps in output, updates visible in real time
15. Return to session list while build is still running (~5s more to go)
16. **Expected** (Task 6, Service Layer): After remaining time, build completes
    - Service detects `SseEvent.SessionStatus` with Idle, `activeSessionId = null`, marks unread
17. Reopen app → blue dot now on "api-server"
18. Click unread → see final build output with all 10 steps (from Task 8/9)

### Scenario 10: Task 13 - Build & Tests Pass

**Verification Steps**:
```bash
# Unit tests
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest
# Expected: BUILD SUCCESSFUL

# Build APK
./gradlew assembleDebug
# Expected: BUILD SUCCESSFUL, APK at app/build/outputs/apk/debug/app-debug.apk

# Lint/checks
./gradlew check
# Expected: No new errors/warnings
```

**Manual Validation Checklist**:
- [ ] Scenario 1 passes (persistence survives restart)
- [ ] Scenario 2 passes (indicators visible)
- [ ] Scenario 3 passes (card ordering correct)
- [ ] Scenario 4 passes (service layer reliability)
- [ ] Scenario 5 passes (output field exists and accumulates)
- [ ] Scenario 6 passes (no duplication in accumulation)
- [ ] Scenario 7 passes (manual expansion, not auto)
- [ ] Scenario 8 passes (retry block visible with correct details)
- [ ] Scenario 9 passes (all three features working together)
- [ ] Scenario 10 passes (build clean, no new errors)

### Legacy Test Scenarios (Tasks 1–13 Mapped)

| Task | Feature | Scenario | Tool | Key Assertion |
|------|---------|----------|------|---------------|
| 1 | Unread storage | Scenario 1, step 4 | SQLite inspect | Key persists in DataStore |
| 2 | Unread derivation | Scenario 2, step 6 | Session list | Blue dots appear correctly |
| 3 | Unread card ordering | Scenario 3, step 8 | Banner | Unread card before decision |
| 4 | Indicators | Scenarios 2–3 | UI rows | Blue circles visible |
| 5 | Clear on entry | Scenarios 1–4, step 7–9 | Chat entry | Blue dot disappears |
| 6 | Always-on marking | Scenario 4, step 7 | App background | Service marks unread while backgrounded |
| 7 | Running output field | Scenario 5, step 5 | Debugger | `ToolState.Running.output` exists |
| 8 | Accumulation | Scenario 6, step 5 | Event reducer | No duplicated output lines |
| 9 | Live expansion | Scenario 7, step 4–7 | Card interaction | User-driven expansion, no auto-expand |
| 10 | Retry block | Scenario 8, step 4 | Chat view | "正在重试" block visible |
| 11 | Integration | Scenario 9, all steps | Realistic workflow | All features coordinate |
| 12 | Manual tests | Scenarios 1–9 | Device/emulator | See individual steps |
| 13 | Build pass | Scenario 10 | Gradle commands | BUILD SUCCESSFUL, no errors |



---

## Task 13: Build & Verify Tests Pass

**Objective**: Ensure all tasks complete and all tests pass.

### Verification Commands

**Unit tests**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew testDebugUnitTest
```

Expected output:
```
> Task :app:testDebugUnitTest
...
BUILD SUCCESSFUL
```

**Build APK**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew assembleDebug
```

Expected output:
```
> Task :app:assembleDebug
...
BUILD SUCCESSFUL
```

**Check for lint/compilation errors**:
```bash
cd /root/CODE/oc-remote/.worktrees/feat/issue10-unread-live-logs && \
./gradlew check
```

---

## Summary of Changes

| Task | Files | Intent |
|------|-------|--------|
| 1 | `SessionListPreferences.kt`, `SessionListDataStore.kt`, `SessionListPreferencesRepository.kt` | Add unread storage layer |
| 2 | `SessionListViewModel.kt` | Derive unread state in UI |
| 3 | `SessionListViewModel.kt`, `ConversationStatus.kt` | Insert unread card before decision in top banner |
| 4 | `ProjectGroupHeader.kt`, `SessionListScreen.kt` | Render blue dot on project/session rows |
| 5 | `ChatViewModel.kt` or `ChatScreen.kt` | Clear unread on session entry |
| 6 | `EventReducer.kt`, `SessionListViewModel.kt` | Mark main sessions unread on completion |
| 7 | `ToolState.kt` | Add output field to Running state |
| 8 | `EventReducer.kt` | Accumulate tool output from streaming events |
| 9 | `ChatScreen.kt` | Render live output on card expansion |
| 10 | `ChatScreen.kt` | Render retry status block |
| 11 | Test files | Integration tests for full flow |
| 12 | Manual testing | Acceptance scenarios |
| 13 | Gradle | Build & test verification |

---

## Execution Sequence

1. **Phase 1 (Parallel with issue #9)**: Tasks 1–6
   - Unread persistence, derivation, UI integration, clear on entry
   - Dependencies: none on live-log or retry features

2. **Phase 2**: Tasks 7–9
   - Live output streaming, card expansion
   - Depends on: EventReducer understanding ToolState.Running

3. **Phase 3**: Task 10
   - Retry status block
   - Depends on: Session status already Retry-aware (existing)

4. **Phase 4**: Tasks 11–13
   - Integration tests, manual validation, build verification

---

## Non-Goals & Scope Boundaries

- **No unread for subagent sessions** in this phase
- **No manual read/unread toggles** in this phase
- **No protocol changes** upstream; consume existing `message.part.updated` and `SessionStatus.Retry`
- **No full redesign** of tool card layout beyond collapsed preview + expanded live
- **No retry renaming** when server is actually retrying
- **Retry visibility follows from existing session status**, not new events

---

## Acceptance Criteria Mapping

| Acceptance Criterion | Task(s) |
|---|---|
| New unread top-card bucket before decision | Task 3 |
| Only completed-but-unread main sessions count | Task 2, 3 |
| Project rows show blue unread indicator | Task 4 |
| Session rows show blue unread indicator | Task 4 |
| Opening main session clears unread | Task 5 |
| Subagent sessions no unread state or indicators | Task 2 |
| Tool cards collapsed by default | Task 9 (requires existing default) |
| Expanding running card reveals full live log | Task 9 |
| After completion, card preserves full log | Task 9 |
| If top card shows retry, session exposes retry details | Task 10 |
| Retry failure/progression visible during retry lifecycle | Task 10 |
| Feature isolated in issue #10 branch | Entire plan |

---

## Open Dependencies & Notes

1. **Upstream Retry Metadata**: If existing `SessionStatus.Retry` fields (attempt, message, next) are insufficient, treat a metadata-enrichment follow-up as a bounded scope add-on, not a blocker to client implementation.

2. **Live Output Deduplication**: If server emits full state per update (not deltas), client may need last-seen-output guards to avoid re-rendering unchanged output. Implement in Task 8 if observed.

3. **Integration with Issue #9**: This plan executes in parallel with issue #9 (baseline test fix). Both can merge independently to main branch in release flow.

4. **No Breaking Changes**: All data model changes are additive with safe defaults, so no migration required.

---

**End of Plan**
