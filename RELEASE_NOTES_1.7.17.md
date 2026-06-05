# 1.7.17

## Roundtable: visible send feedback

Pi Roundtable chat sends now provide immediate feedback instead of feeling like nothing happened:

- Messages sent to the whole table are inserted into the chat timeline as local user bubbles after the `inject` command is accepted.
- Rejected Pi `inject` commands now surface as send failures instead of being silently ignored.
- The fix keeps the existing Pi Roundtable protocol and command shape unchanged.

## Roundtable: live thinking and speaking state

Roundtable live sessions now make role activity visible while the event stream is running:

- `agent_turn_start` marks a role as thinking before the first text chunk arrives.
- `message_delta` marks that role as speaking while partial output streams in.
- `message_end`, `agent_error`, and `awaiting_skip` clear the active turn state.
- The compact roster highlights the active role and shows a clear `role · thinking/speaking` line.
- Streaming message headers show a speaking chip with animated dots.

## Tests

- `ChatViewModelRoundtableSteeringTest` now covers that normal roundtable sends still use `inject` and produce a local user message.
- `ChatViewModelRoundtableSteeringTest` now covers the live state transition from thinking to speaking to idle.
- Verified locally with focused roundtable tests, full debug unit tests, and a debug APK build.

## Version

- `versionName`: `1.7.17`
- `versionCode`: `61`
