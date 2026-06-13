# OC Remote 1.7.25

## Fixes

- Restore stable LaTeX math rendering in chat. The 1.7.23 "performance" change made each formula's WebView hardware-accelerated, enlarged the WebView pool, and changed the cache mode; on real devices this caused math-heavy messages to fall back to raw `\(...\)` / `$$...$$` text and could crash the app. This release reverts those WebView changes (software-layer rendering, smaller pool, no-cache), which restores reliable formula rendering and removes the crash.

## Notes

- Smoothness optimization for messages with very many formulas (batching MathJax to one initialization per message) is planned as a follow-up.

## Verification

- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- Emulator e2e against a live OpenCode server confirmed the regression source (WebView-per-formula destabilization) and that the app installs and runs.
