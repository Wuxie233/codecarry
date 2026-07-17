# OC Remote Agent Notes

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

## Design Context

- Read `PRODUCT.md`, `DESIGN.md`, and `CONTEXT.md` before changing user-facing Compose UI. They define the product contract, visual tokens, and canonical conversation language.
- The product personality is warm, precise, and content-first. Assistant prose is an unbounded reading flow; only user prompts and independently stateful reasoning/tool/diff/approval/error work units earn containers.
- Home prioritizes honest work entry from available connection/activity state. Do not fabricate a cross-backend recent-conversation list; that requires a shared conversation registry and stable `(backend, serverId, conversationId)` identity.
- Preserve Light, Dark, AMOLED, and dynamic color behavior; static theme roles, 4/8dp spacing, 6/10/14dp shapes, zero letter spacing, 48dp targets, TalkBack state semantics, large text, and reduced motion are baseline requirements.

## Gotchas & Decisions

- Codex is a separate backend from OpenCode. Connect Android to `codex app-server --listen ws://...`, not the daemon's local Unix control socket, and keep its bidirectional request/approval lifecycle in `data/codex` rather than adapting it to OpenCode REST/SSE models.
- Codex app-server wire messages omit the `jsonrpc` field. Each connection must send `initialize`, wait for its response, send `initialized`, then `thread/resume` any opened thread before relying on streaming notifications. Preserve unknown fields/types and regenerate the experimental schema when upgrading compatibility.
- Keep Codex sockets in the shared `CodexConnectionManager`: reconnect persistent or leased connections with backoff, and track leased chat thread IDs so a new handshake resumes every still-open thread.
- Keep background Codex threads subscribed after their screen closes while a turn or server request is active. Reconnect must resume the union of screen leases and retained background threads; terminal completion, request resolution, deletion, cancellation, or explicit service disconnect releases retention.
- Treat the `turn/start` response as acceptance only: its turn ID may be provisional, and some server runs omit `turn/started`. Derive the authoritative turn ID and background retention from reducer state after any notification carrying `threadId + turnId`, including item and delta events; a terminal turn must prevent late item events from restoring retention.
- Codex approvals and `requestUserInput` are server-initiated requests whose original IDs must be answered on the same persistent socket. Never auto-approve unknown requests; retain pending requests independently of screen collector timing and clear them on disconnect or `serverRequest/resolved`.
- Codex approval UI must honor the server's ordered primitive `availableDecisions`, including `cancel`, and show the affected command directory, network/permission scope, or file paths before enabling approval. Send each user input with a stable `clientUserMessageId` and reconcile uncertain responses by `userMessage.clientId` before allowing a retry.
- Chat markdown has two render paths: normal markdown uses Compose in `ChatMarkdownRenderer.kt`; KaTeX/math markdown uses the WebView renderer in `MarkdownMessageRenderer.kt`. Keep code blocks, tables, display math, and reasoning/plain text with long unbreakable ASCII tokens independently horizontally scrollable; normal prose should still wrap to the bubble width.
- Connection setup is multi-stage. `OpenCodeConnectionService.connectionPhases` must reflect the real health check, workspace/session sync, activity restore, live-event setup, and retry wait; Home should render those phases in the existing server card instead of reducing them to a generic spinner or fake percentage.
- Response-ready notifications use `SessionNotificationIdentity`. When `ChatViewModel` marks an OpenCode session read, it must cancel that session's response notification and remove the server group summary only when no sibling event notifications remain; permission, question, and error notifications stay independent.

## Module Map

- `ui/theme/`: semantic color, typography, shape, spacing, touch-target, and content-width tokens.
- `ui/components/`: shared state and status presentation; dynamic state combines visible text with TalkBack semantics.
- `ui/screens/home/`: connection-aware work entry and server/local-runtime management. Always render real `ConnectionPhase` state.
- `ui/screens/sessions/`: project/session discovery, active-conversation priority, archive/restore, MCP, and filters.
- `ui/screens/chat/` and `ui/screens/codex/`: backend-specific conversation rendering and composer behavior. Preserve reducer/planner keys, streaming placeholders, drafts, scroll rules, pending-request lifecycle, and protocol semantics.
