# Retry Interrupt Control Implementation Plan

**Goal:** Let the user interrupt a session that is in `Retry` state from the chat screen by reusing the existing abort API, while keeping retry state visible if the abort attempt fails.

**Architecture:** All changes stay inside the existing status-driven chat flow. We introduce one pure helper (`SessionStatus.isInterruptible`) so both the top bar and the retry banner share one definition. `ChatViewModel.abortSession()` is hardened to honor the `Boolean` returned by `OpenCodeApi.abortSession`: only on success do we optimistically push `SessionStatus.Idle` into the reducer; on failure we surface the existing `_error` flow and leave the current status (`Busy`/`Retry`) untouched. The `RetryStatusBanner` gains an `onStop` callback that the chat screen wires to the same view-model entry point.

**Design:** [thoughts/shared/designs/2026-04-28-retry-interrupt-control-design.md](../designs/2026-04-28-retry-interrupt-control-design.md)

**Contract:** none (single-domain — Android frontend only; no server APIs touched)

---

## Senior-engineer decisions (gap fills)

The design states the WHAT but is silent on a few HOWs. Choices made here so implementers do not have to guess:

1. **Where the "interruptible" predicate lives.** Adding a top-level extension `val SessionStatus.isInterruptible: Boolean` in the same file as `SessionStatus`. Reason: it is a pure, sealed-class-exhaustive predicate that the reducer/UI can both reuse, and it is trivially unit-testable without Compose or coroutines.

2. **How `abortSession()` distinguishes success from failure.** `OpenCodeApi.abortSession` already returns `Boolean` based on `response.status.isSuccess()`, but the current view-model code throws away that return value and unconditionally pushes `Idle`. The fix branches on the boolean: `true` → push `Idle` (current behavior); `false` → set `_error` to a localized "stop failed" message and do NOT touch session status. Network exceptions take the same failure branch.

3. **Error message for failed abort.** A new string resource `chat_stop_failed` ("Failed to stop session") is added rather than reusing a generic message, so users can tell why retry state is still showing. Only English `values/strings.xml` is updated; other locales fall back to English (consistent with how this repo introduces new strings — `lokit` handles the translation pass later).

4. **Retry-banner stop affordance.** Implemented as a trailing `IconButton(Icons.Default.Stop)` inside the existing `Row`, mirroring the top-bar stop button's icon and `MaterialTheme.colorScheme.error` tint so the visual language is consistent. The banner does not own abort logic — it takes an `onStop: () -> Unit` callback the screen passes from the view-model.

5. **Revert flow stays intact.** The existing call site in `ChatScreen.kt` line ~2403 (`viewModel.abortSession(); viewModel.revertMessage(...)`) is left exactly as-is. The hardened `abortSession()` is still safe there: if abort succeeds, the local Idle is fine; if it fails, revert proceeds anyway and `revertMessage` drives its own state. The design's "do not change revert semantics" constraint is satisfied because revert never depended on `abortSession()` actually clearing status.

6. **Scope of view-model unit tests.** The repo has zero existing `ChatViewModel` tests and the class is large (1300+ lines, hard to construct in isolation). To stay minimal we extract abort-result handling into a tiny pure function `handleAbortResult(...)` in a new file `ChatAbortResultHandler.kt`, and unit-test that. The view-model calls it and applies side effects via lambdas. This avoids inventing a full view-model test harness while still covering the failure-branch invariant the design calls out.

---

## Dependency Graph

```
Batch 1 (parallel): 1.1, 1.2, 1.3                    [foundation - no deps]
Batch 2 (parallel): 2.1                              [view model wiring - depends on 1.2, 1.3]
Batch 3 (parallel): 3.1, 3.2                         [UI surfaces - depend on 1.1, 2.1]
```

Total: 6 micro-tasks across 3 batches. All file paths are absolute from project root.

---

## Batch 1: Foundation (parallel - 3 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: Add `isInterruptible` predicate on `SessionStatus`

**File:** `app/src/main/kotlin/dev/minios/ocremote/domain/model/SessionStatus.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/domain/model/SessionStatusInterruptibleTest.kt`
**Depends:** none
**Domain:** general

Test (write first, must fail):

```kotlin
package dev.minios.ocremote.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusInterruptibleTest {

    @Test
    fun `Idle is not interruptible`() {
        assertFalse(SessionStatus.Idle.isInterruptible)
    }

    @Test
    fun `Busy is interruptible`() {
        assertTrue(SessionStatus.Busy.isInterruptible)
    }

    @Test
    fun `Retry is interruptible`() {
        val retry = SessionStatus.Retry(attempt = 1, message = "boom", next = 0L)
        assertTrue(retry.isInterruptible)
    }
}
```

Implementation (replace entire file):

```kotlin
package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * Session Status - indicates if session is processing or idle.
 */
@Serializable
sealed class SessionStatus {
    @Serializable
    data object Idle : SessionStatus()

    @Serializable
    data object Busy : SessionStatus()

    @Serializable
    data class Retry(
        val attempt: Int,
        val message: String,
        val next: Long, // Timestamp of next retry
    ) : SessionStatus()
}

/**
 * Whether the user can interrupt this status by triggering an abort.
 *
 * `Busy` and `Retry` represent active work the user may want to halt.
 * `Idle` means there is nothing to interrupt.
 *
 * Centralized here so the chat top bar, the retry banner, and any future
 * surface (e.g. session list) all agree on the same predicate.
 */
val SessionStatus.isInterruptible: Boolean
    get() = when (this) {
        is SessionStatus.Idle -> false
        is SessionStatus.Busy -> true
        is SessionStatus.Retry -> true
    }
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.domain.model.SessionStatusInterruptibleTest`
**Commit:** `feat(chat): classify Busy and Retry as interruptible session states`

---

### Task 1.2: Extract `handleAbortResult` pure helper

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatAbortResultHandler.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ChatAbortResultHandlerTest.kt`
**Depends:** none
**Domain:** frontend

Test (write first, must fail — file does not exist yet):

```kotlin
package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAbortResultHandlerTest {

    @Test
    fun `success result moves session to Idle and clears no error`() {
        var idleCalls = 0
        var capturedError: String? = "untouched"

        handleAbortResult(
            outcome = AbortOutcome.Success,
            onIdle = { idleCalls++ },
            onError = { capturedError = it },
        )

        assertEquals(1, idleCalls)
        assertEquals("untouched", capturedError) // onError not invoked on success
    }

    @Test
    fun `unsuccessful response keeps current status and surfaces error message`() {
        var idleCalls = 0
        var capturedError: String? = null

        handleAbortResult(
            outcome = AbortOutcome.Unsuccessful,
            onIdle = { idleCalls++ },
            onError = { capturedError = it },
        )

        assertEquals(0, idleCalls)
        assertEquals(ABORT_FAILED_MESSAGE, capturedError)
    }

    @Test
    fun `thrown exception keeps current status and surfaces exception message`() {
        var idleCalls = 0
        var capturedError: String? = null

        handleAbortResult(
            outcome = AbortOutcome.Failed(IllegalStateException("network down")),
            onIdle = { idleCalls++ },
            onError = { capturedError = it },
        )

        assertEquals(0, idleCalls)
        assertEquals("network down", capturedError)
    }

    @Test
    fun `thrown exception with null message falls back to default abort failed message`() {
        var idleCalls = 0
        var capturedError: String? = null

        handleAbortResult(
            outcome = AbortOutcome.Failed(RuntimeException()),
            onIdle = { idleCalls++ },
            onError = { capturedError = it },
        )

        assertEquals(0, idleCalls)
        assertEquals(ABORT_FAILED_MESSAGE, capturedError)
    }
}
```

Implementation:

```kotlin
package dev.minios.ocremote.ui.screens.chat

/**
 * Default human-readable fallback message when an abort attempt fails for
 * a reason that does not provide its own message. The view model overrides
 * this with a localized resource where possible; this constant exists so
 * unit tests do not need an Android `Context`.
 */
internal const val ABORT_FAILED_MESSAGE: String = "Failed to stop session"

/**
 * Outcome of a single `POST /session/{id}/abort` attempt as observed by
 * the chat view model.
 *
 * - [Success]: HTTP 2xx returned; the session has been told to stop.
 * - [Unsuccessful]: HTTP non-2xx returned; the server rejected the abort.
 * - [Failed]: an exception was thrown before/after sending the request.
 */
internal sealed class AbortOutcome {
    data object Success : AbortOutcome()
    data object Unsuccessful : AbortOutcome()
    data class Failed(val cause: Throwable) : AbortOutcome()
}

/**
 * Pure result handler for an abort attempt.
 *
 * On [AbortOutcome.Success] this invokes [onIdle] so the caller can
 * optimistically move local session status to `Idle` while waiting for the
 * SSE confirmation. On [AbortOutcome.Unsuccessful] or [AbortOutcome.Failed]
 * it leaves the current status untouched and invokes [onError] with a
 * user-facing message; the retry / busy presentation must remain visible
 * so the user can try again or escalate to the web UI.
 *
 * This logic is extracted to a top-level function so it can be unit-tested
 * without instantiating `ChatViewModel`.
 */
internal fun handleAbortResult(
    outcome: AbortOutcome,
    onIdle: () -> Unit,
    onError: (String) -> Unit,
) {
    when (outcome) {
        AbortOutcome.Success -> onIdle()
        AbortOutcome.Unsuccessful -> onError(ABORT_FAILED_MESSAGE)
        is AbortOutcome.Failed -> onError(outcome.cause.message?.takeIf { it.isNotBlank() } ?: ABORT_FAILED_MESSAGE)
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.chat.ChatAbortResultHandlerTest`
**Commit:** `feat(chat): introduce pure abort-result handler for retry interrupt`

---

### Task 1.3: Add `chat_stop_failed` string resource

**File:** `app/src/main/res/values/strings.xml`
**Test:** none (resource file)
**Depends:** none
**Domain:** general

Add a new string entry alongside the existing `chat_stop` (currently at line ~195). Insert directly below it:

```xml
<string name="chat_stop_failed">Failed to stop session</string>
```

The `chat_stop` line already exists and looks like:

```xml
<string name="chat_stop">Stop</string>
```

Add the new line right after it. Do not touch any other locale; missing translations fall back to English per the project's `lokit` workflow described in README ("Localization workflow — locale files are maintained with `lokit` during development").

**Verify:** `./gradlew :app:assembleDebug` (the resource compiler will fail if the XML is malformed)
**Commit:** `feat(chat): add chat_stop_failed string for retry interrupt`

---

## Batch 2: View-model wiring (parallel - 1 implementer)

### Task 2.1: Harden `ChatViewModel.abortSession()` to honor abort outcome

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`
**Test:** none (covered by `ChatAbortResultHandlerTest` in 1.2; the view-model body becomes a thin coroutine wrapper)
**Depends:** 1.2 (imports `AbortOutcome` and `handleAbortResult`), 1.3 (uses `R.string.chat_stop_failed`)
**Domain:** frontend

Locate the existing `abortSession()` function (currently at lines 839-850):

```kotlin
    fun abortSession() {
        viewModelScope.launch {
            try {
                api.abortSession(conn, sessionId, directory = sessionDirectory)
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                // Optimistically update session status to Idle so UI reflects change immediately
                eventReducer.updateSessionStatus(sessionId, SessionStatus.Idle)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
            }
        }
    }
```

Replace it with the version below. The body splits the request from the result handling, calls the pure helper from 1.2, and only touches reducer status on success. On failure it sets `_error` so the existing snackbar/inline error UI surfaces it; the retry/busy presentation is left untouched because the reducer is not called.

```kotlin
    fun abortSession() {
        viewModelScope.launch {
            val outcome: AbortOutcome = try {
                val ok = api.abortSession(conn, sessionId, directory = sessionDirectory)
                if (ok) AbortOutcome.Success else AbortOutcome.Unsuccessful
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
                AbortOutcome.Failed(e)
            }
            handleAbortResult(
                outcome = outcome,
                onIdle = {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                    // Optimistically update session status to Idle so UI reflects change immediately.
                    // SSE will confirm the final state.
                    eventReducer.updateSessionStatus(sessionId, SessionStatus.Idle)
                },
                onError = { message ->
                    // Do NOT touch session status: retry/busy presentation must remain visible
                    // so the user can try again or switch to the web UI.
                    _error.value = message
                },
            )
        }
    }
```

Add the missing imports near the top of the file (in the existing import block — keep alphabetical order with the surrounding entries):

```kotlin
import dev.minios.ocremote.ui.screens.chat.AbortOutcome
import dev.minios.ocremote.ui.screens.chat.handleAbortResult
```

(They are in the same package, so the imports are technically optional, but adding them explicitly matches the file's existing convention of fully-qualified imports for sibling files.)

**Verify:**
- `./gradlew :app:testDebugUnitTest` — must still be green; existing tests unaffected
- `./gradlew :app:compileDebugKotlin` — must compile cleanly

**Commit:** `fix(chat): keep retry state visible when abort request fails`

---

## Batch 3: UI surfaces (parallel - 2 implementers)

Both tasks edit `ChatScreen.kt` but in non-overlapping regions (top bar around line ~1477 vs. `RetryStatusBanner` around lines ~2318 and ~4746). To keep "ONE file per task" from causing a merge conflict on the same physical file, run them strictly sequentially within Batch 3 — i.e. promote them to two sequential single-task batches if your executor cannot serialize edits to the same file.

> **Executor note:** if the executor's parallelism only branches on tasks AND files, it will already serialize 3.1 and 3.2 because they share `ChatScreen.kt`. Either way, the dependency annotations below (`depends: 2.1`) are correct; the only extra rule is "3.1 must merge before 3.2 starts on the same working tree."

### Task 3.1: Top Stop action covers `Busy` and `Retry`

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
**Test:** none (Compose top-bar visibility — covered by manual check; predicate logic already covered by 1.1)
**Depends:** 1.1 (uses `SessionStatus.isInterruptible`), 2.1 (the abort entry point now respects failure)
**Domain:** frontend

Locate the existing top-bar Stop block (currently at lines 1477-1485):

```kotlin
                    if (uiState.sessionStatus is SessionStatus.Busy) {
                        IconButton(onClick = { viewModel.abortSession() }) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.chat_stop),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
```

Replace the `if` condition only — keep the `IconButton` body identical:

```kotlin
                    if (uiState.sessionStatus.isInterruptible) {
                        IconButton(onClick = { viewModel.abortSession() }) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.chat_stop),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
```

Add the import (alphabetized with the other `dev.minios.ocremote.domain.model.*` imports near the top of the file):

```kotlin
import dev.minios.ocremote.domain.model.isInterruptible
```

Do NOT touch the auto-scroll trigger at line 1394 (`val isBusy = uiState.sessionStatus is SessionStatus.Busy`) or its `LaunchedEffect` — that is purely a scroll trigger and including `Retry` would make the chat jump to the bottom on every retry attempt, which is undesirable.

Do NOT touch the input-bar `isBusy = uiState.sessionStatus is SessionStatus.Busy` at line 1774 — that controls send-button-vs-stop-button swap inside the input row, and the design scope is the *top* Stop affordance plus the retry banner, not the input bar.

**Verify:**
- `./gradlew :app:compileDebugKotlin`
- Manual: with a session in `Retry` state, the Stop icon appears in the top bar and tapping it calls `abortSession()`.

**Commit:** `feat(chat): show top Stop action while session is retrying`

---

### Task 3.2: Add stop action to `RetryStatusBanner`

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
**Test:** none (Compose UI; predicate already tested in 1.1, abort handling tested in 1.2)
**Depends:** 2.1, 3.1 (sequential edits to same file)
**Domain:** frontend

Two edits in this task, both in `ChatScreen.kt`.

**Edit A** — call site (currently at line 2320):

```kotlin
                                RetryStatusBanner(retry = uiState.sessionStatus as SessionStatus.Retry)
```

Replace with:

```kotlin
                                RetryStatusBanner(
                                    retry = uiState.sessionStatus as SessionStatus.Retry,
                                    onStop = { viewModel.abortSession() },
                                )
```

**Edit B** — banner composable (currently at lines 4745-4776):

```kotlin
@Composable
private fun RetryStatusBanner(retry: SessionStatus.Retry) {
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
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.sessions_retrying),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = retryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
```

Replace it with:

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
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.sessions_retrying),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = retryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.chat_stop),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
```

Notes:
- The `Modifier.weight(1f)` on the `Text` pushes the new `IconButton` to the trailing edge.
- `IconButton(modifier = Modifier.size(28.dp))` shrinks the standard 48dp tap target to fit inside the slim banner without breaking the row's vertical rhythm; `Icon(Modifier.size(18.dp))` keeps the visible glyph slightly larger than the leading 16dp Refresh icon to read as a primary action.
- `Icons.Default.Stop` is already imported in this file (used at line 1480), so no new icon import is required.
- `IconButton` is also already imported (used at line 1478 etc.).

**Verify:**
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:assembleDebug`
- Manual: trigger a retry-loop conversation; banner shows the Stop button; tapping it calls `viewModel.abortSession()`. With network disconnected, banner remains visible and an error surfaces (covered by 2.1).

**Commit:** `feat(chat): expose Stop action inside retry status banner`

---

## Final verification

After all batches merge:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected new tests:
- `SessionStatusInterruptibleTest` (3 tests) — Idle / Busy / Retry classification
- `ChatAbortResultHandlerTest` (4 tests) — success, unsuccessful, exception with message, exception without message

Manual smoke test on a device or emulator:
1. Start a session, send a message that triggers a retry loop (e.g. point to a misconfigured provider). Confirm the retry banner shows the new Stop icon and the top bar Stop appears.
2. With network up, tap Stop from the banner — session moves to Idle within ~1s; banner disappears.
3. Repeat with airplane mode on — Stop tap surfaces the failure error; banner remains visible (this is the design's "abort failure flow").
4. Swipe-to-revert a user message — confirm revert dialog still works exactly as before (revert path unchanged).

---

## Out of scope (per user constraints)

- No new server APIs.
- No changes to revert semantics or the swipe-to-revert flow.
- No session list refactors. Adding stop affordances to the session list is explicitly noted as a future enhancement in the design's Open Questions and is NOT part of this plan.
- No proactive session-status refresh on chat entry (also listed as future enhancement in the design).
