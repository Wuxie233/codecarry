# OC Remote 1.7.41

Keep parent conversations visibly active while child subagents are working, including after reconnecting to project-scoped sessions.

## Fixes

- Apply child subagent activity to the parent row, project active count, and top active conversations list from one derived status.
- Merge global and project-scoped session status snapshots so reconnects do not clear busy child sessions that are absent from the global endpoint.
- Preserve existing status state when a project snapshot request fails instead of reconciling an incomplete snapshot.
- Restore horizontal drag for KaTeX-backed markdown blocks.

## Verification

- Passed targeted parent activity and project status snapshot regression tests.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- Confirmed the real OpenCode status endpoint returns project-scoped busy sessions that are absent from the global snapshot.
