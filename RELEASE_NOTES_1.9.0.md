# CodeCarry 1.9.0

Replace Codex, Pi Stack, and Pi Roundtable with a native DSH control surface. OpenCode remains.

## Breaking changes

- Codex, Pi Stack, and Pi Roundtable backends are removed. They cannot be added again.
- Saved servers of those types are dropped the first time 1.9.0 reads persisted configuration. There is no migrate path and no leftover deprecated card.

## Changes

- Add `ServerType.DSH`. Connect with an HTTP(S) URL only. Reachability is DSH `Host` plus `trustedHosts`; CodeCarry does not invent a token DSH does not have.
- Native DSH client uses `POST /api/<method>` plus downlink-only WebSockets `/api/events.mux` and `/api/events.host`. Ready only after `host.describe` succeeds and both sockets are open.
- Reuse existing Sessions and Chat shells for DSH: session list, workspace grouping, archive/unarchive/rename/rehome/create, history plus live mux fold, prompt queue/steer, slash commands, cancel, attachments, models, file mentions via `host.listDirectory`, approvals and questions answered with the host `rpcId`.
- Remaining non-loopback Web unary surfaces (workspaces, skills, git, presets, goals, automation, settings, LLM catalog, subagents, system prompt, directory browse) live under Server Settings. Loopback-locked methods stay hidden on LAN.

## Verification

- Passed `:app:testDebugUnitTest` (572 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.9.0`
- `versionCode`: `105`
