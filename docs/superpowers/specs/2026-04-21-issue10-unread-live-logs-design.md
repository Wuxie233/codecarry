# Design: Unread Main Sessions and Visible Retry/Command Logs

**Date**: 2026-04-21  
**Status**: Approved in chat  
**Scope**: Session list unread state, top-card ordering, project/session unread indicators, live command log visibility, retry visibility

---

## Background

The user approved a new isolated `/btw` workstream, separate from the current baseline-fix/release flow, with three tightly related UX goals:

1. **Unread main-session state**
   - Add a new unread state for completed-but-unread **main sessions** only
   - Show unread in the top status cards, ordered **before** the existing decision card
   - Show a small blue indicator on project cards when a project contains unread main sessions
   - Show a small blue indicator on session cards for unread main sessions
   - Entering a session clears unread

2. **Visible live command logs**
   - Bash/command/tool-call cards stay collapsed by default and show only a command preview
   - When the user expands the card during execution, the full live log becomes visible and keeps updating in real time
   - After completion, the same card keeps the full log

3. **Retry visibility must be real, not opaque**
   - The user observed a top-card `重试中` state while the opened chat did not expose what was being retried
   - The requirement is **not** to rename retry away
   - The requirement is to expose retry attempt details, failure reasons, and retry-related logs so the in-chat view matches the top-card retry state

Read-only exploration confirmed two important facts:

- **Unread does not exist today** in the client state or preferences layer
- **Tool output is not streamed today**: `ToolState.Running` has no output field, and `ToolCallCard` only renders output for `Completed/Error`
- **Retry is server-driven**: the top-card retry state comes from SSE `SessionStatus.Retry`, not from local manual follow-up messages

---

## Approved Product Contract

### A. Unread Main Sessions

- Only **main sessions** participate in unread status, counts, and indicators
- A session becomes unread when new assistant-side content finishes for that session and the user has not re-entered it afterward
- Unread is cleared when the user enters that session again
- Subagent sessions do **not** create unread state and do **not** contribute dots/counts

### B. Top Status Card Order

- The top active-conversation/status strip gains a new **Unread** card
- The **Unread** card is rendered **before** the existing **Decision** card
- The unread card counts completed-but-unread **main sessions** only

### C. Project/Session Indicators

- A project row shows a small blue indicator when that project contains at least one unread main session
- A session row shows a small blue indicator when that session is unread
- The indicator is informational only in this phase; no badge count bubble is required on the row itself unless existing layout naturally supports it

### D. Command/Retry Logs

- Bash/command/tool-call cards remain **collapsed by default**
- The collapsed card shows a **command preview** only
- If the user expands the card while execution is still running, the card reveals the **full live log** and keeps updating in place
- After completion, the same card preserves the full log
- If the active session is in **retry**, the user must be able to see retry-related progress, failure reason, and subsequent retry activity from inside the session view rather than only from the top card

---

## Design 1: Unread Main-Session State

### Current State

The session list stack currently has:

- grouping and aggregates in `SessionListViewModel.kt`
- top-card rendering in `ActiveConversationsBanner.kt`
- project header rendering in `ProjectGroupHeader.kt`
- session card rendering in `SessionListScreen.kt`
- user preferences stored in `SessionListPreferencesRepository.kt`

There is **no existing unread-session persistence or derived state**.

### Proposed Data Model

Add client-side unread persistence keyed by main-session id:

```kotlin
SessionListPreferences(
    ...,
    unreadMainSessionIds: Set<String> = emptySet(),
)
```

Repository additions:

```kotlin
suspend fun markMainSessionUnread(sessionId: String)
suspend fun markMainSessionRead(sessionId: String)
suspend fun markMainSessionsRead(sessionIds: Collection<String>)
```

This is intentionally client-local. It reflects what the user has viewed in the app, not a server-global read receipt.

### State Derivation Rules

In `SessionListViewModel`:

- extend `SessionItem` with `isUnread: Boolean`
- extend `ProjectGroup` with `unreadCount: Int`
- derive `isUnread` only for root/main sessions
- compute project-level unread presence from grouped main sessions only

Mark unread when:

1. the session is a main session
2. the app receives assistant-side content completion or session completion for that session
3. the user is not currently viewing that same session screen as active/foreground

Clear unread when:

1. the user opens the session
2. the session becomes the active visible chat screen

### Top Status Card Integration

`buildActiveConversations()` / banner ordering should insert an unread bucket before the current decision bucket.

Required behavior:

```text
Active / Processing / Unread / Decision / ...existing cards
```

The unread card should surface session title, project/server label, age, and tap-through behavior consistent with existing top cards.

### UI Surfaces

Files likely touched:

| File | Change |
|------|--------|
| `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferences.kt` | Add unread id storage field |
| `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepository.kt` | Add unread CRUD methods + DataStore key |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt` | Derive unread state, unread aggregates, top-card ordering |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ActiveConversationsBanner.kt` | Render unread card before decision |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeader.kt` | Render blue unread indicator on project rows |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` | Render blue unread indicator on session rows |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` or `ChatViewModel.kt` | Clear unread on session entry |

---

## Design 2: Live Command Logs with Expand-to-Reveal Full Streaming Output

### Current Limitation

The current model only supports:

- `ToolState.Running` with title/metadata/timing
- `ToolState.Completed` or `ToolState.Error` with final output/error

This means the client cannot show live tool output while execution is still running because the running state does not carry a streamable output field.

### Approved UX

1. Card is collapsed by default
2. Header shows command/tool preview only
3. User expands card
4. If still running, the card reveals a continuously updating full log area
5. If completed, the card shows the final preserved output in the same area

### Protocol Reality and Client Strategy

Upstream research shows OpenCode already streams bash output via repeated `message.part.updated` events while a tool is running. The main gap is therefore **client consumption**, not inventing a brand-new protocol.

Approved implementation strategy:

1. consume running `ToolPart.state.output` updates from the existing event stream
2. keep the card collapsed by default with preview-only header
3. when expanded, render the latest accumulated running output in real time
4. keep the final completed output in the same component after completion

This still requires careful handling in oc-remote because the server sends repeated full-state updates rather than clean deltas, so the client may need deduplication or last-seen-output guards to avoid visual churn.

### Client Rendering Rules

`ToolCallCard` behavior should become:

- **collapsed + running** → header only, no full log block visible
- **expanded + running** → render the accumulated live log block and keep it updating
- **expanded/collapsed + completed** → render final output using the same body component when expanded

The expand affordance must be available while running; today expansion is effectively reserved for completed/error output.

### Retry-Aware Log Content

If the server internally retries, the surfaced session/log view must include retry-related lines or retry metadata so the user can answer:

- what failed
- why a retry started
- which attempt is current
- whether the next retry is pending or active

This requirement applies even if the visible output is partially synthetic (status lines) rather than raw backend stderr.

### Files Likely Touched

| File | Change |
|------|--------|
| `app/src/main/kotlin/dev/minios/ocremote/domain/model/ToolState.kt` | Extend running/delta-capable tool representation |
| `app/src/main/kotlin/dev/minios/ocremote/domain/model/Part.kt` | Support tool-output accumulation if needed |
| `app/src/main/kotlin/dev/minios/ocremote/domain/model/SseEvent.kt` | Add tool delta / retry visibility event shape if protocol chooses delta path |
| `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt` | Parse new running-tool output/retry events |
| `app/src/main/kotlin/dev/minios/ocremote/data/EventReducer.kt` | Accumulate running tool output + retry info into part state |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` | Allow expansion during running and render live output block |

### External Dependency

For **live bash output**, upstream capability already exists, so this part should be implemented client-side first.

For **retry failure detail visibility**, the first implementation should consume any existing `SessionStatus.Retry` / retry-part metadata already emitted by OpenCode. Only if that proves insufficient should a follow-up upstream protocol change be treated as a blocker.

---

## Design 3: Retry Visibility Must Match the Top Retry State

### Current State

Exploration confirmed:

- top-card retry is driven by server SSE `SessionStatus.Retry`
- local manual follow-up messages do **not** create retry state on their own

Therefore the user-observed mismatch is not “manual follow-up misclassified as retry” on the client. It is a **visibility mismatch**: the session is retrying internally, but the session view does not expose enough retry detail.

### Approved Behavior

Keep `重试中` as a real state, but make it explainable in-session.

When a session is in retry:

1. the top card can keep showing retry
2. the opened session must expose the retry activity
3. the user should be able to see failure cause / failure log / retry attempt progression from the session view

### Proposed In-Session UI

Add a lightweight retry status block tied to the currently retrying execution chain:

```text
正在重试 · 第 N 次
上次失败：<summary>
<optional next retry timing>
```

This block should not replace the full live tool log. It complements it:

- collapsed card: preview only
- expanded card: full live command + retry log stream
- retry status block: compact semantic summary anchored to current retry state

### State Consistency Rule

If a session is in `SessionStatus.Retry`, at least one of the following must be visible from inside the session:

1. retry status block with latest failure summary
2. live running tool log with retry lines visible after expansion
3. both

The user must never be left with “top says retry, but chat shows nothing retry-related”.

### Files Likely Touched

| File | Change |
|------|--------|
| `app/src/main/kotlin/dev/minios/ocremote/domain/model/SessionStatus.kt` | Possibly enrich retry metadata if current fields are too weak |
| `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt` | Parse retry metadata if extended upstream |
| `app/src/main/kotlin/dev/minios/ocremote/data/EventReducer.kt` | Preserve latest retry summary/timing on session status |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt` | Keep retry top-card ordering and mapping coherent |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` | Render retry status block and connect it to live logs |

---

## Non-Goals

- Do not add unread state for subagent sessions in this phase
- Do not add manual read/unread toggles in this phase
- Do not redesign the entire tool output card layout beyond what is needed for collapsed preview + expanded live log
- Do not rename retry away when the server is actually retrying
- Do not claim live full logs if upstream protocol still only emits final output

---

## Acceptance Criteria

- [ ] A new unread top-card bucket exists before the decision card
- [ ] Only completed-but-unread main sessions count toward unread
- [ ] Project rows show a blue unread indicator when they contain unread main sessions
- [ ] Main session rows show a blue unread indicator when unread
- [ ] Opening a main session clears unread state
- [ ] Subagent sessions do not create unread state or unread indicators
- [ ] Bash/command/tool-call cards are collapsed by default and show command preview only
- [ ] Expanding a running card reveals a full live-updating log area
- [ ] After completion, the same card preserves the full log
- [ ] If the top card shows retry, the opened session exposes retry-related details (status summary and/or retry logs)
- [ ] Retry failure reason / retry progression is visible to the user during the retry lifecycle
- [ ] The feature work remains isolated in issue #10 / branch `feat/issue10-unread-live-logs`

---

## Open Dependency Note

The unread feature is fully client-local. The live log feature should now be planned as **client-first**, because upstream already emits running bash output updates. Retry visibility should also start client-first by consuming existing retry/session metadata, with an upstream follow-up only if the currently emitted retry details are too weak for the approved UX.
