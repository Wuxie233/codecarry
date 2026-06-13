# OC Remote 1.7.27

## Fixes

- Rebuild chat math rendering to match the official OpenCode web UI. Messages that
  contain LaTeX are now rendered in a single WebView per message using `marked`
  (markdown) + KaTeX (math), the same proven pipeline OpenCode's web client uses,
  instead of the old per-formula MathJax WebViews. This removes the long-standing
  "formula renders then turns back into raw text", the duplicate/garbled prose, and
  the broken layout. KaTeX renders math as HTML/CSS (not SVG), so it is fast, stable,
  and renders the entire message in one pass.
- Messages without math keep the existing native renderer unchanged.

## Details

- KaTeX `0.16.27` + `marked` are bundled offline under `assets/` (no network needed).
- Supports `$...$`, `$$...$$`, `\(...\)`, and `\[...\]` math delimiters.

## Verification

- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- Emulator e2e against the live server: the math session now renders real formulas
  (e.g. fractions and prime notation render correctly) — confirmed visually, which the
  old MathJax-SVG path could not do on the emulator's software GPU.
