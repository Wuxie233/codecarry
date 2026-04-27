---
date: 2026-04-28
topic: "Retry Interrupt Control"
status: validated
---

## Problem Statement

The APK can show that a conversation is retrying, but the user cannot interrupt it from the app. When a retry loop requires human intervention, the user has to leave the APK and use the web UI to halt the session.

This is a design gap in the Android client. The backend abort capability already exists; the APK simply does not expose it for retry state.

## Constraints

- Reuse the existing OpenCode abort endpoint.
- Do not introduce a new backend API.
- Do not merge abort with revert semantics; abort only stops the current running/retrying session.
- Keep retry recovery possible by default; do not auto-cancel retries.
- Keep the UI change small and consistent with the existing chat screen.
- Preserve SSE as the final source of truth for session status.

## Approach

The chosen approach is to treat `Busy` and `Retry` as user-interruptible running states in the chat UI.

The top-level Stop action should appear when the current session is either busy or retrying. The retry banner should also expose an explicit stop action so the user can interrupt directly from the warning surface that explains the retry loop.

Abort should reuse the existing view model and API path. The view model should only perform a local optimistic idle transition when the abort request succeeds. On failure, the UI should retain the current running/retry state and surface the existing error feedback.

Alternatives considered:

- Adding a new cancel-retry API was rejected because the server already exposes abort and the web UI uses the same halt/abort concept.
- Only adding a banner action was rejected because the top Stop affordance should represent all non-idle interruptible states.
- Automatically stopping retry loops was rejected because retry can be useful for transient provider or network failures.

## Architecture

The fix stays within the existing status-driven chat architecture:

- SSE publishes `session.status` updates.
- The reducer stores the current status for each session.
- The chat view model exposes the current session status to the UI.
- The chat screen decides whether an interrupt action is visible.
- The existing abort API stops the active runner on the OpenCode server.
- SSE idle updates eventually confirm the final state.

No new service, repository, or persistence layer is required.

## Components

### Chat Screen

The chat screen owns visible actions for the active conversation.

Responsibilities:

- Treat `Busy` and `Retry` as interruptible states.
- Show the existing top Stop action for both states.
- Pass the abort callback into retry-specific UI.
- Avoid showing Stop for `Idle`.

### Retry Status Banner

The retry banner explains the retry state and should provide a contextual escape hatch.

Responsibilities:

- Continue showing retry attempt, message, and next retry information.
- Add a clear stop/abort action.
- Delegate the actual abort behavior to the chat screen/view model.
- Avoid implying that abort deletes messages or reverts history.

### Chat View Model

The view model already owns abort behavior.

Responsibilities:

- Call the existing abort API for the active session.
- Preserve directory context when aborting.
- Only switch local status to idle when the abort request succeeds.
- Keep current status intact and surface an error when abort fails.

### API Client

The API client already wraps `POST /session/{sessionId}/abort`.

Responsibilities:

- Keep using the existing abort endpoint.
- Preserve current authentication and directory header behavior.
- Return a success/failure signal that the view model can trust.

## Data Flow

Retry interrupt flow:

- Server enters retry state.
- SSE sends retry status.
- Reducer updates session status.
- Chat UI renders retry banner and Stop action.
- User taps Stop.
- View model calls the abort API.
- On success, local status can move to idle immediately.
- SSE idle update later confirms the final state.

Abort failure flow:

- User taps Stop during retry.
- Abort API fails or returns an unsuccessful result.
- View model does not clear the retry state.
- UI keeps the retry/Stop affordance visible.
- Existing error feedback tells the user the stop attempt failed.

## Error Handling

Abort should be user-safe and reversible in the UI.

If abort succeeds, the UI can leave the active retry presentation while waiting for SSE confirmation. If abort fails, the app should not pretend the session is idle; retry state should remain visible so the user can try again or switch to the web UI.

The view model should avoid swallowing unsuccessful abort responses. A failed abort is materially different from a successful stop because the retry loop may still be running.

## Testing Strategy

Focused regression tests should cover the missing behavior directly:

- Retry status is classified as interruptible.
- Busy status remains interruptible.
- Idle status is not interruptible.
- Retry banner exposes a stop action when retrying.
- Stop during retry calls the existing abort path.
- Abort success can move local state to idle.
- Abort failure does not clear retry/running state.
- Existing revert behavior that aborts before revert remains intact.

The test scope should stay at UI state, view model behavior, and API-result handling. No server changes are required.

## Open Questions

- A future enhancement could refresh session status on chat entry to compensate for missed SSE events, but that is not required for the core retry interrupt defect.
- Session list and active conversation banners may eventually expose stop actions too, but the first fix should focus on the active chat where the user is already diagnosing the retry loop.
