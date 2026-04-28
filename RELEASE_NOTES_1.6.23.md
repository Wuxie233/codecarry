# OC Remote v1.6.23 — Release Notes

## Highlights

- Fixed forked conversations being created under the root project instead of the source conversation's project.
  - Fork requests now preserve the source session directory context.
  - Forked sessions are merged into local session state immediately after creation.
- Added a way to stop conversations stuck in retry loops from the APK.
  - Retry state is now treated as interruptible, like busy state.
  - The chat top bar and retry banner expose Stop for retrying sessions.
  - Failed abort attempts no longer incorrectly clear the running/retry state.

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅
- `:app:assembleDebug` ✅
- `:app:lintDebug` ✅

## Version

- `versionName`: `1.6.23`
- `versionCode`: `36`
