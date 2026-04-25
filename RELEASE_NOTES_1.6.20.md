# OC Remote v1.6.20 — Beta Test Release Notes（测试版发布说明）

## Beta release scope

- This is a beta/test release build for closing and validating #10, #11, #14, #15, and #16.
- Android metadata is bumped to `versionName` `1.6.20` and `versionCode` `33`.

## Issue closure and validation notes

- **#10 — unread lifecycle and live command logs**: unread main-session handling is included, expanded command/tool cards can show live execution logs, and the chat lifecycle clears the active session by matching session id so leaving an old chat cannot clear a newer visible chat.
- **#11 — release artifact versioning**: manual release workflow hardening is included for beta validation, including building from the requested tag and checking APK metadata before publishing.
- **#14 — MCP management / empty-project handling**: MCP list states, session archiving, and empty project visibility/opening changes are included for regression validation.
- **#15 — update progress and management UX**: update downloads now surface progress details, with the related management-state fixes included in this beta test build.
- **#16 — root project sessions**: per-project session loading keeps root-project sessions visible so projects with existing root sessions do not appear empty.

## Version

- `versionName`: `1.6.20`
- `versionCode`: `33`
