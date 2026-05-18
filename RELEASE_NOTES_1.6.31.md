# OC Remote v1.6.31 — Release Notes

## Highlights

- Hardens new chat session startup against compact OpenCode API responses that may omit fields present in older/full responses.
- Fixes the crash path seen when opening a newly created session after v1.6.30.
- v1.6.30 only added a narrower compatibility fix for missing `Session.time`; this release broadens coverage across the chat startup flow so compact session/message payloads can be handled safely.

## Tests

- `:app:testDebugUnitTest` — run for this release.
- `:app:lintDebug` — run for this release.
- `:app:assembleRelease` — run for this release when signing config is available.

## Version

- `versionName`: `1.6.31`
- `versionCode`: `43`

## Known limitations

- Manual device smoke testing is not covered by the automated release workflow.

## Artifact

- Artifact: `oc-remote-1.6.31.apk`
- SHA-256: GitHub Actions release workflow uploads the signed APK artifact.
- Signature verification: GitHub Actions release workflow verifies the signed APK with `apksigner`.
