---
date: 2026-04-29
topic: "MCP fallback fix + global UX audit + signed compatible release"
issue: 20
scope: release
contract: none
---

# MCP Fallback Correction, Global UX Audit, and Signed Compatible Release Plan

**Goal:** Ship a corrective release that (a) makes MCP resolution fall through empty/not-found candidates so the user's screenshot scenario shows the global MCP servers, (b) records a standards-guided global UX audit and lands its safe P0/P1 fixes, and (c) builds a signed APK whose signer cert SHA-256 matches v1.6.23, then publishes a GitHub Release.

**Architecture:** Surgical change inside `ServerRepository.resolveMcpConfigLoadState` to treat `Empty`/`NotFound` as non-terminal while keeping `Loaded`/hard `Error` terminal. Audit lives as a markdown artifact under `thoughts/shared/audits/`. UX fixes restricted to copy, accessibility labels, touch targets, and small Compose state polish — no ChatScreen/SessionList rewrites. Release is gated by an automated signer-cert comparison against the v1.6.23 reference.

**Design:** [thoughts/shared/designs/2026-04-29-mcp-fallback-global-ux-design.md](../designs/2026-04-29-mcp-fallback-global-ux-design.md)

**Contract:** none (single-stack Android Kotlin app; no cross-domain split).

---

## Reference Constants

- v1.6.23 reference APK: `/tmp/oc-remote-signfix.12Nyf5/oc-remote-1.6.23.apk`
- v1.6.23 signer cert SHA-256: `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`
- apksigner: `/root/Android/Sdk/build-tools/35.0.0/apksigner`
- Worktree root: `/root/CODE/issue-20-correct-mcp-fallback-behavior-and-perform-a-stan`
- All paths in this plan are relative to the worktree root unless otherwise noted.

---

## Acceptance Criteria — Screenshot Scenario (must all pass before release)

1. With project dir set such that `/root/CODE/oc-remote/.opencode/opencode.json` exists but contains no `mcpServers` / `mcp` key, the APK MCP sheet shows the MCP servers declared in the global fallback (`$HOME/.config/opencode/opencode.json` or `$HOME/.config/opencode/config.json`), not the "暂无 MCP 服务器" empty card.
2. With the same setup but the global config also empty, the MCP sheet shows an Empty state that names the LAST encountered empty path (preferring project-level `.opencode/opencode.json` since that is what the user expected to find), and explicitly tells the user no fallback config provided servers.
3. With all candidate paths missing, the MCP sheet shows the MissingConfig state with the full checked-paths list (current behavior preserved).
4. If reading any candidate fails with a hard error (auth/permission/parse/network), the MCP sheet shows ReadError or ParseError pointing at the failing path; later candidates are NOT silently consulted.
5. New unit test `projectEmptyFallsThroughToGlobalLoaded` passes in `ServerRepositoryTest`.
6. `:app:testDebugUnitTest` is green.

---

## Dependency Graph

```
Batch 1 (parallel): 1.1, 1.2 [foundation - no deps]
   1.1 resolver semantics + tests (data layer)
   1.2 capture v1.6.23 signer cert reference into repo (release infra)

Batch 2 (parallel): 2.1, 2.2, 2.3 [depends on batch 1]
   2.1 McpViewModel/McpUiState message tweak for fallback-exhausted Empty
   2.2 McpManagementSheet Empty/Missing copy + a11y polish
   2.3 Global UX audit artifact (independent file, no code deps)

Batch 3 (parallel): 3.1, 3.2, 3.3, 3.4 [depends on batch 2; safe P0/P1 UX fixes only]
   3.1 SessionListScreen MCP hint copy + content descriptions
   3.2 ChatScreen retry/error banner contentDescription + min touch target audit fixes
   3.3 StateCards (Loading/Empty/Error) WCAG contrast + label fixes
   3.4 Settings / server form a11y labels and error recovery affordance

Batch 4 (sequential — same files, lockstep): 4.1, 4.2, 4.3, 4.4 [depends on batch 3]
   4.1 Bump versionCode/versionName + RELEASE_NOTES_1.6.25.md
   4.2 Build release APK via assembleRelease
   4.3 Run signer-cert guard script (compare SHA-256 to v1.6.23 reference)
   4.4 Publish GitHub Release with the signed APK
```

Batch 4 is intentionally sequential because every step mutates or depends on the same release artifact. Implementer must run them in order and stop if any guard fails.

---

## Batch 1: Foundation — MCP resolver semantics + signing reference (parallel)

All tasks in this batch have NO dependencies and run simultaneously.
Tasks: 1.1, 1.2

### Task 1.1: MCP resolver — fall through Empty / NotFound, keep hard errors terminal
**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/ServerRepository.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryTest.kt`
**Depends:** none
**Domain:** general

**What changes (semantic, not just code):**
The current `resolveMcpConfigLoadState` returns the FIRST candidate that is readable, even if that candidate is an empty config (no `mcpServers`/`mcp` key, or empty servers map). That is the bug: an empty `/root/CODE/oc-remote/.opencode/opencode.json` shadows a fully-populated `~/.config/opencode/opencode.json`.

New semantics:
- `OpenCodeFileNotFoundException` → continue (unchanged).
- Hard read error (any other exception) → return `Error(filePath=path, ...)` immediately (unchanged — terminal).
- Blank file content → record as a "remembered empty" diagnostic and continue.
- Parse → `Loaded` → return immediately (terminal success).
- Parse → `Empty` → record as remembered empty and continue.
- Parse → `Error` → return immediately (terminal).
- After loop: if a remembered empty exists, return it; otherwise return `NotFound(checkedPaths)`.

The remembered empty MUST prefer the FIRST encountered empty candidate (project-level path, which is what the user expected to populate); this gives the best diagnostic message in the all-empty case.

**Implementation sketch (the executor decides exact code style; this is the contract):**

```kotlin
internal fun resolveMcpConfigLoadState(
    candidateReads: List<McpConfigCandidateRead>,
): McpConfigLoadState {
    var rememberedEmpty: McpConfigLoadState.Empty? = null

    for ((path, readResult) in candidateReads) {
        if (readResult.isFailure) {
            val error = readResult.exceptionOrNull()!!
            if (error is OpenCodeFileNotFoundException) continue
            return McpConfigLoadState.Error(
                filePath = path,
                message = error.message ?: "Failed to read MCP config",
                cause = error,
            )
        }

        val raw = readResult.getOrThrow()
        val content = raw.takeIf { it.isNotBlank() }
        if (content == null) {
            // Blank file: record empty diagnostic, keep searching.
            if (rememberedEmpty == null) {
                rememberedEmpty = McpConfigLoadState.Empty(
                    config = McpConfig(filePath = path, rawJson = raw, servers = emptyMap()),
                )
            }
            continue
        }

        when (val parsed = McpConfigParser.parseState(path, content)) {
            is McpConfigLoadState.Loaded -> return parsed
            is McpConfigLoadState.Empty -> {
                if (rememberedEmpty == null) rememberedEmpty = parsed
                // continue to next candidate
            }
            is McpConfigLoadState.Error -> return parsed
            is McpConfigLoadState.NotFound -> {
                // parser does not produce NotFound; defensive no-op
            }
        }
    }

    return rememberedEmpty ?: McpConfigLoadState.NotFound(candidateReads.map { it.path })
}
```

Also update `readMcpConfig` (the `Result<McpConfig?>` flavor) — its existing branches are still fine since `Empty` already maps to `Result.success(state.config)`. No change needed there.

**Tests to ADD to `ServerRepositoryTest`** (add alongside any existing tests; do not delete existing ones):

```kotlin
@Test
fun `project empty falls through to global loaded`() {
    val projectPath = "/proj/.opencode/opencode.json"
    val globalPath = "/home/u/.config/opencode/opencode.json"
    val reads = listOf(
        McpConfigCandidateRead(projectPath, Result.success("{}")),
        McpConfigCandidateRead(globalPath, Result.success("""{"mcpServers":{"fs":{"command":"npx"}}}""")),
    )
    val result = ServerRepository.resolveMcpConfigLoadStateForTest(reads) // helper exposing internal
    assertTrue(result is McpConfigLoadState.Loaded)
    assertEquals(globalPath, (result as McpConfigLoadState.Loaded).config.filePath)
    assertEquals(setOf("fs"), result.config.servers.keys)
}

@Test
fun `blank project file falls through to global loaded`() {
    val projectPath = "/proj/.opencode/opencode.json"
    val globalPath = "/home/u/.config/opencode/opencode.json"
    val reads = listOf(
        McpConfigCandidateRead(projectPath, Result.success("")),
        McpConfigCandidateRead(globalPath, Result.success("""{"mcpServers":{"fs":{"command":"npx"}}}""")),
    )
    val result = ServerRepository.resolveMcpConfigLoadStateForTest(reads)
    assertTrue(result is McpConfigLoadState.Loaded)
}

@Test
fun `loaded project config is terminal and does not consult global`() {
    val projectPath = "/proj/.opencode/opencode.json"
    val globalPath = "/home/u/.config/opencode/opencode.json"
    val reads = listOf(
        McpConfigCandidateRead(projectPath, Result.success("""{"mcpServers":{"a":{"command":"x"}}}""")),
        McpConfigCandidateRead(globalPath, Result.success("""{"mcpServers":{"b":{"command":"y"}}}""")),
    )
    val result = ServerRepository.resolveMcpConfigLoadStateForTest(reads)
    assertTrue(result is McpConfigLoadState.Loaded)
    assertEquals(setOf("a"), (result as McpConfigLoadState.Loaded).config.servers.keys)
}

@Test
fun `all empty returns first remembered empty diagnostic`() {
    val projectPath = "/proj/.opencode/opencode.json"
    val globalPath = "/home/u/.config/opencode/opencode.json"
    val reads = listOf(
        McpConfigCandidateRead(projectPath, Result.success("{}")),
        McpConfigCandidateRead(globalPath, Result.success("{}")),
    )
    val result = ServerRepository.resolveMcpConfigLoadStateForTest(reads)
    assertTrue(result is McpConfigLoadState.Empty)
    assertEquals(projectPath, (result as McpConfigLoadState.Empty).config.filePath)
}

@Test
fun `all missing returns NotFound with checked paths`() {
    val p1 = "/proj/.opencode/opencode.json"
    val p2 = "/home/u/.config/opencode/opencode.json"
    val reads = listOf(
        McpConfigCandidateRead(p1, Result.failure(OpenCodeFileNotFoundException(p1))),
        McpConfigCandidateRead(p2, Result.failure(OpenCodeFileNotFoundException(p2))),
    )
    val result = ServerRepository.resolveMcpConfigLoadStateForTest(reads)
    assertTrue(result is McpConfigLoadState.NotFound)
    assertEquals(listOf(p1, p2), (result as McpConfigLoadState.NotFound).checkedPaths)
}

@Test
fun `hard read error on first candidate is terminal`() {
    val p1 = "/proj/.opencode/opencode.json"
    val p2 = "/home/u/.config/opencode/opencode.json"
    val reads = listOf(
        McpConfigCandidateRead(p1, Result.failure(java.io.IOException("permission denied"))),
        McpConfigCandidateRead(p2, Result.success("""{"mcpServers":{"fs":{"command":"npx"}}}""")),
    )
    val result = ServerRepository.resolveMcpConfigLoadStateForTest(reads)
    assertTrue(result is McpConfigLoadState.Error)
    assertEquals(p1, (result as McpConfigLoadState.Error).filePath)
}

@Test
fun `parse error on candidate is terminal`() {
    val p1 = "/proj/.opencode/opencode.json"
    val p2 = "/home/u/.config/opencode/opencode.json"
    val reads = listOf(
        McpConfigCandidateRead(p1, Result.success("""{ this is not json """)),
        McpConfigCandidateRead(p2, Result.success("""{"mcpServers":{"fs":{"command":"npx"}}}""")),
    )
    val result = ServerRepository.resolveMcpConfigLoadStateForTest(reads)
    assertTrue(result is McpConfigLoadState.Error)
}
```

**Test access detail:** `resolveMcpConfigLoadState` and `McpConfigCandidateRead` are currently `internal`. The test class is in the same module, so it can call them directly as `ServerRepository.resolveMcpConfigLoadState(reads)` (companion-style) IF they are moved to a `companion object` or kept as static-like internal. Pragmatic path: keep them as `internal` instance members and instantiate the repository with mocked deps in the test, OR add a thin `internal fun resolveMcpConfigLoadStateForTest` companion bridge. Implementer picks whichever matches existing test conventions in `ServerRepositoryTest`.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*ServerRepositoryTest*'`
**Commit:** `fix(mcp): fall through empty/not-found candidates so global config wins when project config has no MCP servers`

---

### Task 1.2: Capture v1.6.23 signer cert reference into the repo
**File:** `scripts/release/v1623-signer-cert.sha256`
**Test:** none (release infra artifact)
**Depends:** none
**Domain:** general

This is a checked-in reference value used by the Batch 4 signer guard. It encodes the cert SHA-256 of v1.6.23 so that any future builder (us, CI, the user on a different host) can verify continuity without hunting for the historical APK.

**File content (exact):**

```
# v1.6.23 signer certificate SHA-256 (canonical reference for OC Remote releases).
# Captured from /tmp/oc-remote-signfix.12Nyf5/oc-remote-1.6.23.apk via:
#   apksigner verify --print-certs <apk> | grep 'SHA-256'
# Future releases MUST be signed with a key whose cert hashes to this value.
fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc
```

**Verify:**
```sh
test -f scripts/release/v1623-signer-cert.sha256 && \
  grep -q 'fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc' scripts/release/v1623-signer-cert.sha256
```
**Commit:** `chore(release): record v1.6.23 signer cert SHA-256 as canonical reference`

---

## Batch 2: ViewModel/UI mapping + UX audit artifact (parallel)

All tasks in this batch depend on Batch 1 completing.
Tasks: 2.1, 2.2, 2.3

### Task 2.1: McpUiState — distinguish "all candidates empty" Empty diagnostic
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelTest.kt`
**Depends:** 1.1 (resolver now produces Empty only after exhausting fallbacks)
**Domain:** frontend

**Why:** Now that `Empty` only fires after the resolver exhausted all candidates (project + global), the existing `EmptyConfig(filePath)` UI message ("已找到配置 X，但其中未声明任何 MCP 服务器。") is no longer fully accurate — the user has multiple empty configs across the search path. The state mapping itself does not change shape, but we add ONE field so the sheet can render a more accurate message.

**Change:**

```kotlin
data class EmptyConfig(
    val filePath: String,
    // NEW: when true, every candidate (project + global) was empty or not-found.
    // Drives the "且全局回退也未提供 MCP 服务器" suffix in the UI.
    val fallbackExhausted: Boolean = true,
) : McpUiState()
```

In `McpStateController.loadCurrentConfig()` `is McpConfigLoadState.Empty` branch:

```kotlin
is McpConfigLoadState.Empty -> {
    lastLoaded = null
    _state.value = McpUiState.EmptyConfig(
        filePath = loadState.config.filePath,
        fallbackExhausted = true, // resolver only emits Empty after exhausting candidates
    )
}
```

Keep all other branches unchanged.

**Tests to ADD to `McpViewModelTest`:**

```kotlin
@Test
fun loadMapsEmptyToEmptyConfigWithFallbackExhaustedTrue() = runTest {
    val filePath = "/proj/.opencode/opencode.json"
    val controller = newController(
        scope = this,
        readState = { _, _ ->
            McpConfigLoadState.Empty(
                config = McpConfig(filePath = filePath, rawJson = "{}", servers = emptyMap()),
            )
        },
    )
    controller.load(testConn, "/proj")
    advanceUntilIdle()
    val state = controller.state.value as McpUiState.EmptyConfig
    assertEquals(filePath, state.filePath)
    assertTrue(state.fallbackExhausted)
}
```

The existing `loadMapsEmptyConfigToEmptyConfigStateNotEmptyGeneric` test still passes because `fallbackExhausted` defaults to true.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*McpViewModelTest*'`
**Commit:** `feat(mcp): mark EmptyConfig as fallback-exhausted so UI can explain global fallback also had no servers`

---

### Task 2.2: McpManagementSheet — copy + a11y polish for Empty / Missing / Loaded states
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheetCopyTest.kt` (new, optional — see below)
**Depends:** 2.1
**Domain:** frontend

**Copy changes:**

`is McpUiState.EmptyConfig`:
- title: `"暂无 MCP 服务器"` (unchanged)
- message when `fallbackExhausted`:
  `"已检查项目与全局 OpenCode 配置，但都未声明任何 MCP 服务器。最近一次检查的配置位于 ${current.filePath}。在该文件中加入 mcpServers 后下拉刷新即可生效。"`
- (No fallbackExhausted=false branch needed in this release, since the resolver always emits with that flag = true.)

`is McpUiState.MissingConfig`:
- Add a one-line hint that lists the FIRST checked path inline in the title row, plus an expandable "查看全部检查路径" affordance. Reason: NN/g heuristic 9 (help users recognize, diagnose, recover). The current full list is useful but visually noisy; primary path inline + expandable details keeps the empty state scannable.

`McpServerRow` and refresh icon:
- Refresh `IconButton` already has `contentDescription = "刷新 MCP 配置"` — keep.
- Each `Switch` for an MCP server MUST get a `Modifier.semantics { contentDescription = "${server.name} 启用状态" }` if not already present, and the row should be a single clickable target with `Modifier.minimumInteractiveComponentSize()` (Material 3) so the touch target is ≥ 48dp.

**A11y / WCAG additions:**
- All static text in Empty/Missing/Read/Parse error cards uses `MaterialTheme.colorScheme.onSurfaceVariant` for body, `onSurface` for titles. Verify contrast in dark theme; if either drops below 4.5:1 against its container, swap to `onSurface` for body too.
- Min touch target on the refresh button: ensure parent `IconButton` uses default 48dp; do NOT shrink it.

**Optional test (recommended):** Add a Compose UI test that asserts the EmptyConfig branch renders the substring `"已检查项目与全局"` when given `EmptyConfig(filePath = "/x", fallbackExhausted = true)`. Use the same `composeTestRule` style already used in `StateCardsTest` if available; otherwise skip and rely on manual screenshot.

**Verify:**
- `./gradlew :app:testDebugUnitTest`
- Manual: launch debug build pointed at the screenshot scenario, confirm Empty card now reads "已检查项目与全局…" if BOTH project and global are empty.

**Commit:** `feat(ux/mcp): clarify Empty state copy after fallback exhaustion and tighten a11y on MCP sheet`

---

### Task 2.3: Global UX audit artifact (NN/g + Material 3 + WCAG + Android quality + Baymard severity)
**File:** `thoughts/shared/audits/2026-04-29-global-ux-audit.md`
**Test:** none (markdown artifact)
**Depends:** none structurally; placed in batch 2 so batch 3 fixes can cite finding IDs.
**Domain:** general

**Required structure (executor MUST produce all sections):**

```markdown
---
date: 2026-04-29
topic: "OC Remote global UX audit"
issue: 20
audit_method: ["NN/g 10 heuristics", "Material 3", "WCAG 2.2 AA mobile", "Android Core/Adaptive App Quality", "Baymard-style severity"]
status: draft
---

# OC Remote — Global UX Audit (v1.6.24 baseline)

## Method
- Walked each surface twice: once naturally as a first-time user, once with the heuristics checklist.
- Severity: P0 (blocks task / accessibility violation), P1 (major friction or standards miss, safe to fix), P2 (polish), P3 (nice-to-have / requires redesign).
- Each finding cites: surface, flow step, evidence (screenshot or code path), violated heuristic(s), severity, suggested fix, and acceptance criteria.

## Surfaces covered
1. Startup / first-run / server list empty state
2. Server add/edit form
3. Server connection error states
4. Session list (project grouping, MCP hint badge, archive swipe)
5. Chat screen (composer, retry banner, busy state, stop button, attachments)
6. Tool call rendering and permissions prompts
7. Question prompts (modal, slash command, Octto-style)
8. Model / agent picker
9. MCP management sheet (project + fallback)
10. LSP / plugins management
11. Settings (theme, network, accessibility)
12. Terminal pane
13. Update/release notification flow
14. Empty / loading / error patterns across screens
15. Dark / AMOLED + small-screen + large-text scaling
16. Touch targets, content descriptions, focus order

## Findings table

| ID | Surface | Severity | Heuristic(s) | Evidence | Suggested fix | Acceptance criteria | Implemented in this release? |
|----|---------|----------|--------------|----------|----------------|---------------------|------------------------------|
| F-001 | MCP sheet Empty state | P0 | NN/g #9, WCAG 3.3.3 | `McpManagementSheet.kt` Empty branch hides the fact that global config was also checked | Copy update from Task 2.2 | Empty card explicitly mentions both project and global searches | yes (Task 2.2) |
| F-002 | … | … | … | … | … | … | yes/no |
| ... | one row per finding ... |

## P0/P1 implemented in this release
- Cross-reference Task 2.2, 3.1, 3.2, 3.3, 3.4 by finding ID.

## P0/P1 deferred (with rationale)
- Findings that require non-trivial refactors (ChatScreen composer redesign, SessionList virtualization, etc.) get logged here with: rationale, scope estimate, and the GitHub issue number to be opened as a follow-up.

## P2/P3 backlog
- Polish items recorded for later sweeps.
```

**Hard requirements for the artifact:**
- At least 8 findings total. At least 3 P0 and 3 P1. (If the app is genuinely cleaner than that, adjust — but typical first-pass audits surface 15-30.)
- Every P0/P1 fix shipped in batch 3 MUST have a finding in this table with "Implemented in this release? = yes".
- Findings deferred MUST state WHY they are deferred (risk, scope, design dependency) — not just "out of scope".

**Verify:** `test -f thoughts/shared/audits/2026-04-29-global-ux-audit.md && grep -q 'P0' thoughts/shared/audits/2026-04-29-global-ux-audit.md`
**Commit:** `docs(ux): add standards-guided global UX audit covering all surfaces with severity-ranked findings`

---

## Batch 3: Safe P0/P1 UX implementations (parallel)

All tasks in this batch depend on Batch 2 (audit artifact must enumerate the findings being fixed). Each task targets a distinct file so they run in parallel safely.
Tasks: 3.1, 3.2, 3.3, 3.4

### Task 3.1: SessionListScreen — MCP hint copy + content descriptions (P1)
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` (and the `ProjectGroupHeader.kt` companion if the MCP hint badge lives there)
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeaderMcpHintTest.kt` (extend existing)
**Depends:** 2.3 (audit assigns finding ID to this fix)
**Domain:** frontend

**Scope (KEEP TIGHT):**
1. Where the project group header renders an MCP hint badge, ensure it has a `contentDescription` like `"该项目已配置 N 个 MCP 服务器"` or `"该项目使用全局 MCP 配置"` so TalkBack reads it. Do NOT add a fallback-vs-project distinction beyond what the existing badge model supports — if the existing model only knows count, only describe count.
2. If the badge is currently a clickable affordance to open the MCP sheet, ensure its hit area is ≥ 48dp via `Modifier.minimumInteractiveComponentSize()`.
3. No layout / icon / color change unless required to hit WCAG 4.5:1 contrast in dark theme.

**Forbidden in this task:** restructuring the SessionList, changing how projects are grouped, modifying archive swipe behavior, changing list item layout.

**Tests:** Extend `ProjectGroupHeaderMcpHintTest` with one assertion that the badge node has a non-empty `contentDescription`.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*ProjectGroupHeaderMcpHintTest*'`
**Commit:** `feat(ux/sessions): add a11y description and proper touch target to project MCP hint badge`

---

### Task 3.2: ChatScreen — retry/error banner contentDescription + touch target audit (P1)
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
**Test:** none unit (manual TalkBack walk-through; documented in audit)
**Depends:** 2.3
**Domain:** frontend

**Scope (KEEP TIGHT):**
1. Retry banner: ensure the Stop button (added in v1.6.23) has `contentDescription = "停止重试"` and a tooltip; ensure its tap target is ≥ 48dp. Do NOT change banner layout, colors, or behavior.
2. Top bar Stop button: same a11y check.
3. If the banner uses a transient `Snackbar`-style for hard errors today, change ONLY the role/duration so it stays visible until dismissed for non-recoverable failures (per design "important failures should have persistent context"). If this is too risky to scope-bound (i.e., requires a banner refactor), DEFER to follow-up issue and log it in the audit's deferred section.
4. No composer rewrite, no slash command rewrite, no message bubble layout change.

**Forbidden in this task:** rewriting the composer, changing message rendering, restructuring toolbar.

**Verify:** `./gradlew :app:testDebugUnitTest && ./gradlew :app:lintDebug`
**Commit:** `feat(ux/chat): tighten a11y descriptions and touch targets on retry/stop affordances`

---

### Task 3.3: StateCards — WCAG contrast + label fixes (P1)
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/components/` — the file containing LoadingStateCard / EmptyStateCard / ErrorStateCard (locate via `grep -l 'LoadingStateCard' app/src/main/kotlin`)
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/components/StateCardsTest.kt`
**Depends:** 2.3
**Domain:** frontend

**Scope:**
1. Verify every card title uses `MaterialTheme.colorScheme.onSurface` and body uses `onSurfaceVariant`; if any card hard-codes a color, replace with theme tokens.
2. Where an action button exists (e.g., "重试"), ensure it carries a meaningful `contentDescription` and ≥ 48dp tap target.
3. Where the card surfaces a "checked paths" list (Missing config), wrap the list in a collapsed-by-default expander with a "查看检查路径" affordance, OR keep the inline list if it's already short — implementer judgment, but the card MUST NOT exceed roughly half the screen height in default state on a 360dp width device.

**Tests:** Extend `StateCardsTest` to assert that a representative ErrorStateCard renders a button with a non-empty contentDescription. Re-use existing test idioms.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*StateCardsTest*'`
**Commit:** `feat(ux/components): align state cards with M3 color tokens and WCAG touch-target guidance`

---

### Task 3.4: Server form / settings — a11y labels and error recovery (P1)
**File:** Locate via `grep -rl 'class.*ServerFormScreen\|fun ServerFormScreen\|fun SettingsScreen' app/src/main/kotlin` and modify the matching file. (Likely `app/src/main/kotlin/dev/minios/ocremote/ui/screens/servers/ServerFormScreen.kt` or similar.)
**Test:** existing screen test if any; otherwise none.
**Depends:** 2.3
**Domain:** frontend

**Scope (TIGHT):**
1. Every text field (host, port, name, token) must have an explicit `label` and a `contentDescription` derived from the label so TalkBack reads field purpose.
2. The "test connection" / save button must have a non-generic content description.
3. Network/auth error responses must surface in a persistent inline error region with a "重试" affordance, not just a Snackbar.

**Forbidden:** restructuring the form, adding new fields, changing validation rules, changing the data model.

If a finding in the audit calls for a deeper restructure, log it as deferred and DO NOT implement it here.

**Verify:** `./gradlew :app:testDebugUnitTest && ./gradlew :app:lintDebug`
**Commit:** `feat(ux/servers): add a11y labels and inline error recovery to server form`

---

## Batch 4: Signed compatible release + GitHub publish (sequential)

These tasks share files (`app/build.gradle.kts`, release APK, GitHub Release) and MUST be executed in order. Treat any guard failure as a stop condition; do not proceed.
Tasks: 4.1, 4.2, 4.3, 4.4

### Task 4.1: Bump versionCode/versionName + RELEASE_NOTES_1.6.25.md
**File:** `app/build.gradle.kts` AND `RELEASE_NOTES_1.6.25.md`
**Test:** none (release infra)
**Depends:** all batches 1–3 merged on this branch
**Domain:** general

**Edits:**

In `app/build.gradle.kts`:
```
versionCode = 38     // was 37
versionName = "1.6.25"  // was 1.6.24
```

Create `RELEASE_NOTES_1.6.25.md` with this exact skeleton (executor fills `<...>` placeholders with real findings/IDs from the audit):

```markdown
# OC Remote v1.6.25 — Release Notes

## Highlights

- MCP correctness fix
  - Project `.opencode/opencode.json` that exists but declares no MCP servers no longer shadows the global config. The APK now falls through to global fallback config (`~/.config/opencode/opencode.json` and `~/.config/opencode/config.json`) and shows those servers.
  - Hard read/parse/auth errors remain terminal and visible.
  - Empty / Missing / Read / Parse states all have clearer copy.

- Standards-guided UX audit
  - Conducted a global audit using NN/g 10 heuristics, Material 3, WCAG 2.2 AA mobile, Android Core/Adaptive App Quality, and Baymard-style severity ranking.
  - Audit artifact: `thoughts/shared/audits/2026-04-29-global-ux-audit.md` (in repo).
  - Implemented P0/P1 quick wins in this release: <list finding IDs>.
  - Larger redesigns deferred with rationale and follow-up issue links.

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅
- `:app:assembleRelease` ✅
- `:app:lintDebug` ✅
- Signer cert SHA-256 matches v1.6.23 reference ✅

## Version

- `versionName`: `1.6.25`
- `versionCode`: `38`
```

**Verify:** `grep -q '1.6.25' app/build.gradle.kts && test -f RELEASE_NOTES_1.6.25.md`
**Commit:** `chore(release): bump to 1.6.25 and draft release notes`

---

### Task 4.2: Build release APK via assembleRelease
**File:** none (build artifact under `app/build/outputs/apk/release/`)
**Test:** none
**Depends:** 4.1
**Domain:** general

**Steps:**
1. Confirm `app/keystore/signing.properties` exists and points at the canonical OC Remote keystore (the one that previously produced v1.6.23). If the only available keystore is the mismatched local one, STOP and escalate — do not produce a signed-but-incompatible release.
2. Run from the worktree root:
   ```sh
   ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleRelease
   ```
3. Locate the signed APK: `app/build/outputs/apk/release/app-release.apk`.
4. Copy / rename to `release-apks/oc-remote-1.6.25.apk`.

**Verify:**
```sh
test -f release-apks/oc-remote-1.6.25.apk
```
**Commit:** `chore(release): build signed v1.6.25 APK`
(Do NOT commit the APK binary if the repo's gitignore policy excludes `release-apks/*.apk`. If it is committed historically, follow the existing convention.)

---

### Task 4.3: Signer-cert continuity guard (BLOCKING)
**File:** `scripts/release/verify-signer-cert.sh`
**Test:** the script itself runs as the verification step
**Depends:** 4.2 (release APK must exist)
**Domain:** general

**Create** `scripts/release/verify-signer-cert.sh` with the following exact content:

```sh
#!/usr/bin/env sh
# verify-signer-cert.sh — fail if the given APK's signer cert SHA-256 does not match
# the canonical v1.6.23 reference recorded in scripts/release/v1623-signer-cert.sha256.

set -eu

APK_PATH="${1:-release-apks/oc-remote-1.6.25.apk}"
REF_FILE="$(dirname "$0")/v1623-signer-cert.sha256"
APKSIGNER="${APKSIGNER:-/root/Android/Sdk/build-tools/35.0.0/apksigner}"

if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: APK not found at $APK_PATH" >&2
  exit 2
fi
if [ ! -x "$APKSIGNER" ] && [ ! -f "$APKSIGNER" ]; then
  echo "ERROR: apksigner not found at $APKSIGNER" >&2
  exit 2
fi
if [ ! -f "$REF_FILE" ]; then
  echo "ERROR: reference file not found at $REF_FILE" >&2
  exit 2
fi

REF_SHA=$(grep -E '^[0-9a-f]{64}$' "$REF_FILE" | head -n1)
if [ -z "$REF_SHA" ]; then
  echo "ERROR: no SHA-256 line found in $REF_FILE" >&2
  exit 2
fi

ACTUAL=$("$APKSIGNER" verify --print-certs "$APK_PATH" 2>/dev/null \
  | awk -F': ' '/SHA-256 digest/ {print $2; exit}')

if [ -z "$ACTUAL" ]; then
  echo "ERROR: could not extract signer cert SHA-256 from $APK_PATH" >&2
  exit 3
fi

echo "Reference (v1.6.23): $REF_SHA"
echo "Actual    ($APK_PATH): $ACTUAL"

if [ "$ACTUAL" != "$REF_SHA" ]; then
  echo "FAIL: signer cert mismatch. Do NOT publish this APK." >&2
  exit 1
fi

echo "OK: signer cert matches v1.6.23 reference."
```

Make it executable: `chmod +x scripts/release/verify-signer-cert.sh`.

**Verify (this is the BLOCKING gate for batch 4):**
```sh
./scripts/release/verify-signer-cert.sh release-apks/oc-remote-1.6.25.apk
```
Expected exit code 0 and output ending in `OK: signer cert matches v1.6.23 reference.`

**STOP CONDITION:** if exit code is non-zero, do NOT proceed to Task 4.4. Escalate to user with the actual vs reference SHA so they can produce the correct keystore.

**Commit:** `chore(release): add signer-cert continuity guard and verify v1.6.25 against v1.6.23`

---

### Task 4.4: Publish GitHub Release
**File:** none (GitHub remote action)
**Test:** none
**Depends:** 4.3 returned exit 0
**Domain:** general

**Pre-flight (mandatory per global AGENTS.md repo-ownership rules):**
```sh
git remote -v
gh repo view --json nameWithOwner,isFork,parent,owner,viewerPermission
```
Confirm the target is the user's fork (`origin`), not upstream. State the classification one-liner in chat before proceeding.

**Publish:**
```sh
gh release create v1.6.25 \
  release-apks/oc-remote-1.6.25.apk \
  --title "OC Remote v1.6.25" \
  --notes-file RELEASE_NOTES_1.6.25.md
```

**Verify:**
```sh
gh release view v1.6.25 --json tagName,assets --jq '{tag: .tagName, assets: [.assets[].name]}'
```
Expected: tag is `v1.6.25` and assets include `oc-remote-1.6.25.apk`.

**Then:** call `lifecycle_log_progress(kind="status", summary="release v1.6.25 published")` followed by `lifecycle_finish(issue_number=20)` per global lifecycle rules. (The executor handles this; planner does not.)

**Commit:** none (release publish is its own action; no source change beyond Task 4.3's commit).
