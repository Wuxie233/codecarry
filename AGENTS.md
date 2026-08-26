# OC Remote Agent Notes

The current product identity is CodeCarry. It is an independently maintained
fork based on OC Remote; the Android namespace/applicationId is
`dev.wuxie233.codecarry`, and the canonical repository is
<https://github.com/Wuxie233/codecarry>.

## Architecture

- CodeCarry is a single-module Android application in `app/`, built with
  Kotlin, Jetpack Compose, Hilt, Ktor, coroutines, and kotlinx serialization.
- `ServerType` is the backend boundary: `OPENCODE` and `DSH` have separate
  transport contracts and capability routing. Codex, Pi Stack, and Pi
  Roundtable were product-deleted in 1.9.0; persisted rows of those types
  drop on DataStore read.
- OpenCode uses REST for snapshots and commands plus SSE for live state.
  `OpenCodeConnectionService` owns connection continuity, `EventReducer` owns
  the server-scoped live aggregate, and screen ViewModels derive UI state and
  coordinate user actions.
- DSH uses `POST /api/<method>` plus downlink-only WebSockets
  `/api/events.mux` and `/api/events.host`. `DshApiClient` owns unary RPC and
  respond, `DshConnectionManager` owns generation readiness and reconnect,
  `DshEventReducer` owns mux/host aggregates. Ready only after `host.describe`
  plus both sockets.
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
  envelope is `{ rpcId, payload }`; mux/host frames are server-originated
  `server-request` text. Client sends no application data on those sockets.
  Reconnect reopens both sockets and refetches history. No SSE fallback.
- DSH connect is HTTP(S) only. Password is optional: stock DSH has no auth,
  only Host plus `trustedHosts`. If `ServerConfig.password` is set, CodeCarry
  sends HTTP Basic (`Authorization: Basic` of `:<password>`) on unary POST,
  `/api/respond`, and mux/host WebSocket handshakes for a fronting proxy such
  as dsh-auth. A passworded connection is treated as loopback because that
  proxy rewrites Host to `127.0.0.1:18790`. Passwordless non-loopback URLs
  still hide: `host.pickDirectory`, `host.openPath`, `credentials.*`,
  `settings.openDocument`, `llm.discoverModels`,
  `agentPreset.read/copy/openDocument/remove`. A 401 is `DshAuthRequiredException`.
- Answer DSH `approval/requested` and `question/requested` on unary
  `POST /api/respond` with the host `rpcId`. Pending requests survive screen
  collector timing and clear on disconnect or resolved frames. DSH approval
  has no Always grant.
- `session.prompt` mode is `queue` or `steer`. A sole text block starting with
  `/` is a host slash command. DSH file mentions use `host.listDirectory`,
  not OpenCode `@file` search. DSH has no shell/terminal.
- Remaining non-loopback DSH unary surfaces (workspace, skill, git, preset
  list/select, goal, automation, settings mutate, llm catalog, subagent,
  systemPrompt, directory browse) live under Server Settings via
  `DshHostSurfaceController`.
- Chat markdown has two render paths: normal markdown uses Compose in `ChatMarkdownRenderer.kt`; KaTeX/math markdown uses the WebView renderer in `MarkdownMessageRenderer.kt`. Keep code blocks, tables, display math, and reasoning/plain text with long unbreakable ASCII tokens independently horizontally scrollable; normal prose should still wrap to the bubble width.
- Chat Markdown has one structural pipeline: `MarkdownDocumentParser.kt` builds the GFM AST-backed document, `MarkdownRenderPlan.kt` assigns block routes and structured table data, and `MarkdownStreamingPlan.kt` reconciles completed-prefix identity while reparsing the open suffix. Do not add a second line scanner or whole-message/max-chunks fallback.
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
- Background OpenCode status continuity has two safeguards: the SSE read must use a finite timeout so silent half-open sockets enter the service reconnect loop, and `ProcessLifecycleOwner.ON_START` reconciles every connected OpenCode server from REST snapshots. Snapshot application must remain revision-safe, preserve newer live SSE events, and fail closed when a complete project-scope list cannot be discovered or reused.
- Native Chat layout now has focused boundaries: `ChatHeader.kt` owns compact/expanded context actions, `ChatResponseDock.kt` owns retry/permission/question placement above the primary composer, `ChatAdaptiveShell.kt` owns `WindowSizeClass` and safe-drawing layout, and `ChatFollowTailPolicy.kt` owns long-session follow-tail state. Keep backend callbacks and the single `LazyListState` scroll owner in `ChatScreen.kt`; do not reintroduce pending request cards in the timeline or hard-coded width breakpoints.
- Chat timeline grammar is owned by `ChatMessageRowPlanner.kt`: assistant `Part.Reasoning` / `tool == "skill"` / other tools / leftover file-patch parts become independent Think, Skill, Tool, and Content rows. Assistant prose has no Response bubble chrome; user messages stay bubbles. `ChatProcessRows.kt` owns Think/Skill disclosure chrome. Spec: `docs/specs/chat-timeline-grammar.md`.
- `SessionWorkspaceOverview.kt` and `SessionProjectsViewport.kt` share a centered 960dp content cap for the selected server's recent work, view controls, and project queue. Preserve `SessionListViewModel` as the server-scoped state authority when changing the visual hierarchy.

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
- `app/src/main/kotlin/dev/wuxie233/codecarry/data/dsh/`: DSH RPC, mux/host
  downlinks, event reduce, chat fold, and remaining host unary surfaces.
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
