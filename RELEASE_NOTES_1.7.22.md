# 1.7.22

## Pi Roundtable continue flow

Pi Roundtable now treats the chat composer as the primary control surface without accidentally appending control words to the transcript:

- Sending `继续`, `可`, or `continue` from the composer now routes to the roundtable continue command when the table is waiting for `AwaitingCommand`.
- Continue-like input no longer becomes a normal participant supplement while a table is still speaking.
- The misleading “roundtable is full” message was removed from the app.

## Error recovery

Oversized roundtable supplements now get a recoverable error instead of a terminal-sounding state:

- Long supplements explain that the note is too large and suggest shortening it or sending Continue without extra text.
- Draft preservation behavior remains unchanged, so failed sends keep the user’s input.

## Tests

- `ChatViewModelRoundtableSteeringTest` covers composer Continue routing, unavailable Continue state, and oversized supplement guidance.
- `PiRoundtableTransportTest` continues to cover Pi command rejection details.
- Verified locally with focused Pi command tests before release preparation.

## Version

- `versionName`: `1.7.22`
- `versionCode`: `66`
