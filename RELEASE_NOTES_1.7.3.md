# OC Remote v1.7.3 - Release Notes

## Highlights

- Stabilizes the session-list **New conversation** flow so creating a conversation from the session list no longer follows the crash path seen after v1.7.2.
- Adds a standalone **Diagnostics** page for generated app, session, and network diagnostic logs with list, selection, delete, and upload controls.
- Bundles selected generated diagnostics into a single ZIP upload after the user selects logs to upload; generated diagnostics stay app-owned and app-private until that user action.

## Tests

- `:app:testDebugUnitTest` - passed in Task 10 release preparation.
- `:app:compileDebugKotlin` - passed in Task 10 release preparation.
- `GIT_MASTER=1 git diff --check` - passed for this metadata update.

## Version

- `versionName`: `1.7.3`
- `versionCode`: `47`

## Known limitations

- Device-level manual QA was not available in this environment because `adb` is not installed; validation relies on code review, compilation, unit tests, and static checks.

## Artifact

- Artifact: `oc-remote-1.7.3.apk`
- SHA-256: GitHub Actions release workflow uploads the signed APK artifact.
- Signature verification: GitHub Actions release workflow verifies the signed APK with `apksigner`.
