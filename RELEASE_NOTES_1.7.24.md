# OC Remote 1.7.24

## Fixes

- Render markdown-escaped dollar math delimiters such as `\$\$...\$\$` so model responses no longer show raw `$$` blocks in chat.
- Preserve support for escaped currency text like `\$20` without treating it as math.

## Verification

- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
