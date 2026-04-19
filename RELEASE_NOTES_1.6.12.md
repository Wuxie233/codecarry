# OC Remote v1.6.12 — Release Notes

## Session loading fixed (the missing children)

- The session list previously fetched only root sessions (`?roots=true`), so every subagent child session was invisible. Verified against the live server: 43 root sessions vs 94 child sessions — most of which were not reachable from the UI.
- Now the app pulls all root sessions in one call, and additionally fetches each project's full session tree (root + children) in parallel. Root sessions and their subagents are merged into the local store.
- Subagent children appear inside their parent project group (expandable from the parent card) and in the Active Subagents banner when busy.

## Chat picker UX

- The agent selector and thinking-intensity selector now open a list-style picker (same dialog pattern as the model picker), instead of cycling through values on each tap.
- Selecting an agent or variant from the picker is persisted in the per-session draft just like before.

## Localization

- All session list and picker strings introduced since v1.6.8 now have proper Simplified Chinese translations (zh-CN).

## Version

- `versionName`: `1.6.12`
- `versionCode`: `25`

## Notes

- Same temporary test signing key as the previous test releases. Direct upgrade is fine.
