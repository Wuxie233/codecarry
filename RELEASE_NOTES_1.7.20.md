# 1.7.20

## Pi Roundtable chat-first fixes

Pi Roundtable now behaves more like a live group chat and avoids rejected continue commands:

- Registry `paused` is treated as `awaiting_command`, so valid tables can continue correctly.
- Registry `awaiting` is treated as `awaiting_skip`, so the client no longer sends `可` when the table is waiting for a skip decision.
- Agent turns create a live chat placeholder before the first text delta, making thinking and speaking states visible sooner.
- The old roundtable steering entry was removed from the composer; `@` suggestions now insert role mentions and natural-language moderator requests instead.

## Project notes

- Added project `AGENTS.md` with the user's OC Remote preferences, Pi Roundtable semantics, and manual release workflow reminders.

## Tests

- `EventReducerPiRoundtableTest` covers live agent placeholders before first text deltas.
- `ChatViewModelRoundtableSteeringTest` covers continue gating for awaiting-skip tables.
- `RoundtableCenterViewModelTest` covers paused/awaiting registry status mapping.
- Verified locally with focused Pi tests and a debug APK build before release preparation.

## Version

- `versionName`: `1.7.20`
- `versionCode`: `64`
