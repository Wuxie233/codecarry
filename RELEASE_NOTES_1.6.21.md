# OC Remote v1.6.21 — Signed Beta Test Release Notes（签名测试版发布说明）

## Why this release exists

- `v1.6.20` validated the issue-closure code path, but its GitHub Release APK was built without release signing secrets and could be rejected by Android as an invalid package.
- `v1.6.21` rebuilds the same closure scope with release signing enabled and adds workflow guards so unsigned release APKs cannot be published silently again.

## Included validation scope

- **#10 — unread lifecycle and live command logs**: keeps the active-session cleanup guarded by matching session id, preventing stale chat lifecycle cleanup from clearing a newer visible chat.
- **#11 — release artifact versioning/signing**: requires release signing secrets, verifies APK metadata, and verifies the APK signature before upload.
- **#14 — MCP management / empty-project handling**: carries forward MCP list states, archive/restore/delete flow, and empty project visibility/opening fixes.
- **#15 — update progress**: carries forward update download progress details.
- **#16 — root project sessions**: carries forward per-project root-session visibility fixes.

## Version

- `versionName`: `1.6.21`
- `versionCode`: `34`
