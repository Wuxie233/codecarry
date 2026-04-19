# OC Remote v1.6.11 — Release Notes

## Critical bugfix (the real one this time)

Previously a persisted session-list filter (e.g. "Working" or "Has errors") could leave the session list permanently empty even after upgrading the app. The filter value was stored in DataStore and survived app restarts, so every session was filtered out on startup.

Changes:
- Session list filter is now in-memory only; it resets to "All" every time the session list screen is opened.
- On first launch after upgrade, any persisted bad filter value is cleared automatically.
- Creating a new session also resets the filter to "All" so the new session is always visible.
- Service pre-load now fetches all sessions in one request, matching the fix already applied to the session list view.

## Version

- `versionName`: `1.6.11`
- `versionCode`: `24`

## Notes

- Same temporary test signing key as v1.6.8/1.6.9/1.6.10. Direct upgrade is fine.
