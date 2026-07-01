# OC Remote 1.7.33

Renderer refactor release for chat markdown rendering.

## Improvements

- Extract chat markdown rendering behind `ChatMarkdownRenderer`, keeping `ChatScreen` thinner while preserving native markdown behavior.
- Add focused renderer route coverage for math, non-math markdown, Mermaid fences, and dollar-sign edge cases.

## Verification

- Passed focused `ChatMarkdownRendererTest`.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
