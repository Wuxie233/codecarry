---
date: 2026-05-12
topic: "Actionable Permission Wait Handling"
status: validated
---

# Actionable Permission Wait Handling

## Problem Statement

Users can interpret a stalled task as "the assistant is not replying" when the real state is "OpenCode is waiting for a permission decision". Today the native app already receives permission requests and exposes a chat-level PermissionCard, but the user may only discover the blocker after opening the web UI or the exact native chat screen.

We need permission waiting to become visible and actionable from the surfaces users already monitor: Android notifications and session-level status. The user must still make an explicit permission decision; the app must not auto-approve anything.

## Constraints

- Reuse the existing `permission.asked` / `permission.replied` SSE flow and `POST /permission/{requestId}/reply` API.
- Keep the existing chat PermissionCard as the canonical in-app fallback.
- Preserve existing notification settings, server grouping, child-session filtering, and native chat deep-link behavior.
- Do not introduce a new server-side permission contract, QQ remote ask approval path, or auto-approval behavior.
- Make "Always allow" distinct enough from "Allow once" to reduce accidental long-term authorization.
- Handle Android notification permission/channel suppression by keeping session-level awaiting-permission status visible in-app.

## Approach

Use the existing permission state pipeline as the source of truth and add a faster action surface at the notification layer.

The chosen design is **notification quick actions plus lightweight in-app fallback**:

- Permission notifications include explicit actions for allow once, always allow, and reject.
- Tapping the notification body still opens the relevant native chat session.
- Session list / active conversation surfaces continue to expose awaiting-permission state so missed or suppressed notifications do not hide the blocker.
- Successful notification replies update local pending-permission state promptly while still accepting the later `permission.replied` SSE event as final consistency.

This solves the actual failure mode: the user does not know the assistant is waiting for permission. A pure chat-card improvement would not be enough because it still depends on the user already being in the right screen.

## Architecture

The architecture stays close to the existing Android client flow:

- `SseClient` parses OpenCode permission events into domain events.
- `EventReducer` stores pending permissions by session.
- `OpenCodeConnectionService` observes permission events, posts grouped high-priority notifications, and now handles notification action intents.
- `OpenCodeApi` remains the only network writer for permission replies.
- Chat and session screens consume reducer state to show pending permissions and awaiting-permission status.

No new server component is required. The notification action handler is an app-side shortcut to the same reply path already used by the chat PermissionCard.

## Components

### OpenCodeConnectionService

Responsible for turning a permission request into an actionable Android notification.

- Accept the full permission event, or at least the fields required to reply: server id, session id, request id, permission text, and optional session directory context.
- Build stable `PendingIntent.getService()` actions for allow once, always allow, and reject.
- Dispatch new service actions from `onStartCommand()` before normal connection startup work.
- Resolve the target server connection and call the existing permission reply API.
- Cancel or update the permission notification after a successful reply.
- Preserve current child-session filtering, grouping, and deep-link behavior.

### EventReducer

Responsible for pending-permission state.

- Continue adding permissions on `permission.asked`.
- Continue removing permissions on `permission.replied`.
- Add a local removal path for successful notification replies, matching the existing optimistic question-reply pattern.
- Keep removal idempotent so duplicate replies or late SSE events do not break state.

### OpenCodeApi

Responsible for sending permission replies.

- Reuse `replyToPermission` with reply values `once`, `always`, and `reject`.
- Preserve existing authorization and directory header behavior.
- Do not add a new endpoint.

### Chat UI

Responsible for the full in-app fallback.

- Keep the existing PermissionCard and its three decisions.
- Benefit from reducer state updates when a notification action has already handled the request.
- Continue showing permission details and patterns for users who prefer reviewing inside the app.

### Session Surfaces

Responsible for discoverability when notifications are missed.

- Keep or strengthen awaiting-permission status in active conversations and session lists.
- Show enough count/status information for users to identify which task is blocked.
- Do not replace chat-level permission details with a separate complex approval center in this iteration.

## Data Flow

1. OpenCode emits `permission.asked` for a session.
2. `SseClient` parses the event.
3. `EventReducer` records it in pending permissions for that session.
4. Chat observes reducer state and renders the PermissionCard.
5. `OpenCodeConnectionService` posts a high-priority grouped permission notification.
6. The user either opens the session or taps a notification action.
7. For notification actions, the service receives an action intent carrying the target request and reply value.
8. The service calls `OpenCodeApi.replyToPermission` using the same contract as the chat UI.
9. On success, the app removes the permission locally and clears the notification.
10. When `permission.replied` arrives over SSE, reducer removal remains idempotent and finalizes consistency.

## Error Handling

### Stale or duplicate actions

The same permission may be handled from the chat card, the notification, or the web UI. Notification actions must tolerate already-replied requests by logging the failure, clearing stale local notification state when safe, and leaving the user with the chat fallback.

### Missing server or session context

If the action handler cannot resolve the target server connection or required directory context, it must not guess. It should fail safely, keep/open the chat fallback path, and avoid claiming that the permission was approved.

### Always allow risk

Always allow is intentionally preserved because it already exists in the in-app permission model, but notification text must make the long-term nature clear. The action label and surrounding notification copy should distinguish it from allow once.

### Notification suppression

Android notification permissions or channel settings can hide notifications. The session-level awaiting-permission state remains the fallback discovery path.

## Testing Strategy

- Unit-test reducer behavior for permission asked, replied, and local successful-reply removal.
- Test notification construction includes open-session content intent plus once, always, and reject actions.
- Test action intent dispatch maps each action to the correct reply value.
- Test successful reply cancels or updates the correct notification and removes local pending state.
- Test stale / duplicate reply handling is safe and idempotent.
- Extend session status tests to ensure awaiting-permission remains visible and does not regress busy/idle ordering.
- Preserve existing chat PermissionCard behavior and child-session notification filtering.

## Open Questions

- Whether service-side permission reply should require a fully resolved session directory or can safely omit it when unavailable depends on the current OpenCode server behavior. The implementation should prefer passing the directory when available and fail safely otherwise if the API requires it.
- Exact notification action labels may need localization adjustments, especially for distinguishing "always allow" from "allow once".
