# OC Remote 1.8.5

Fix long assistant messages that combine Markdown tables with lists, blockquotes, raw HTML, or indented code.

## Fixes

- Preserve complex root Markdown structures as atomic chunks instead of falling back the entire message to one oversized chat row.
- Keep surrounding prose and tables split into bounded Compose rows so wide tables later in long messages retain horizontal scrolling.
- Preserve exact Markdown source reconstruction, link definitions, fenced code blocks, and oversized-table header repetition.

## Verification

- Passed focused JVM tests for Markdown chunk planning and chat row planning, including production-shaped mixed Markdown with a later wide table.
- Passed `:app:compileDebugAndroidTestKotlin`.
- Passed `:app:assembleDebug` and `:app:assembleRelease`.
- Emulator and real-session E2E were intentionally skipped for this release.
- The full JVM suite completed 668 of 669 tests; the unrelated existing `ChatViewModelRetryNowTest` retry-state convergence assertion remains failing.
