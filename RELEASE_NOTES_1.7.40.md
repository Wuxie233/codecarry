# OC Remote 1.7.40

Keep parent conversations visible in the top active list while their subagents are still running.

## Improvements

- Treat a parent conversation as active when any child subagent is busy or retrying.
- Remove the derived active state as soon as all child subagents return to idle.
- Preserve the existing priority of unread conversations, pending questions, and pending permissions over background activity.

## Verification

- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- Cold-launched the debug APK on the Android emulator without a crash or error dialog.
