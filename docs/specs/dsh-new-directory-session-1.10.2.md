# CodeCarry 1.10.2: DSH pick-directory new conversation

## Goal

Let an operator on a DSH server pick a host directory (or No Repo) from the
Sessions list, land a real conversation there, and open Chat — the same
connect-workspace path the Web GUI uses.

## Scenario

The operator opens a Ready DSH server's Sessions surface, taps +, browses the
host filesystem (or taps No Repo), optionally creates a subfolder, and is taken
into a new or reused blank conversation whose workspace membership matches the
chosen directory.

## In Scope

1. Show the Sessions + FAB on DSH. Tapping it opens the existing in-app
   directory browser (`host.listDirectory` / `host.createDirectory`), not the
   loopback-only OS picker.
2. Selecting a directory: `workspace.create(path)` (idempotent) then reuse that
   workspace's unarchived blank session, else `session.create(workspaceId)`.
   Navigate to Chat immediately. Do not leave an empty group.
3. A No Repo row at the bottom of the picker: `session.create` with no `cwd`
   and no `workspaceId` so the host attaches No Repo.
4. Existing group "new here" / Chat "new session" use the same attach path
   (workspace id, not a bare `cwd` that can land Ungrouped).
5. Directory browser parent navigation uses DSH listing crumbs, not
   string-split paths. Create-folder stays available. OpenCode-only recursive
   search stays hidden on DSH.
6. Release metadata: `versionName` `1.10.2`, `versionCode` `111`,
   `RELEASE_NOTES_1.10.2.md`.

## Non-Goals

- `session.rewrite` / swipe undo on DSH.
- Chat-side `agentPreset` picker (Host Surfaces remains the preset surface).
- `session.search` RPC for the Sessions search box.
- Recursive `@file` mentions (still one `host.listDirectory` level).
- Productizing `DshHostSurfacesScreen`.
- Loopback `host.pickDirectory` / `host.openPath`.
- OpenCode project-register / MCP / shell behavior.

## Constraints and Decisions

- Wire: `/root/CODE/deepseek-harness/packages/host/apiproxy/src/api/` —
  `session.create` accepts at most one of `workspaceId` / `cwd`; omitting both
  uses No Repo. Passing only `cwd` creates the session but does not attach a
  workspace.
- Blank sessions stay hidden from the Sessions list; reuse reads
  `DshEventReducer` snapshots, not the mapped OpenCode `Session` list.
- Reuse requires workspace membership (`sessionId` in that workspace's
  `sessionIds`), matching canonical cwd, `blank == true`, and not archived.
  Concurrent creates for the same workspace stay single-flighted in the
  ViewModel (existing `isCreatingSession` gate).
- Directory picker is the existing `OpenProjectDialog`, branched by
  `ServerType`. OpenCode keep: pin directory. DSH: create-and-open.
- User-facing copy: English + zh-rCN in Android resources; other locales fall
  back until lokit.
- Java 21 Gradle. Focused JVM tests on the create planner and SessionList
  DSH path; repository `:app:testDebugUnitTest` and `:app:assembleDebug` once
  at integration.

## Acceptance Evidence

- DSH Sessions shows + even with zero conversations. Tapping it opens the
  directory browser rooted at `host.describe.home` / `host.listDirectory()`.
- Choosing `/some/project` calls `workspace.create` then `session.create` with
  `workspaceId` (not bare `cwd`) and navigates to that session.
- Choosing a directory that already has an unarchived blank member reuses that
  session id and does not mint a second blank.
- Choosing No Repo calls `session.create` with empty payload fields and
  navigates.
- Create-folder in the picker uses `host.createDirectory` and the new folder
  becomes selectable.
- Parent/up navigation follows DSH crumbs.
- OpenCode + still pins a project; it does not start calling DSH RPCs.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes.
- `versionName` `1.10.2`, `versionCode` `111`, `RELEASE_NOTES_1.10.2.md` agree.

## Repository Facts

- FAB gated by `SessionListUiState.supportsProjectRegister` (`!isDsh` today) in
  `SessionListScreen.kt`; `onSelect` of `OpenProjectDialog` calls `pinDirectory`.
- DSH browse/create already exist on `SessionListViewModel.browseDirectories` /
  `createDirectory` via `DshApiClient.hostListDirectory` / `hostCreateDirectory`.
- `DshApiClient.sessionCreate` already accepts `workspaceId` and `cwd`.
- Blank sessions are dropped in `mapDshEventStateToSessions`.
- Chat `createNewSession` currently passes `cwd = sessionDirectory` only.

## Assumptions

- Operators who need LAN DSH already have the host in `trustedHosts`; browse
  RPCs are not loopback-locked.
- A passworded DSH URL is treated as loopback for hidden methods, but this
  delivery only uses browse methods.
- Manual GitHub Release remains after land, per `README.md`.
