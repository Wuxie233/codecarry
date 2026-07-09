# OC Remote 1.7.37

Follow-up chat horizontal drag fix for clipped reasoning and plain-text assistant content.

## Fixes

- Allow long unbreakable reasoning text to be dragged horizontally instead of being clipped at the right edge.
- Apply the same wide plain-text handling to raw error payload text that is not rendered as HTML.
- Preserve normal wrapped prose for ordinary assistant text while disabling selection only on detected wide plain-text scroll surfaces so drag gestures reach the horizontal scroller.

## Verification

- Passed `MessageMarkdownHorizontalDragTest` on the `clawchat-mvp` emulator; both wide markdown and wide reasoning/plain text visibly shift after horizontal swipes.
- Passed `:app:compileDebugAndroidTestKotlin`.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
