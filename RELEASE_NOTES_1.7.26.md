# OC Remote 1.7.26

## Fixes

- Stop rendered math formulas from reverting back to raw LaTeX text. After a formula
  rendered, scrolling / recomposition / message re-streaming could recreate its WebView,
  and under contention the re-render would time out and flip the already-rendered formula
  back to raw `\(...\)` / `$$...$$` text. A formula's rendered result is now cached, so once
  it has rendered it starts rendered on every later appearance and is never downgraded to
  the text fallback again.

## Known follow-up

- Each formula still renders in its own WebView. If many-formula messages remain janky,
  the planned next step is to render all of a message's math in a single MathJax pass.

## Verification

- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- Emulator e2e: app installs/connects/opens the math session with no crash. Note: the
  emulator's software GPU (swiftshader) cannot rasterize MathJax SVG, so pixel-level
  rendering must be confirmed on a real device; the fix is a deterministic state-machine
  guard that prevents the rendered-to-fallback downgrade.
