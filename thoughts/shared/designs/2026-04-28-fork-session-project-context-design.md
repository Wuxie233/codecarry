---
date: 2026-04-28
topic: "Fork Session Project Context"
status: validated
---

## Problem Statement

Forking a conversation currently creates the new conversation under the root project instead of the source conversation's project.

The observed behavior points to a context propagation bug: regular new-session creation passes the current project directory to the OpenCode server, while fork-session creation does not. The server then falls back to its default/root context and records the forked session there.

## Constraints

- Keep the Android client architecture unchanged.
- Use the existing OpenCode directory context mechanism rather than introducing a new protocol.
- Do not rework session list grouping, DataStore preferences, or pinned directory behavior.
- Do not fake or override the service-returned session directory on the client.
- Do not modify OpenCode server code from this repository.
- Keep the fix minimal and regression-tested.

## Approach

The chosen approach is to make fork-session creation inherit the same directory context used by create-session, prompt, and command calls.

The fork path should carry the source session directory from the chat screen state into the API layer. The API layer should attach the same directory context header used elsewhere so the OpenCode server creates the forked session inside the intended project.

Fork success should also update local session state immediately through the existing reducer path. This keeps the session list responsive and avoids relying only on SSE or a later refresh.

Alternatives considered:

- Changing session list grouping was rejected because it would only hide the display symptom while the forked session would still execute in the wrong directory.
- Forcing the returned session directory client-side was rejected because it would create a split-brain state between client and server.
- Fixing only the server was rejected for this pass because the current repository owns the Android client and already has an established directory header pattern.

## Architecture

The fix stays within the existing client-side request flow:

- The chat screen triggers a fork action.
- The chat view model resolves the source session's current directory context.
- The API client sends the fork request with that directory context.
- The OpenCode server creates the new session in the correct project.
- The view model merges the returned session into the reducer.
- Navigation opens the forked session by ID.

No new architectural layer is needed.

## Components

### Chat View Model

The chat view model owns the source session context during a chat session.

Responsibilities:

- Keep the current session directory loaded from the source session.
- Pass that directory into fork-session API calls.
- Avoid silently firing a context-sensitive fork before session context is available.
- Merge the returned forked session into local state using the existing reducer path.

### API Client

The API client owns HTTP request construction.

Responsibilities:

- Allow fork-session requests to receive an optional directory context.
- Attach the existing OpenCode directory header when a directory is present.
- Preserve current fork behavior when no directory is available.

### Event Reducer

The reducer remains the central local session state path.

Responsibilities:

- Accept the forked session returned by the API.
- Merge it consistently with sessions created through the normal new-session path.

### Session List

The session list should not need structural changes.

Responsibilities:

- Continue grouping sessions by the service-returned directory.
- Benefit naturally once forked sessions are created with the correct directory.

## Data Flow

Normal fork flow:

- Source chat session is loaded.
- Source session directory is stored in chat state.
- User triggers fork from the menu or slash command.
- View model sends fork request with the source directory.
- API client adds the directory context header.
- Server returns the forked session.
- View model writes the session into reducer state.
- UI navigates to the new session.

Directory context fallback flow:

- If the current directory is not loaded yet, the view model should attempt to use the freshest source session context it already has or reloads.
- If directory still cannot be resolved, the request may fall back to existing behavior, but the code path should make that fallback explicit and testable.

## Error Handling

Fork failures should continue using the existing user-visible error path.

The important behavior change is not to hide context loss. If directory context cannot be resolved, that should be a deliberate fallback rather than an accidental null flowing into the API call.

Local reducer synchronization should not create a second failure mode for the user. If the API returns a valid forked session, navigation can still proceed; reducer update should use the same safe merge behavior as normal session creation.

## Testing Strategy

Regression tests should focus on the context boundary that caused the bug.

Coverage targets:

- Fork API request includes the OpenCode directory context when supplied.
- Chat view model passes the loaded source session directory into fork calls.
- Fork success merges the returned session into reducer-backed local state.
- Menu fork and slash-command fork continue sharing the same view model path.
- A missing directory context is handled explicitly instead of accidentally defaulting through an untested null path.

The tests should avoid large UI snapshot churn and should not require changing unrelated session list behavior.

## Open Questions

- The OpenCode server should ideally inherit fork context from the source session even without a directory header. That is outside this repository, but this client-side fix should remain compatible with such a server-side improvement later.
- If a source session has no directory at all, the current fallback behavior should be preserved unless product requirements later define a stricter error.
