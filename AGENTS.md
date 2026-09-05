# OC Remote Agent Notes

The current product identity is CodeCarry. It is an independently maintained
fork based on OC Remote; the Android namespace/applicationId is
`dev.wuxie233.codecarry`, and the canonical repository is
<https://github.com/Wuxie233/codecarry>.

## Architecture

- CodeCarry is a single-module Android application in `app/`, built with
  Kotlin, Jetpack Compose, Hilt, Ktor, coroutines, and kotlinx serialization.
- `ServerType` is the backend boundary: `OPENCODE`, `DSH`, and `CODEX` have separate
  transport contracts and capability routing. Codex returned in 1.12.0.
  Pi Stack and Pi Roundtable remain removed; persisted rows of those types
  drop on DataStore read.
- OpenCode uses REST for snapshots and commands plus SSE for live state.
  `OpenCodeConnectionService` owns connection continuity, `EventReducer` owns
  the server-scoped live aggregate, and screen ViewModels derive UI state and
  coordinate user actions.
- DSH uses Typert Remote: `POST /api/<namespace>/<method>` with
  `{ type, rpcId, method, payload: { args } }`, plus one downlink WebSocket
  `/api/remote.mux` with logical streams. `DshApiClient` owns unary RPC and
  `$events/result`, `DshConnectionManager` owns generation readiness and
  reconnect, `DshEventReducer` owns mux aggregates. Ready only after cookie
  exchange, mux open, `$events` ready (`host.home`), `session/control` opening
  baseline, and `workspace/follow` opening baseline. Contract:
  `docs/specs/dsh-remote-auth-1.11.0.md`.
- Codex uses native app-server JSON-RPC over WSS, with `CodexConnectionManager`
  owning reconnect/generation boundaries and `CodexEventReducer` owning live
  state. Its dedicated thread/chat screens route by `serverId`. Do not send
  Codex through OpenCode REST/SSE or DSH RPC.
- `codex-bridge/` forwards authenticated WebSocket frames to the existing
  shared daemon Unix socket. It owns connections, never agent processes.
  Terminal clients must share that daemon (`codex --remote unix://`) for live
  steering. A separate WebSocket app-server or standalone `codex exec` is a
  different live runtime. Details: `codex-bridge/README.md`.
- When WSS connects but model turns time out, inspect the shared daemon
  process environment. Shell proxy exports do not reach a systemd daemon;
  configure its owning unit. See the outbound proxy section in
  `codex-bridge/README.md`.
- A newly started Codex thread has no durable rollout before its first turn.
  Keep its start response on the same client connection; clear that cache on
  first submission or connection failure. Thread lists set `modelProviders=[]`
  to avoid hiding sessions when the default provider changes.
- Compose screens own presentation. Keep navigation, transport, reducer, and
  backend-specific state outside reusable UI components.

## Conventions

- Route backend behavior through `ServerType` and explicit capability models;
  do not infer protocol compatibility from similar UI features.
- Scope OpenCode reducer reads and mutations by `serverId` before joining on
  session, permission, question, or parent IDs.
- Keep REST snapshots merge-safe with live events. A late snapshot may fill
  missing history, but must not overwrite newer SSE-derived state.
- Put JVM tests in `app/src/test` and device/gesture coverage in
  `app/src/androidTest`. Prefer focused tests for a changed state machine, then
  run the repository verification commands below once after integration.
- Keep user-facing strings in Android resources and preserve the existing
  Compose component boundaries when changing chat or session layouts.

## Communication

- Prefer concise Chinese for user-facing updates and final summaries.
- Keep code symbols, commands, file paths, version tags, and API names in their original spelling.
- Do not ask code-level questions. Make technical decisions from the codebase; only ask when product behavior or user intent is genuinely unclear.
- The user prefers `vibe talking`: natural chat-first UX, minimal visible control panels, and no unnecessary explanation.

## Release Workflow

- Releases are manual-only and should follow `README.md`: bump `versionName` and `versionCode`, add `RELEASE_NOTES_<version>.md`, verify locally, push `master`, create/push tag, then manually trigger `.github/workflows/release.yml` with the tag.
- Tag pushes alone do not publish releases for this repo.
- Use `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` for local Gradle verification in this environment.
- If Gradle/Hilt/Kotlin generated cache errors appear, run a clean build/test before treating them as code failures.

## Safety

- Do not restart OpenCode services or processes from this repo work.
- Do not force-push or clobber existing tags unless the user explicitly requests it.
- Keep `.codegraph/`, `.kotlin/`, and local OpenCode cache/config artifacts out of commits.

## Gotchas & Decisions

- Native OpenCode send readiness is independent of full REST history and
  unrelated screen initialization. A usable OpenCode connection plus an
  available route directory is enough to start draining sends; do not wait for
  delayed session metadata or project sync once that directory is known.
- Early native OpenCode submissions are captured in a per-chat, in-memory FIFO.
  Additional submissions may append while a queue exists. Remove only
  successful heads and drain in order; later entries must not overtake the
  head.
- If the FIFO head fails, keep it at the head with visible retry semantics.
  Retrying resumes the same queue; it must not duplicate a successful send or
  silently discard the failed request.
- The native pending-send FIFO is ViewModel memory, not durable storage. It does
  not survive process death; persisted composer drafts are a separate feature.
- OpenCode message history remains a limit-only REST contract. Loading older
  messages raises the limit and refetches a larger full page; there is no
  cursor or incremental-page API to document. Render reducer messages already
  available while that request or other initialization remains in flight.
- Merge a restored OpenCode REST page into the current reducer state rather
  than replacing it. On conflicts, the current live message/part state wins so
  a late REST response cannot roll back newer SSE deltas.
- DSH is its own transport. Do not reuse OpenCode REST/SSE models. Unary
  envelope is `{ type, rpcId, method, payload: { args } }` on slash Remote
  paths. Live state rides `/api/remote.mux` with logical `open`/`item`/`end`
  streams. Reconnect reopens mux and re-follows `$events`, `session/control`,
  and `workspace/follow`. Per-chat history uses `session/follow` plus
  `session/page`. Both take a Host `SessionAddress`: ordinary sessions
  `{ kind: "session", sessionId }`; a child with `origin == "subagent"`
  `{ kind: "subagent", parentSessionId, childSessionId, mode }`. Never
  follow/page a subagent origin as `kind: "session"` — Host returns
  `session/agent-busy`. If origin is subagent and parent is still
  unknown, wait; do not invent a parent. `session/page` throughSeq is
  the follow snapshot cursor (later live seqs), never a JS sentinel;
  unknown cursor skips the page call. Chat send/stop on that child use
  `subagents/prompt` and `subagents/interruptByParent`. No SSE fallback.
  `host.describe`, `/api/events.mux`, `/api/events.host`, `/api/respond`,
  and `workspace.list` are gone.
- DSH `assistant/message` already embeds `tool-call` blocks. A later mux
  `tool/call` with the same `callId` must update that part, not append a
  second one, or Chat renders two identical Shell/MCP rows.
- DSH connect is HTTP(S) only. Latest DSH requires a process launch token:
  `GET /?token=` mints an authority-bound Connection cookie. CodeCarry
  accepts `ServerConfig.token` or `?token=` on the saved URL (strip the query
  after save), follows the 303, and keeps the cookie in the in-memory
  generation. Shared Ktor stays cookie-free; DSH attaches `Cookie` per
  request. Optional `ServerConfig.password` remains HTTP Basic for a fronting
  proxy such as dsh-auth. A passworded public host still GETs `/` so the
  proxy can attach the current process token, and is treated as loopback
  because that proxy rewrites Host to `127.0.0.1:18790`. Passwordless
  non-loopback URLs still hide: `directoryPicker/pick`,
  `session/openWorkspacePath`, `credentials.*`,
  `settings/openSettingsDocument`, `llm/discoverModels`,
  `agentPresets` read/copy/openDocument/remove. A 401 is
  `DshAuthRequiredException`.
- Answer DSH `approval/request` and `user-questions/request` waterfalls on
  `POST /api/$events/result` with `{ args: { clientId, eventId, outcome } }`.
  Question answers must echo each question `id` and put custom text in
  `custom`, never in `selected`. Single-select custom and selected labels are
  mutually exclusive. Approval outcome is `allowed-once` | `rejected` (map
  Always to allowed-once). A rejected receipt must surface an error and
  unlock the card. Pending requests survive screen collector timing and clear
  on disconnect or resolved frames. DSH approval has no Always grant.
- `session/prompt` mode is `queue` or `steer` and requires a client-minted
  `requestId`. A sole text block starting with `/` is a host slash command.
  DSH file mentions use `directoryPicker/list`, not OpenCode `@file` search.
  DSH has no shell/terminal.
- Remaining non-loopback DSH unary surfaces (workspace, skill, git, preset
  list/select, goal, automation, settings mutate, llm catalog, subagent,
  systemPrompt, directory browse) live under Server Settings via
  `DshHostSurfaceController`.
- DSH Sessions + opens the in-app `directoryPicker/list` browser, not the
  loopback OS picker. Selecting a directory runs `workspace/create` then
  reuses an unarchived blank member or `session/create(workspaceId)`. No Repo
  omits both `cwd` and `workspaceId`. Do not create with a bare `cwd`; that
  leaves the session Ungrouped. Planner: `DshConnectWorkspace.kt`.
- Chat markdown has two render paths: normal markdown uses Compose in `ChatMarkdownRenderer.kt`; KaTeX/math markdown uses the WebView renderer in `MarkdownMessageRenderer.kt`. Keep code blocks, tables, display math, and reasoning/plain text with long unbreakable ASCII tokens independently horizontally scrollable; normal prose should still wrap to the bubble width.
- Chat Markdown has one structural pipeline: `MarkdownDocumentParser.kt` builds the GFM AST-backed document, `MarkdownRenderPlan.kt` assigns block routes and structured table data, and `MarkdownStreamingPlan.kt` extends or reparses only the open suffix. Do not add a second line scanner or whole-message/max-chunks fallback.
- Streaming chat must not replan unchanged earlier messages. `ChatMessageRowPlanningState` caches rows by message fingerprint; `reuseStableChatMessages` keeps earlier `ChatMessage` identities so Compose keys stay put. DSH live folds go through `DshHistoryFolder` and only apply new seqs.
- Long assistant Markdown is split into typed top-level rows. Tables and fenced code own their rows; root lists, blockquotes, raw HTML, and indented code remain atomic; prose may coalesce within the size budget.
- Compose rendering dispatches planned blocks independently. Tables always consume structured cells through `MarkdownTableLayout.kt` with 80..280dp content-adaptive columns; only a non-table block containing math routes through the KaTeX/WebView adapter. Keep prose selection intact: JVM gesture diagnosis proved `SelectionContainer + DisableSelection` does not block table dragging.
- Compose ordered lists use a local renderer because mikepenz 0.28.0 resets every independent list to `1.`. Preserve each `ORDERED_LIST` AST start number and recompute nested list starts independently.
- Compose blockquotes use a local renderer because mikepenz 0.28.0 renders only the first paragraph child. Preserve every quote child in source order, including later paragraphs, nested quotes, lists, code, and tables.
- Kimi Code Web layout research is pinned to the last source snapshot `e7d5a0a` in `docs/research/kimi-code-web-layout.md`; current `kimi-code` main keeps only `apps/kimi-code/dist-web`. Reuse its information architecture and interaction contracts, not Vue code, CSS, branding, fonts, or generated bundles.
- Cursor is not a third OpenCode-like HTTP server. Official control surfaces are the Cloud Agents REST API (`api.cursor.com`), CLI ACP (`agent acp` over stdio JSON-RPC), and `cursor-sdk-bridge` (`sdk.v1` Connect on loopback). Cursor for iOS already uses the cloud/remote-control path; Android is planned with no date. Product preference if CodeCarry adds Cursor later: local agent via a host sidecar, not cloud VMs and not IDE CDP. Do not reverse-engineer IDE protobuf or OpenAI-compatible private-endpoint proxies (ToS §1.5; staff have said that path can ban accounts). Feasibility notes: `docs/research/cursor-control-surface-feasibility.md`.
- First-table drag coverage now spans Compose tables, long planned Compose rows, KaTeX/WebView tables, and a user-style `SwipeToDismissBox` parent in `MessageMarkdownHorizontalDragTest.kt`; all 16 physical-drag tests pass on the task AVD. A field failure that remains after these paths pass needs the exact message payload and input context before changing production gesture arbitration.
- Connection setup is multi-stage. `OpenCodeConnectionService.connectionPhases` must reflect the real health check, workspace/session sync, activity restore, live-event setup, and retry wait; Home should render those phases in the existing server card instead of reducing them to a generic spinner or fake percentage.
- Response-ready notifications use `SessionNotificationIdentity`. When `ChatViewModel` marks an OpenCode session read, it must cancel that session's response notification and remove the server group summary only when no sibling event notifications remain; permission, question, and error notifications stay independent.
- `EventReducer.sessions` is a cross-server aggregate. Any Home or chat derivation that follows parent/child session relationships must first restrict IDs through `EventReducer.serverSessions[serverId]`; OpenCode session IDs and parent IDs are not safe cross-server join keys by themselves.
- Parent/child topology must come from `EventReducer.serverSessionDetails[serverId]`, not from the global `sessions` list filtered by IDs; another server may reuse the same session ID with different metadata.
- Pending OpenCode permissions and questions retain ownership in `EventReducer.permissionsByServer` and `questionsByServer`. Consumers and optimistic removals must select the active `serverId`; duplicate session or request IDs across servers must never share or clear pending state.
- OpenCode recent work belongs inside the selected server's Sessions control surface and must be derived from that server's `serverSessions[serverId]`; do not place a cross-server recent-work feed on global Home.
- Background OpenCode status continuity has two safeguards: the SSE read must use a finite timeout so silent half-open sockets enter the service reconnect loop, and `ProcessLifecycleOwner.ON_START` reconciles every connected OpenCode server from REST snapshots. Snapshot application must remain revision-safe, preserve newer live SSE events, and fail closed when a complete project-scope list cannot be discovered or reused. The same `ON_START` also merges OpenCode session lists, refreshes Ready DSH `session/list` plus the `workspace/follow` catalog, and asks an open `ChatViewModel` to merge only that session's history via `session/follow`/`session/page`. A failed Ready DSH catalog refetch reconnects `/api/remote.mux` instead of leaving stale running flags.
- Native Chat layout now has focused boundaries: `ChatHeader.kt` owns compact/expanded context actions, `ChatResponseDock.kt` owns retry/permission/question placement above the primary composer, `ChatAdaptiveShell.kt` owns `WindowSizeClass` and safe-drawing layout, and `ChatFollowTailPolicy.kt` owns long-session follow-tail state. Keep backend callbacks and the single `LazyListState` scroll owner in `ChatScreen.kt`; do not reintroduce pending request cards in the timeline or hard-coded width breakpoints.
- Chat timeline grammar is owned by `ChatMessageRowPlanner.kt`: assistant `Part.Reasoning` / `tool == "skill"` / other tools / leftover file-patch parts become independent Think, Skill, Tool, and Content rows. Assistant prose has no Response bubble chrome; user messages stay bubbles. `ChatProcessRows.kt` owns Think/Skill disclosure chrome. Spec: `docs/specs/chat-timeline-grammar.md`.
- `SessionWorkspaceOverview.kt` and `SessionProjectsViewport.kt` share a centered 960dp content cap for the selected server's recent work, view controls, and project queue. Preserve `SessionListViewModel` as the server-scoped state authority when changing the visual hierarchy.
- DSH presets are session-scoped. Chat and new-session flows use
  `DshPresetPickerSheet`; `DshConnectWorkspace` must carry explicit choices
  through blank-session reuse. Never route DSH preset selection through
  OpenCode's draft-only `selectAgent` path. Only ordinary idle sessions may
  switch, and a receipt from an obsolete connection generation cannot apply.
  Model display follows session `modelSelection.next`; the catalog's global
  default is valid only when the projection explicitly selects that default.
- Codex mobile presentation is split between `CodexTimeline`,
  `CodexTimelineViewport`, and `CodexComposerAttachments`. The viewport owns
  the single lazy-list scroll state and uses `ChatFollowTailPolicy`; physical
  drags, not programmatic scrolling, suspend tail following.
- Codex plan/diff/token notifications have state separate from turn items in
  `CodexEventReducer`. Their thread/turn maps must not overwrite streamed
  items; reconnect invalidates notification-only state before resume so new
  events remain authoritative. Do not display missing token usage as zero.
- Codex attachments send image bytes as image data URLs, never Android-local
  paths as daemon `localImage` inputs. Camera files are restricted to the
  FileProvider `codex-attachments/` cache path. Keep binary attachments out of
  saved-instance Bundles. Skills with parse errors must not hide valid Skills.

- DSH preset reads observe only the active server generation and publish the
  roster independently of `session/list`. Ready starts separate preset and
  session load jobs; disconnect cancels both. `agentPresets/list` has a 15-second
  deadline that becomes a retryable failure; caller cancellation still propagates.
- DSH current preset comes from `projections.values.agentPreset` or its selected
  log event, not the creation header. Explicit null means unassigned; missing
  state is unresolved, never the catalog default. Capture the preset sequence
  before a selection request so a late receipt cannot replace newer live state.
- Codex project screens reuse the shared session presentation components while
  keeping their native transport and server-scoped project preferences. Remote
  directory browsing uses `fs/readDirectory`; preserve exact directory strings,
  including trailing spaces, when starting a thread.
- Codex composer Enter inserts a newline. Keep submission on the explicit send
  button rather than wiring the text field IME action to submit.
- Codex and native chat share `ChatHeader` and `ProcessDisclosureRow`
  presentation. Preserve native composer spacing, AMOLED treatment, and
  navigation insets when adding backend-specific controls.
- Codex user-message images come from `raw.content`: `image.url` is a data or
  HTTP URL, while `localImage.path` belongs to the daemon and must use its
  `fs/readFile` RPC. Never resolve remote image paths through Android files.
  Image load failures must leave a retryable placeholder in the timeline.
- Different Codex threads need independent navigation entries and ViewModel
  owners, including notification navigation. Same-destination single-top can
  retain the parent's constructor-bound thread ID. Open with full `thread/resume`
  history; do not require a disk-backed read for a running, unmaterialized child.
- Publish a Codex resume snapshot before optional goal/model metadata loads.
  Late snapshots cannot regress terminal turns, and unrelated notifications
  must not erase a local open error or a newer server error.

## Commands

Use Java 21 for Gradle on this host:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Run one JVM test class with:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests 'fully.qualified.TestClass'
```

If generated Hilt/Kotlin caches fail, rerun the affected verification after
`./gradlew clean` before diagnosing a source regression.

## Module Map

- `app/src/main/kotlin/dev/wuxie233/codecarry/data/api/`: OpenCode wire APIs
  and DTOs.
- `app/src/main/kotlin/dev/wuxie233/codecarry/data/dsh/`: DSH Remote RPC,
  `/api/remote.mux` downlinks, event reduce, chat fold, connect-workspace
  planner, and remaining host unary surfaces.
- `app/src/main/kotlin/dev/wuxie233/codecarry/data/repository/`: persisted
  repositories and shared OpenCode event reduction.
- `app/src/main/kotlin/dev/wuxie233/codecarry/domain/`: backend-neutral models
  and transport contracts.
- `app/src/main/kotlin/dev/wuxie233/codecarry/service/`: foreground connection,
  notification, reconciliation, and local-runtime services.
- `app/src/main/kotlin/dev/wuxie233/codecarry/ui/screens/`: screen state,
  ViewModels, and backend-specific user workflows.
- `app/src/main/kotlin/dev/wuxie233/codecarry/ui/components/`: reusable Compose
  presentation components.
- `app/src/main/res/`: Android resources and localized strings.
- `app/src/test/`: JVM/Robolectric behavior tests.
- `app/src/androidTest/`: device and physical-gesture tests.
- `docs/research/`: evidence-backed design and compatibility research.

- `turn/start` command responses contain an authoritative turn ID. Apply that
  receipt before releasing the composer for steering; do not wait exclusively
  for `turn/started`. Ignore a late start receipt when the reducer already
  knows that turn, preserving streamed items and terminal status.
