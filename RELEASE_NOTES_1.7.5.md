# OC Remote v1.7.5 - Release Notes

## Highlights

- Fixed a session-list **New conversation** crash caused by duplicate `session.created` state after optimistic creation.
- Persisted create-new diagnostics breadcrumbs (`tapped`, `success`, `failure`) so future uploads include better evidence for session creation failures.

## Tests

- Includes the session duplicate-create crash fix and diagnostics breadcrumb tests.
- Focused verification passed: `EventReducerTest` and `SessionListViewModelTest`.
- Full verification passed: `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, and `git diff --check`.

## Version

- `versionName`: `1.7.5`
- `versionCode`: `49`

## Known limitations

- Device-level manual QA was not repeated for this release note update.

## Artifact

- Artifact: pending release workflow upload as `oc-remote-1.7.5.apk`.
- SHA-256: pending GitHub Actions release workflow output.
- Signature verification: pending GitHub Actions release workflow verification with `apksigner`.
