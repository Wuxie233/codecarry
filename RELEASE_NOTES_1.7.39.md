# OC Remote 1.7.39

Unify chat overflow behavior so assistant prose, reasoning text, code blocks, tables, and KaTeX-backed markdown stop fighting each other.

## Fixes

- Centralize chat wrap-vs-horizontal-drag decisions behind one shared overflow policy instead of letting markdown paragraphs, plain text, code blocks, and WebView markdown each apply separate heuristics.
- Keep ordinary review-style prose wrapped even when it contains markdown file links like `[DispatchAsync](/root/...:72)` by excluding hidden link targets from wide-token detection.
- Preserve horizontal drag for real assistant markdown paragraphs that contain medium-length inline-code and path tokens, and keep reasoning/plain-text behavior aligned with the same rule.
- Keep code blocks on the existing `Code word wrap` setting while leaving tables and display math as dedicated structured horizontal-scroll surfaces.

## Verification

- Passed `:app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.ChatMarkdownRendererTest" --tests "dev.minios.ocremote.ui.screens.chat.MarkdownMessageRendererTest"`.
- Passed `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin`.
- Passed `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.minios.ocremote.ui.screens.chat.MessageMarkdownHorizontalDragTest,dev.minios.ocremote.ui.screens.chat.MarkdownMessageWebViewScrollTest` on the `clawchat-mvp` emulator.
- Passed `:app:assembleDebug`.
