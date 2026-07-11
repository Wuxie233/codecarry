# OC Remote 1.7.44

Improve chat readability and make connection recovery more transparent and immediate.

## Fixes

- Wrap ordinary mixed-language prose even when it contains long Docker image tags, version identifiers, or similar ASCII tokens.
- Preserve horizontal scrolling for standalone unbreakable tokens, code blocks, tables, reasoning, and KaTeX content.
- Clear response-ready notifications when their sessions are marked read without dismissing unrelated permission, question, or error alerts.

## Improvements

- Show the real connection setup, workspace synchronization, activity restoration, live-event setup, and retry phases on server cards.
- Add an immediate retry action while a connection is waiting for its next backoff attempt.

## Verification

- Passed `:app:testDebugUnitTest` and `:app:assembleRelease`.
- Passed all 9 `MessageMarkdownHorizontalDragTest` device tests before release preparation.
- Confirmed mixed Chinese/English prose exposes no horizontal-scroll semantics and does not move after a horizontal swipe.
