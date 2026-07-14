# OC Remote 1.7.45

Add native remote Codex app-server support with persistent background connections, live turns, and safe approval handling.

## Codex

- Add Codex servers alongside OpenCode and Pi Roundtable connections.
- Support native thread browsing, creation, rename, fork, compact, archive, restore, interrupt, and deletion flows.
- Stream reasoning, assistant text, command output, file changes, plans, and tool activity as each turn runs.
- Keep active Codex turns and server requests subscribed in the background and resume them after reconnecting.
- Add model selection, goals, per-thread memory mode, usage reporting, and stable user-message reconciliation.

## Approvals and Input

- Present command, file, network, and permission context before approval.
- Preserve the server's ordered approval choices, including cancel, and support Codex permission grants.
- Handle `requestUserInput` prompts and retain unresolved server requests across screen navigation.

## Connection and Security

- Support authenticated `ws://` loopback and encrypted `wss://` remote Codex endpoints.
- Require TLS for non-loopback Codex connections so bearer tokens and protocol traffic are not sent in plaintext.
- Preserve unknown experimental protocol fields for forward compatibility with newer Codex CLI versions.

## Verification

- Passed clean unit tests and debug/release APK builds.
- Passed live Codex CLI protocol checks for initialize, streaming, reconnect/resume, thread actions, stable message IDs, and permissions.
- Passed Android emulator connection, thread-list, and new-thread acceptance flows.
- Passed authenticated public `wss://` initialize and unauthorized-token rejection checks.
