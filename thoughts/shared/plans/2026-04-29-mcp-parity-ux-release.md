---
date: 2026-04-29
topic: "APK MCP Visibility Parity, UX Optimization, and Release"
issue: 19
scope: app
contract: none
---

# APK MCP Visibility Parity, UX Optimization, and Release Implementation Plan

**Goal:** Restore MCP server parity between APK and web UI for issue #19, then harden MCP-specific UX, apply low-risk app-wide UX polish, and ship a verified release-ready build.

**Architecture:** Single Activity Compose Navigation, Material 3, Hilt, DataStore, OpenCode REST/SSE. Vertical slice through MCP repository → ViewModel → Sheet first; expand `OpenCodeApi` MCP-related calls to mirror per-project `x-opencode-directory` semantics already used by other endpoints. Add `~/.config/opencode/opencode.json` to the candidate list to match the OpenCode server's own resolution policy. Surface server-supplied command source ("command" / "mcp" / "skill") in the chat slash picker. UX work confined to existing screens and shared primitives — no new navigation, no rewrite of `ChatScreen.kt` or `SessionListScreen.kt` shells. Release work limited to versionName/Code bump + release notes + verified build artifact; signed publishing is gated on user-supplied credentials.

**Design:** [thoughts/shared/designs/2026-04-29-mcp-parity-ux-release-design.md](../designs/2026-04-29-mcp-parity-ux-release-design.md)

**Contract:** none (single-domain Android/Kotlin plan; no parallel frontend/backend implementers)

**Hard gate (release publishing):** `app/keystore/signing.properties` does not exist in this worktree. Release-signed APK requires either the user dropping that file in or executor running an interactive flow with the user. Plan stops at "verified release-ready unsigned + debug-signed artifact + draft release notes" if no signing credentials are provided. Tasks in Batch 4 explicitly call this out and do NOT push or publish anything to upstream.

---

## Dependency Graph

```
Batch 1 (parallel - MCP functional fix): 1.1, 1.2, 1.3, 1.4 [no deps; foundational data + repo wiring]
Batch 2 (parallel - MCP UX diagnostics): 2.1, 2.2, 2.3 [depends on Batch 1]
Batch 3 (parallel - safe app-wide UX polish): 3.1, 3.2, 3.3, 3.4 [depends on Batch 2]
Batch 4 (sequential - release verification): 4.1 then 4.2 then 4.3 [depends on Batch 3]
```

Strict phase ordering is enforced by the executor: do NOT start Batch N until Batch N-1's reviewer cycles have all passed.

---

## Batch 1: MCP Functional Fix (parallel - 4 implementers)

All tasks in this batch have NO dependencies on each other and run simultaneously.
Tasks: 1.1, 1.2, 1.3, 1.4

This batch restores read parity between APK and web UI. The fix is split across four files: API layer (directory header + path semantics), repository (path candidate set + project directory propagation), ViewModel (no behaviour change required, only test updates against new repository contract), and config parser (defensive widening for `opencode.json` aliases the server treats as equivalent).

### Task 1.1: Pass project `directory` header on MCP-related file/path API calls

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiMcpHeaderTest.kt`
**Depends:** none
**Domain:** general

**Why this is the fix:** Other endpoints (`listSessions`, `findFiles`, `listDirectory`, `createSession`, etc.) accept a `directory: String? = null` parameter and forward it as `x-opencode-directory`. The OpenCode server uses this header to bind the request to a specific project worktree. Currently `getServerPaths`, `readFile`, `readFileText`, and `writeFile` do NOT accept a directory parameter, so they always resolve against the server's CWD-derived project. Web UI scopes its config reads to the project the user has open; APK does not. This is the most likely root cause of "web sees MCP, APK does not" for projects that are not the server's CWD.

**Implementation guidance:**
- Add `directory: String? = null` parameter (last optional, before defaults) to: `getServerPaths`, `readFile`, `readFileText`, `writeFile`.
- Forward via the existing pattern: `directory?.let { header("x-opencode-directory", android.net.Uri.encode(it)) }`.
- Do NOT change call sites in this task; Task 1.2 wires the new parameters through. Existing callers compile unchanged because the new parameters are optional with default `null`.

**Test (write first, must FAIL before implementation):**
- Spin a `MockEngine` with one route per affected method.
- Assert that when `directory = "/workspace/my-project"` is supplied, the captured request contains a header `x-opencode-directory` whose value equals `Uri.encode("/workspace/my-project")`.
- Assert that when `directory = null`, the header is absent.
- Cover all four methods: `getServerPaths`, `readFile`, `readFileText`, `writeFile`.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.api.OpenCodeApiMcpHeaderTest"
./gradlew :app:compileDebugKotlin
```

**Commit:** `fix(mcp): forward project directory header on file/path API calls`

---

### Task 1.2: Resolve MCP config against the project directory and widen candidate paths

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/ServerRepository.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryTest.kt` (extend existing)
**Depends:** 1.1
**Domain:** general

**Why this is the fix:** The repository today only checks three paths and never passes the project directory header to the API. Two concrete bugs:
1. The candidate list lacks `<home>/.config/opencode/opencode.json`. The OpenCode server treats `opencode.json` as the canonical filename and `config.json` as a legacy alias. Web UI follows this; APK currently only probes `config.json` at home.
2. `api.getServerPaths(conn)` and `api.readFileText(conn, path)` are called without `directory = projectDir`, so the server may return a different home or refuse paths it cannot map to the active project.

**Implementation guidance:**
- Pass `directory = projectDir.takeIf { it.isNotBlank() }` to both `api.getServerPaths(...)` and the per-candidate `api.readFileText(...)` calls inside `readMcpConfigState`.
- Pass `directory = config.filePath.substringBeforeLast('/').takeIf { it.isNotBlank() }` to `api.writeFile(...)` inside `writeMcpConfig`. (Use the directory the config lives in; the server treats this as the project context.)
- Extend the candidate list IN ORDER:
  1. `<projectDir>/.opencode/opencode.json`  *(new — primary project-level)*
  2. `<projectDir>/.opencode/config.json`    *(existing — legacy)*
  3. `<projectDir>/opencode.json`            *(existing — root-level)*
  4. `<homeDir>/.config/opencode/opencode.json`  *(new — primary global)*
  5. `<homeDir>/.config/opencode/config.json`    *(existing — legacy global)*
- Do NOT short-circuit on the first `OpenCodeFileNotFoundException` — keep iterating, the existing `for` loop already does this. Just confirm the new candidates are appended in priority order.
- Keep the rest of `resolveMcpConfigLoadState` untouched; its behaviour is correct given the new candidate list.

**Test (extend existing `ServerRepositoryTest.kt`):**
- New test: `resolveMcpConfigLoadStateProbesAllFiveCandidatesInPriorityOrder` — feed five `McpConfigCandidateRead`s where the first three are `OpenCodeFileNotFoundException` and the fourth has valid JSON; assert `Loaded` and that `filePath` equals candidate #4.
- New test: `resolveMcpConfigLoadStateReturnsNotFoundWhenAllFiveAreMissing` — assert `NotFound` with `checkedPaths.size == 5`.
- New test: `resolveMcpConfigLoadStateReturnsErrorOnPermissionFailure` — feed an `OpenCodeFileReadException` (status 403) at position 2; assert `Error` with that path.
- For directory-header propagation, add an integration-style test in the new `OpenCodeApiMcpHeaderTest` (Task 1.1) — do NOT mock the repository's API call here, that's covered by 1.1.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.ServerRepositoryTest"
./gradlew :app:compileDebugKotlin
```

**Commit:** `fix(mcp): probe opencode.json variants and scope MCP read to project directory`

---

### Task 1.3: Defensive parser for `mcp` alias key and stdio default `enabled`

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpConfigParser.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/McpConfigParserTest.kt` (extend existing)
**Depends:** none
**Domain:** general

**Why this is the fix (sr engineering call):** The OpenCode server config schema has evolved. Newer configs use the top-level key `mcp` instead of `mcpServers`, and treat servers as `enabled = true` when the field is absent or the server uses `type: "remote"` without an explicit boolean. The current parser only recognises `mcpServers`, so a project whose config has migrated to the newer schema reports empty even though servers are clearly declared.

Design says "Empty config: explain that config exists but contains no MCP servers" — but only after we've actually looked under the right keys. This is a parser correctness fix, not a UX fix.

**Implementation guidance:**
- In `parseState`, look up `mcpServers` first; if absent, fall back to `mcp`. Treat both the same way.
- Accept entries with NO `command` field if `type == "remote"` (remote MCP servers are URL-based, not command-based) — record `command = null` and keep them. Today such an entry returns `Error`; that is wrong.
- Keep the existing `enabled` default of `true` for absent field.
- In `serialize`, write back to whichever key (`mcpServers` or `mcp`) was originally present in `rawJson`. If neither, default to `mcpServers` (preserves backward compat).
- Do not break existing tests; their `mcpServers`-keyed JSON must continue to pass.

**Test (extend existing):**
- `parseStateAcceptsTopLevelMcpKey` — JSON has `{"mcp": {"x": {"command": "node"}}}`, expect `Loaded` with one server named `x`.
- `parseStateAcceptsRemoteServerWithoutCommand` — JSON has `{"mcpServers": {"r": {"type": "remote", "url": "https://example"}}}`, expect `Loaded` with `command = null` for `r`.
- `serializePreservesMcpAliasKey` — round-trip a config originally keyed under `mcp`; assert output still contains `"mcp"` key (not `"mcpServers"`).
- `parseStateStillRejectsServerEntryThatIsNotJsonObject` — keep existing behaviour (regression guard).

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.McpConfigParserTest"
```

**Commit:** `fix(mcp): support mcp alias key and remote server entries`

---

### Task 1.4: Plumb `projectDir` through ViewModel and update test coverage

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelTest.kt` (extend existing)
**Depends:** 1.2
**Domain:** general

**Why this exists in Batch 1:** `McpStateController.load(conn, projectDir)` already takes `projectDir`, but the file is small and the test must be updated to mirror the wider repository contract introduced by 1.2 (more candidate paths, project directory header semantics). The ViewModel itself needs only minor changes — keep `lastLoaded` separate from the in-flight `Saving` state (already done), but ensure that on `refresh()` after a save-failure, the previous `editedServers` state is preserved if the new load succeeds with the SAME server keys. This avoids silently dropping the user's unsaved edits when they retry after a transient network blip.

**Implementation guidance:**
- Add a field `private var pendingEdits: Map<String, McpServer>? = null` on the controller.
- In `loadCurrentConfig`, after a successful `Loaded` mapping, if `pendingEdits != null` and its keys are a subset of `loadState.config.servers.keys`, merge the pending toggles into the new `editedServers` and set `dirty = true`. Otherwise set `pendingEdits = null` and reset to clean `Loaded`.
- In `toggleServer`, set `pendingEdits = newState.editedServers`.
- In the `save() onSuccess` branch, set `pendingEdits = null`.
- In the `save() onFailure` branch, leave `pendingEdits` intact so that a subsequent successful `refresh` reapplies them.
- No public API change — `load`, `refresh`, `retry`, `toggleServer`, `save`, `canReload`, `hasReloadContext` all keep their signatures.

**Test (extend existing):**
- `refreshAfterSaveFailurePreservesUnsavedEdits` — load a config with two servers both enabled, toggle one off, simulate `save` failure, then `refresh` succeeds with same two servers; assert state is `Loaded` with `dirty = true` and the toggled server still disabled in `editedServers`.
- `refreshAfterServerKeysChangeDropsStaleEdits` — load with servers `{a, b}`, toggle `b`, simulate refresh that returns `{a, c}`; assert state is clean `Loaded` (no `b` to preserve), `dirty = false`, `editedServers == config.servers`.
- Keep all four existing tests green.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.McpViewModelTest"
```

**Commit:** `fix(mcp): preserve unsaved server toggles across refresh after save failure`

---

## Batch 2: MCP UX Diagnostics (parallel - 3 implementers)

All tasks in this batch depend on Batch 1 completing and reviewer-approved.
Tasks: 2.1, 2.2, 2.3

This batch makes the MCP sheet diagnostic enough that users do not see a misleading "暂无 MCP 服务器" when the real cause is path / permission / parse / network. It also hardens command surface visibility for MCP-sourced server commands.

### Task 2.1: Split MCP UI state into typed diagnostic categories

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelTest.kt` (extend)
**Depends:** 1.2, 1.4
**Domain:** general

**Why:** Today `McpUiState.Empty(title, message)` is used for both "config exists with no servers" AND "no config file found". The design (lines 70-77, 122-130) explicitly requires SEPARATE states so the sheet can render different copy and different actions per case. Lumping them together is what causes the misleading "暂无 MCP 服务器" message users see when the real issue is missing config / parse / network.

**Implementation guidance:**
- Replace the single `McpUiState.Empty(title, message)` with five concrete subtypes plus the existing `Loading`, `Loaded`, `Saving`, `SaveSuccess`:
  - `data class EmptyConfig(val filePath: String) : McpUiState()` — config file found, but no MCP servers declared
  - `data class MissingConfig(val checkedPaths: List<String>) : McpUiState()` — no config file found at any candidate
  - `data class ReadError(val filePath: String?, val message: String) : McpUiState()` — permission/network/IO failure (preserves last known good state in `lastLoaded` if present)
  - `data class ParseError(val filePath: String, val message: String) : McpUiState()` — config found but cannot be parsed
  - Keep the legacy `Error(message)` removed in favour of the typed subtypes.
- Update `McpStateController.loadCurrentConfig` to map repository states one-to-one:
  - `McpConfigLoadState.Loaded` → `Loaded`
  - `McpConfigLoadState.Empty` → `EmptyConfig(filePath = state.config.filePath)`
  - `McpConfigLoadState.NotFound` → `MissingConfig(checkedPaths = state.checkedPaths)`
  - `McpConfigLoadState.Error` whose `cause` is an `OpenCodeFileReadException` or general I/O → `ReadError`
  - `McpConfigLoadState.Error` whose `cause` is a `SerializationException` (or whose message indicates parse failure) → `ParseError`
  - On `ReadError`, do NOT clear `lastLoaded` — the design (line 130) says preserve last known good state on network failure.
- Public ViewModel API unchanged.

**Test:**
- `loadMapsEmptyConfigToEmptyConfigStateNotEmptyGeneric`
- `loadMapsNotFoundToMissingConfigState`
- `loadMapsParseFailureToParseErrorState`
- `loadMapsReadFailurePreservesLastLoaded` — first load Loaded with one server; second refresh fails with ReadError; assert `state` is `ReadError` and `lastLoaded` still has the server (verifiable by toggling — `toggleServer` should still apply against the cached state).

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.McpViewModelTest"
```

**Commit:** `feat(mcp): split UI state into diagnostic-specific categories`

---

### Task 2.2: Render diagnostic states and always-visible refresh in MCP sheet

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt`
**Test:** none (Compose-only UI; verified via screenshots in task 4.1 and lint)
**Depends:** 2.1
**Domain:** general

**Why:** Design lines 70-77 require visible refresh and diagnostic-specific copy. Today the refresh button is buried inside the empty branch and absent from error / loading / loaded states. The Loaded branch hides refresh when `dirty = true`, which traps users with a stale list and unsaved toggles.

**Implementation guidance:**
- In the existing `when (val current = state)` block, replace the `is McpUiState.Empty` arm with four new arms:
  - `is McpUiState.EmptyConfig` — title "暂无 MCP 服务器", body "已找到配置 ${current.filePath}，但其中未声明任何 MCP 服务器。"
  - `is McpUiState.MissingConfig` — title "未找到 MCP 配置", body "已检查以下路径：${current.checkedPaths.joinToString("\n• ", prefix = "• ")}"
  - `is McpUiState.ReadError` — title "无法读取 MCP 配置", body `current.message`, render in `colorScheme.error`, action: "重试" + "关闭"
  - `is McpUiState.ParseError` — title "MCP 配置解析失败", body `${current.filePath}\n${current.message}`, render in `colorScheme.error`, action: "重试" + "关闭"
- Add a small header row ABOVE the state-specific content with a persistent IconButton(refresh) that calls `viewModel.refresh()` when `viewModel.canReload()`. This makes refresh visible across all states (including `Loaded` + `dirty`).
- In the `Loaded` branch, when `dirty = true`, refresh should still be enabled but show a confirm step: short Snackbar / inline warning "将丢失未保存的修改" with "继续刷新" / "取消" buttons. Implement using a `var pendingRefreshConfirm by remember { mutableStateOf(false) }` and an inline `AlertDialog` when `true`. Do NOT silently discard pending edits.
- Keep the save / cancel button pair, the saveError display, and the `LazyColumn` of `McpServerRow` items unchanged in semantics.
- Continue to pass `enabled = !isSaving` to all action buttons during `Saving`.

**Verify:**
```
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
```

Manual smoke test (executor records device-or-emulator screenshots in `screenshots/` for the four new states; deferred to Batch 4.1 where the full screenshot pass runs):
- EmptyConfig, MissingConfig, ReadError, ParseError, Loaded with refresh-while-dirty confirm dialog.

**Commit:** `feat(mcp): show diagnostic states and persistent refresh in management sheet`

---

### Task 2.3: Surface MCP-sourced commands in the chat slash picker with source labels

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/SlashCommandMergeTest.kt`
**Depends:** 1.1 (header propagation in case future server command lookup gains directory scope)
**Domain:** general

**Why:** Design lines 80-82 state "MCP commands should be exposed consistently with other commands". The current merge in `ChatScreen.kt` lines 6259-6266 merges server commands but discards `CommandInfo.source` (drops it on conversion to `SlashCommand`), so the picker shows MCP commands indistinguishably from project / built-in commands. Users cannot tell which commands come from which server, and an MCP command load failure has no visible signal.

**Implementation guidance:**
- Extend `private data class SlashCommand` (line 259) with a `source: String? = null` field carrying the original `CommandInfo.source` (`"command"`, `"mcp"`, `"skill"`, or `null` for client-side commands).
- In the merge block (around line 6259), preserve the source: replace `.map { SlashCommand(it.name, it.description, "server") }` with code that maps each `CommandInfo` to a `SlashCommand` whose `source` field equals `it.source` and whose existing `type` field stays `"server"` (kept for backwards compat with the action dispatcher).
- In the `filteredCommands` rendering (around line 6276 and downward — locate the row that renders each `SlashCommand`), add a subtle trailing `AssistChip` or text label whose copy depends on `source`:
  - `"mcp"` → label "MCP"
  - `"skill"` → label "Skill" (today these are filtered out of the picker; KEEP that filter — design says command surface should "degrade gracefully", not surface skills which are not commands)
  - `"command"` or `null` → no label (default, avoid clutter)
- When `commands.isEmpty()` AND `text.startsWith("/")` AND the chat session is connected, do NOT render a misleading "no commands" state — design says graceful degradation. The existing fallback to built-in client commands already handles this; no change needed beyond verifying with a test.

**Test (new file `SlashCommandMergeTest.kt`):**
- This is a pure-Kotlin unit test that exercises the merge logic. Extract the merge into a small `internal` top-level function `mergeSlashCommands(client: List<SlashCommand>, server: List<CommandInfo>): List<SlashCommand>` so it is testable without Compose.
- `mergeSurfacesMcpSourceLabel` — two server commands, one with `source = "mcp"`, one with `source = "command"`; assert the resulting `SlashCommand.source` fields match.
- `mergeFiltersOutSkillSource` — server command with `source = "skill"` is excluded.
- `mergeDeduplicatesByName` — server command with same name as a client command is dropped (existing behaviour).
- `mergeWithEmptyServerListReturnsClientOnly` — graceful degradation.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.SlashCommandMergeTest"
./gradlew :app:lintDebug
```

**Commit:** `feat(chat): label MCP-sourced slash commands in the picker`

---

## Batch 3: Safe App-Wide UX Polish (parallel - 4 implementers)

All tasks in this batch depend on Batch 2 completing and reviewer-approved.
Tasks: 3.1, 3.2, 3.3, 3.4

These are deliberately small, additive UX improvements — no rewrites of `ChatScreen.kt` (~6300 lines) or `SessionListScreen.kt`. Each task touches one file and is independently revertable.

### Task 3.1: Extract shared loading / empty / error state cards

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/components/StateCards.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/components/StateCardsTest.kt`
**Depends:** 2.2
**Domain:** general

**Why:** Design "Shared UX Components" (lines 84-90) calls for reusable state cards. The MCP sheet, session list empty state, and chat error banners all build similar boxes inline. Extracting them once into a small primitive makes future UX consistent and lets MCP sheet (task 2.2) be revisited for visual polish without further state machine churn.

**Implementation guidance:**
- Create a new file `StateCards.kt` exporting three Composables:
  - `LoadingStateCard(modifier: Modifier = Modifier, label: String? = null)` — centered `CircularProgressIndicator` with optional label below
  - `EmptyStateCard(title: String, message: String, action: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier)` — title + body + optional trailing action slot
  - `ErrorStateCard(title: String, message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier)` — error-coloured title, body, optional Retry button
- Use Material 3 `Surface` with `tonalElevation = 1.dp` for non-AMOLED, transparent for AMOLED (read `rememberIsAmoledTheme()` like `ProjectGroupHeader` does).
- Do NOT migrate existing call sites in this task. That is a follow-up task (3.2 migrates MCP sheet only as a representative consumer).
- Keep dependencies minimal — only Material 3, no new third-party libs.

**Test:** Pure structural test using `androidx.compose.ui.test.junit4.createComposeRule` (already in test deps). Verify each Composable renders the expected text. If `composeRule` setup is heavy, fall back to a smoke test that confirms the file compiles and the Composables exist (kotlin-reflect on the file's exported symbols). Reviewer's call which approach.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.components.StateCardsTest"
./gradlew :app:lintDebug
```

**Commit:** `feat(ui): add shared loading/empty/error state cards`

---

### Task 3.2: Migrate MCP sheet diagnostic states to shared StateCards

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt`
**Test:** none (visual)
**Depends:** 3.1
**Domain:** general

**Why:** Validates that the shared cards (3.1) handle all four MCP diagnostic shapes without bespoke layout code. If the shared cards are insufficient, surface that gap before propagating elsewhere.

**Implementation guidance:**
- Replace the bespoke `Box(...) { CircularProgressIndicator() }` for `Loading` with `LoadingStateCard(label = "正在加载 MCP 配置")`.
- Replace each diagnostic branch (`EmptyConfig`, `MissingConfig`, `ReadError`, `ParseError`) with the corresponding `EmptyStateCard` / `ErrorStateCard`. The action slot of `EmptyStateCard` carries the existing "刷新" / "关闭" buttons.
- Keep the `Loaded` and `Saving` branches unchanged — those are not generic state cards, they have domain-specific layout.
- Visually verify dark mode and AMOLED do not regress.

**Verify:**
```
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
```

**Commit:** `refactor(mcp): use shared StateCards for diagnostic branches`

---

### Task 3.3: Session list — add MCP-status hint to project group menu

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeader.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeaderMcpHintTest.kt`
**Depends:** 2.1
**Domain:** general

**Why:** Today the "管理 MCP" entry in the project group's overflow menu has no indicator of whether the project actually has MCP servers. After Batch 1 fixes parity, users still need a low-effort signal. Design line 85-90 ("compact list rows and badges", "consistent spacing"). This is the smallest possible session-list improvement that depends on Batch 1+2 working.

**Implementation guidance:**
- Add an optional parameter `mcpServerCount: Int? = null` to `ProjectGroupHeader` (line 51-69 of the existing file). Default `null` means "unknown / not loaded yet" — render no badge.
- When `mcpServerCount != null && mcpServerCount > 0`, show a small numeric badge to the right of the "管理 MCP" menu entry (around line 281, inside the `onManageMcp?.let { action -> ... }` block).
- When `mcpServerCount == 0`, show a muted "未启用" subtitle on the same menu entry.
- Wiring at the call site in `SessionListScreen.kt` is in this same task: thread the count in from `mcpViewModel.state.value` IF it is `Loaded` for the active project. If it is `Loading` / any diagnostic state / a different project, pass `null`.
- Keep the change strictly additive — no existing parameter renames, no refactor of the menu DSL.

**Test:** Pure logic test on a small helper `internal fun mcpHintLabel(count: Int?): String?` that you extract for testability. Cover `null` → null, `0` → "未启用", `1` → "1", `42` → "42".

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.components.ProjectGroupHeaderMcpHintTest"
./gradlew :app:lintDebug
```

**Commit:** `feat(sessions): show MCP server count hint in project group menu`

---

### Task 3.4: Chat input — improve slash command picker contrast in AMOLED

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
**Test:** none (visual)
**Depends:** 2.3
**Domain:** general

**Why:** Design line 90 mentions consistent dark/AMOLED treatment. The current slash picker (around line 6276 onward) renders on a translucent surface that disappears on pure-black AMOLED screens. Quickest safe fix: apply a 1dp outline or a tonal background that respects `rememberIsAmoledTheme()`. This is the only chat-screen UX improvement in this batch — broader rewrites are out of scope for this plan.

**Implementation guidance:**
- Locate the slash command suggestions container (the rendering of `filteredCommands`, around lines 6280-6320).
- Wrap the surface with `if (isAmoled) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), RoundedCornerShape(12.dp)) else Modifier`. Match the pattern already used in `SessionListScreen.kt` for AMOLED outlines.
- Ensure the source-label chip introduced in Task 2.3 has sufficient contrast in AMOLED — set its container color to `MaterialTheme.colorScheme.surfaceVariant` (not `surface`) so it does not blend into pure black.
- Do NOT touch any other ChatScreen behaviour.

**Verify:**
```
./gradlew :app:lintDebug
./gradlew :app:compileDebugKotlin
```

Manual: open chat in AMOLED mode, type `/`, screenshot in `screenshots/` (filename `slash-picker-amoled-after.png`) for inclusion in release notes.

**Commit:** `style(chat): improve slash picker contrast in AMOLED theme`

---

## Batch 4: Release Verification (SEQUENTIAL - 1 implementer at a time)

Tasks in this batch run STRICTLY SEQUENTIALLY because they all touch versioning / release artifacts and must observe each other.
Tasks: 4.1 → 4.2 → 4.3

Release-signing publishing is gated on credentials being present. If `app/keystore/signing.properties` is absent, executor stops at task 4.3 with an unsigned artifact and a draft release note, and reports the manual step needed to publish.

### Task 4.1: Full unit + lint sweep and screenshot pass

**File:** `screenshots/2026-04-29-mcp-states/` (new directory of PNGs)
**Test:** none — this task IS the test pass
**Depends:** 3.1, 3.2, 3.3, 3.4
**Domain:** general

**Why:** Before bumping the version we must prove every prior task passes its own tests AND that no test regresses. The screenshot pass is the visual contract for the release notes — design line 147 ("MCP sheet screenshots/preview states for loading, loaded, empty, missing, error, saving, and saved").

**Steps for the executor implementer:**
1. From the worktree root run `./gradlew :app:testDebugUnitTest`. All tests must pass. If any fail, stop and fix or escalate to planner.
2. Run `./gradlew :app:lintDebug`. New warnings introduced by tasks 1-3 must be addressed; pre-existing warnings noted only.
3. Run `./gradlew :app:assembleDebug` and confirm the debug APK builds. Output path: `app/build/outputs/apk/debug/app-debug.apk`.
4. Capture screenshots on a real device or AVD running this debug APK against an OpenCode server with: (a) one project with MCP servers, (b) one project with empty MCP config, (c) one project with no MCP config, (d) one project where the config file is intentionally malformed (parse error), and (e) one network-error simulation (server stopped mid-load). Save them under `screenshots/2026-04-29-mcp-states/{loaded,empty-config,missing-config,parse-error,read-error,saving,save-success,refresh-while-dirty,slash-picker-with-mcp-label,slash-picker-amoled}.png`.
5. Document any deviation between captured screenshots and design intent in a short markdown file `screenshots/2026-04-29-mcp-states/NOTES.md`.

**Verify:**
```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
ls screenshots/2026-04-29-mcp-states/
```

All ten screenshot files present, all gradle tasks BUILD SUCCESSFUL.

**Commit:** `chore(release): capture MCP state screenshots for v1.6.24`

---

### Task 4.2: Bump versionName/versionCode and write release notes

**File:** `app/build.gradle.kts` (one-line edits to versionCode/versionName)
**Also writes:** `RELEASE_NOTES_1.6.24.md`
**Test:** none
**Depends:** 4.1
**Domain:** general

**Why:** Plan delivers a real user-visible fix (MCP parity) plus UX polish — must ship under a new version. Following the existing repo convention of `1.6.x` for incremental fixes; this is not a 1.7 candidate because no new feature surfaces or contract changes shipped.

**Implementation guidance:**
- In `app/build.gradle.kts`, change `versionCode = 36` → `versionCode = 37` and `versionName = "1.6.23"` → `versionName = "1.6.24"`. Do NOT touch any other gradle config.
- Create `RELEASE_NOTES_1.6.24.md` at the repo root, following the structure of `RELEASE_NOTES_1.6.23.md`. Required sections:
  - **Highlights** — three to five bullets covering: MCP visibility parity restored (the issue #19 headline), MCP diagnostic states (Empty / Missing / ReadError / ParseError), MCP-labelled chat slash commands, AMOLED slash picker polish, project group MCP server count hint.
  - **Tests** — list the four `:app:` gradle commands run and their pass status.
  - **Version** — `versionName: 1.6.24`, `versionCode: 37`.
  - **Known limitations** — explicitly call out: "Release-signed APK requires `app/keystore/signing.properties`; this build is debug-signed only unless that file is present at build time."
- Append a single bullet to `KNOWN_ISSUES.md` ONLY IF Batch 4.1 surfaced any defect that did not get fixed; otherwise leave `KNOWN_ISSUES.md` untouched.

**Verify:**
```
grep "versionName" app/build.gradle.kts
grep "versionCode" app/build.gradle.kts
test -f RELEASE_NOTES_1.6.24.md && head -30 RELEASE_NOTES_1.6.24.md
```

`versionName = "1.6.24"` and `versionCode = 37` confirmed. `RELEASE_NOTES_1.6.24.md` exists with all required sections.

**Commit:** `chore(release): prepare v1.6.24`

---

### Task 4.3: Build verified release artifact (signing-gated)

**File:** `release-apks/oc-remote-1.6.24.apk` (output, not edited)
**Test:** none
**Depends:** 4.2
**Domain:** general

**Why:** The final step is producing the artifact users will download. Two paths:

**Path A — signing credentials present:**
- If `app/keystore/signing.properties` exists at build time, run `./gradlew :app:assembleRelease`. Copy `app/build/outputs/apk/release/app-release.apk` to `release-apks/oc-remote-1.6.24.apk`.
- Compute and record SHA-256: `sha256sum release-apks/oc-remote-1.6.24.apk` and append to `RELEASE_NOTES_1.6.24.md` under a new **Artifact** section.

**Path B — signing credentials ABSENT (HARD GATE):**
- `./gradlew :app:assembleRelease` will produce an unsigned APK. Do NOT push or publish. Copy `app/build/outputs/apk/release/app-release-unsigned.apk` to `release-apks/oc-remote-1.6.24-unsigned.apk` for record-keeping.
- Append to `RELEASE_NOTES_1.6.24.md`:
  > **Manual publish step required.** Drop a valid `app/keystore/signing.properties` (matching the existing repo convention) into the worktree, then re-run `./gradlew :app:assembleRelease` and rename the output to `release-apks/oc-remote-1.6.24.apk`.
- Report this gate to the user via `lifecycle_log_progress(kind="blocker", summary="release signing credentials missing; unsigned artifact generated")` and STOP. Do NOT call `lifecycle_finish`.

**Pre-flight ownership check (mandatory):**
- Run `git remote -v` and `gh repo view --json nameWithOwner,isFork,parent,viewerPermission`. Confirm `origin` is the user's fork (`Wuxie233/oc-remote`), not upstream (`crim50n/oc-remote`).
- The lifecycle's `lifecycle_commit` should auto-push each commit to `origin` (the fork). Never push to `upstream`.
- Do NOT call `lifecycle_finish` in this task — the user explicitly approved a continuous workflow up to "release-ready"; the merge-and-close decision belongs to the user.

**Verify:**
```
git remote -v
ls -la release-apks/oc-remote-1.6.24*.apk
sha256sum release-apks/oc-remote-1.6.24*.apk
./gradlew :app:assembleRelease
```

In Path A, signed APK exists at `release-apks/oc-remote-1.6.24.apk`. In Path B, unsigned APK exists with `-unsigned` suffix and a blocker is logged.

**Commit:** `chore(release): build v1.6.24 artifact` (Path A) or `chore(release): build v1.6.24 unsigned artifact (signing gate)` (Path B)
