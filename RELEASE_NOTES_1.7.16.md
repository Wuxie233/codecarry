# 1.7.16

## Roundtable: supplement guidance from chat

Roundtable steering now has a first-class supplement path in the main chat:

- Waiting roundtables show `Supplement` / `补充说明` next to `Continue` / `继续`.
- Tapping it switches the composer into a supplement mode for adding constraints, background, or guidance for the whole table.
- Sending supplement text reuses the existing table context command, so the host can re-plan the next step without exposing command syntax to users.

## Roundtable feedback and recovery

- Empty supplement sends now explain what is missing instead of doing nothing.
- Supplement mode blocks attachments with a clear message instead of silently ignoring them.
- If a roundtable command is already running, the composer now tells users to wait and preserves their text.
- Returning from casting now refreshes the Roundtable Center so draft and confirmed roundtable state stays current.
- Android back from casting uses the same return path as the top-bar back action.

## Tests

- `ChatViewModelRoundtableSteeringTest` now covers that supplement guidance still sends the existing `inject` command and trims user content.
- Verified locally with focused roundtable steering tests and a debug APK build.

## Version

- `versionName`: `1.7.16`
- `versionCode`: `60`
