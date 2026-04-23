# Issue #15 Implementation Plan: MCP Management, Session Archive/Restore, and Empty-Project Visibility

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:tester-first-execution (recommended) or superpowers:executing-plans to implement this plan task-by-task. Use superpowers:subagent-driven-development only for high-risk work or when stricter review is explicitly wanted.

**Goal:** Make MCP management explicit and retryable, add archive/restore with archived filtering while keeping delete, and ensure opening an empty folder project always produces a visible project card instead of hanging or disappearing.

**Architecture:** Keep the three concerns separated by layer: repository/API state modeling for MCP, session mutation/state derivation for archive/restore, and project-list derivation for empty folders. UI should only render explicit states produced by ViewModels; it should not infer success from nulls or missing rows.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines/Flow, repository/ViewModel pattern, OpenCode API DTOs, Gradle/JUnit 4 unit tests, Android LSP diagnostics.

---

## Acceptance Criteria

1. MCP management loads configured MCP servers, shows a real empty state when none exist, shows explicit errors on failure, and supports refresh/retry.
2. Session management supports archive and restore, exposes archived list/filter behavior, and keeps delete unchanged.
3. Opening an empty folder project no longer hangs; it renders a visible project card in the workspace and the app remains usable.
4. Three manual scenarios are verifiable, and relevant build/type/lsp checks pass.
5. Scope stays limited to directly required nearby bugs only.

---

## File Map

### MCP management
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/ServerRepository.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpConfigParser.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpConfig.kt`
- `app/src/test/kotlin/dev/minios/ocremote/data/repository/McpConfigParserTest.kt`
- `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryTest.kt` (new if missing)
- `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelTest.kt` (new if missing)

### Archive / restore
- `app/src/main/kotlin/dev/minios/ocremote/domain/model/Session.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeader.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferences.kt`
- `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt`
- `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt` (new if missing)

### Empty-project visibility
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepository.kt`
- `app/src/main/kotlin/dev/minios/ocremote/domain/model/Project.kt`
- `app/src/test/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepositoryTest.kt`
- `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt`

---

## Task Graph

### Wave 1: backend/state foundations (parallel where files do not overlap)

- **1A → MCP loading state model and parser normalization**
- **1B → archive/restore API + reducer path**
- **1C → empty-project derivation fix**

### Wave 2: UI wiring (depends on Wave 1)

- **2A → MCP sheet explicit states + refresh/retry** depends on 1A
- **2B → archive/restore actions + archived filter surface** depends on 1B
- **2C → empty-project card visibility and post-pin refresh** depends on 1C

### Wave 3: tests + verification (depends on Waves 1–2)

- **3A → extend/add unit tests for all three flows** depends on 1A/1B/1C
- **3B → run build/type/lsp + manual scenarios** depends on 2A/2B/2C

**L1 vs L2 recommendation:**
- **L1 direct:** plan maintenance, final verification coordination, and note updates only.
- **L2 worktrees:** all code tasks above. This issue spans shared files (`SessionListScreen.kt`, `SessionListViewModel.kt`, `OpenCodeApi.kt`) and will stay cleaner if MCP, archive/restore, and empty-project work are implemented in separate L2 worktrees and then merged.

---

## Wave 1 Details

### Task 1A: MCP loading state model + parser normalization

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/ServerRepository.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpConfigParser.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpConfig.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/data/repository/McpConfigParserTest.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryTest.kt`

**Dependency:** none.

**Recommended delegation:** L2 worktree, `subagent_type=explore` for existing MCP flow inspection, then `subagent_type=tester-first-execution` for implementation/verification.

**Step 1: Write the failing tests**
- Add a parser test that proves a configured-but-empty MCP list becomes an explicit empty result, not a silent success.
- Add a parser test that proves a null/absent connection entry is surfaced as an explicit parse failure or rejected entry, not dropped silently.
- Add a repository test that proves API exceptions become an explicit error state with a retryable message.

**Step 2: Run the tests to confirm failure**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest --tests "*McpConfigParserTest*" --tests "*ServerRepositoryTest*"`
- Expected: failures on the new assertions before implementation exists.

**Step 3: Implement the minimal state normalization**
- Update the parser/model so configured MCP servers are preserved long enough to distinguish: loading, empty, parsed, and failed.
- Ensure repository mapping returns explicit empty and error states instead of null swallowing.
- Keep success payloads immutable so refresh can re-read without stale cache reuse.

**Step 4: Re-run the focused tests**
- Run the same Gradle command.
- Expected: parser/repository tests pass.

### Task 1B: archive/restore transport + reducer path

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/domain/model/Session.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt`

**Dependency:** 1A is independent; 1B can run in parallel with 1C in separate worktrees because it touches shared ViewModel files.

**Recommended delegation:** L2 worktree, `subagent_type=explore` for event/update flow tracing, `subagent_type=tester-first-execution` for the reducer and ViewModel tests.

**Step 1: Write the failing tests**
- Add an EventReducer test that starts from an active session, applies an archive mutation, and asserts the session is marked archived without being deleted.
- Add a second reducer test that applies restore and asserts the archived flag is cleared.
- Add a ViewModel test that ensures archived sessions are still available through `SessionFilter.ARCHIVED` and disappear from active filters after archive.

**Step 2: Run the tests to confirm failure**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest --tests "*EventReducerTest*" --tests "*SessionListViewModelTest*"`
- Expected: failures until archive/restore is wired.

**Step 3: Implement the minimal archive/restore path**
- Add/extend the OpenCode API request for session update/patch so archive and restore are real mutations, not client-only flags.
- Teach EventReducer to map archive/restore events onto existing `Session.time.archived` / `Session.isArchived` fields.
- Keep delete behavior intact and untouched.

**Step 4: Re-run the focused tests**
- Run the same Gradle command.
- Expected: reducer/ViewModel tests pass.

### Task 1C: empty-project derivation fix

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepository.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferences.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/domain/model/Project.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepositoryTest.kt`

**Dependency:** 1C is independent from 1A/1B but shares `SessionListViewModel.kt`, so keep it in a separate L2 worktree if running in parallel.

**Recommended delegation:** L2 worktree, `subagent_type=explore` for project-list derivation tracing, `subagent_type=tester-first-execution` for the view-model test.

**Step 1: Write the failing tests**
- Add a ViewModel test that pins an empty directory and asserts a visible project card exists even when the directory has zero sessions.
- Add a ViewModel test that confirms the `allDirectories` derivation includes `pinnedDirs` rather than only `projects + rootSessions`.
- Add a preferences repository test that confirms pinning triggers a refreshed directory list visible to the ViewModel.

**Step 2: Run the tests to confirm failure**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest --tests "*SessionListViewModelTest*" --tests "*SessionListPreferencesRepositoryTest*"`
- Expected: the empty-folder assertions fail until the union logic is fixed.

**Step 3: Implement the minimal derivation fix**
- Include `prefs.pinnedDirs` in the project directory union so empty pinned directories still produce project cards.
- Keep zero-session groups visible instead of filtering them away.
- Make refresh after pin/update explicit so the card appears without reopening the screen.

**Step 4: Re-run the focused tests**
- Run the same Gradle command.
- Expected: tests pass and the empty directory card is retained.

---

## Wave 2 Details

### Task 2A: MCP management sheet explicit states + refresh/retry

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelTest.kt`

**Dependency:** requires Task 1A.

**Recommended delegation:** L2 worktree, `subagent_type=explore` for current state-machine inspection, `subagent_type=tester-first-execution` for UI-state tests.

**Step 1: Write the failing tests**
- Add a ViewModel test for `loading -> empty` when no MCP servers are configured.
- Add a ViewModel test for `loading -> error` when repository fetch fails.
- Add a ViewModel test for `retry()` / `refresh()` causing a second load attempt.

**Step 2: Run the tests to confirm failure**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest --tests "*McpViewModelTest*"`
- Expected: the refresh/retry assertions fail before wiring.

**Step 3: Implement the minimal UI state machine**
- Represent MCP state explicitly as loading, empty, data, and error.
- Render empty and error states differently in `McpManagementSheet.kt`.
- Add visible retry/refresh actions and keep them enabled only when the state can recover.

**Step 4: Re-run the focused tests**
- Run the same Gradle command.
- Expected: MCP ViewModel tests pass.

### Task 2B: archive/restore actions + archived filter surface

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeader.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

**Dependency:** requires Task 1B.

**Recommended delegation:** L2 worktree, `subagent_type=explore` for existing action menu/filter wiring, `subagent_type=tester-first-execution` for UI-state validation.

**Step 1: Write the failing tests**
- Extend the ViewModel test to assert archived sessions appear in the archived filter and stay out of active filters.
- Add a UI-oriented state assertion if an existing screen test harness exists; otherwise keep it at the ViewModel boundary.

**Step 2: Run the tests to confirm failure**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest --tests "*SessionListViewModelTest*"`
- Expected: archive/restore UI-state assertions fail until the screen wires actions.

**Step 3: Implement the minimal UI wiring**
- Surface archive and restore actions in the existing session menu without removing delete.
- Make `SessionFilter.ARCHIVED` selectable in the visible filter order.
- Keep list counts and headers aligned with the filtered set so archived sessions are discoverable.

**Step 4: Re-run the focused tests**
- Run the same Gradle command.
- Expected: archive/restore filtering tests pass.

### Task 2C: empty-project card visibility and post-pin refresh

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepository.kt`

**Dependency:** requires Task 1C.

**Recommended delegation:** L2 worktree, `subagent_type=explore` for the open-project flow, `subagent_type=tester-first-execution` for the refresh regression.

**Step 1: Write the failing tests**
- Add a ViewModel test for the plus-button / directory-picker path that confirms an empty folder produces a visible project card.
- Add a refresh test that confirms the card stays visible after pinning and the list updates without a silent no-op.

**Step 2: Run the tests to confirm failure**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest --tests "*SessionListViewModelTest*" --tests "*SessionListPreferencesRepositoryTest*"`
- Expected: the empty-project visibility assertions fail before wiring.

**Step 3: Implement the minimal UI flow fix**
- Ensure the open-folder flow emits a project card even when the folder has no sessions yet.
- Keep the app in a normal interactive state after the card is shown; failures must be surfaced instead of swallowed.
- Trigger a visible refresh after pinning so the newly pinned empty folder appears immediately.

**Step 4: Re-run the focused tests**
- Run the same Gradle command.
- Expected: empty-project visibility tests pass.

---

## Wave 3 Details

### Task 3A: broaden unit tests for all three flows

**Files:**
- Modify: `app/src/test/kotlin/dev/minios/ocremote/data/repository/McpConfigParserTest.kt`
- Create or modify: `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryTest.kt`
- Create or modify: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelTest.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModelTest.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/data/preferences/SessionListPreferencesRepositoryTest.kt`

**Dependency:** requires Waves 1–2.

**Recommended delegation:** L2 worktree, `subagent_type=tester-first-execution` only; this wave is mostly test expansion and should be fast once the behavior is stable.

**Step 1: Write the missing regression assertions**
- Add one MCP test for empty state and one for explicit fetch failure.
- Add one archive/restore regression assertion in reducer tests.
- Add one empty-project visibility assertion that guards the pinned-folder union.

**Step 2: Run the focused tests**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest --tests "*Mcp*Test*" --tests "*EventReducerTest*" --tests "*SessionListViewModelTest*" --tests "*SessionListPreferencesRepositoryTest*"`
- Expected: all focused unit tests pass.

### Task 3B: final build, lsp, and manual verification

**Files:** none; this is verification only.

**Dependency:** requires all implementation and test tasks.

**Recommended delegation:** direct in L1 for coordination, or L2 if a device/emulator sanity pass is being run in parallel.

**Step 1: Run the full relevant Gradle checks**
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew testDebugUnitTest`
- Run: `ANDROID_HOME=/root/Android/Sdk ANDROID_SDK_ROOT=/root/Android/Sdk ./gradlew assembleDebug`
- Expected: both exit 0.

**Step 2: Run LSP diagnostics on all modified Kotlin files**
- Run `lsp_diagnostics` with severity `error` on every modified Kotlin file listed in Waves 1–2.
- Expected: zero errors.

**Step 3: Manually verify the three required scenarios**
1. MCP management: open Manage MCP, confirm configured servers load; confirm empty state when none exist; force a fetch failure and confirm the explicit error plus retry button; tap retry and confirm recovery.
2. Archive/restore: archive an existing session, switch to archived filter, confirm it appears there, restore it, confirm it returns to the normal list, and confirm delete still works.
3. Empty project: open an empty folder project from the plus-button flow, confirm a visible project card appears in the workspace, and confirm the app stays responsive for normal navigation afterward.

**Step 4: Record final evidence**
- Save the exact Gradle commands used, the LSP result summary, and the three manual scenario outcomes in the session notes or adjacent plan notes.

---

## Key Risks

1. `SessionListScreen.kt` and `SessionListViewModel.kt` are shared across all three concerns; edit order matters and conflicts are likely if the work is not split into L2 worktrees.
2. Archive/restore may need a real session update/patch path in `OpenCodeApi.kt`; if the API shape differs from assumptions, the reducer alone will not be enough.
3. The empty-project bug is easy to “half-fix” by showing an empty card once but not refreshing pinned directories; the plan explicitly requires pin-refresh coverage.
4. MCP null-connection handling can regress into silent omission if parser failures are treated as “no servers”; tests must distinguish empty from error.

---

## Out of Scope

- No new MCP protocol support beyond the configured servers already returned by the backend.
- No background polling, auto-refresh timers, or retry backoff policy changes.
- No bulk archive/delete operations beyond the existing single-session delete flow.
- No redesign of the session cards, project cards, or overall session layout.
- No changes to unrelated connection, auth, or terminal-mode flows.

---

## Verification Contract

- Gradle baseline must be run with `ANDROID_HOME=/root/Android/Sdk` and `ANDROID_SDK_ROOT=/root/Android/Sdk`.
- Required checks: focused unit tests during each task, full `./gradlew testDebugUnitTest`, full `./gradlew assembleDebug`, and `lsp_diagnostics` on modified Kotlin files.
- Completion is only valid after the three manual scenarios above are verified on a real app run.
