# OC Remote 1.7.30

Hotfix for markdown horizontal scrolling in chat messages.

## Fixes

- Fix the remaining multi-block horizontal drag failure for markdown code blocks.
  The real highlighted-code path still used the old `pointerInteropFilter` guard,
  so later code blocks in the same message could still fail to drag horizontally.
- Fix markdown tables, code blocks, and display math inside math-enabled messages.
  When a message contains math, the entire markdown payload renders through the
  KaTeX WebView path; v1.7.29 only fixed the native Compose table path. The WebView
  renderer now wraps every table, code block, and display-math block in its own
  `markdown-horizontal-scroll` container with `touch-action: pan-x`, so each block
  can scroll horizontally independently.

## Verification

- Passed focused RED→GREEN unit coverage for the WebView HTML scroll preparation.
- Passed Compose instrumentation controls showing multiple native horizontal
  scroll siblings still scroll independently.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug` and local signed `:app:assembleRelease`.
- On-device chat e2e for the original reported message will be verified manually.
