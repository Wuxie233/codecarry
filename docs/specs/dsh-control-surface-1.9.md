# CodeCarry 1.9: Archive Codex/Pi, Add DSH

## Goal

Ship CodeCarry 1.9.0 with OpenCode kept, Codex / Pi Stack / Pi Roundtable deleted, and a native DSH control surface that covers the Web GUI's `/api` RPC plus the two downlink WebSockets.

## Scenario

An operator adds a DSH server by HTTP(S) URL, opens its workspace/session library, chats with streaming turns, answers approvals and questions, steers or queues prompts, and uses the same host-facing surfaces the Web GUI uses (models, workspaces, goals, automation, subagents, settings, skills, git, presets) except loopback-locked methods.

## In Scope

1. Delete Codex, Pi Stack, and Pi Roundtable product surfaces: `ServerType` values, picker, Home cards, routes, transports, screens, strings, and tests.
2. On first 1.9 read of persisted servers, drop every saved `CODEX` / `PI_ROUNDTABLE` / `PI_STACK` config. No migrate, no leftover "deprecated" card.
3. Add `ServerType.DSH`. Connect with HTTP(S) URL only. No token field. Reachability is DSH `Host` + `trustedHosts`; CodeCarry does not invent an auth layer DSH does not have.
4. Native DSH client for the Web unary map and both downlinks:
   - Unary: `POST /api/<method>` with `{ rpcId, payload }` (see `packages/host/apiproxy/src/api/rpc-map.ts` in `/root/CODE/deepseek-harness`).
   - Downlinks: one WebSocket `/api/events.mux` and one `/api/events.host`. Client sends no application data on those sockets. Both must be open and `host.describe` must succeed before ready. Reconnect = reopen both + refetch history. No SSE fallback.
5. Session programming path: list/search/create/history/prompt/cancel/updateQueue/fork/rewrite/rename/rehome/models/selectModel/attachment.
6. Live mux/host frames: session events, queue, jobs, projections, approvals, questions, session added/removed/status, workspace and archive set changes.
7. Remaining Web unary surfaces that are not loopback-locked: workspace.*, skill.*, git.*, agentPreset.list/select, goal.*, automation.*, settings.describe/update/replace/mutate, llm.providers/models, subagent.*, systemPrompt.list, host.listDirectory/createDirectory.
8. Chat uses existing native Chat shell. DSH events fold into that shell; do not WebView the DSH GUI.
9. Release metadata: `versionName` `1.9.0`, `versionCode` `105`, `RELEASE_NOTES_1.9.0.md`. Notes state the three backends are gone and existing saved configs of those types are dropped.

## Non-Goals

- Do not keep Codex/Pi code behind a flag.
- Do not wrap DSH Web in WebView.
- Do not add DSH authentication, tokens, or `--host 0.0.0.0` support.
- Do not pretend loopback-locked methods work over LAN. Hide them when the server Host is not loopback: `host.pickDirectory`, `host.openPath`, `credentials.*`, `settings.openDocument`, `llm.discoverModels`, `agentPreset.read/copy/openDocument/remove`.
- Do not change OpenCode transport, reducer, or Chat markdown pipeline.
- Do not restart OpenCode or DSH host processes from this repo.
- Do not tag/publish the GitHub Release in this delivery; land master-ready 1.9.0 metadata. Manual tag + workflow stay operator-owned.

## Constraints and Decisions

- `ServerType` after this delivery is `OPENCODE` and `DSH` only. Unknown persisted type values are dropped with the archived backends.
- DSH is its own transport. Do not reuse OpenCode REST/SSE, Codex WS JSON-RPC, or Pi Control v1 models.
- Wire source of truth is `/root/CODE/deepseek-harness/packages/host/apiproxy/src/api/` (`rpc-map.ts`, `sessions.ts`, `events.ts`, and sibling domain files). Match method names, payload fields, and error codes; do not invent a second protocol.
- Unary envelope follows the existing API Proxy: request `{ rpcId, payload }`, response echoes `rpcId`. Mux/host frames are server-originated `RpcRequest` text messages.
- Answerable mux frames (`approval/requested`, `question/requested`) must be answered on the unary connection with the host's `rpcId`. Pending requests survive screen collector timing and clear on disconnect or resolved frames.
- `session.prompt` `mode` is `queue` or `steer`. A sole text block starting with `/` is a host slash command, not a model prompt.
- Workspace/session identity is DSH `sessionId` / `workspaceId`. OpenCode session IDs are not join keys.
- File mentions for DSH use `host.listDirectory` (and composed file-reference remotes if mounted); they are not OpenCode `@file` search.
- Compose screens own presentation. Keep DSH RPC, mux reduce, and connection continuity out of reusable chat rows.
- User-facing copy stays in Android resources. 15 locales: English source plus existing lokit workflow.
- Java 21 Gradle. Focused JVM tests per slice; repository `:app:testDebugUnitTest` and `:app:assembleDebug` once at captain integration.

## Acceptance Evidence

- Server picker offers OpenCode and DSH only.
- Decoding a persisted server list that contains Codex/Pi/Roundtable rows yields only OpenCode/DSH rows and writing it back does not restore the dropped rows.
- A DSH server becomes ready only after `host.describe` plus both WebSockets; losing either socket leaves the generation and reconnects.
- Prompt, history fold, live mux events, cancel, queue edit/remove/steer, approval, and question each have focused JVM tests against recorded or fake frames.
- Workspace list/create, session archive/unarchive/rehome/rename, model catalog/select, goals, automation, subagent list/prompt/interrupt, settings mutate, skills, and git.describe have focused tests or are proven through the same fake RPC layer.
- Loopback-locked methods are absent from the DSH UI for a non-loopback URL.
- No remaining production references to `ServerType.CODEX`, `PI_ROUNDTABLE`, `PI_STACK`, `data/codex`, Pi Stack Control, or roundtable screens.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes.
- `versionName` `1.9.0`, `versionCode` `105`, and `RELEASE_NOTES_1.9.0.md` agree.

## Repository Facts

- App module: `app/`. Identity `dev.wuxie233.codecarry`. Current `1.8.9` / `104`.
- Backend switch today: `domain/model/ServerConfig.kt`, `ui/screens/home/ServerDialog.kt`, `HomeScreen.kt`, `NavGraph.kt`, `ChatBackendCapabilities.kt`, `data/repository/ServerRepository.kt`.
- OpenCode keepers: `data/api/OpenCodeApi.kt`, `data/transport/OpenCodeTransport.kt`, `data/repository/EventReducer.kt`, `service/OpenCodeConnectionService.kt`, existing Chat/Sessions screens.
- Delete set includes `data/codex/**`, `data/api/PiApi.kt`, `data/api/PiStackApi.kt`, `data/transport/PiRoundtableTransport.kt`, `data/transport/PiStackTransport.kt`, `data/repository/PiStackEventReducer.kt`, `ui/screens/codex/**`, `ui/screens/roundtable/**`, and their tests.
- DSH new home: `data/dsh/` for RPC, sockets, reduce; `ui/screens/dsh/` only if a DSH-only surface cannot live in existing Sessions/Chat/Home. Prefer extending existing screens via `ServerType` + capability flags.

## Assumptions

- Operators who need LAN DSH already add the serving authority to DSH `trustedHosts`; CodeCarry only documents that requirement in the DSH URL hint.
- Directory browse uses `host.listDirectory` / `host.createDirectory` (the remote-capable picker), not the native OS chooser.
- AgentTeams / Host Automation on DSH are the existing unary APIs (`goal.*`, `automation.*`, `subagent.*`), not a second Android-side team runtime.
- Manual GitHub Release remains after this land, per `README.md`.
