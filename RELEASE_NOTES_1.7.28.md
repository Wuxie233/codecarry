# OC Remote 1.7.28

Follow-up fixes for the KaTeX chat math renderer shipped in 1.7.27.

## Fixes

- Fix chat messages rendering completely blank. The new math WebView used a software
  layer, which blanks tall messages on some devices/GPUs. Switched to the default
  (tiled) rendering layer so long math messages render fully.
- Fix math occasionally failing to render because `marked` was momentarily undefined.
  The `marked` and KaTeX scripts are now inlined directly into each message's HTML
  instead of being loaded via `<script src=...>`, removing the load race entirely.
- Fix digit-leading inline math like `$3x^2y$` being mistaken for a currency amount,
  which inverted math and surrounding prose. The currency heuristic now only treats a
  `$` as money when the digits are followed by whitespace, punctuation, or end of text
  — not by letters or math symbols (`^_{}\()+=*/|<>`).

## Verification

- Passed `:app:testDebugUnitTest` (added a regression test for digit-leading math).
- Passed `:app:assembleDebug`.
- Emulator e2e against the live server: the large math session now renders fully —
  inline math, display equations (e.g. `y' = y(1 + 3x^2)`), superscripts, and prime
  notation all render correctly with no blank messages and no math/prose inversion.
