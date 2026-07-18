# OC Remote 1.7.48

This release introduces the new OpenCode workspace interface and improves visibility across active sessions and subagents.

## OpenCode Workspace

- Add Activity and Projects views to Sessions, with each server remembering its last selected view.
- Group pending actions, running work, retries, and unread completions using live OpenCode session data.
- Keep project groups directly accessible and preserve each view's scroll position when switching modes.
- Refresh the OpenCode Home experience with compact server rows, recent work, and real connection phases.

## Subagents

- Add a discoverable subagent button and right-side drawer in OpenCode chat.
- Separate running and historical direct subagents, with title search for history.
- Keep parent and child session relationships isolated to the current server.

## Reliability and Accessibility

- Deduplicate pending-session totals while preserving all secondary activity signals.
- Keep project names and metrics readable on narrow screens with large text.
- Stabilize session preference tests and expand cross-server regression coverage.

## Verification

- Passed the full debug unit test suite and built debug and Android test APKs.
- Passed all 17 instrumentation tests on an Android emulator.
- Verified light, dark, AMOLED, narrow-screen, large-text, and landscape layouts.
