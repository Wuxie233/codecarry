# OC Remote 1.8.6

Refactor chat Markdown rendering so each top-level block owns its layout and interaction behavior.

## Changes

- Render prose, tables, fenced code, complex root structures, and math placeholders as typed blocks instead of routing or falling back at whole-message scope.
- Give GFM tables dedicated Compose rows with content-adaptive 80..280dp columns and their own horizontal scroll state.
- Route only chunks containing math placeholders through KaTeX/WebView, so tables elsewhere in a math-bearing message remain native Compose tables.
- Remove the whole-message and maximum-chunk fallbacks while preserving exact source reconstruction, table header repetition, link definitions, ordered-list numbering, code, Mermaid, and prose selection.

## Verification

- Passed focused JVM tests for block planning, renderer routing, measured table allocation, and horizontal dragging in a production-shaped message hierarchy.
- The measured production-shaped table exposed `maxValue=32` and reached `postDragValue=32` after a horizontal swipe.
- JVM diagnosis confirmed `SelectionContainer + DisableSelection` does not block the table scroll gesture at 288dp, 330dp, or 360dp widths.
- Passed `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`, and `:app:assembleRelease`.
- Emulator and real-session E2E were intentionally not run.
- The full JVM suite completed 684 of 685 tests; the unrelated existing `ChatViewModelRetryNowTest` retry-state convergence assertion remains failing.
