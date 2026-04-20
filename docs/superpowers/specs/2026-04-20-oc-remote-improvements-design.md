# Design: oc-remote Four Feature Improvements

**Date**: 2026-04-20  
**Status**: Approved  
**Scope**: Session list UI, MCP management, agent message metadata, edit-abort bug fix

---

## Background

Four improvements requested by the user:

1. **Banner whitespace** — active conversations banner has awkward empty space below cards in non-AMOLED mode
2. **MCP management** — per-project MCP server list with enable/disable toggles, accessed from the project three-dot menu
3. **Agent reply metadata** — show faint model name + timestamp in the bottom-right of each assistant message bubble
4. **Edit-abort bug** — long-pressing a user message and selecting Edit does not abort the current running session first, causing a race condition

---

## Task 1: Banner Whitespace Fix

### Problem

`ActiveConversationsBanner.kt` — `Column(padding vertical = 8.dp)` contains:
- Label text
- `Spacer(8.dp)` above LazyRow
- `LazyRow` (the cards)
- [AMOLED only] `Spacer(8.dp)` + `HorizontalDivider`

In non-AMOLED mode there is no spacing below the LazyRow, so the cards sit flush against whatever comes next in `SessionListScreen`, which looks awkward.

### Solution

Add an unconditional `Spacer(Modifier.height(8.dp))` after the `LazyRow`, before the AMOLED-only conditional block. This makes top and bottom padding symmetric in all themes.

```
Column(padding vertical=8.dp)
  ├─ [AMOLED] Spacer(8.dp) + Divider
  ├─ Text(title)
  ├─ Spacer(8.dp)
  ├─ LazyRow
  ├─ Spacer(8.dp)          ← NEW, unconditional
  └─ [AMOLED] Spacer(8.dp) + Divider   (keep existing)
```

### Files
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ActiveConversationsBanner.kt` (~3 lines)

---

## Task 2: MCP Management

### Overview

Allow users to view and toggle MCP servers for a specific project. Since the OpenCode REST API has no MCP management endpoints, the implementation reads and writes the OpenCode config file directly via the server's filesystem API.

### Config File Discovery

Look for the config file in this priority order given a project's `projectDir`:

1. `{projectDir}/.opencode/config.json`
2. `{projectDir}/opencode.json`
3. `~/.config/opencode/config.json` (global fallback)

Use whichever is found first. If none exist, show empty state: "此项目无 MCP 配置".

### Config Format

Standard OpenCode MCP config shape:
```json
{
  "mcpServers": {
    "server-name": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": {},
      "enabled": true
    }
  }
}
```

- `enabled` field: toggle target. If absent, treat as `true`. To disable: set `"enabled": false`.
- All other fields are preserved verbatim on write (round-trip safe).

### Domain Models (new file)

`app/src/main/kotlin/dev/minios/ocremote/domain/model/McpConfig.kt`

```kotlin
data class McpConfig(
    val filePath: String,           // absolute path of the config file that was found
    val servers: Map<String, McpServer>
)

data class McpServer(
    val name: String,
    val type: String?,
    val command: String?,
    val args: List<String> = emptyList(),
    val enabled: Boolean = true
)
```

### Repository Layer

`ServerRepository` gains two new suspend functions:

```kotlin
suspend fun readMcpConfig(conn: ServerConnection, projectDir: String): Result<McpConfig?>
suspend fun writeMcpConfig(conn: ServerConnection, config: McpConfig): Result<Unit>
```

`readMcpConfig` logic:
1. Try each candidate path via the existing file-read API (`GET /file?path=...` or equivalent)
2. Parse JSON → `McpConfig`
3. Return `null` result (not error) if no config file found

`writeMcpConfig` logic:
1. Serialize modified `McpConfig` back to JSON (preserve unknown fields via `JsonObject` round-trip)
2. Write via the existing file-write API

### ViewModel

New file: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt`

State:
```kotlin
sealed class McpUiState {
    object Loading : McpUiState()
    data class Loaded(val config: McpConfig, val dirty: Boolean = false) : McpUiState()
    data class Error(val message: String) : McpUiState()
    object NoConfig : McpUiState()   // no config file found
    object Saving : McpUiState()
    object SaveSuccess : McpUiState()
    data class SaveError(val message: String) : McpUiState()
}
```

Actions:
- `load(projectDir: String)` — triggers config file discovery + read
- `toggleServer(name: String)` — flips `enabled` in local state, sets `dirty = true`
- `save()` — writes config, emits `SaveSuccess` (triggers Toast + dismiss)

### UI

**Entry point**: `ProjectGroupHeader.kt` — add menu item "管理 MCP" to the existing `DropdownMenu`. Only show this item when the project has a non-null `directory`.

**Bottom Sheet** (`ModalBottomSheet` from Material3):

```
┌─────────────────────────────────────┐
│  MCP 服务器 · {项目名}                 │
│  ─────────────────────────────────  │
│  [●] my-filesystem-server            │
│      npx @mcp/server-filesystem...  │  ← command preview, 1 line, ellipsis
│                                 ⏻   │  ← Switch (on/off)
│  ─────────────────────────────────  │
│  [○] disabled-server                 │
│      python -m mcp_server...        │
│                                 ⏻   │
│  ─────────────────────────────────  │
│  (empty state: "此项目无 MCP 配置")    │
│  ─────────────────────────────────  │
│  [取消]                    [保存]    │
└─────────────────────────────────────┘
```

- Each server row: name (bodyMedium, bold) + command preview (labelSmall, alpha=0.55) + Switch  
- Switch reflects `McpServer.enabled`, toggling calls `viewModel.toggleServer(name)`  
- "保存" calls `viewModel.save()`, shows loading state on button while saving  
- On `SaveSuccess`: dismiss sheet + `Toast("已保存，重启 OpenCode 后生效")`  
- On `SaveError`: `Snackbar` with error message, sheet stays open  
- On `Error`/initial load failure: error message + Retry button inside sheet

**New file**: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt`

### Files Summary

| File | Change |
|------|--------|
| `domain/model/McpConfig.kt` | New — domain models |
| `data/repository/ServerRepository.kt` | Add `readMcpConfig` + `writeMcpConfig` |
| `ui/screens/sessions/McpViewModel.kt` | New — ViewModel |
| `ui/screens/sessions/components/McpManagementSheet.kt` | New — bottom sheet UI |
| `ui/screens/sessions/components/ProjectGroupHeader.kt` | Add menu item + bottom sheet trigger |
| `ui/screens/sessions/SessionListScreen.kt` | Pass McpViewModel / wire up sheet state |

---

## Task 3: Agent Reply Model + Time Display

### Data Available

`Message.Assistant` already has:
- `modelId: String?` — the model ID (e.g. `claude-sonnet-4-5`)
- `providerId: String?` — provider (e.g. `anthropic`)
- `time.created: Long` — Unix ms timestamp
- `time.completed: Long?` — Unix ms when finished (nullable)

### Display

Add a single row at the bottom of each assistant message bubble's content area:

- **Position**: bottom-right aligned, inside the bubble
- **Format**: `HH:mm  ·  {modelId}` — if `modelId` is null, show only `HH:mm`
- **Style**:
  - `MaterialTheme.typography.labelSmall`
  - `color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)`
  - `textAlign = TextAlign.End`
  - `modifier = Modifier.fillMaxWidth()`
- **Time**: format `time.created` using `SimpleDateFormat("HH:mm")`
- **No padding** added — sits within the existing bubble content padding

### Condition

Only rendered when the message has at least one content part (not an error-only message). Error messages already have distinct styling.

### Files
- `ui/screens/chat/ChatScreen.kt` — assistant bubble composable (~15 lines)

---

## Task 4: Long-Press Edit Abort Fix

### Problem

`ChatScreen.kt` around line 2386-2398: the `onRevert` callback triggered by the "编辑" menu item calls `viewModel.revertMessage(messageId)` without first calling `viewModel.abortSession()`. If the assistant is generating when the user taps Edit, the generation continues concurrently — a race condition.

### Fix

In the `onRevert` callback site:

```kotlin
// Before (buggy)
viewModel.revertMessage(messageId)

// After (fixed)
viewModel.abortSession()    // abort first — safe to call even if already idle
viewModel.revertMessage(messageId)
```

`abortSession()` (`ChatViewModel.kt:831`) is safe to call unconditionally: if the session is already idle the API call may 404/no-op but the optimistic local state reset is harmless.

### Files
- `ui/screens/chat/ChatScreen.kt` (~1 line)

---

---

## Task 5: FAB → Add Project to Workspace

### Background

The ➕ FAB currently opens `NewSessionQuickDialog` (recent project shortcuts → create session → navigate to chat). For multi-project development, users want to add a project directory to the workspace list without automatically starting a new conversation.

**Constraint**: The OpenCode server has no independent "register project" API (`GET /project` is read-only, `POST /project` does not exist). Projects on the server are a side effect of creating sessions. Therefore, "add to workspace" must be implemented as **client-side local storage**.

### Behavior Change

| Current | New |
|---------|-----|
| ➕ → `NewSessionQuickDialog` (recent shortcuts) → `createNewSession()` → navigate to chat | ➕ → `OpenProjectDialog` (directory browser) → `pinDirectory(dir)` → stays on session list |

`NewSessionQuickDialog` is no longer used as the FAB entry point. It can be kept for future use or removed — it is not wired to the FAB.

### Data Layer

**`SessionListPreferences`** (existing DataStore preferences class) gains one new field:
```kotlin
val pinnedDirectories: Set<String>  // default: emptySet()
```

**`SessionListPreferencesRepository`** gains:
```kotlin
suspend fun addPinnedDirectory(directory: String)
suspend fun removePinnedDirectory(directory: String)
fun pinnedDirectoriesFlow(): Flow<Set<String>>
```

### ViewModel

`SessionListViewModel` already loads sessions and groups them by directory. Extend the group-building logic to also include pinned directories that have no sessions:

```kotlin
// Pseudo-code for merged groups
val sessionGroups: Map<String, List<Session>>  // existing
val pinned: Set<String>                         // from DataStore
val allDirs = (sessionGroups.keys + pinned).distinct()
// Build ProjectGroup for each dir; pinnedEmpty = true if no sessions
```

New ViewModel function:
```kotlin
fun pinDirectory(directory: String)   // writes to DataStore, updates state
fun unpinDirectory(directory: String) // used by future "remove project" action
```

### UI

**FAB click**: always open `OpenProjectDialog` (skip `NewSessionQuickDialog`).

**After directory selected** in `OpenProjectDialog`: call `viewModel.pinDirectory(dir)` instead of `viewModel.createNewSession(dir)`. No navigation.

**Empty pinned project group** (pinned but zero sessions): render the project group header normally, but instead of a session list, show a single subtle row:
```
  + 新建对话          (labelMedium, primary color, clickable → createNewSession(dir))
```

### Files

| File | Change |
|------|--------|
| `data/preferences/SessionListPreferences.kt` | Add `pinnedDirectories: Set<String>` field |
| `data/preferences/SessionListPreferencesRepository.kt` | Add `addPinnedDirectory`, `removePinnedDirectory`, `pinnedDirectoriesFlow` |
| `ui/screens/sessions/SessionListViewModel.kt` | Merge pinned dirs into groups, add `pinDirectory` / `unpinDirectory` |
| `ui/screens/sessions/SessionListScreen.kt` | FAB → `OpenProjectDialog` always; on select → `pinDirectory`; empty group row |

---

## Implementation Order

All five tasks are independent. Suggested parallel wave:

| Wave | Tasks | Notes |
|------|-------|-------|
| 1 (parallel) | Task 1 (Banner) + Task 4 (abort fix) + Task 3 (metadata display) | Small, isolated changes |
| 2 (parallel) | Task 2 (MCP management) + Task 5 (FAB / pinned projects) | Both add new data layer + UI |

## Out of Scope

- Adding/deleting/creating MCP servers (config write UI only does enable/disable)
- MCP server status/health indicators (no API support)
- In-app OpenCode restart (user restarts manually after save)
- Global (non-project) MCP management
- Persisting pinned directories across server switches (pinned list is per-server-connection)
