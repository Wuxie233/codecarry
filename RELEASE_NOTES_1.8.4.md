# OC Remote 1.8.4

Restore reliable interaction for long native Markdown assistant messages and ordered lists.

## Fixes

- Split long non-math assistant Markdown into bounded Compose chat rows so later tables remain physically horizontally draggable.
- Preserve vertical chat scrolling and Compose table interaction across segmented long messages.
- Preserve ordered-list starting numbers, including separate and nested ordered lists, while keeping the existing Markdown renderer dependency.

## Verification

- Passed the full `MessageMarkdownHorizontalDragTest` device suite: 16/16 on `clawchat-mvp`.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:compileDebugAndroidTestKotlin`.
- Passed `:app:assembleDebug` and `:app:assembleRelease`.
