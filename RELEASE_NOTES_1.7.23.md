# OC Remote 1.7.23

## Fixes

- Preserve Pi Roundtable paused/awaiting-command state after transcript replay so composer continue requests route correctly.

## Performance

- Reduce markdown math rendering churn in chat by stabilizing formula segment composition and reusing more WebView renderers.
- Allow cached MathJax asset loading and hardware-backed WebView rendering for smoother chats with many formulas.

## Verification

- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
