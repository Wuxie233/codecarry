# OC Remote 1.7.43

Keep long links readable inside chat bubbles instead of turning normal prose into a horizontal scroller.

## Fixes

- Wrap bare HTTP(S) URLs within the available message width.
- Wrap Markdown links, including links with optional titles and angle-bracket destinations.
- Preserve horizontal scrolling for genuinely unbreakable non-link tokens, code blocks, tables, reasoning, and KaTeX content.

## Verification

- Passed `:app:testDebugUnitTest` and `:app:assembleRelease`.
- Passed all 9 `MessageMarkdownHorizontalDragTest` device tests.
- Confirmed the exact release URL remains within a 220dp message area, exposes no horizontal scroll semantics, and does not move after a horizontal swipe.
- Passed independent functional and CJK visual reviews on a fresh emulator capture.
