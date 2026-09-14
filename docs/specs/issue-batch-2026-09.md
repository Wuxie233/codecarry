# Open Issue Batch Fixes — 2026-09 (#30–#47)

One-shot delivery contract covering all 18 open issues filed against review
baseline `1dd1e1514686ab82d54e518feb345595fd7d8d8a`. Each issue body on
GitHub (`gh issue view <n> -R Wuxie233/codecarry`) is authoritative for its
acceptance criteria; this spec adds the cross-issue contracts and the
implementation slicing.

## Product Goal

Close every open defect/gap from the 2026-09 review round: complete
server-scoped isolation for shared reducer state and persisted unread
records, a cross-backend (OpenCode / DSH / Codex) reply-read model so unread
marks reflect actually-unread replies, and the per-screen UX gaps in Codex
chat, the generic session list, and the DSH host surfaces screen.

## Cross-Issue Contracts

### C1. Server-scoped reducer state (#41)

`EventReducer.sessionStatuses`, `.messages`, `.parts` become server-scoped,
following the existing `serverSessions` / `serverSessionDetails` shape
(`Map<serverId, Map<sessionId, …>>`). All consumers are updated in the same
slice: `ChatViewModel`, `OpenCodeConnectionService`,
`SessionListViewModel` (status join region), `DiagnosticsViewModel`, and the
JVM tests (`EventReducerTest`, `SessionStatusSnapshotTest`,
`ChatViewModelRetryNowTest`, `ChatViewModelOptimisticBusyTest`,
`EventReducerBadPartFilterTest`, …). Duplicate session/message/part IDs on
two servers must not overwrite each other; disconnect cleanup for one server
must not clear another. Do not break the AGENTS.md rule that parent/child
derivations restrict IDs through the server-scoped maps first.

### C2. Shared read model for unread (#34, #35, #33, #32, #31, #30)

All unread marks and read cursors are keyed by `serverId + sessionId`
(Codex: `serverId + threadId`). One shared persistence owner
(`SessionListPreferencesRepository`) — no per-backend boolean stores that
compete with it.

- Storage: per-server unread sets (e.g. key `unread_main_session_ids/<serverId>`)
  replacing the single global set. Migration of the legacy global set:
  attribute legacy IDs to **every** server's set on first read — over-marking
  is harmless because consumers only query pairs that exist on that server,
  and marks self-heal on read. Document this in the repository KDoc.
- Read cursor (#35): persist a last-read anchor per `serverId+sessionId`
  (latest read assistant message id or equivalent stable anchor). Unread is
  derived from "new readable assistant output exists beyond the cursor",
  not from bare Busy→Idle transitions. Stop/failure/tool-only activity must
  not fabricate unread. Foreground reconcile (`ON_START`) recomputes
  idempotently from snapshots.
- Read advancement (#33): a session is marked read only when new replies are
  actually presented to the user (chat visible, following tail, history load
  succeeded). Opening a chat that fails to load must not clear unread;
  scrolling old history must not clear not-yet-seen newer replies.
- Visibility (#32): "active chat" tracking follows screen/app lifecycle
  (ON_START/ON_STOP), not ViewModel lifetime. Backgrounded chat with the
  ViewModel alive still marks unread and is not suppressed from
  notifications.
- Producers (#31, #30): DSH turn completion with new readable output and
  Codex turn completion write into the same server-scoped store; DSH must
  not reuse OpenCode SSE assumptions. Codex unread survives restart and does
  not depend on system notifications being enabled.

### C3. Generic session list operations (#42, #43, #44)

- #42: operation helpers (`serverScopedSessions()` and its callers such as
  project archive) read from `serverSessionDetails[serverId]`, never from
  the global session list filtered by IDs.
- #43: with list content present, mutation/refresh failures surface as a
  visible, actionable error (keep the list; don't regress the existing
  per-item `UndoAction.Failure` snackbar semantics).
- #44: rename/archive/restore and friends express connection capability and
  per-operation pending state (disable or clear retry feedback when
  disconnected; prevent double-tap re-entry).

### C4. Codex chat fixes (#36–#40)

- #36: `recheckPendingSend` / `reconcileUncertainSend` publish the merged
  reducer state (same as the normal recovery path), never a raw older
  `readThread` snapshot over newer live items; keep connection-generation
  fencing; no duplicate sends or lost attachments.
- #37: goal and model catalog have independent loading/empty/error/loaded
  states with retry; failures never block history or sending; keep already
  valid stale data marked as such.
- #38: memory mode gets authoritative read-back if the bridge protocol
  exposes a read RPC (verify against `codex-bridge/` + app-server protocol);
  otherwise the UI shows explicit receipt semantics ("set to X at …",
  unknown ≠ authoritative) — never fake a live value.
- #39: MCP form required booleans without schema default can be submitted
  as the displayed `false` directly; display and submitted value never
  disagree.
- #40: display the thread's authoritative `approvalPolicy` / sandbox mode
  (decoded but unconsumed fields in `CodexThreadSession`) in the chat
  control surface with unknown-state handling; stale values must not show
  after reconnect. Read-only display — no arbitrary JSON editing, no
  permission self-escalation.

### C5. DSH host surfaces (#45, #46, #47)

- #46: `loadAll` splits into per-module loads (directories, presets,
  automations, settings, models, …) with independent loading/error/data and
  per-module retry; success publishes progressively; one module's failure
  never blocks others; cancellation and connection-generation isolation
  preserved.
- #45: wire a real skills entry: a session-context selector that satisfies
  the DSH session-scoped skill contract; distinguish empty / failed /
  no-session-selected; retry and session switching work; chat's existing
  skill capability is untouched.
- #47: extended operations (preset/goal/subagent/settings) inherit the
  current session context or offer an explicit picker showing target name +
  server; common settings become forms; raw JSON stays behind an explicit
  advanced path with validation. Keep backend capability constraints
  (non-loopback hiding rules) intact.

## Non-Goals

- No new backends, no protocol redesigns, no OpenCode service restarts.
- Issue #10 (historical unread feature) stays closed/superseded; this batch
  only fixes the listed gaps.

## Constraints

- Repository conventions per `AGENTS.md` (ServerType routing, server-scoped
  reads, snapshot/SSE merge safety, Compose boundaries, string resources).
- JVM tests in `app/src/test`; focused tests per slice, full
  `:app:testDebugUnitTest :app:assembleDebug` once at terminal integration
  (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`).
- Shared checkout: slices own disjoint file sets; the captain alone commits.
- Immutable delivery baseline: `1dd1e1514686ab82d54e518feb345595fd7d8d8a`
  (bundle: `/flyshop/dev/tmp/codecarry-issue-batch-baseline`); pre-existing
  untracked `.agent-teams/`, `.dsh-filess/`, `.opencode/goals/`,
  `.pi-subagents/` are not part of this delivery.

## Acceptance Evidence

Per issue: focused tests covering the issue's stated scenario (duplicate IDs
across servers, offline completion, visibility transitions, delayed
snapshot vs live event races, boolean form submission, per-module failure
isolation, …) plus the issue's own acceptance bullets. Terminal: full JVM
suite + assembleDebug green on integrated tree, one bugbot-review pass over
the cumulative diff, atomic per-slice commits on `master` pushed to
`origin/master`, every issue #30–#47 closed with evidence comments.

## Release (user-authorized, after issues close)

Follow `README.md` manual release flow with the next version after
1.14.8/132 (expected `1.14.9`/133): bump `versionName`/`versionCode`, add
`RELEASE_NOTES_1.14.9.md` (Chinese, 1.14.8 note style), verify
`:app:testDebugUnitTest :app:assembleDebug`, push `master`, create and push
tag `v1.14.9`, manually trigger `.github/workflows/release.yml` with the
tag, then confirm exactly one APK `codecarry-1.14.9.apk` on the release
with matching install metadata.

## Slice Graph (implementation coordination)

| Slice | Issues | Owner | Files (exclusive) | Depends on |
| --- | --- | --- | --- | --- |
| R | #41 | reducer-scope | `EventReducer.kt` + its consumers/tests | — |
| C | #36–#40 | codex-chat | `CodexChatViewModel.kt`, `CodexChatScreen.kt`, `CodexModels.kt` (+tests) | — |
| D | #45–#47 | dsh-surfaces | `DshHostSurfaces*.kt`, `DshHostSurfaceController.kt` (+tests) | — |
| U1 | #34,#35,#33,#32 | unread-reader | `SessionListPreferencesRepository.kt`, `SessionListPreferences.kt`, `ChatViewModel.kt`, `ChatScreen.kt`, `OpenCodeConnectionService.kt`, `SessionListViewModel.kt` (unread region) (+tests) | R |
| U2 | #31 | unread-reader | DSH service/reducer/manager unread producer (+tests) | U1 |
| U3 | #30 | codex-chat | `CodexThreadListScreen.kt`/VM, Codex manager/reducer unread (+tests) | U1 |
| S | #42,#43,#44 | reducer-scope | `SessionListViewModel.kt` (operations), `SessionListScreen.kt` (+tests) | R, U1 |
| INT | all | captain | integration, full suite, review, commits, push, issue closure | R,C,D,U1,U2,U3,S |
