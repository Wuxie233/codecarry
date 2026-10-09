# Codex projects and new-conversation parity

## Status

Product direction accepted. Native Android implementation uses the shared
project API and defers thread creation until the first submission. Deployment
and client acceptance remain separate from source and build verification.

## Accepted scope

- Change only the Codex project and new-conversation experience. Keep OpenCode
  and DSH workflows independent.
- Use a compact, Web-style project list with a top-level New conversation action,
  expandable project rows, and a direct new-conversation action on each project.
- Move search, archive, and status filtering into secondary controls. Replace
  the prominent recent-work cards and stacked control rows on this surface.
- Retain native Android interaction and accessibility rather than embedding the
  Web application or copying desktop-only interaction requirements.
- Synchronize project data in both directions with Codex Web; independent
  Android-only project management does not satisfy this requirement.

## Previous Android behavior

`CodexProjectPreferences.kt` derives projects by grouping root conversations by
`cwd`. Its persisted preferences contain collapsed, pinned, and hidden directory
sets plus the list view mode, all scoped by server ID. There is no independent
project record, explicit project name, or empty-project catalog in this model.

`CodexThreadListScreen.kt` opens `CodexDirectoryPicker` for the global create
action. Selecting a directory immediately invokes
`CodexThreadListViewModel.createThread(cwd)`, which calls `thread/start`, inserts
the returned thread into the reducer, navigates, and refreshes the catalog.

`codex-bridge/codex_bridge.py` currently forwards authenticated WebSocket text
frames to the shared daemon. It does not implement a project catalog API.

## Terms

- **Project**: A user-managed entry in the Web-style project catalog, distinct
  from an automatically inferred group of conversations sharing a directory.
- **Working directory**: The daemon-side filesystem directory used to execute a
  conversation. A directory string alone is not proof of project identity.
- **Project conversation**: A conversation associated with a project according
  to the shared catalog's actual membership rules.
- **New-conversation draft**: The pre-send composition state. Its relationship
  to creation of a daemon thread must follow the verified Web behavior.

## Shared project authority

Use the daemon project catalog, not direct edits to Web's local state file.
The current Web main-process adapter uses `project/list`, `project/create`,
`project/read`, `project/update`, `project/move`, and `project/delete` when the
daemon supports projects. Its legacy ID mapping is not the Android identity.

The read-only live probe returned 15 projects with fields `id`, `name`, `roots`,
`metadata`, `position`, `createdAt`, `updatedAt`, and `recencyAt`. The daemon
reported version `0.159.2`; `nextCursor` was null for that response. Implement
pagination regardless of that particular catalog size.

Project updates use `projectId`, optional `name`, and `roots: [{ path }]`.
Ordering uses `project/move` with `projectId` and `beforeProjectId` (null means
the end). Create uses an `idempotencyKey`. Preserve metadata owned by other
clients when editing a project's name or roots. Roots can contain multiple
directories; a single-directory-only Android model would lose Web data.

Use `(serverId, projectId)` as Android identity. Retain empty projects, explicit
names, and catalog order. Do not recreate every historical `cwd` as a project.
Keep unassigned conversations accessible separately. Resolve synchronization
through the daemon contract and refresh on re-entry/reconnect; never silently
fall back to an independent writable Android catalog on unsupported servers.

## New conversation

The project-row action prepares a project-scoped draft and navigates to the
composer. It does not call `thread/start`. Submission of the first message
creates the conversation and records its project assignment. The global action
also enters composition rather than forcing directory selection immediately.
It retains the selected project context where applicable; do not redefine it
as always projectless. For a project with multiple roots, Web uses the first
root as `cwd` and carries the complete list as workspace roots.

Android needs a draft entry route independent of a constructor-bound thread ID.
Retain selected project, text, and composer choices before creation; keep binary
attachments in memory under the existing attachment policy. The returned thread
ID becomes the conversation owner after creation. Do not lose a draft or replay
an uncertain first submission if connection recovery occurs between creation and
the first turn.

Project removal is distinct from conversation archive or delete. Web removes the
project assignment and keeps its former conversations accessible as unassigned;
it does not delete conversations or disk files. Its native project deletion is
delayed for an undo window. Preserve that user-visible distinction.

## Thread membership and synchronization

The locally generated experimental schema for Codex 0.159.2 confirms:

- `thread/start.projectId` assigns a new thread. Durable threads persist this
  identity; ephemeral threads expose it only in live responses.
- `thread/metadata/update.projectId` uses omission to leave membership unchanged,
  an empty string to clear it, and an existing ID to assign it.
- `thread/list.projectId` uses omission for all projects, explicit null for
  unassigned threads, and an ID for one project.
- `project/changed {projectId, changeType}` reports created/updated/deleted;
  `thread/project/updated {threadId, projectId}` reports assignment changes.

Generation-fence catalog reads and writes, and handle these notifications
without overwriting newer membership with an older snapshot. Changing project
roots must not implicitly move historical conversations between projects.

Web still has a legacy `thread-project-assignments` adapter. Native schema support
alone does not prove that every Web entry point observes Android assignments.
Verify the two-client behavior during implementation and resolve any adapter
mismatch; matching names or directories is not sufficient acceptance evidence.
Presentation preferences such as expansion are not proven to be part of the
shared project catalog and remain client-local unless further evidence requires
otherwise.

Web's projectless creation uses its `projectless-thread-cwd` main-process helper
to prepare a dedicated workspace. Before implementing that entry point, resolve
its configured workspace root and filesystem behavior; do not substitute `/`
or hard-code a developer machine's home directory. Project removal undo must
coordinate delayed deletion, rather than assuming an immediate native delete
can be undone with the same project ID.

## Source evidence

The reference is the deployed Codex Web desktop bundle inspected on 2026-10-09.
These are bundle-relative references, not new application dependencies:

- `.vite/build/main-C5K7o1Hr.js`: `vOe` project adapter (line 118022), native
  CRUD/order calls (118130–118239), paged list (118335), and `removeLocal`
  (118892) preserve the distinction between a project and its conversations.
- `webview/assets/app-initial-236e1501144c.js`: project schema (118926),
  `P0` draft navigation (387801), project-row action `l8o` (445952), and
  first-send path `GPi` (290015).
- `codex app-server generate-json-schema --experimental --out <temporary-dir>`:
  `ThreadStartParams`, `ThreadMetadataUpdateParams`, `ThreadListParams`,
  `ProjectChangedNotification`, and `ThreadProjectUpdatedNotification`.

Use these contracts to implement native components; do not copy generated Web
bundles into the Android application.

## Verification boundaries

Use existing focused checks and direct behavior verification. Verify an empty
project, project editing from each client, creation from both entry points,
leaving an unsent draft, archive behavior, reconnect, and server isolation as
applicable to the verified contract. Source/build results and actual two-client
behavior are separate evidence; neither establishes physical-device acceptance.

Preserve existing archive receipt/undo safeguards, subagent topology, generation
fencing, and reconnect history behavior. Never reinterpret removal of a project
as authorization to delete its filesystem contents.
