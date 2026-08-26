# CodeCarry: Chat Timeline Grammar and Foreground Resume

## Goal

Make native Chat a scannable developer timeline: process events are independent rows, assistant prose has no bubble chrome. Returning from background refreshes session busy/unread without opening a conversation.

## Scenario

An operator watches an OpenCode or DSH turn: Think, a skill load, tools, then prose. Each process event is its own compact row. After backgrounding the app, Home and Sessions show current busy/unread; an already-open Chat also fills missed history.

## In Scope

1. Timeline grammar for assistant messages: `Think`, `skill`, and other tools are independent rows in part order. No `Show/Hide steps` fold inside a bubble.
2. Skill row chrome: collapsed summary is `Skill {name}` from the `name` argument. Expanded body is the durable result as Instructions. Do not dump JSON args on the summary.
3. Other tools keep their existing card bodies, but each card is a timeline row. Default collapsed; the existing auto-expand setting controls default expanded state.
4. Assistant prose is left-aligned, no Response header, no assistant bubble. User messages stay in bubbles.
5. Copy stays on long-press / message actions. The last assistant prose row of a message shows a quiet `time · model` line.
6. OpenCode and DSH share this grammar. OpenCode only renders parts it already has.
7. Rename the settings copy from auto-expand tool cards to auto-expand process rows. Keep the same stored boolean.
8. On process `ON_START`, refresh connected OpenCode and DSH servers: session busy/unread on Sessions and Home backing data; if Chat is already open, merge that session's history. One-shot, not polling. Do not fetch history for closed sessions.

## Non-Goals

- User-invoked `/skill` cards.
- DSH context injection, compaction dividers, full turn-tail (copy/feedback/branch/duration).
- Stacking consecutive tools into one group.
- Fetching every session's messages on resume.
- Markdown parser/AST/render-plan/WebView rewrite.
- Backend protocol changes, Kimi/DSH visual assets, or a second navigation graph.

## Constraints and Decisions

- `ChatMessageRowPlanner` owns the grammar. Assistant parts become rows in source order: reasoning → Think; `tool == "skill"` → Skill; other tools → Tool; text → existing markdown Whole/TextChunk planning; remaining content stays on the prose path.
- User messages remain `ChatMessageRow.Whole` with the current bubble.
- Process rows are not inside `ChatMessageBubble`. Prose rows render without assistant Surface chrome.
- `LocalCollapseTools` remains the default-expanded flag for process rows (Think, Skill, Tool).
- Follow-tail and horizontal overflow stay on the existing single `LazyListState` and overflow policy.
- OpenCode foreground resume extends the existing `ProcessLifecycleOwner.ON_START` status reconcile. It must also merge REST session list metadata so updated times/unread can move without opening Chat. An open `ChatViewModel` merges the current REST/history page; live state still wins on conflicts.
- DSH has no current `ON_START` path. A Ready generation refetches `workspace.list` + `session.list` into `DshEventReducer`. An open DSH `ChatViewModel` refetches `session.history` and merges. Half-open sockets that fail the refetch reconnect (reopen mux+host, then refetch). Passwordless non-loopback hide rules stay unchanged.
- Unread for OpenCode stays `markMainSessionUnread` from live events; resume must not clear unread. DSH busy comes from `session.list` / `host/session-status` `running`.
- Do not copy Kimi Vue/CSS or DSH React. Reuse interaction contracts only.

## Acceptance Evidence

- Planning an assistant message with reasoning, a `skill` tool, another tool, and text yields distinct Think, Skill, Tool, and prose rows in that order; no steps toggle row exists.
- A skill row collapsed label is `Skill {name}`; expanded content contains the result text, not the raw argument envelope as the summary.
- Assistant prose has no Response header and no assistant bubble; user bubbles still exist.
- With auto-expand off, process rows start collapsed; with it on, they start expanded.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest` covers planner grammar, process-row default expand, OpenCode foreground target selection, and DSH foreground list/history merge.
- Foreground tests prove: disconnected servers are skipped; connected OpenCode and Ready DSH are refreshed; Chat history merge runs only for the open session; a failed DSH refetch on a Ready generation triggers reconnect rather than leaving stale running flags.
- `:app:assembleDebug` passes after integration.

## Repository Facts

- Planner: `app/src/main/kotlin/dev/wuxie233/codecarry/ui/screens/chat/ChatMessageRowPlanner.kt` and `ChatMessageRowPlannerTest.kt`.
- Bubble/steps: `ChatScreen.kt` (`ChatMessageBubble`, `resolveStepsStatus`, tool cards). Extract process rows out of that file.
- Settings: `SettingsRepository.collapseTools`, `settings_auto_expand_tools*`.
- OpenCode resume: `OpenCodeConnectionService` `ForegroundStatusRefreshObserver`; `shouldReconcileForegroundStatus` currently OpenCode-only.
- DSH: `DshConnectionManager` has no lifecycle hook; `SessionListViewModel.loadSessions` / `ChatViewModel.loadDshHistory` only run on init or Ready transition.
- DSH fold already maps `tool/call` onto assistant `Part.Tool`; skill is a tool named `skill`.

## Assumptions

- OpenCode has no first-class skill tool; a `skill` tool part still uses Skill chrome if present.
- Home has no cross-server recent feed; refreshing session backing data is enough for Home/Sessions busy/unread.
- Version metadata for this delivery is `1.10.0` / `versionCode` `109` with `RELEASE_NOTES_1.10.0.md`.
