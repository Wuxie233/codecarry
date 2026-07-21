# OC Remote 1.8.0

This release adds Pi Stack as a dedicated remote development backend.

## Pi Stack Projects

- Connect to a Pi Stack Control server with bearer authentication.
- Browse server-approved directories and register projects without sending an arbitrary client working directory.
- Group sessions by project, create a session, and reopen existing work from the Sessions screen.

## Pi Stack Chat

- Restore structured, paginated conversation history with text and tool activity.
- Stream message and tool updates through reconnect-safe Control events.
- Send prompts, stop active work, and reconcile live updates with restored history.
- Answer structured questions and receive background completion notifications.

## Reliability

- Reject stale Control generations and repair event gaps from a fresh snapshot.
- Preserve live state when history and event recovery overlap.
- Keep unsupported OpenCode-only actions hidden in Pi Stack sessions.

## Verification

- Passed the full debug unit test suite (639 tests).
- Built the debug, Android test, and release APKs successfully.
