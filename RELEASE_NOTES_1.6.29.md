# OC Remote v1.6.29 — Release Notes

## Highlights

- Permission-waiting tasks are now visible from Android notifications instead of silently appearing stalled.
- Permission notifications expose quick actions for **Allow once**, **Always allow**, and **Reject**, using the existing OpenCode permission reply contract.
- Successful notification replies optimistically clear local pending-permission state while keeping the existing SSE `permission.replied` path as the final source of consistency.
- The existing in-chat PermissionCard and session-level `AWAITING_PERMISSION` fallback remain available when notifications are missed or disabled.

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventReducerTest"` ✅
- `:app:testDebugUnitTest --tests "dev.minios.ocremote.service.OpenCodeConnectionServicePermissionActionTest"` ✅
- `:app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.BuildActiveConversationsTest"` ✅
- `:app:lintDebug` ✅
- `:app:assembleDebug` ✅

## Version

- `versionName`: `1.6.29`
- `versionCode`: `42`

## Known limitations

- Android notification permissions or channel settings can still suppress notifications; the app keeps session-level awaiting-permission state as the fallback discovery path.
- Manual device smoke testing was not run in this environment.

## Artifact

- Artifact: `oc-remote-1.6.29.apk`
- SHA-256: GitHub Actions release workflow uploads the signed APK artifact.
- Signature verification: GitHub Actions release workflow verifies the signed APK with `apksigner`.
