---
date: 2026-05-07
topic: "Chat Status Visibility and Active Conversation Ordering"
issue: 22
scope: chat
contract: none
---

# Chat Status Visibility and Active Conversation Ordering Implementation Plan

**Goal:** Move the live retry status banner from the top of the chat message list to the bottom area just above the composer, and reorder the active conversations banner so unread sessions outrank busy/running sessions.

**Architecture:** Two small, presentation-layer fixes. (1) In `ChatScreen.kt`, relocate the existing `RetryStatusBanner` from a `LazyColumn` item to the `Scaffold.bottomBar` slot, where it will sit immediately above `ChatInputBar` and remain visible regardless of scroll position. (2) In `SessionListViewModel.kt`, change the explicit priority `when`-block in `buildActiveConversations` so `UNREAD` sorts ahead of `BUSY`/`RETRY`, and update the existing `BuildActiveConversationsTest` cases that depended on the old order. No protocol, reducer, or domain-model changes.

**Design:** [thoughts/shared/designs/2026-05-07-chat-status-visibility-ordering-design.md](../designs/2026-05-07-chat-status-visibility-ordering-design.md)

**Contract:** none (single-domain plan; both tasks are `frontend`).

**Senior-engineer decisions filled in:**

- **Where to put the live status banner:** Design says "bottom/current interaction area, near the message composer or latest-message area". Decision: render it inside `Scaffold.bottomBar`, directly above `ChatInputBar`, wrapped in a single `Column` so the existing `bottomBar` shape (terminal-mode vs. normal-mode) is preserved. Rationale: `Scaffold` already isolates the bottom region, so the banner naturally sticks to the composer regardless of scroll, and it cooperates with IME insets that the input bar already handles. An overlay-on-LazyColumn alternative was considered and rejected because it would have required reworking the existing IME-aware layout the input bar relies on.
- **Full priority order for active conversations:** Design only mandates "unread before busy/running". Decision: full new order is `UNREAD < AWAITING_QUESTION < AWAITING_PERMISSION < BUSY < RETRY` (lowest sort key wins). This matches the enum's declaration order in `ConversationStatus.kt`, so the simplest implementation is `compareBy { it.status.ordinal }`. Rationale: pending question/permission already represent direct user-attention demand and naturally belong in the user-attention tier ahead of background BUSY/RETRY; this matches the design's "attention priority" framing without expanding scope.
- **Test policy:** Task 1.1 is genuine sort-logic with regression risk → real test (update existing JUnit test, plus a new explicit assertion for unread-before-busy). Task 1.2 is a Compose layout move of an already-tested banner component with no behavior change → `Test: none` per the semantic-risk rule (no exported logic, no state transition, no parsing/validation; the banner itself is unchanged).

---

## Dependency Graph

```
Batch 1 (parallel): 1.1, 1.2 [independent files - no deps]
```

Both files are in different modules (`SessionListViewModel.kt` under `ui/screens/sessions/`, `ChatScreen.kt` under `ui/screens/chat/`) and neither imports the other. They can be implemented and reviewed in parallel.

---

## Batch 1: UI Fixes (parallel - 2 implementers)

All tasks in this batch have NO dependencies and run simultaneously.
Tasks: 1.1, 1.2

### Task 1.1: Reorder active conversation priorities (UNREAD outranks BUSY/RETRY)
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/BuildActiveConversationsTest.kt`
**Depends:** none
**Domain:** frontend

**What to change in production code:**

Inside `buildActiveConversations` (currently around lines 893–942 in `SessionListViewModel.kt`), the `.sortedWith(...)` call uses an explicit `when`-mapping that puts `BUSY` and `RETRY` ahead of `UNREAD`. Replace that explicit mapping with a sort by `ConversationStatus.ordinal`. The enum declaration in `ui/screens/sessions/components/ActiveConversationItem.kt` is already `UNREAD, AWAITING_QUESTION, AWAITING_PERMISSION, BUSY, RETRY` — using `ordinal` realises the new attention priority directly.

The exact existing block to replace:

```kotlin
        .sortedWith(
            compareBy<ActiveConversationItem> {
                when (it.status) {
                    ConversationStatus.BUSY -> 0
                    ConversationStatus.RETRY -> 1
                    ConversationStatus.UNREAD -> 2
                    ConversationStatus.AWAITING_QUESTION -> 3
                    ConversationStatus.AWAITING_PERMISSION -> 4
                }
            }
                .thenByDescending { it.updatedAt }
        )
        .toList()
}
```

Replace with:

```kotlin
        .sortedWith(
            // Priority follows ConversationStatus declaration order:
            // UNREAD < AWAITING_QUESTION < AWAITING_PERMISSION < BUSY < RETRY.
            // Unread/awaiting items demand user attention and outrank background
            // busy/retry progress. Within the same priority, newer activity wins.
            compareBy<ActiveConversationItem> { it.status.ordinal }
                .thenByDescending { it.updatedAt }
        )
        .toList()
}
```

No other lines in `buildActiveConversations` change. The classification `when` block earlier in the function (UNREAD wins over question/permission/busy/retry) is already correct under the new order and must NOT be touched.

**TDD step 1 — write the failing test first.** Update `BuildActiveConversationsTest.kt` so it encodes the new ordering. Two existing tests reference the old order and must change; one new test asserts the unread-before-busy invariant explicitly.

Replace the entire body of `BuildActiveConversationsTest.kt` with the version below. The class's helpers (`rootSession`, `questionAsked`, `permissionAsked`) are unchanged; only the test cases that depend on priority order are modified, and one new case is appended.

Diff summary (for the reviewer):
- Test `status priority comes before updated time and within-priority sorts by updated desc`: expected order changes from `[b1, r1, q2, q1]` to `[q2, q1, b1, r1]`, and the assertion message is updated. Rationale: under the new priority, `AWAITING_QUESTION` (ordinal 1) outranks `BUSY` (3) and `RETRY` (4); within `AWAITING_QUESTION`, `q2` has a higher `updatedAt` so it sorts before `q1`.
- Existing test `unread sorts before pending decision items` is unchanged in intent (UNREAD already preceded AWAITING_QUESTION in the old order too) but kept verbatim because it remains a useful invariant.
- New test `unread sorts before busy and retry sessions` directly encodes the issue #22 invariant.

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.ui.screens.sessions.components.ConversationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildActiveConversationsTest {

    @Test
    fun `idle root with no pending decisions is excluded`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `busy root is included with BUSY status`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Busy),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.BUSY, items[0].status)
        assertEquals(0, items[0].pendingCount)
    }

    @Test
    fun `retry root is included with RETRY status`() {
        val root = rootSession("root1", updated = 100)
        val retry = SessionStatus.Retry(attempt = 2, message = "retrying", next = 0L)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to retry),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.RETRY, items[0].status)
    }

    @Test
    fun `pending question wins over busy and sets pendingCount`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Busy),
            pendingQuestions = mapOf(root.id to listOf(questionAsked("q1"), questionAsked("q2"))),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.AWAITING_QUESTION, items[0].status)
        assertEquals(2, items[0].pendingCount)
    }

    @Test
    fun `pending permission wins over busy but loses to pending question`() {
        val permissionOnly = rootSession("root1", updated = 100)
        val bothPending = rootSession("root2", updated = 90)

        val items = buildActiveConversations(
            rootSessions = listOf(permissionOnly, bothPending),
            statuses = mapOf(
                permissionOnly.id to SessionStatus.Busy,
                bothPending.id to SessionStatus.Idle,
            ),
            pendingQuestions = mapOf(
                bothPending.id to listOf(questionAsked("q1")),
            ),
            pendingPermissions = mapOf(
                permissionOnly.id to listOf(permissionAsked("p1")),
                bothPending.id to listOf(permissionAsked("p2")),
            ),
            unreadSessionIds = emptySet(),
        )

        assertEquals(listOf("root2", "root1"), items.map { it.sessionId })
        assertEquals(ConversationStatus.AWAITING_QUESTION, items[0].status)
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[1].status)
    }

    @Test
    fun `status priority comes before updated time and within-priority sorts by updated desc`() {
        val question1 = rootSession("q1", updated = 100)
        val question2 = rootSession("q2", updated = 200)
        val busy1 = rootSession("b1", updated = 500)
        val retry1 = rootSession("r1", updated = 999)

        val items = buildActiveConversations(
            rootSessions = listOf(question1, question2, busy1, retry1),
            statuses = mapOf(
                busy1.id to SessionStatus.Busy,
                retry1.id to SessionStatus.Retry(1, "x", 0L),
            ),
            pendingQuestions = mapOf(
                question1.id to listOf(questionAsked("qa1")),
                question2.id to listOf(questionAsked("qa2")),
            ),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        // New priority: AWAITING_QUESTION (1) outranks BUSY (3) and RETRY (4),
        // so question rows come first; within AWAITING_QUESTION, higher
        // updatedAt wins (q2 > q1). BUSY (3) outranks RETRY (4).
        assertEquals(listOf("q2", "q1", "b1", "r1"), items.map { it.sessionId })
    }

    @Test
    fun `archived root is excluded even if it has pending decisions`() {
        val archived = rootSession("root1", updated = 100, archivedAt = 50L)

        val items = buildActiveConversations(
            rootSessions = listOf(archived),
            statuses = mapOf(archived.id to SessionStatus.Busy),
            pendingQuestions = mapOf(archived.id to listOf(questionAsked("q1"))),
            pendingPermissions = emptyMap(),
            unreadSessionIds = emptySet(),
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `unread root is included with UNREAD status`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = setOf(root.id),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.UNREAD, items[0].status)
    }

    @Test
    fun `unread sorts before pending decision items`() {
        val unread = rootSession("unread", updated = 100)
        val question = rootSession("question", updated = 200)

        val items = buildActiveConversations(
            rootSessions = listOf(unread, question),
            statuses = mapOf(
                unread.id to SessionStatus.Idle,
                question.id to SessionStatus.Idle,
            ),
            pendingQuestions = mapOf(question.id to listOf(questionAsked("qa"))),
            pendingPermissions = emptyMap(),
            unreadSessionIds = setOf(unread.id),
        )

        assertEquals(listOf("unread", "question"), items.map { it.sessionId })
        assertEquals(ConversationStatus.UNREAD, items[0].status)
        assertEquals(ConversationStatus.AWAITING_QUESTION, items[1].status)
    }

    @Test
    fun `unread sorts before busy and retry sessions`() {
        // Issue #22: unread conversations must outrank busy/running ones,
        // because unread content needs the user's attention while busy/retry
        // is just background progress.
        val unread = rootSession("unread", updated = 100)
        val busy = rootSession("busy", updated = 500)
        val retry = rootSession("retry", updated = 999)

        val items = buildActiveConversations(
            rootSessions = listOf(unread, busy, retry),
            statuses = mapOf(
                unread.id to SessionStatus.Idle,
                busy.id to SessionStatus.Busy,
                retry.id to SessionStatus.Retry(attempt = 1, message = "x", next = 0L),
            ),
            pendingQuestions = emptyMap(),
            pendingPermissions = emptyMap(),
            unreadSessionIds = setOf(unread.id),
        )

        assertEquals(listOf("unread", "busy", "retry"), items.map { it.sessionId })
        assertEquals(ConversationStatus.UNREAD, items[0].status)
        assertEquals(ConversationStatus.BUSY, items[1].status)
        assertEquals(ConversationStatus.RETRY, items[2].status)
    }

    private fun rootSession(id: String, updated: Long, archivedAt: Long? = null) = Session(
        id = id,
        slug = id,
        projectId = "p",
        directory = "/root/CODE/demo",
        parentId = null,
        title = id,
        version = "1.0.0",
        time = Session.Time(created = updated - 10, updated = updated, archived = archivedAt),
    )

    private fun questionAsked(id: String) = SseEvent.QuestionAsked(
        id = id,
        sessionId = "s",
        questions = emptyList(),
    )

    private fun permissionAsked(id: String) = SseEvent.PermissionAsked(
        id = id,
        sessionId = "s",
        permission = "p",
    )
}
```

**TDD step 2 — run the test, confirm RED.** With only the test file updated and the production sort still using the old `when`-block, the new `unread sorts before busy and retry sessions` test should pass (UNREAD already had a smaller priority key than BUSY in the old code: 2 < 3? wait — actually no, old map: BUSY=0, RETRY=1, UNREAD=2; UNREAD currently sorts AFTER busy/retry, so the new test FAILS, which is the expected RED). The updated `status priority comes before updated time...` test will also FAIL because it now expects `[q2, q1, b1, r1]` while the old code still produces `[b1, r1, q2, q1]`. Confirm both failures before moving on.

**TDD step 3 — apply the production change.** Replace the `.sortedWith` block in `SessionListViewModel.kt` exactly as shown above (full block replacement, including the comment header).

**TDD step 4 — run the test again, confirm GREEN.** All ten tests in `BuildActiveConversationsTest` should pass.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.BuildActiveConversationsTest"`
**Commit:** `fix(sessions): rank unread conversations above busy and retry (#22)`

---

### Task 1.2: Move live retry status banner from list top to bottom (above composer)
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
**Test:** none
**Depends:** none
**Domain:** frontend

**Test policy rationale:** This is a Compose layout move of an already-rendered banner whose internal logic (`RetryStatusBanner` composable, lines ~4761–4807) is unchanged. No exported logic, no state transitions, no parsing/validation, no error-handling branches change. The banner's visibility condition (`uiState.sessionStatus is SessionStatus.Retry`) and its `onStop` wiring (`viewModel.abortSession()`) are preserved verbatim. Per the semantic-risk rule this qualifies as `Test: none`. Verification is by manual smoke test (see "Verify" below).

**What to change.** Two precise edits, in this order, in `ChatScreen.kt`:

**Edit A — remove the banner from the top of the LazyColumn.**

Locate this block, currently around lines 2331–2338, immediately after the `if (uiState.hasOlderMessages) { item(key = "load_older") { ... } }` block and before `items(uiState.messages, key = { it.message.id }) { ... }`:

```kotlin
                        if (uiState.sessionStatus is SessionStatus.Retry) {
                            item(key = "retry_status") {
                                RetryStatusBanner(
                                    retry = uiState.sessionStatus as SessionStatus.Retry,
                                    onStop = { viewModel.abortSession() },
                                )
                            }
                        }

```

Delete the entire block (the `if`, the `item { ... }`, and the trailing blank line). The `items(...)` call that follows must remain unchanged. After deletion, the message-list area no longer renders the live retry banner; it will be re-introduced at the bottom by Edit B.

**Edit B — add the banner to `Scaffold.bottomBar` directly above `ChatInputBar`.**

Locate the `bottomBar = { ... }` lambda, currently starting around line 1663. The current shape is:

```kotlin
        bottomBar = {
            val modelLabel = if (uiState.selectedModelId != null && uiState.providers.isNotEmpty()) {
                val provider = uiState.providers.find { it.id == uiState.selectedProviderId }
                val model = provider?.models?.get(uiState.selectedModelId)
                model?.name ?: uiState.selectedModelId ?: ""
            } else ""

            if (!isTerminalMode) {
            ChatInputBar(
                textFieldValue = inputText,
                onTextFieldValueChange = { newValue ->
                    // ... existing ChatInputBar arguments unchanged ...
```

Wrap the `if (!isTerminalMode)` body in a `Column` and prepend the live retry banner. The new shape is:

```kotlin
        bottomBar = {
            val modelLabel = if (uiState.selectedModelId != null && uiState.providers.isNotEmpty()) {
                val provider = uiState.providers.find { it.id == uiState.selectedProviderId }
                val model = provider?.models?.get(uiState.selectedModelId)
                model?.name ?: uiState.selectedModelId ?: ""
            } else ""

            if (!isTerminalMode) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Issue #22: live session retry status renders directly above the
                // composer so users do not need to scroll to the top of the message
                // list to see it. Historical Part.Retry parts continue to render
                // inline in the message timeline (see ChatMessageBubble).
                if (uiState.sessionStatus is SessionStatus.Retry) {
                    RetryStatusBanner(
                        retry = uiState.sessionStatus as SessionStatus.Retry,
                        onStop = { viewModel.abortSession() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                ChatInputBar(
                    textFieldValue = inputText,
                    onTextFieldValueChange = { newValue ->
                        // ... existing ChatInputBar arguments unchanged ...
```

Then, at the matching closing brace for `if (!isTerminalMode) {` (the line that previously read `}` after the `ChatInputBar(...)` call's closing parenthesis), add a closing brace for the new `Column { ... }` block. Concretely, the existing structure is:

```kotlin
            if (!isTerminalMode) {
            ChatInputBar(
                /* ... */
            )
            }
        },
```

becomes:

```kotlin
            if (!isTerminalMode) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (uiState.sessionStatus is SessionStatus.Retry) {
                    RetryStatusBanner(
                        retry = uiState.sessionStatus as SessionStatus.Retry,
                        onStop = { viewModel.abortSession() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                ChatInputBar(
                    /* ... */
                )
            }
            }
        },
```

The implementer should be careful: the `ChatInputBar(...)` call spans many lines (roughly 1671 through 1828) and includes `onSlashCommand`, `attachments`, and many other named arguments. The whole call moves inside the new `Column` lambda but its arguments are otherwise untouched.

**Edit C — extend `RetryStatusBanner` to accept a `Modifier`.**

`RetryStatusBanner` (lines ~4761–4807) currently has signature:

```kotlin
@Composable
private fun RetryStatusBanner(
    retry: SessionStatus.Retry,
    onStop: () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val retryText = stringResource(R.string.chat_retry, retry.attempt, retry.message)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
```

Add an optional `modifier: Modifier = Modifier` parameter and chain it before the existing modifier on `Surface`. New signature and body header:

```kotlin
@Composable
private fun RetryStatusBanner(
    retry: SessionStatus.Retry,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    val retryText = stringResource(R.string.chat_retry, retry.attempt, retry.message)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)) else null,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
```

The rest of `RetryStatusBanner`'s body (the `Row`, `Icon`, `Text`, `IconButton` block, lines 4777–4806) is unchanged.

**Defensive notes for the implementer.**

- The `as SessionStatus.Retry` cast is safe because it is guarded by the `is SessionStatus.Retry` smart-cast check on the same line; preserve this pattern (it matches the existing code style elsewhere in the file).
- Do NOT add the banner to the `isTerminalMode` branch — terminal mode does not have a chat composer, and the live retry banner should not appear over the terminal overlay. The new `Column` lives strictly inside the existing `if (!isTerminalMode)` branch.
- Keep the existing comment-style and indentation (4-space) of the surrounding block; in particular the existing block uses an unindented `ChatInputBar(` that sits at the same column as `if (!isTerminalMode) {`. Match that style for the new `Column(` line and the new `}` closing brace, so the diff stays minimal and the formatter does not re-flow the entire `bottomBar` lambda.
- The `Part.Retry` rendering inside `ChatMessageBubble` (around line 4220) is the historical-timeline view of past retry attempts and is explicitly out of scope. Do not touch it. The design preserves historical retry parts in their original chronological context.
- `uiState.error` rendering (the empty-state error block at lines 2245–2268) is for cold-load failures with zero messages, not the live session retry status. Do not touch it.
- The error-handling rule from the design ("rendering should degrade safely if retry/error fields are missing or blank") is already satisfied: `RetryStatusBanner` already uses `retry.attempt` and `retry.message` directly without crashing on blank strings, and the stop affordance (`IconButton`) is unconditional inside the `Row`. No additional guard is needed.

**Verify (build + manual smoke test):**

1. `./gradlew :app:assembleDebug` — must build cleanly with no Kotlin/Compose errors.
2. `./gradlew :app:testDebugUnitTest` — full unit-test suite must still pass (no test was added for this task, but no existing test should regress).
3. Manual smoke test on a debug build:
   - Open a chat session that has enough messages to require scrolling (e.g., 30+).
   - Trigger a retry (force a model error or simulate a flaky provider). Confirm `RetryStatusBanner` appears immediately above the composer at the bottom of the screen.
   - Scroll to the very top of the message list. Confirm the banner stays pinned to the bottom (it does NOT appear at the top of the list any more).
   - Tap the stop icon in the banner. Confirm the session aborts (`viewModel.abortSession()` runs) and the banner disappears.
   - Open a chat session that contains historical `Part.Retry` parts (you can find one by searching past sessions for retry events). Confirm those historical retry parts still render inline within the message bubble at their original position in the timeline.
   - Switch to terminal mode (if available in the build). Confirm no retry banner is rendered over the terminal area.

**Commit:** `fix(chat): pin live retry status banner above composer (#22)`
