# OC Remote 1.7.42

Restore reliable horizontal dragging for wide code blocks deep inside very long KaTeX-backed assistant messages.

## Fixes

- Split supported oversized KaTeX messages into bounded top-level chat rows so later code blocks remain reachable by touch.
- Keep vertical chat scrolling and streaming auto-follow working across segmented message rows.
- Preserve message headers, tool steps, metadata, copy actions, round navigation, and pending-card positions after segmentation.
- Keep fenced code blocks atomic and conservatively avoid splitting Markdown structures whose rendering context crosses chunk boundaries.
- Normalize raw HTML before chunk planning and reject non-HTTP(S) WebView navigation.
- Clear page-bound WebView callbacks before pooled views are reused.

## Verification

- Reproduced the original issue in the real target session on the Android emulator.
- Confirmed a physical horizontal swipe changed the target code block from `scrollLeft=0` to `scrollLeft=300.36` and revealed the hidden content.
- Confirmed vertical dragging over the same chunk still scrolls the parent conversation.
- Passed `:app:testDebugUnitTest` and `:app:assembleRelease`.
- Passed all 8 `MessageMarkdownHorizontalDragTest` device tests repeatedly with physical finger input events.
