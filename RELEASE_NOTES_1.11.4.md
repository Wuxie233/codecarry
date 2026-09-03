# CodeCarry 1.11.4

Fix DSH subagent Chat opening onto `session/agent-busy: subagent Sessions require their durable parent address`.

## Changes

- DSH `session/follow` and `session/page` now send the Host `SessionAddress`. Ordinary chats stay `{ kind: "session", sessionId }`. A child with origin `subagent` uses `{ kind: "subagent", parentSessionId, childSessionId, mode }`.
- Chat send/stop on that child use `subagents/prompt` and `subagents/interruptByParent` instead of `session/prompt` / `session/cancel`.
- If a subagent origin is known but the parent id is not yet in the list snapshot, Chat waits instead of following as an ordinary session.

## Verification

- Focused JVM tests: child follow/page use the subagent address; ordinary follow stays `kind: session`; missing parent does not emit `kind: session` for a subagent origin; child prompt includes `mode: continuable`.
- Passed `:app:testDebugUnitTest` and `:app:assembleDebug`.

## APK integrity

If the installer reports a parse error, the download is truncated — verify what landed on the device:

- Size: exactly `7,302,111` bytes (≈6.96 MB)
- SHA256: `bc0a978c2ba9319c914f8b833c9b47b78a29e55c6a38cfa688ee46a8a81df12f`

## Metadata

- `versionName`: `1.11.4`
- `versionCode`: `118`
