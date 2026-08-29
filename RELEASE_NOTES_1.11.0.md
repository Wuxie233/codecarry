# CodeCarry 1.11.0

Connect to current DSH Web again: launch-token cookie authentication and the new Remote transport.

## Changes

- Authenticate every DSH request with the Connection cookie minted from `GET /?token=`. The launch token comes from the new **Launch token** field, or paste the whole `dsh web:` URL and CodeCarry splits the token out of it.
- The optional DSH password stays for fronting proxies such as dsh-auth; a passworded host still performs the cookie exchange through the proxy.
- Unary calls moved to slash Remote endpoints (`session/list`, `workspace/create`, `directoryPicker/list`, …) with the `{ args }` envelope. `host.describe`, `workspace.list`, `skill.catalog`, and dotted methods are gone on current DSH and are gone here too.
- Live state rides one `/api/remote.mux` WebSocket. A server is Ready only after `$events` (host home), `session/control`, and `workspace/follow` baselines arrive; per-chat history streams over `session/follow` with `session/page` for older pages.
- Approvals and questions now answer through `$events/result` on the mux generation. Always maps to `allowed-once`; question answers keep `custom` text out of `selected`.
- `session/prompt` and subagent prompts mint the `requestId` current DSH requires; models come from `session/modelCatalog`; skills are session-scoped via `skills/list`.

## Verification

- Live probed against `dsh web` on this host: cookie exchange, `session/list` (392 sessions), `remote.mux` handshake, `$events` ready, control baseline, and workspace baseline all pass.
- Focused JVM suites rewritten for the new transport: cookie/redirect handling, slash+args envelopes, 401 → auth-required without reconnect loops, generation readiness gating, follow/control/workspace reduce, waterfall replies (62 `data.dsh` tests).
- Passed `:app:testDebugUnitTest` and `:app:assembleDebug`.

## Metadata

- `versionName`: `1.11.0`
- `versionCode`: `114`
