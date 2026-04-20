# OC Remote v1.6.16 — Release Notes

## Session list

- **Hide projects**: projects can now be hidden from the session list via the project action menu (⋮). Hidden projects disappear from the list; a "N hidden" badge appears in the controls area and can be tapped to toggle their visibility. Hidden state is persisted across restarts.

## Bug fixes

- **Chinese (non-ASCII) project paths**: sending a message in a project whose directory contains Chinese or other non-ASCII characters no longer fails with an error. The `x-opencode-directory` header is now URL-encoded, matching the official OpenCode SDK behaviour.

## Version

- `versionName`: `1.6.16`
- `versionCode`: `29`
