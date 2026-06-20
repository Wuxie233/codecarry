# OC Remote 1.7.31

Hotfix for native markdown code-block horizontal dragging.

## Fixes

- Harden native markdown code fences and mermaid fallback code blocks so horizontal
  drag gestures are handled by their own scroll container instead of the enclosing
  message selection container. This keeps later long code blocks draggable in the
  same way as the table and WebView markdown hotfixes from 1.7.29 and 1.7.30.
- Preserve code word-wrap behavior while using the Material `Text` composable inside
  an explicit horizontal scroll box for non-wrapped code blocks.

## Verification

- Passed focused markdown renderer unit coverage.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- Passed focused emulator instrumentation coverage for native multi-scroll siblings
  and WebView table/code/math scroll blocks on `clawchat-mvp`.
