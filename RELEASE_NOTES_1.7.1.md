# OC Remote v1.7.1 - Release Notes

## Highlights

- Fixes the remaining new-conversation crash path by waiting for the current chat directory before creating the next session.
- Preserves project directory context when OpenCode returns compact create-session responses without a `directory` field.
- Replaces in-chat session switching with a single atomic navigation operation to avoid pop/navigate races.
- Applies the same blank-directory repair to project/session-list new conversation entry points.

## Tests

- `:app:compileDebugKotlin` - run for this release.
- `:app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.chat.ChatNewSessionFirstLoadCompactTest` - run for this release.
- `:app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.sessions.SessionListViewModelTest` - run for this release.
- `:app:testDebugUnitTest` - run for this release.

## Version

- `versionName`: `1.7.1`
- `versionCode`: `45`

## Known limitations

- Device-level manual QA was not available in this environment because `adb` is not installed; validation relies on code review, compilation, and unit tests.

## Artifact

- Artifact: `oc-remote-1.7.1.apk`
- SHA-256: GitHub Actions release workflow uploads the signed APK artifact.
- Signature verification: GitHub Actions release workflow verifies the signed APK with `apksigner`.
