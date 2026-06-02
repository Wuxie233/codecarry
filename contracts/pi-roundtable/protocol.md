# Pi Roundtable Wire Protocol v1

This directory is the single source of truth for the Pi Roundtable wire protocol consumed by the TypeScript service and Kotlin/Android client. The schemas use JSON Schema draft-07 and every schema has a stable `$id` under `https://schemas.oc-remote.local/pi-roundtable/v1/`.

## Version

`protocolVersion` is an integer carried by every SSE event, command payload, transcript, and derived artifact. Version `1` is the frozen initial contract.

Compatibility rules:

- Additive changes that add optional fields, optional enum values negotiated by feature flags, or new ignorable metadata keep the same `protocolVersion`.
- Breaking changes that remove fields, change required field semantics, rename fields, change stream ordering, or change message reassembly rules require a new major `protocolVersion`.
- Producers must emit a single `protocolVersion` per stream.
- Consumers must reject versions newer than they explicitly support only when the major wire behavior is incompatible. Otherwise they should parse known fields and ignore unknown extensions.

## SSE Envelope

Every Server-Sent Event data frame is a JSON `RoundtableSseEvent` matching `schema/roundtable-sse-event.json`. The same `eventId` is also used as the SSE `id`, and clients reconnect with `Last-Event-ID`.

Required envelope fields:

- `protocolVersion`: integer.
- `eventId`: globally monotonic, globally increasing integer across all roundtable streams served by the service.
- `roundId`: stable roundtable identifier.
- `turnId`: stable turn identifier, or `null` for round-level events.
- `sequence`: monotonic integer within one SSE stream for one roundtable connection.
- `type`: one of `round_start`, `agent_turn_start`, `message_delta`, `message_end`, `moderator_synthesis`, `awaiting_command`, `agent_retry`, `agent_fallback`, `agent_error`, `awaiting_skip`, `round_end`, `error`.
- `author`: `{ id, name, mbti, role, colorSeed }`, where `role` is `persona`, `moderator`, `user`, or `system`.
- `payload`: event-specific object.
- `ts`: ISO-8601 timestamp.

## Event Ordering Guarantees

- `eventId` is globally increasing and never reused.
- `sequence` is increasing within the stream observed by a client.
- Events sharing a `turnId` are ordered by `sequence` and then `eventId` if a consumer needs a deterministic tie-breaker.
- A turn starts with `agent_turn_start`, may emit zero or more fallback-engine signals, may emit zero or more `message_delta` chunks, and is finalized by either `message_end`, `agent_error`, or `awaiting_skip`.
- Failed turns are part of the transcript. `agent_retry`, `agent_fallback`, `agent_error`, and `awaiting_skip` events MUST be appended to the event log and represented in the derived transcript view.

## Event Payloads

Payload schemas live in `schema/event-payloads.json`.

- `round_start`: topic, speaker policy, participant IDs, moderator ID, and runtime limits.
- `agent_turn_start`: persona/model selected for the turn plus attempt number and optional action tag.
- `message_delta`: incremental UTF-8 text chunk with `deltaIndex` and optional `charStart`.
- `message_end`: finalizes the turn with `deltaCount` and either `finalText`, `contentSha256`, or both. Producers should send both when available so consumers can verify text and persist a hash.
- `moderator_synthesis`: markdown body plus next-layer guiding question. `markdownBody` MUST contain a fenced ` ```mermaid ` block.
- `awaiting_command`: service is waiting for user steering through `POST /roundtables/:id/command`.
- `agent_retry`: same provider/model is being retried; includes persona, provider, model, attempt, reason, and retry delay.
- `agent_fallback`: provider/model changed; includes persona, previous model ref, next model ref, attempt, and reason.
- `agent_error`: current persona/model attempt failed; includes recoverability.
- `awaiting_skip`: all configured attempts for the persona are exhausted or paused; UI should offer skip.
- `round_end`: round stopped or completed.
- `error`: stream/system error not attributable to a single persona turn.

## Message Delta Reassembly

For each `turnId`, consumers reassemble the visible message by sorting `message_delta` events by `deltaIndex` and appending each `chunk` exactly once. `sequence` and `eventId` preserve stream ordering; `deltaIndex` protects against duplicate delivery after reconnect.

`message_end.payload.deltaCount` is the number of accepted deltas for the turn. Consumers verify that they assembled exactly `deltaCount` chunks. If `finalText` is present, it is authoritative and should match the joined chunks. If `contentSha256` is present, it is the lowercase hex SHA-256 of the final UTF-8 text.

## Commands

`POST /roundtables/:id/command` accepts JSON matching `schema/command.json`. It is a discriminated union on `command`:

- `可`: continue.
- `止`: stop and wrap up.
- `深入`: deepen a target turn, topic node, or quoted point.
- `引入新人物`: introduce a new persona using the same shape as `schema/persona.json`.
- `@mention`: steer a specific participant by ID.
- `inject`: user speaks as participant content. This is transcript content, not orchestration control or a hidden system instruction.
- `switch_cadence`: change the active `SpeakerPolicy`.

## Catalog and Persona Contracts

`schema/catalog.json` describes gateway/model catalog entries exposed to clients. It includes `providerId`, `displayName`, `baseUrl`, `api`, `models`, ordered `fallback`, `enabled`, and `validation`. It intentionally has no key, token, secret, password, or credential field.

`schema/persona.json` describes persona configuration: optional `id`, `name`, `mbti`, `stancePrompt`, `style`, `actionTagPrefs`, primary `provider`, primary `model`, ordered `fallback`, and `enabled`.

## Transcript Contract

`schema/transcript.json` stores an append-only `events` array and append-only `commands` array plus an `assembled` derived view. The transcript MUST capture retries, fallbacks, skips, commands, timestamps, failed turns, and moderator synthesis content. The assembled view is derived from the event log and can be regenerated.

## Forward Compatibility and Unknown Fields

Forward compatibility is mandatory. A receiver that sees an unknown event `type` MUST ignore that event without crashing, preserve stream progress using `eventId` when possible, and continue processing later known events.

Receivers MUST ignore unknown extra fields on known objects. Unknown fields are extensions, not parse failures. This ignore-unknown-fields rule applies to SSE events, payloads, commands, catalog entries, personas, and transcripts.

Consumers MUST NOT treat unknown fields as control instructions. Only documented command payloads affect orchestration.

## Security and Secret Placement

No schema, example, transcript, catalog, persona, or evidence artifact may contain key, token, secret, password, or credential values. Gateway credentials are server-side only and are not part of this contract.

TODO(task-2): Bearer authentication, shared fixtures, and fake-provider specifications land in Task 2. Do not infer auth headers or credential fixtures from this v1 protocol freeze.
