# OC Remote v1.6.10 — Release Notes

## Critical bugfix

- **Session list empty**: Fixed a regression where the session list always appeared empty. The v1.6.8 redesign fetched sessions per-project using a server-side directory filter that is not supported by all OpenCode versions, causing zero sessions to be returned. Sessions are now fetched all at once and grouped client-side instead.

## Version

- `versionName`: `1.6.10`
- `versionCode`: `23`
