---
date: 2026-05-07
topic: "Chat Status Visibility and Active Conversation Ordering"
status: validated
---

## Problem Statement

Users currently have to scroll upward to find current error/retry information in chat, which makes transient failures hard to notice and act on.

In the active conversations banner, unread conversations should appear before conversations that are merely running, because unread content requires user attention while running status is background progress.

## Constraints

- Scope is limited to the two user-selected UI issues: current error/retry visibility and unread-versus-busy ordering.
- Plugin system exploration is explicitly out of scope for this task.
- Historical retry/error records inside the message timeline must remain available in their original chronological context.
- Existing retry affordances, including visibility of retry attempt information and stop/cancel behavior, must be preserved.
- Changes should be focused and avoid broad ChatScreen, EventReducer, or navigation refactors.

## Approach

The chosen approach is to treat current retry/error state as **live session status**, not as old message history. Live status should render near the current interaction area at the bottom of the chat, while historical retry parts remain in the message stream.

For active conversation ordering, the chosen approach is to adjust the sorting priority so **unread outranks busy/running**. This preserves the existing active conversation banner model while correcting the attention priority.

I considered a broader redesign of the chat status system, but rejected it for this pass because the user asked to land two concrete UX fixes first. A larger status model can wait until we have more related pressure.

## Architecture

The implementation should stay within the current architecture:

- Chat screen owns placement of live session banners and message-list chrome.
- Existing domain models continue to represent assistant errors, retry parts, and session retry status.
- Session list view model continues to derive active conversation items from reducer state.
- Active conversation UI continues to render the already-derived ordered list.

This keeps the change local to presentation and derivation layers without changing protocol models or SSE reduction semantics.

## Components

**Chat live status area:** Responsible for showing current retry/error state close to the bottom/current viewport region, near the message composer or latest-message area.

**Message timeline:** Continues to render historical retry parts and assistant error payloads in chronological order when those records are part of the conversation history.

**Active conversation derivation:** Updates the status priority used to sort active conversations so unread conversations appear before busy/running conversations.

**Active conversation banner:** Should not need a structural redesign; it should simply reflect the corrected ordering produced by the view model.

## Data Flow

For chat status visibility:

- SSE/session state updates continue to flow into the existing session status state.
- Chat UI observes the same state as today.
- Current retry/error state is rendered in a bottom/current-interaction slot instead of being placed before the message items.
- Historical retry parts continue to flow through the message part renderer.

For active conversation ordering:

- Reducer state continues to provide unread counts, pending questions/permissions, busy state, and retry state.
- Session list view model derives each active conversation status.
- The final active conversation list is sorted with unread ahead of busy/running.
- The banner renders the supplied order unchanged.

## Error Handling

Rendering should degrade safely if retry/error fields are missing or blank: show the generic retry/error status without crashing or hiding the stop affordance.

The ordering change should preserve deterministic fallback sorting for conversations with the same priority, such as last activity or existing secondary ordering.

## Testing Strategy

- Add or update focused tests around active conversation ordering so unread conversations sort before busy/running conversations.
- Verify retry status still appears while a session is retrying and remains actionable.
- Verify historical retry parts still render in the message timeline.
- Manually smoke-test a chat with enough messages to require scrolling, confirming the live retry/error status is visible near the bottom without scrolling to the top.

## Open Questions

None for this scoped pass. Plugin ecosystem design is intentionally deferred and should not influence this implementation.
