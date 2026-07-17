# OC Remote 1.7.47

Restore the proven 1.7.45 interface after the 1.7.46 workspace redesign caused visual and navigation regressions.

## Rollback

- Restore the previous Home, Sessions, OpenCode chat, Codex chat, composer, theme, and component presentation.
- Restore the compact horizontal active-conversation strip so a large number of active conversations cannot consume the session screen.
- Keep the project and conversation list visible, vertically scrollable, and available for selection below the active strip.

## Verification

- Passed `:app:testDebugUnitTest` and `:app:assembleDebug`.
- Verified the session screen with more active conversations than fit on screen and confirmed the project list remains reachable and selectable.
