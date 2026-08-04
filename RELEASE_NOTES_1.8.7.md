# OC Remote 1.8.7

Replace the chat Markdown compatibility layers with one GFM AST-backed render pipeline.

## Changes

- Parse chat Markdown once into an immutable document model, then derive typed render blocks and stable streaming identities from that model.
- Keep tables on the native Compose path with structured cells and content-adaptive columns, including in messages that also contain math.
- Route only math-bearing blocks through the KaTeX/WebView adapter while preserving code, Mermaid, ordered-list numbering, links, raw HTML protection, and selectable prose.
- Remove the legacy line scanner, global message routing, duplicate math scanner, raw table parser, and whole-message fallback paths.
- Preserve completed block keys as streaming content grows while isolating planning state by message and part identity.

## Verification

- Passed the complete `:app:testDebugUnitTest` suite.
- Passed `:app:compileDebugAndroidTestKotlin`.
- Passed `:app:assembleDebug` and `:app:assembleRelease`.
- Emulator, device, and real-session E2E were not run for this release.
