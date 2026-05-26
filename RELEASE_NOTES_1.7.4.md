# OC Remote v1.7.4 - Release Notes

## Highlights

- Includes the v1.7.3 session-list **New conversation** stabilization so creating a conversation from the session list no longer follows the crash path seen after v1.7.2.
- Keeps the standalone **Diagnostics** page for generated app, session, and network diagnostic logs with list, selection, delete, and upload controls.
- Bundles selected generated diagnostics into a single ZIP upload after the user selects logs to upload; generated diagnostics stay app-owned and app-private until that user action.
- Fixes diagnostics redaction so password, bearer, token, cookie, query-secret, and `uploadToken` patterns are redacted without corrupting generated JSON content.

## Tests

- Targeted diagnostics generator and upload tests - passed for the JSON-preserving redaction fix in `e61c9a4`.
- `GIT_MASTER=1 git diff --check` - passed for this metadata update.

## Version

- `versionName`: `1.7.4`
- `versionCode`: `48`

## Known limitations

- Device-level manual QA was not available in this environment because `adb` is not installed; validation relies on code review, compilation, unit tests, and static checks.
- Release workflow execution and APK publication are pending until the `v1.7.4` tag workflow succeeds.

## Artifact

- Artifact: pending release workflow upload as `oc-remote-1.7.4.apk`.
- SHA-256: pending GitHub Actions release workflow output.
- Signature verification: pending GitHub Actions release workflow verification with `apksigner`.
