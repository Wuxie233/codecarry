# Deterministic Fake-Provider Spec

The fake provider is a test-only contract used by the TypeScript service and cross-platform contract tests. It is a scripted data source: the same script input MUST produce the same stream output every time. It never calls a real model and never contains credentials.

## Script Shape

```json
{
  "scriptId": "fixture-normal-round",
  "protocolVersion": 1,
  "defaults": {
    "providerId": "fake-provider",
    "model": "fake-model",
    "chunkCadenceMs": 0
  },
  "turns": [
    {
      "personaId": "persona-ada",
      "turnId": "turn-ada-001",
      "attempt": 1,
      "reply": {
        "text": "Deterministic reply text.",
        "chunks": ["Deterministic ", "reply text."],
        "finishReason": "stop"
      },
      "stream": {
        "chunkCadenceMs": 25,
        "emitEmptyDeltas": false
      },
      "failures": [],
      "truncation": null,
      "illegalOutput": null
    }
  ]
}
```

## Fields

- `scriptId`: stable fixture identifier for test diagnostics.
- `protocolVersion`: expected protocol major version.
- `defaults.providerId`: provider id used when a turn omits `providerId`.
- `defaults.model`: model id used when a turn omits `model`.
- `defaults.chunkCadenceMs`: default delay between emitted `message_delta` chunks.
- `turns[]`: ordered scripted provider calls. The orchestrator selects by `personaId`, `turnId`, and `attempt`.
- `turns[].reply.text`: complete normal reply for the persona/turn.
- `turns[].reply.chunks`: exact `message_delta.payload.chunk` sequence. Joining these chunks MUST equal `reply.text` unless the turn intentionally uses `truncation` or `illegalOutput`.
- `turns[].reply.finishReason`: `message_end.payload.finishReason` for normal completion.
- `turns[].stream.chunkCadenceMs`: per-turn delay between chunks for slow/chunked streaming tests.
- `turns[].stream.emitEmptyDeltas`: when true, empty chunks are emitted exactly as listed in `reply.chunks`.
- `turns[].failures[]`: failure injection list. Each entry has `attempt`, `phase`, `errorCode`, `reason`, and `recoverable`.
- `turns[].truncation`: half-delta/truncated stream control. Shape: `{ "afterDeltaIndex": 0, "omitMessageEnd": true, "partialChunk": "half" }`. The provider emits deltas through `afterDeltaIndex`, optionally emits `partialChunk` as the next `message_delta`, and omits `message_end` when `omitMessageEnd` is true.
- `turns[].illegalOutput`: non-conforming output control. Shape: `{ "mode": "schema_violation", "description": "missing deltaIndex", "payload": {} }`. Tests use this to assert validation failure handling.

## Failure Injection

A `failures[]` entry expresses which attempt fails and what error is produced:

```json
{
  "attempt": 2,
  "phase": "before_first_delta",
  "errorCode": "provider_timeout",
  "reason": "fake timeout before first chunk",
  "recoverable": true
}
```

Supported `phase` values are `before_first_delta`, `after_delta`, `before_message_end`, and `after_message_end`. Recoverable failures should drive `agent_retry` or `agent_fallback`; non-recoverable failures should drive `agent_error` and, when configured attempts are exhausted, `awaiting_skip`.

## Slow and Chunked Streaming

Slow streaming is represented only by `stream.chunkCadenceMs` and the exact `reply.chunks` array. Tests may advance fake time by this cadence; no wall-clock delay is required in unit tests.

## Truncated or Half Delta

`truncation` intentionally produces a `message_delta` sequence without a matching `message_end`. Consumers must treat the turn as unfinished until the service emits retry/fallback/error events or a later reconnect supplies the missing completion.

## Illegal Output

`illegalOutput` asks the fake provider to emit a payload that violates the protocol, such as a missing `deltaIndex`, an invalid `finishReason`, or a non-string chunk. The service test decides whether the bad payload is rejected before streaming or converted into an `error`/`agent_error` event according to the scenario.
