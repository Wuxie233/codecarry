# OC Remote 1.7.36

Chat markdown horizontal drag fix for clipped assistant messages.

## Fixes

- Allow assistant markdown paragraphs with long unbreakable ASCII tokens, file paths, or arrow-prefixed lines to be dragged horizontally instead of being clipped at the right edge.
- Keep normal prose wrapped to the message bubble width, so ordinary replies do not become wide scroll surfaces.
- Re-enable WebView horizontal scrolling for KaTeX-backed markdown as a fallback while preserving existing independent scroll containers for code, tables, and display math.

## Verification

- Passed focused `ChatMarkdownRendererTest` and `MarkdownMessageRendererTest`.
- Passed `:app:compileDebugAndroidTestKotlin`.
- Passed `MessageMarkdownHorizontalDragTest` on the `clawchat-mvp` emulator; the rendered long assistant markdown paragraph visibly shifts after a horizontal swipe.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
