# OC Remote 1.7.38

Fix horizontal drag for ordinary assistant markdown paragraphs that contain medium-length inline-code and path tokens.

## Fixes

- Lower the assistant markdown paragraph wide-token detection threshold so real-world inline-code tokens like `chatgpt-comparison-detection` and `~/.config/opencode/skills/**/SKILL.md` enter the horizontal drag path on mobile.
- Add a regression test that reproduces the real assistant message shape instead of only testing very long synthetic tokens.

## Verification

- Reproduced the real server chat flow on the `clawchat-mvp` emulator with `ses_0cdb39ea0ffeu46TKYSpbc1ZdB`.
- Confirmed real assistant-message body movement after a horizontal swipe with screenshot crop diff `changed_pixels=14320` and `strong_changed_pixels=11295`.
- Passed `MessageMarkdownHorizontalDragTest` on the emulator, including the new medium inline-code paragraph case.
- Passed `MultiHorizontalScrollGestureTest` on the emulator.
- Passed `:app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.ChatMarkdownRendererTest"`.
- Passed `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:compileDebugAndroidTestKotlin` during verification.
