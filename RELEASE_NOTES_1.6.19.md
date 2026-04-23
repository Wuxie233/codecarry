# OC Remote v1.6.19 — Release Notes

## New features

- **Explicit MCP management states**: the MCP management sheet now shows loading, empty, and error states with refresh/retry actions, making broken or empty MCP configs much easier to diagnose and recover from (closes #15).
- **Session archive and restore**: sessions can now be archived without deleting them and restored later from the archived filter, making it easier to keep the active list clean without losing history (closes #15).

## Bug fixes

- **Empty pinned projects**: pinned empty folders now stay visible as project cards instead of disappearing or leaving the session list looking stuck (closes #15).
- **Session list refresh and ordering**: project rows now refresh more reliably after pin/archive actions, and session ordering stays aligned with the latest timestamps (closes #15).
- **Release publishing reliability**: the release workflow now validates APK metadata and resolves the real output filename before uploading assets, reducing release publishing failures and mismatched artifacts (refs #11).

## Version

- `versionName`: `1.6.19`
- `versionCode`: `32`
