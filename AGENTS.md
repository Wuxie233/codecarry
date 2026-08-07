# OC Remote Agent Notes

The current product identity is CodeCarry. It is an independently maintained
fork based on OC Remote; the Android namespace/applicationId is
`dev.wuxie233.codecarry`, and the canonical repository is

## Communication

- Prefer concise Chinese for user-facing updates and final summaries.
- Keep code symbols, commands, file paths, version tags, and API names in their original spelling.
- Do not ask code-level questions. Make technical decisions from the codebase; only ask when product behavior or user intent is genuinely unclear.
- The user prefers `vibe talking`: natural chat-first UX, minimal visible control panels, and no unnecessary explanation.

## Pi Roundtable Product Preferences

- Treat the chat composer as the primary Pi Roundtable interaction surface.
- Avoid reintroducing a separate “roundtable steering/scheduler” main UI unless explicitly requested.
- `@` suggestions in Pi Roundtable are input helpers only. They should insert role mentions or natural-language requests; they must not directly send control commands.
- Streaming is a product requirement: thinking states and each agent’s live turn should appear as soon as possible, including placeholders before the first text delta.
- Preserve current service semantics: registry `paused` means internal `awaiting_command`; registry `awaiting` means internal `awaiting_skip`.
- Send `可` / continue only when the domain status is `Roundtable.Status.AwaitingCommand`; never send continue for `AwaitingSkip`.

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

- Pi Stack is a separate `PI_STACK` backend, not an OpenCode-compatible API.
  Android connects to the native Bearer-authenticated Control v1 endpoint and
  keeps Pi Stack transport models under `data/api/PiStackApi.kt`. Origin-only
  URLs resolve to `/control`; explicit custom Control paths remain unchanged.
- The Pi Stack control server enforces a bearer secret of at least 32
  characters (`PI_CONTROL_BEARER_SECRET`); the app token must match it exactly.
  Health-check failures must keep their `PiStackApiErrorKind` classification:
  `healthCheckErrorMessage` in `HomeViewModel.kt` maps Auth/Protocol failures
  to actionable messages instead of a generic "Server is not responding".
- Pi Stack project selection is server-authoritative. The client browses only
  server-allowed absolute directories, registers a project, and then creates
  or resumes sessions within that project. Do not relabel an allowlist root as
  `~` or let the client supply an unchecked session working directory.
- Pi Stack history is the recovery authority and SSE supplies live deltas. When
  live and restored messages lack a shared stable ID, merge the nearest
  unmatched message with the same role and complete parts content one-to-one;
  preserve legitimate repeated turns.
- Codex is a separate backend from OpenCode. Connect Android to `codex app-server --listen ws://...`, not the daemon's local Unix control socket, and keep its bidirectional request/approval lifecycle in `data/codex` rather than adapting it to OpenCode REST/SSE models.
- Codex app-server wire messages omit the `jsonrpc` field. Each connection must send `initialize`, wait for its response, send `initialized`, then `thread/resume` any opened thread before relying on streaming notifications. Preserve unknown fields/types and regenerate the experimental schema when upgrading compatibility.
- Keep Codex sockets in the shared `CodexConnectionManager`: reconnect persistent or leased connections with backoff, and track leased chat thread IDs so a new handshake resumes every still-open thread.
- Keep background Codex threads subscribed after their screen closes while a turn or server request is active. Reconnect must resume the union of screen leases and retained background threads; terminal completion, request resolution, deletion, cancellation, or explicit service disconnect releases retention.
- Treat the `turn/start` response as acceptance only: its turn ID may be provisional, and some server runs omit `turn/started`. Derive the authoritative turn ID and background retention from reducer state after any notification carrying `threadId + turnId`, including item and delta events; a terminal turn must prevent late item events from restoring retention.
- Codex approvals and `requestUserInput` are server-initiated requests whose original IDs must be answered on the same persistent socket. Never auto-approve unknown requests; retain pending requests independently of screen collector timing and clear them on disconnect or `serverRequest/resolved`.
- Codex approval UI must honor the server's ordered primitive `availableDecisions`, including `cancel`, and show the affected command directory, network/permission scope, or file paths before enabling approval. Send each user input with a stable `clientUserMessageId` and reconcile uncertain responses by `userMessage.clientId` before allowing a retry.
- Chat markdown has two render paths: normal markdown uses Compose in `ChatMarkdownRenderer.kt`; KaTeX/math markdown uses the WebView renderer in `MarkdownMessageRenderer.kt`. Keep code blocks, tables, display math, and reasoning/plain text with long unbreakable ASCII tokens independently horizontally scrollable; normal prose should still wrap to the bubble width.
- Chat Markdown has one structural pipeline: `MarkdownDocumentParser.kt` builds the GFM AST-backed document, `MarkdownRenderPlan.kt` assigns block routes and structured table data, and `MarkdownStreamingPlan.kt` reconciles completed-prefix identity while reparsing the open suffix. Do not add a second line scanner or whole-message/max-chunks fallback.
- Long assistant Markdown is split into typed top-level rows. Tables and fenced code own their rows; root lists, blockquotes, raw HTML, and indented code remain atomic; prose may coalesce within the size budget.
- Compose rendering dispatches planned blocks independently. Tables always consume structured cells through `MarkdownTableLayout.kt` with 80..280dp content-adaptive columns; only a non-table block containing math routes through the KaTeX/WebView adapter. Keep prose selection intact: JVM gesture diagnosis proved `SelectionContainer + DisableSelection` does not block table dragging.
- Compose ordered lists use a local renderer because mikepenz 0.28.0 resets every independent list to `1.`. Preserve each `ORDERED_LIST` AST start number and recompute nested list starts independently.
- Compose blockquotes use a local renderer because mikepenz 0.28.0 renders only the first paragraph child. Preserve every quote child in source order, including later paragraphs, nested quotes, lists, code, and tables.
- Kimi Code Web layout research is pinned to the last source snapshot `e7d5a0a` in `docs/research/kimi-code-web-layout.md`; current `kimi-code` main keeps only `apps/kimi-code/dist-web`. Reuse its information architecture and interaction contracts, not Vue code, CSS, branding, fonts, or generated bundles.
- First-table drag coverage now spans Compose tables, long planned Compose rows, KaTeX/WebView tables, and a user-style `SwipeToDismissBox` parent in `MessageMarkdownHorizontalDragTest.kt`; all 16 physical-drag tests pass on the task AVD. A field failure that remains after these paths pass needs the exact message payload and input context before changing production gesture arbitration.
- Connection setup is multi-stage. `OpenCodeConnectionService.connectionPhases` must reflect the real health check, workspace/session sync, activity restore, live-event setup, and retry wait; Home should render those phases in the existing server card instead of reducing them to a generic spinner or fake percentage.
- Response-ready notifications use `SessionNotificationIdentity`. When `ChatViewModel` marks an OpenCode session read, it must cancel that session's response notification and remove the server group summary only when no sibling event notifications remain; permission, question, and error notifications stay independent.
- `EventReducer.sessions` is a cross-server aggregate. Any Home or chat derivation that follows parent/child session relationships must first restrict IDs through `EventReducer.serverSessions[serverId]`; OpenCode session IDs and parent IDs are not safe cross-server join keys by themselves.
- Parent/child topology must come from `EventReducer.serverSessionDetails[serverId]`, not from the global `sessions` list filtered by IDs; another server may reuse the same session ID with different metadata.
- Pending OpenCode permissions and questions retain ownership in `EventReducer.permissionsByServer` and `questionsByServer`. Consumers and optimistic removals must select the active `serverId`; duplicate session or request IDs across servers must never share or clear pending state.
- OpenCode recent work belongs inside the selected server's Sessions control surface and must be derived from that server's `serverSessions[serverId]`; do not place a cross-server recent-work feed on global Home.
- Background OpenCode status continuity has two safeguards: the SSE read must use a finite timeout so silent half-open sockets enter the service reconnect loop, and `ProcessLifecycleOwner.ON_START` reconciles every connected OpenCode server from REST snapshots. Snapshot application must remain revision-safe, preserve newer live SSE events, and fail closed when a complete project-scope list cannot be discovered or reused.
- Native Chat layout now has focused boundaries: `ChatHeader.kt` owns compact/expanded context actions, `ChatResponseDock.kt` owns retry/Roundtable/permission/question placement above the primary composer, `ChatAdaptiveShell.kt` owns `WindowSizeClass` and safe-drawing layout, and `ChatFollowTailPolicy.kt` owns long-session follow-tail state. Keep backend callbacks and the single `LazyListState` scroll owner in `ChatScreen.kt`; do not reintroduce pending request cards in the timeline or hard-coded width breakpoints.
- `SessionWorkspaceOverview.kt` and `SessionProjectsViewport.kt` share a centered 960dp content cap for the selected server's recent work, view controls, and project queue. Preserve `SessionListViewModel` as the server-scoped state authority when changing the visual hierarchy.
