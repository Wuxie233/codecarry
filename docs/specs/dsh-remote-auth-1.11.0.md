# CodeCarry 1.11.0: DSH Connection auth + Remote transport

## Goal

Connect CodeCarry to current DSH Web (token cookie + Typert Remote) and ship
`1.11.0`. Operators can add a DSH server, reach Ready, list sessions, chat,
answer approvals/questions, and use remaining host surfaces.

## Scenario

Latest DSH no longer admits unauthenticated `/api`. A process launch token on
`GET /?token=` mints an authority-bound `dsh-auth-*` cookie. Unary calls are
`POST /api/<namespace>/<method>` with
`{ type, rpcId, method, payload: { args } }`. Live state rides one
`/api/remote.mux` WebSocket with logical streams. `host.describe`,
`/api/events.mux`, `/api/events.host`, and `/api/respond` are gone.

## In scope

1. Authenticate every DSH unary and WebSocket request with the Connection
   cookie. Optional HTTP Basic remains only for a fronting proxy such as
   dsh-auth.
2. Exchange the process launch token on `GET /` (follow 303, capture
   `Set-Cookie`). Persist the cookie in the in-memory generation, not as a
   second durable secret.
3. Accept a DSH launch token from `ServerConfig.token` or from `?token=` on
   the saved URL (strip the query after save). Keep the optional DSH password
   for dsh-auth. A passworded public host still GETs `/` so dsh-auth can attach
   the current process token and mint the DSH cookie.
4. Replace dotted unary paths with slash Remote endpoints and wrap business
   fields in `payload.args` using Host descriptor wire names.
5. Replace mux/host sockets with one `/api/remote.mux` connection. Ready only
   after cookie exchange, mux open, `$events` ready (`host.home`),
   `session/control` opening baseline, and `workspace/follow` opening baseline.
   Per-chat history uses `session/follow` plus `session/page`.
6. Answer `approval/request` and `user-questions/request` waterfalls on
   `POST /api/$events/result` with `{ args: { clientId, eventId, outcome } }`.
   No `/api/respond`.
7. Update catalog, strings, README, AGENTS.md. Bump to `versionName` `1.11.0`,
   `versionCode` `114`, `RELEASE_NOTES_1.11.0.md`. Land master, tag `v1.11.0`,
   trigger `.github/workflows/release.yml`.

## Non-goals

- Do not change OpenCode transport.
- Do not add logout, TLS-proxy policy, or `--host 0.0.0.0` support.
- Do not persist the process launch token beyond the saved server row.
- Do not keep a compatibility mode for pre-token DSH.
- Do not restart dsh-web, dsh-auth, OpenCode, or nginx.

## Constraints and decisions

- Probe against live `http://127.0.0.1:18790` with a real cookie exchange is
  the source of truth. Wire names come from
  `/root/CODE/deepseek-harness` Remote owners.
- Cookie is host-only and authority-bound. Android must send the cookie DSH
  set; it must not rewrite `Host`. dsh-auth already rewrites Host to
  `127.0.0.1:18790` for public traffic.
- Shared Ktor client stays cookie-free. DSH sends `Cookie` per request from
  generation state so OpenCode is unaffected.
- Loopback-only methods stay hidden on passwordless non-loopback URLs. A
  passworded public host remains treated as loopback because dsh-auth rewrites
  Host.
- `session/prompt` requires a client-minted `requestId`.
- `session/list` args are `{ _request: { cursor? } }`. Empty-arg methods send
  `{ args: {} }`.
- `workspace.list` is gone; the workspace catalog is the `workspace/follow`
  baseline.
- `host.describe` is gone; generation ready carries `{ home }` from `$events`.
  Capability queries (`session/canOpenWorkspacePath`,
  `settings/canOpenAgentPresetDirectory`) run when those surfaces open.
- History records are `{ type: "event", event }` or `{ type: "chunks", event }`.
  Folders consume the inner event the same way as today.
- Approval outcome remains `allowed-once` | `rejected` (map Always to
  allowed-once). Question answers stay `{ answers: [{ id, selected, custom? }] }`
  inside the waterfall result value.

## Endpoint map

| Old | New | Args |
|---|---|---|
| `session.list` | `session/list` | `_request` |
| `session.search` | `session/search` | `request` |
| `session.create` | `session/create` | `request` |
| `session.history` | `session/page` | `request` (`address`, `throughSeq`, `beforeSeq?`, `maxMessages?`) |
| `session.models` | `session/modelCatalog` | `{}` |
| `session.selectModel` | `session/selectModel` | `request` |
| `session.rename` | `session/rename` | `request` |
| `session.rehome` | `session/rehome` | `request` |
| `session.fork` | `session/fork` | `request` |
| `session.rewrite` | `session/rewrite` | `request` |
| `session.prompt` | `session/prompt` | `request` (+ `requestId`) |
| `session.attachment` | `session/attachment` | `request` |
| `session.updateQueue` | `session/updateQueue` | `request` |
| `session.cancel` | `session/cancel` | `request` |
| `host.listDirectory` | `directoryPicker/list` | `path` |
| `host.createDirectory` | `directoryPicker/createDirectory` | `path`, `name` |
| `host.pickDirectory` | `directoryPicker/pick` | `{}` |
| `host.openPath` | `session/openWorkspacePath` | `request.path` |
| `workspace.*` mutations | `workspace/<method>` | `request` |
| `workspace.list` | `workspace/follow` baseline | stream |
| `skill.list` | `skills/list` | `request.sessionId` |
| `skill.catalog` | drop; use `skills/list` | |
| `git.*` | `git/<method>` | `request` |
| `agentPreset.list` | `agentPresets/list` | `{}` |
| `agentPreset.select` | `agentPresets/select` | `agentId`, `agentPreset` |
| `agentPreset.read/copy/remove` | `agentPresets/<method>` | |
| `agentPreset.openDocument` | `settings/openAgentPresetDirectory` | `agentPreset` |
| `goal.*` | `goals/<method>` | `agentId` + `ref`/`request` |
| `automation.*` | `automation/<method>` | `request` or `{}` for list |
| `settings.describe/update/replace/mutate` | `settings/<method>` | named params |
| `settings.openDocument` | `settings/openSettingsDocument` | `{}` |
| `credentials.*` | `credentials/<method>` | `refs` / `ref`,`value` |
| `llm.providers` | `llm/listProviders` | `{}` |
| `llm.models` | `session/modelCatalog` | `{}` |
| `llm.discoverModels` | `llm/discoverModels` | |
| `subagent.list` | `subagents/list` | `parentSessionId` |
| `subagent.prompt` | `subagents/prompt` | `request` |
| `subagent.interrupt` | `subagents/interruptByParent` | `childSessionId`, `parentSessionId`, `mode=continuable` |
| `systemPrompt.list` | `systemPrompt/list` | `{}` |
| `/api/respond` | `$events/result` | `clientId`, `eventId`, `outcome` |
| `/api/events.mux` + `/api/events.host` | `/api/remote.mux` | logical `open`/`item`/`end` |

Logical streams on mux: `$events` (`args: {}`), `session/control`,
`workspace/follow`, and per-session `session/follow`
(`args.request.address`).

`$events` frames: `ready` `{ clientId, host: { home } }`, `emit`, `waterfall`,
`cancel`. Map emit names:

- `api-session/added|removed|status|error|activity` → existing host session frames
- `approval/request` waterfall → pending approval (`agentId` is session id)
- `user-questions/request` waterfall → pending question

`session/control` frames: `baseline`, `queue`, `jobs`, `projection`.
`workspace/follow` frames: `baseline`, `upsert`, `remove`, `order`, `archived`,
`hidden`.

## Acceptance

- Direct loopback with a launch token reaches Ready and `session/list` succeeds.
- Passworded `https://dsh.wuxie233.com` still connects: Basic to dsh-auth plus
  DSH cookie after GET `/`.
- Passwordless non-loopback without a token fails closed as auth-required, not
  as a generic transport error.
- Missing/expired cookie is 401 → `DshAuthRequiredException`, no reconnect loop.
- Chat loads history from follow snapshot/page, streams live events, prompts
  with `requestId`, answers approval/question via `$events/result`.
- Workspace catalog comes from `workspace/follow` baseline; Sessions + directory
  browse uses `directoryPicker/list`.
- Focused JVM tests cover cookie exchange, slash+args envelope, 401, ready
  generation, follow/control/workspace frames, and waterfall replies.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes.
- `origin/master` has `1.11.0` / `114`, tag `v1.11.0`, release workflow triggered.

## Assumptions

- Live DSH on this host is the compatibility target.
- dsh-auth continues to attach the launch token on authenticated GET `/` and
  forward `Cookie` + rewritten Host; CodeCarry does not change dsh-auth.
- `ServerConfig.token` is free to reuse as the DSH launch token.
