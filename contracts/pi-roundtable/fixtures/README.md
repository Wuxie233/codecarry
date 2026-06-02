# Pi Roundtable Shared Fixtures

Each `*.json` file is a JSON array of `RoundtableSseEvent` objects. Test consumers should validate each event against `schema/roundtable-sse-event.json`, deduplicate by `eventId`, sort accepted events by `sequence` then `eventId`, and reassemble visible persona messages by `turnId` plus `deltaIndex`.

## Canonical Successful Outcome

The happy canonical outcome is:

- `turn-ada-001`: `Truth seeking should lead because coverage without pressure-testing becomes trivia.`
- `turn-curie-001`: `Coverage still matters when it maps the disagreement space before depth.`
- `turn-moderator-001.markdownBody`: `The round keeps truth as the goal and coverage as the map.\n\n```mermaid\ngraph TD\n  A[Truth seeking] --> B[Pressure-test claims]\n  C[Coverage] --> D[Map disagreement space]\n  B --> E[Next experiment]\n  D --> E\n```\n`
- `turn-moderator-001.nextQuestion`: `Which pressure test should the group run next?`

The expected SHA-256 values are:

- `turn-ada-001`: `ed09bbb66b36c76d284bcae5b6e708e3603e957d98423a0c629ba6743ca76f87`
- `turn-curie-001`: `775516bc108e67c4b16a9909158a577326443b81c8c435a7fc227d6e23a49aed`

## Fixture Expectations

- `happy-one-round.json`: clean single round. Expected assembled outcome is the canonical successful outcome above, followed by `awaiting_command` and `round_end.reason=completed`.
- `reconnect-midturn.json`: stream cuts after Ada `message_delta` index `0`, then resumes with a duplicate event for the last seen `eventId`. Deduplication by `eventId` and sorting by `sequence` must produce the same canonical successful outcome as `happy-one-round.json`.
- `out-of-order.json`: the JSON array intentionally presents events in shuffled arrival order while each event keeps the canonical `sequence` and `eventId`. Sorting by `sequence` then `eventId` must produce the same canonical successful outcome as `happy-one-round.json`.
- `duplicate-events.json`: repeats selected `message_delta` and `moderator_synthesis` events with the same `eventId`. Deduplication by `eventId` must produce the same canonical successful outcome as `happy-one-round.json`.
- `fallback-then-skip.json`: Turing fails with `agent_retry`, `agent_fallback`, `agent_error`, and `awaiting_skip`; the skip command effect is represented by the next `agent_turn_start` reason and the round continues without Turing. Expected successful assembled messages are the same Ada and Curie texts as the happy fixture, plus the failed transcript events for `turn-turing-001`, then `moderator_synthesis` and `round_end.reason=completed`.
