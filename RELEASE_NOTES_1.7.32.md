# OC Remote 1.7.32

Proactive state sync: the client now fetches current server state on connect and on
opening a session, matching the web client, instead of only reacting to pushed events.

## Features

- Proactively fetch the current session status (busy / retry-cooldown) on connect,
  reconnect, and when opening a session, so an in-progress or retrying conversation is
  visible immediately instead of only after the next streamed event.
- Proactively fetch pending permission requests on connect and when opening a session, so
  a permission asked before the client connected is shown and notified without waiting for
  a pushed event. The snapshot is fetched only after the live stream is open so nothing is
  lost, and notifications are deduplicated across reconnects.
- Optimistically show the "working" state the moment a prompt is sent, instead of waiting
  for the agent's first response, then reconcile from the live status stream.

## Fixes

- Reconcile stale session statuses and pending permissions across reconnects (a conversation
  that finished, or a permission resolved, while disconnected is cleared) while preserving
  state that arrived live on the new stream.

## Verification

- Passed `:app:testDebugUnitTest` (411 tests, including new EventReducer status/permission
  reconcile, `/session/status` parser, permission-mapper, and optimistic-busy coverage).
- Passed `:app:assembleDebug`.
