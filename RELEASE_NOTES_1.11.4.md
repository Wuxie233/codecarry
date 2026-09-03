# CodeCarry 1.11.4

Fix DSH subagent Chat opening onto `session/agent-busy: subagent Sessions require their durable parent address`.

## Changes

- DSH `session/follow` and `session/page` now send the Host `SessionAddress`. Ordinary chats stay `{ kind: "session", sessionId }`. A child with origin `subagent` uses `{ kind: "subagent", parentSessionId, childSessionId, mode }`.
- Chat send/stop on that child use `subagents/prompt` and `subagents/interruptByParent` instead of `session/prompt` / `session/cancel`.
- If a subagent origin is known but the parent id is not yet in the list snapshot, Chat waits instead of following as an ordinary session.

## Verification

- Focused JVM tests: child follow/page use the subagent address; ordinary follow stays `kind: session`; missing parent does not emit `kind: session` for a subagent origin; child prompt includes `mode: continuable`.
- Passed `:app:testDebugUnitTest` and `:app:assembleDebug`.

## Metadata

- `versionName`: `1.11.4`
- `versionCode`: `118`
