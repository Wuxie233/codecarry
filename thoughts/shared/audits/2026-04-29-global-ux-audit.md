---
date: 2026-04-29
topic: "OC Remote global UX audit"
issue: 20
audit_method: ["NN/g 10 heuristics", "Material 3", "WCAG 2.2 AA mobile", "Android Core/Adaptive App Quality", "Baymard-style severity"]
status: draft
---

# OC Remote — Global UX Audit (v1.6.24 baseline)

## Method

- Walked each major surface twice: first as a task-oriented mobile user, then against NN/g 10 heuristics, Material 3 component guidance, WCAG 2.2 AA mobile accessibility, Android Core/Adaptive App Quality, and Baymard-style severity ranking.
- Evidence is code-path based from the v1.6.24 baseline and this issue's plan; no screenshots were invented. Findings cite screens/components such as `HomeScreen.kt`, `ServerDialog.kt`, `SessionListScreen.kt`, `ProjectGroupHeader.kt`, `ChatScreen.kt`, `McpManagementSheet.kt`, `StateCards.kt`, `SettingsScreen.kt`, `ServerSettingsScreen.kt`, `ServerProvidersScreen.kt`, `ServerModelFilterScreen.kt`, `ServerTerminalWorkspace.kt`, `TerminalEmulator.kt`, `AppUpdateDialog.kt`, and `Theme.kt`.
- Severity: P0 = blocks completion or creates an accessibility failure for common tasks; P1 = major friction / standards miss with safe release fix available; P2 = polish or consistency backlog; P3 = redesign / research-dependent improvement.
- Release rule: v1.6.25 only takes safe copy, accessibility-label, touch-target, contrast-token, and persistent-recovery fixes. Larger ChatScreen, SessionList, permission, and settings IA redesigns are deferred with explicit acceptance criteria.

## Surfaces covered

1. Startup / first-run / server list empty state
2. Server add/edit form
3. Server connection error states
4. Session list (project grouping, MCP hint badge, archive swipe)
5. Chat screen (composer, retry banner, busy state, stop button, attachments)
6. Tool call rendering and permissions prompts
7. Question prompts (modal, slash command, Octto-style)
8. Model / agent picker
9. MCP management sheet (project + fallback)
10. LSP / plugins management
11. Settings (theme, network, accessibility)
12. Terminal pane
13. Update/release notification flow
14. Empty / loading / error patterns across screens
15. Dark / AMOLED + small-screen + large-text scaling
16. Touch targets, content descriptions, focus order

## Findings table

| ID | Surface | Severity | Heuristic(s) | Evidence | Suggested fix | Acceptance criteria | Implemented in this release? |
|----|---------|----------|--------------|----------|----------------|---------------------|------------------------------|
| F-001 | MCP sheet Empty state | P0 | NN/g #9; WCAG 3.3.3; Android quality error recovery | `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt` `EmptyConfig` branch says only one config was found empty, while issue #20 changes resolver semantics to exhaust project + global candidates. | Task 2.2 copy update for fallback-exhausted Empty state. | Empty card explicitly says project and global OpenCode configs were checked and no MCP servers were declared; it includes the most useful checked path and refresh recovery. | yes (Task 2.2) |
| F-002 | MCP sheet Missing state | P1 | NN/g #6/#9; Material 3 progressive disclosure | `McpManagementSheet.kt` `MissingConfig` branch renders all checked paths inline in one message, which is diagnostically useful but visually noisy on a bottom sheet. | Task 2.2: primary checked path inline plus an expandable full checked-path list. | Default Missing state is scannable on 360dp width; full checked paths remain available behind a clear affordance. | yes (Task 2.2) |
| F-003 | MCP server enable switches | P0 | WCAG 2.5.5 target size; WCAG 4.1.2 name/role/value; Material 3 touch target | `McpManagementSheet.kt` `McpServerRow` renders a `Switch` with no server-specific content description and only local switch padding; row is not a single accessible 48dp target. | Task 2.2: add `${server.name} 启用状态` semantics and `minimumInteractiveComponentSize()` / row click target. | TalkBack announces each server's enabled state by name; switch/row target is at least 48dp. | yes (Task 2.2) |
| F-004 | Session list MCP hint badge | P1 | WCAG 1.3.1 info relationships; WCAG 4.1.2; NN/g #1 | `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeader.kt` shows MCP counts / hints in dropdown trailing UI, while the merged project-header semantics only announces project name and directory. | Task 3.1: add non-empty content description for MCP badge and ensure 48dp hit area when clickable. | TalkBack exposes whether the project has configured MCP servers or uses global MCP within the current model's available data. | yes (Task 3.1) |
| F-005 | Chat retry / stop affordances | P1 | WCAG 2.5.5; WCAG 4.1.2; NN/g #3 user control | `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` owns busy, retry, abort, and stop actions; these are critical escape hatches during streaming/retry states and must be explicitly named. | Task 3.2: add `contentDescription = "停止重试"` / matching labels and keep 48dp tap targets without toolbar rewrite. | Retry banner stop and top-bar stop controls are discoverable by accessibility services and remain easy to tap. | yes (Task 3.2) |
| F-006 | Shared loading / empty / error cards | P1 | WCAG 1.4.3 contrast; WCAG 4.1.2; Material 3 color roles | `app/src/main/kotlin/dev/minios/ocremote/ui/components/StateCards.kt` centralizes `LoadingStateCard`, `EmptyStateCard`, and `ErrorStateCard`; card action buttons need explicit accessible names and theme-token contrast verification. | Task 3.3: align title/body colors to M3 tokens, add meaningful action content descriptions, and keep large checked-path lists from dominating default state. | Representative state-card test confirms non-empty action content description; default text contrast remains token-based in light/dark/AMOLED. | yes (Task 3.3) |
| F-007 | Server add/edit form labels and recovery | P0 | WCAG 3.3.1/3.3.3 input error; WCAG 4.1.2; NN/g #5/#9 | `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/ServerDialog.kt` has visible labels, URL validation, and save/cancel actions, but form fields/buttons need explicit screen-reader descriptions; connection failures in `HomeScreen.kt` / `ServerSettingsScreen.kt` must provide persistent recovery. | Task 3.4: add explicit field/action content descriptions and persistent inline retry affordance for network/auth errors. | Host/port/name/token or equivalent fields are announced by purpose; save/test buttons are non-generic; connection/auth errors remain visible with retry. | yes (Task 3.4) |
| F-008 | Chat hard-error persistence | P1 | NN/g #9; WCAG 3.3.3; Android quality resilience | `ChatScreen.kt` and `ChatViewModel.kt` hold `error`, retry, and session status state; converting all hard failures from transient feedback into persistent banners may cross composer/message-flow boundaries. | Defer a scoped error-state design after v1.6.25; keep Task 3.2 limited to stop/retry labels unless persistence is already safe in-place. | Follow-up defines recoverable vs terminal errors, keeps terminal failures visible until dismissed, and preserves message-list scroll position. | no — deferred (follow-up issue TBD after #20) |
| F-009 | Session list archive / grouping focus order | P1 | WCAG 2.4.3 focus order; NN/g #7 flexibility; Android adaptive quality | `SessionListScreen.kt` and `ProjectGroupHeader.kt` combine project grouping, unread/activity counts, hidden/pinned state, MCP management, and archive actions in compact rows/menus. | Defer a focused SessionList accessibility pass; no archive-swipe or grouping restructure in this corrective release. | Follow-up validates keyboard/TalkBack order for expand, actions menu, archive all, and session rows; destructive actions remain confirmable/undoable. | no — deferred (follow-up issue TBD after #20) |
| F-010 | Tool-call and permission prompt hierarchy | P1 | NN/g #4/#5; WCAG 3.3.4 error prevention; Material dialogs | `ChatScreen.kt` renders tool output, permission prompts, and question prompts from SSE state; mixed prompt types risk inconsistent primary/secondary action order. | Defer a permission/question prompt spec and component audit; safe release only avoids changing prompt behavior. | Follow-up documents one action hierarchy for allow/deny/edit/cancel, with destructive choices requiring clear labels and stable focus. | no — deferred (follow-up issue TBD after #20) |
| F-011 | First-run server empty state | P2 | NN/g #10 help/documentation; Android onboarding | `HomeScreen.kt` renders `EmptyServersView` when remote servers are empty and may also show the local runtime card; first-run guidance is present but spread across local/runtime/server-add flows. | Add concise first-run task checklist later. | Empty state explains add remote server, start local runtime, and where to find settings without exceeding one screen. | no — backlog |
| F-012 | Model / agent picker density | P2 | NN/g #6 recognition; Material menus | `ChatScreen.kt`, `ServerProvidersScreen.kt`, and `ServerModelFilterScreen.kt` expose provider/model/agent choices; long model catalogs can become dense on mobile. | Add search/filter affordance and clearer active-model summary later if not already available per server catalog. | User can confirm provider, model, and agent before send with no horizontal overflow at 360dp. | no — backlog |
| F-013 | LSP / plugins discoverability | P2 | NN/g #1/#10; Android settings IA | LSP/plugins are managed through settings/server capability surfaces (`SettingsScreen.kt`, `ServerSettingsScreen.kt`) rather than a single obvious hub. | Add cross-links or short explanatory helper text later. | User can tell whether LSP/plugins are server capabilities, app settings, or unavailable for the selected server. | no — backlog |
| F-014 | Terminal pane scaling | P2 | WCAG 1.4.4 resize text; Android large-screen quality | `ServerTerminalWorkspace.kt`, `TerminalEmulator.kt`, and `SettingsScreen.kt` terminal font size setting cover terminal usage, but need large-text/small-screen regression checks. | Add terminal scaling test matrix and optional preview snapshots later. | Terminal remains readable at large text, with prompt/input not clipped on 360dp and landscape widths. | no — backlog |
| F-015 | Update / release notification clarity | P2 | NN/g #9/#10; Android install trust | `AppUpdateDialog.kt`, `SettingsScreen.kt`, and `ApkInstaller.kt` handle update checks and APK installation; release provenance and signer continuity should be explicit in notes/dialog copy. | Keep release notes explicit in Task 4; defer richer in-app provenance copy. | Update flow shows version, source, and actionable install/failure states without decorative icons being announced. | no — backlog |
| F-016 | Dark / AMOLED visual regression sweep | P3 | WCAG 1.4.3; Material dynamic color | `Theme.kt`, `HomeScreen.kt`, `ServerDialog.kt`, and `SettingsScreen.kt` contain AMOLED-specific color branches and dynamic-color toggles. | Run a dedicated visual regression pass after release; avoid broad palette changes in v1.6.25. | Representative startup, sessions, chat, settings, terminal, MCP, and update screens meet contrast in light/dark/AMOLED. | no — backlog |

## P0/P1 implemented in this release

- Task 2.2 implements F-001, F-002, and F-003 in `McpManagementSheet.kt`: fallback-aware Empty copy, scannable Missing diagnostics, and accessible MCP server toggles/touch targets.
- Task 3.1 implements F-004 in `ProjectGroupHeader.kt` / session-list MCP hint UI: content descriptions and minimum touch target for the MCP hint affordance.
- Task 3.2 implements F-005 in `ChatScreen.kt`: accessible names and touch targets for retry/stop escape hatches while avoiding composer/message rewrites.
- Task 3.3 implements F-006 in `StateCards.kt`: Material 3 token contrast, action labels, and bounded default state-card content.
- Task 3.4 implements F-007 in server form/settings surfaces: explicit field/action labels and persistent inline recovery for server connection/settings load errors where existing state exposes a safe retry action.

## P0/P1 deferred (with rationale)

- F-007 — Exact replay for provider auth-operation failures inside modal flows.
  - Rationale: `ServerSettingsViewModel` exposes `loadProviders()` for safe network/settings retry, but it does not retain the last failed auth operation (API key connect, OAuth method index, completion code) after the dialog state changes. Replaying those actions from UI without storing explicit intent risks changing auth flow/data handling. The release fix keeps errors persistent and offers safe reload retry; exact auth replay needs a small state design.
  - Scope estimate: 0.5–1 day including state shape, auth-flow regression, and TalkBack pass.
  - Follow-up issue: TBD after #20; acceptance criteria: failed API/OAuth actions remain visible until dismissed and expose a retry that repeats the same operation without losing typed input or changing credentials semantics.
- F-008 — Chat hard-error persistence.
  - Rationale: a full distinction between recoverable streaming failures, terminal API errors, retry banners, Snackbar-style feedback, and message-list scroll behavior can affect core chat behavior. Task 3.2 may safely label existing controls, but a broader error-state redesign is too risky for this corrective release.
  - Scope estimate: 1–2 days including state taxonomy, Compose implementation, TalkBack pass, and regression tests/manual scripts.
  - Follow-up issue: TBD after #20; acceptance criteria: terminal failures remain visible until dismissed/retried, retry/stop remains 48dp and named, message history and composer draft are preserved.
- F-009 — Session list archive / grouping focus order.
  - Rationale: the session list combines grouping, collapse, pin/hide, unread/activity counts, MCP management, archive, and swipe/menus. Changing focus order or archive interactions safely requires a dedicated pass and possibly previews/tests.
  - Scope estimate: 1–2 days including TalkBack/keyboard traversal, destructive action review, and small/large-screen checks.
  - Follow-up issue: TBD after #20; acceptance criteria: project header, MCP action, archive actions, and session rows traverse in a predictable order; destructive actions have undo/confirmation affordance.
- F-010 — Tool-call and permission prompt hierarchy.
  - Rationale: permission/question/tool prompts are safety-critical and tied to SSE flow. Standardizing action order and modal behavior needs design review to avoid accidentally changing command execution semantics.
  - Scope estimate: 2–3 days including prompt inventory, component consolidation, and permission regression tests.
  - Follow-up issue: TBD after #20; acceptance criteria: allow/deny/edit/cancel order is consistent, primary action labels are explicit, destructive permission grants are distinguishable, and focus lands on the prompt title or first safe action.

## P2/P3 backlog

- F-011 — First-run server empty state: add a compact checklist for adding a remote server vs starting local runtime.
- F-012 — Model / agent picker density: add search/filter or active selection summary for large catalogs.
- F-013 — LSP / plugins discoverability: add cross-links/helper text clarifying whether capabilities are server-side or app settings.
- F-014 — Terminal pane scaling: create small-screen, large-text, and landscape checks for terminal font settings and terminal input/output clipping.
- F-015 — Update/release notification clarity: make version/source/signer continuity easier to verify in the release/update path.
- F-016 — Dark / AMOLED visual regression sweep: run a full contrast pass across startup, sessions, chat, MCP, settings, terminal, and update surfaces after this safe release.
