# OC Remote 1.7.34

Markdown currency rendering fix for chat messages.

## Fixes

- Keep financial prose such as `$827/$1484`, `$7000)`, and `$769 + $58 = $827` on the native Compose markdown route instead of misdetecting it as dollar-delimited math.
- Preserve real inline math rendering for numeric formulas such as `$3/4$`, `$3+2$`, and `$3x^2y$`.

## Verification

- Passed focused `MarkdownMathRendererTest` and `ChatMarkdownRendererTest`.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
