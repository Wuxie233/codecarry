# oc-remote Four Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:tester-first-execution (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement four improvements: fix banner whitespace, add MCP management via config file read/write, show agent message model+time metadata, and fix the long-press-edit missing abort bug.

**Architecture:** Wave 1 contains three small isolated changes (banner, metadata, abort fix) that can be parallelized. Wave 2 is the MCP management feature which requires new domain model, repository methods (including a new writeFile API call), ViewModel, and UI bottom sheet, wired into the existing project three-dot menu.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, kotlinx.serialization, Hilt (DI), Retrofit-style suspend functions in OpenCodeApi

**Spec:** `docs/superpowers/specs/2026-04-20-oc-remote-improvements-design.md`

---

## File Map

### Wave 1 — Small Isolated Changes

| File | Change |
|------|--------|
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ActiveConversationsBanner.kt` | Add `Spacer(8.dp)` after LazyRow |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` | Add `abortSession()` before `revertMessage()`; add model+time row to assistant bubble |

### Wave 2 — MCP Management

| File | Change |
|------|--------|
| `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt` | Add `writeFile(conn, path, content)` suspend function |
| `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpConfig.kt` | **New** — `McpConfig` + `McpServer` data classes |
| `app/src/main/kotlin/dev/minios/ocremote/data/repository/ServerRepository.kt` | Add `readMcpConfig()` + `writeMcpConfig()` |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt` | **New** — `McpUiState` sealed class + ViewModel |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt` | **New** — `ModalBottomSheet` composable |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeader.kt` | Add "管理 MCP" menu item + `onManageMcp` callback |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` | Instantiate `McpViewModel`, wire bottom sheet open state |
| `app/src/test/kotlin/dev/minios/ocremote/data/repository/McpConfigParserTest.kt` | **New** — unit tests for JSON parsing |

---

## Wave 1, Task A: Banner Whitespace Fix

**File:** `ActiveConversationsBanner.kt`

The `Column` in `ActiveConversationsBanner` has `Spacer(8.dp)` above the `LazyRow` but nothing below it in non-AMOLED mode. AMOLED adds its own spacer+divider below. Fix: add an unconditional `Spacer(Modifier.height(8.dp))` after the `LazyRow`, before the AMOLED conditional.

- [ ] **Step 1: Locate insertion point**

  Open `ActiveConversationsBanner.kt`. Find the `LazyRow` block (lines ~81-88). The code after it looks like:
  ```kotlin
  }  // end LazyRow

  if (isAmoled) {
      Spacer(modifier = Modifier.height(8.dp))
      HorizontalDivider(...)
  }
  ```

- [ ] **Step 2: Insert spacer**

  After the closing brace of `LazyRow` and before the `if (isAmoled)` block, insert:
  ```kotlin
  Spacer(modifier = Modifier.height(8.dp))
  ```

- [ ] **Step 3: Visual verification**

  Build and run. In the session list with an active conversation, confirm:
  - Non-AMOLED: equal spacing above and below the cards (both ~8dp)
  - AMOLED: same as before (spacer + divider below, divider + spacer above)

---

## Wave 1, Task B: Long-Press Edit Abort Fix

**File:** `ChatScreen.kt` (~line 2386-2398)

When user selects "Edit" from the long-press message menu, the `onRevert` callback is fired. Currently it only calls `viewModel.revertMessage(messageId)`. It must also call `viewModel.abortSession()` first.

- [ ] **Step 1: Find the callback**

  In `ChatScreen.kt`, search for `revertMessage` calls. The relevant one is inside the callback passed to a user message bubble composable, around line 2386. It looks like:
  ```kotlin
  onRevert = {
      // possibly some state reset
      viewModel.revertMessage(messageId)
  }
  ```

- [ ] **Step 2: Add abort call**

  Insert `viewModel.abortSession()` as the first line inside that lambda:
  ```kotlin
  onRevert = {
      viewModel.abortSession()      // NEW: abort any running generation first
      viewModel.revertMessage(messageId)
  }
  ```

  `abortSession()` is defined at `ChatViewModel.kt:831`. It is safe to call when idle (the API call may return a non-fatal error, which is swallowed).

- [ ] **Step 3: Verify**

  Build. Test manually:
  - Start a long-running prompt. While assistant is generating, long-press the user message → Edit.
  - Confirm: generation stops (busy indicator disappears), draft is populated with the original message text.
  - Also test editing a message when the session is idle — should work the same as before.

---

## Wave 1, Task C: Agent Reply Model + Time Metadata

**File:** `ChatScreen.kt` (~line 3733-3771, assistant bubble composable)

`Message.Assistant` has `modelId: String?` and `time: TimeInfo` (with `created: Long`). These are not currently rendered. Add a faint metadata row at the bottom of the assistant bubble.

- [ ] **Step 1: Find the assistant bubble composable**

  Search `ChatScreen.kt` for the `@Composable` function that renders assistant messages (explore subagent found it around line 3733). Look for where the assistant message content Column ends.

- [ ] **Step 2: Add a date formatter**

  At the top of the composable function (or as a `remember`), add:
  ```kotlin
  val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
  ```

- [ ] **Step 3: Build the metadata string**

  ```kotlin
  val metaText = remember(message.modelId, message.time.created) {
      val time = timeFormat.format(Date(message.time.created))
      if (!message.modelId.isNullOrBlank()) "$time  ·  ${message.modelId}" else time
  }
  ```

- [ ] **Step 4: Render the metadata row**

  At the bottom of the content `Column`, after all existing content, add:
  ```kotlin
  Text(
      text = metaText,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
      textAlign = TextAlign.End,
      modifier = Modifier.fillMaxWidth()
  )
  ```

  Required imports (add if not present):
  - `androidx.compose.ui.text.style.TextAlign`
  - `java.text.SimpleDateFormat`
  - `java.util.Date`
  - `java.util.Locale`

- [ ] **Step 5: Verify**

  Build and run. Open any session with assistant messages. Confirm:
  - Each assistant bubble shows faint `HH:mm  ·  modelId` in bottom-right
  - If `modelId` is null (older messages), shows only `HH:mm`
  - Text does not interfere with content readability (alpha = 0.35 is very faint)
  - Check both light and AMOLED themes

---

## Wave 2, Task D: Add `writeFile` to OpenCodeApi

**File:** `OpenCodeApi.kt` (~line 845, near existing `readFile`)

The current API client has `readFile` (`GET /file/content`) but no write endpoint. The OpenCode server supports file writes — we need to add the client method.

- [ ] **Step 1: Check the OpenCode server write endpoint**

  Read `OpenCodeApi.kt` around line 845 to see `readFile`:
  ```kotlin
  suspend fun readFile(conn: ServerConnection, path: String): FileContent {
      return get(conn, "/file/content") { parameter("path", path) }
  }
  ```

  The write endpoint on the OpenCode server is `PUT /file/content` with body `{"path": "...", "content": "..."}`. Verify by checking if the OpenCode server source/docs confirm this, or test with `curl -X PUT http://<server>/file/content -d '{"path":"/tmp/test","content":"hello"}'`.

- [ ] **Step 2: Add request body data class**

  Near the existing `FileContent` data class in `OpenCodeApi.kt`, add:
  ```kotlin
  @Serializable
  data class WriteFileRequest(
      val path: String,
      val content: String
  )
  ```

- [ ] **Step 3: Add `writeFile` suspend function**

  After the `readFile` function:
  ```kotlin
  suspend fun writeFile(conn: ServerConnection, path: String, content: String) {
      put(conn, "/file/content") {
          setBody(WriteFileRequest(path = path, content = content))
          contentType(ContentType.Application.Json)
      }
  }
  ```

  Check how other `put` calls are structured in `OpenCodeApi.kt` (search for `put(conn`) and match the same pattern exactly.

- [ ] **Step 4: Verify compilation**

  Run: `./gradlew :app:compileDebugKotlin`  
  Expected: builds without errors.

---

## Wave 2, Task E: MCP Domain Model

**File:** `domain/model/McpConfig.kt` (new file)

- [ ] **Step 1: Create the file**

  ```kotlin
  package dev.minios.ocremote.domain.model

  data class McpConfig(
      val filePath: String,
      val rawJson: String,                     // original JSON for round-trip safe write
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

  Note: `rawJson` stores the original file content so unknown fields are preserved on write.

- [ ] **Step 2: Write the JSON parser (pure function, testable)**

  Create `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpConfigParser.kt`:
  ```kotlin
  package dev.minios.ocremote.data.repository

  import dev.minios.ocremote.domain.model.McpConfig
  import dev.minios.ocremote.domain.model.McpServer
  import kotlinx.serialization.json.*

  object McpConfigParser {

      /**
       * Parse the raw JSON string from an OpenCode config file.
       * Returns null if the JSON has no "mcpServers" key (not an error — just no MCP config).
       * Throws on malformed JSON.
       */
      fun parse(filePath: String, rawJson: String): McpConfig? {
          val root = Json.parseToJsonElement(rawJson).jsonObject
          val mcpServers = root["mcpServers"]?.jsonObject ?: return null

          val servers = mcpServers.entries.associate { (name, element) ->
              val obj = element.jsonObject
              name to McpServer(
                  name = name,
                  type = obj["type"]?.jsonPrimitive?.contentOrNull,
                  command = obj["command"]?.jsonPrimitive?.contentOrNull,
                  args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                  enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
              )
          }
          return McpConfig(filePath = filePath, rawJson = rawJson, servers = servers)
      }

      /**
       * Produce updated JSON with the "enabled" field toggled for the given servers.
       * All other fields and unknown fields in the original JSON are preserved.
       */
      fun serialize(config: McpConfig): String {
          val root = Json.parseToJsonElement(config.rawJson).jsonObject.toMutableMap()
          val mcpServers = root["mcpServers"]?.jsonObject?.toMutableMap() ?: mutableMapOf()

          config.servers.forEach { (name, server) ->
              val existing = mcpServers[name]?.jsonObject?.toMutableMap() ?: mutableMapOf()
              existing["enabled"] = JsonPrimitive(server.enabled)
              mcpServers[name] = JsonObject(existing)
          }

          root["mcpServers"] = JsonObject(mcpServers)
          return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(root))
      }
  }
  ```

- [ ] **Step 3: Verify compilation**

  Run: `./gradlew :app:compileDebugKotlin`

---

## Wave 2, Task F: McpConfigParser Unit Tests

**File:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/McpConfigParserTest.kt` (new)

- [ ] **Step 1: Write failing tests**

  ```kotlin
  package dev.minios.ocremote.data.repository

  import org.junit.Assert.*
  import org.junit.Test

  class McpConfigParserTest {

      private val validJson = """
          {
            "mcpServers": {
              "filesystem": {
                "type": "stdio",
                "command": "npx",
                "args": ["-y", "@mcp/server-filesystem"],
                "enabled": true
              },
              "disabled-server": {
                "command": "python",
                "args": ["-m", "mcp_server"],
                "enabled": false
              }
            },
            "providers": {}
          }
      """.trimIndent()

      @Test
      fun `parse returns McpConfig with correct servers`() {
          val config = McpConfigParser.parse("/tmp/config.json", validJson)
          assertNotNull(config)
          assertEquals(2, config!!.servers.size)
          val fs = config.servers["filesystem"]!!
          assertEquals("npx", fs.command)
          assertEquals(listOf("-y", "@mcp/server-filesystem"), fs.args)
          assertTrue(fs.enabled)
          assertFalse(config.servers["disabled-server"]!!.enabled)
      }

      @Test
      fun `parse returns null when no mcpServers key`() {
          val json = """{"providers": {}}"""
          val result = McpConfigParser.parse("/tmp/config.json", json)
          assertNull(result)
      }

      @Test
      fun `serialize preserves unknown fields and updates enabled`() {
          val config = McpConfigParser.parse("/tmp/config.json", validJson)!!
          val toggled = config.copy(
              servers = config.servers.mapValues { (name, server) ->
                  if (name == "filesystem") server.copy(enabled = false) else server
              }
          )
          val output = McpConfigParser.serialize(toggled)
          val reparsed = McpConfigParser.parse("/tmp/config.json", output)!!
          assertFalse(reparsed.servers["filesystem"]!!.enabled)
          // providers key must still be present
          assertTrue(output.contains("\"providers\""))
      }

      @Test
      fun `parse handles missing enabled field as true`() {
          val json = """
              {"mcpServers": {"s": {"command": "node"}}}
          """.trimIndent()
          val config = McpConfigParser.parse("/", json)!!
          assertTrue(config.servers["s"]!!.enabled)
      }
  }
  ```

- [ ] **Step 2: Run tests (expect FAIL since McpConfigParser doesn't exist yet)**

  Run: `./gradlew :app:testDebugUnitTest --tests "*.McpConfigParserTest" -i`  
  Expected: compilation error (class not found). Confirm expected failure.

- [ ] **Step 3: Run tests after Task E is done (expect PASS)**

  Run: `./gradlew :app:testDebugUnitTest --tests "*.McpConfigParserTest" -i`  
  Expected: 4 tests pass.

---

## Wave 2, Task G: Repository Methods

**File:** `ServerRepository.kt`

Add `readMcpConfig` and `writeMcpConfig` that use `McpConfigParser` and the API.

- [ ] **Step 1: Add `readMcpConfig`**

  In `ServerRepository.kt`, add:
  ```kotlin
  /**
   * Searches for an OpenCode config file in the project directory (or global fallback)
   * and parses MCP server entries from it.
   * Returns null if no config file is found (not an error).
   * Returns failure Result if a config file exists but cannot be read or parsed.
   */
  suspend fun readMcpConfig(
      conn: ServerConnection,
      projectDir: String
  ): Result<McpConfig?> = runCatching {
      val homeDir = getHomeDirectory(conn) ?: ""
      val candidates = listOf(
          "$projectDir/.opencode/config.json",
          "$projectDir/opencode.json",
          "$homeDir/.config/opencode/config.json"
      )
      for (path in candidates) {
          val result = runCatching { api.readFile(conn, path) }
          if (result.isSuccess) {
              val raw = result.getOrNull()?.content ?: continue
              return@runCatching McpConfigParser.parse(filePath = path, rawJson = raw)
          }
      }
      null  // no config found
  }
  ```

  Check how `getHomeDirectory` is called in `ServerRepository` — it may already exist for the directory browser feature. If not, it can be replaced with a hardcoded `"~"` expansion by calling `api.listDirectory(conn)` to discover home.

- [ ] **Step 2: Add `writeMcpConfig`**

  ```kotlin
  suspend fun writeMcpConfig(
      conn: ServerConnection,
      config: McpConfig
  ): Result<Unit> = runCatching {
      val updatedJson = McpConfigParser.serialize(config)
      api.writeFile(conn, path = config.filePath, content = updatedJson)
  }
  ```

- [ ] **Step 3: Verify compilation**

  Run: `./gradlew :app:compileDebugKotlin`

---

## Wave 2, Task H: McpViewModel

**File:** `ui/screens/sessions/McpViewModel.kt` (new)

- [ ] **Step 1: Create the ViewModel**

  ```kotlin
  package dev.minios.ocremote.ui.screens.sessions

  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.viewModelScope
  import dagger.hilt.android.lifecycle.HiltViewModel
  import dev.minios.ocremote.data.repository.ServerRepository
  import dev.minios.ocremote.domain.model.McpConfig
  import dev.minios.ocremote.domain.model.McpServer
  import dev.minios.ocremote.domain.model.ServerConfig
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.launch
  import javax.inject.Inject

  sealed class McpUiState {
      object Loading : McpUiState()
      data class Loaded(
          val config: McpConfig,
          val editedServers: Map<String, McpServer> = config.servers,
          val dirty: Boolean = false
      ) : McpUiState()
      object NoConfig : McpUiState()
      data class Error(val message: String) : McpUiState()
      object Saving : McpUiState()
      object SaveSuccess : McpUiState()
      data class SaveError(val message: String) : McpUiState()
  }

  @HiltViewModel
  class McpViewModel @Inject constructor(
      private val repository: ServerRepository
  ) : ViewModel() {

      private val _state = MutableStateFlow<McpUiState>(McpUiState.Loading)
      val state: StateFlow<McpUiState> = _state

      private var currentConn: dev.minios.ocremote.data.api.ServerConnection? = null

      fun load(conn: dev.minios.ocremote.data.api.ServerConnection, projectDir: String) {
          currentConn = conn
          _state.value = McpUiState.Loading
          viewModelScope.launch {
              repository.readMcpConfig(conn, projectDir)
                  .onSuccess { config ->
                      if (config == null) {
                          _state.value = McpUiState.NoConfig
                      } else {
                          _state.value = McpUiState.Loaded(config)
                      }
                  }
                  .onFailure { e ->
                      _state.value = McpUiState.Error(e.message ?: "Unknown error")
                  }
          }
      }

      fun toggleServer(name: String) {
          val current = _state.value as? McpUiState.Loaded ?: return
          val updated = current.editedServers.toMutableMap()
          val server = updated[name] ?: return
          updated[name] = server.copy(enabled = !server.enabled)
          _state.value = current.copy(editedServers = updated, dirty = true)
      }

      fun save() {
          val current = _state.value as? McpUiState.Loaded ?: return
          val conn = currentConn ?: return
          val updatedConfig = current.config.copy(servers = current.editedServers)
          _state.value = McpUiState.Saving
          viewModelScope.launch {
              repository.writeMcpConfig(conn, updatedConfig)
                  .onSuccess { _state.value = McpUiState.SaveSuccess }
                  .onFailure { e -> _state.value = McpUiState.SaveError(e.message ?: "Save failed") }
          }
      }
  }
  ```

- [ ] **Step 2: Verify compilation**

  Run: `./gradlew :app:compileDebugKotlin`

---

## Wave 2, Task I: McpManagementSheet UI

**File:** `ui/screens/sessions/components/McpManagementSheet.kt` (new)

- [ ] **Step 1: Create the composable**

  ```kotlin
  package dev.minios.ocremote.ui.screens.sessions.components

  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.lazy.LazyColumn
  import androidx.compose.foundation.lazy.items
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.text.style.TextOverflow
  import androidx.compose.ui.unit.dp
  import dev.minios.ocremote.domain.model.McpServer
  import dev.minios.ocremote.ui.screens.sessions.McpUiState
  import dev.minios.ocremote.ui.screens.sessions.McpViewModel

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun McpManagementSheet(
      projectName: String,
      viewModel: McpViewModel,
      onDismiss: () -> Unit,
      onSaveSuccess: () -> Unit,   // caller shows Toast and dismisses
  ) {
      val state by viewModel.state.collectAsState()

      // Handle success side-effect
      LaunchedEffect(state) {
          if (state is McpUiState.SaveSuccess) {
              onSaveSuccess()
          }
      }

      ModalBottomSheet(onDismissRequest = onDismiss) {
          Column(
              modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 24.dp)
                  .navigationBarsPadding()
          ) {
              // Header
              Text(
                  text = "MCP 服务器 · $projectName",
                  style = MaterialTheme.typography.titleMedium,
                  modifier = Modifier.padding(bottom = 16.dp)
              )

              when (val s = state) {
                  is McpUiState.Loading -> {
                      Box(
                          modifier = Modifier.fillMaxWidth().height(80.dp),
                          contentAlignment = Alignment.Center
                      ) { CircularProgressIndicator() }
                  }

                  is McpUiState.NoConfig -> {
                      Text(
                          text = "此项目无 MCP 配置",
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                          modifier = Modifier.padding(vertical = 24.dp)
                      )
                      TextButton(
                          onClick = onDismiss,
                          modifier = Modifier.align(Alignment.End)
                      ) { Text("关闭") }
                  }

                  is McpUiState.Error -> {
                      Text(
                          text = s.message,
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.error
                      )
                      Spacer(Modifier.height(8.dp))
                      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                          TextButton(onClick = onDismiss) { Text("取消") }
                          // Retry not available here — caller must re-open sheet
                      }
                  }

                  is McpUiState.Loaded, is McpUiState.Saving, is McpUiState.SaveError -> {
                      val loaded = when (s) {
                          is McpUiState.Loaded -> s
                          is McpUiState.Saving -> null  // use last loaded state, frozen
                          is McpUiState.SaveError -> null
                          else -> null
                      }
                      val servers: Map<String, McpServer> = when (s) {
                          is McpUiState.Loaded -> s.editedServers
                          else -> (s as? McpUiState.Loaded)?.editedServers ?: emptyMap()
                      }
                      val isSaving = s is McpUiState.Saving

                      LazyColumn(
                          modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp),
                          verticalArrangement = Arrangement.spacedBy(0.dp)
                      ) {
                          items(servers.entries.toList(), key = { it.key }) { (name, server) ->
                              McpServerRow(
                                  server = server,
                                  enabled = !isSaving,
                                  onToggle = { viewModel.toggleServer(name) }
                              )
                              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                          }
                      }

                      if (s is McpUiState.SaveError) {
                          Text(
                              text = s.message,
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.error,
                              modifier = Modifier.padding(top = 8.dp)
                          )
                      }

                      Row(
                          modifier = Modifier
                              .fillMaxWidth()
                              .padding(vertical = 16.dp),
                          horizontalArrangement = Arrangement.End,
                          verticalAlignment = Alignment.CenterVertically
                      ) {
                          TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") }
                          Spacer(Modifier.width(8.dp))
                          Button(
                              onClick = { viewModel.save() },
                              enabled = !isSaving && (s as? McpUiState.Loaded)?.dirty == true
                          ) {
                              if (isSaving) {
                                  CircularProgressIndicator(
                                      modifier = Modifier.size(16.dp),
                                      strokeWidth = 2.dp,
                                      color = MaterialTheme.colorScheme.onPrimary
                                  )
                              } else {
                                  Text("保存")
                              }
                          }
                      }
                  }

                  is McpUiState.SaveSuccess -> { /* handled by LaunchedEffect */ }
              }
          }
      }
  }

  @Composable
  private fun McpServerRow(
      server: McpServer,
      enabled: Boolean,
      onToggle: () -> Unit
  ) {
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically
      ) {
          Column(modifier = Modifier.weight(1f)) {
              Text(
                  text = server.name,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
              )
              val preview = listOfNotNull(server.command, server.args.take(2).joinToString(" "))
                  .joinToString(" ")
              if (preview.isNotBlank()) {
                  Text(
                      text = preview,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.padding(top = 2.dp)
                  )
              }
          }
          Switch(
              checked = server.enabled,
              onCheckedChange = { onToggle() },
              enabled = enabled,
              modifier = Modifier.padding(start = 16.dp)
          )
      }
  }
  ```

- [ ] **Step 2: Fix the `servers` variable in the `when` block**

  The `when` block's server state extraction for `Saving`/`SaveError` states is incomplete — we lose the last loaded data. Refactor by keeping a `lastLoaded` remembered value:

  ```kotlin
  // Replace the servers / loaded extraction with:
  var lastLoadedState by remember { mutableStateOf<McpUiState.Loaded?>(null) }
  LaunchedEffect(state) {
      if (state is McpUiState.Loaded) lastLoadedState = state as McpUiState.Loaded
  }
  // Then use lastLoadedState?.editedServers for Saving / SaveError
  ```

- [ ] **Step 3: Verify compilation**

  Run: `./gradlew :app:compileDebugKotlin`

---

## Wave 2, Task J: Wire Up ProjectGroupHeader + SessionListScreen

**Files:** `ProjectGroupHeader.kt` + `SessionListScreen.kt`

### ProjectGroupHeader.kt (lines 51-68, 215-273)

- [ ] **Step 1: Add `onManageMcp` callback to the composable signature**

  In the function parameters (around line 51), add:
  ```kotlin
  onManageMcp: (() -> Unit)? = null,   // null = project has no directory, hide item
  ```

- [ ] **Step 2: Add menu item to DropdownMenu (after line 245, after "Copy Path")**

  ```kotlin
  onManageMcp?.let { action ->
      DropdownMenuItem(
          text = { Text("管理 MCP") },
          onClick = {
              showMenu = false
              action()
          },
          leadingIcon = {
              Icon(
                  imageVector = Icons.Default.Extension,
                  contentDescription = null
              )
          }
      )
  }
  ```

  `Icons.Default.Extension` is the puzzle-piece icon — appropriate for MCP/plugins. Add import if needed.

### SessionListScreen.kt

- [ ] **Step 3: Declare bottom sheet state**

  Near the top of the `SessionListScreen` composable, add:
  ```kotlin
  var mcpSheetProjectDir by remember { mutableStateOf<String?>(null) }
  var mcpSheetProjectName by remember { mutableStateOf("") }
  val mcpViewModel: McpViewModel = hiltViewModel()
  ```

- [ ] **Step 4: Show the bottom sheet when `mcpSheetProjectDir != null`**

  After the main `LazyColumn` (or in the same scope as other dialogs), add:
  ```kotlin
  mcpSheetProjectDir?.let { dir ->
      val context = LocalContext.current
      McpManagementSheet(
          projectName = mcpSheetProjectName,
          viewModel = mcpViewModel,
          onDismiss = { mcpSheetProjectDir = null },
          onSaveSuccess = {
              mcpSheetProjectDir = null
              Toast.makeText(context, "已保存，重启 OpenCode 后生效", Toast.LENGTH_LONG).show()
          }
      )
  }
  ```

- [ ] **Step 5: Pass `onManageMcp` to `ProjectGroupHeader`**

  Find where `ProjectGroupHeader` is called in `SessionListScreen.kt`. Pass:
  ```kotlin
  onManageMcp = project.directory?.let { dir ->
      {
          mcpSheetProjectName = project.name
          mcpSheetProjectDir = dir
          mcpViewModel.load(conn = viewModel.currentConnection, projectDir = dir)
      }
  },
  ```

  Check how `viewModel.currentConnection` is accessed — look at how `SessionListViewModel` exposes the active `ServerConnection` (it's used for other repository calls). Expose it as a val if needed.

- [ ] **Step 6: Full build + verify**

  Run: `./gradlew :app:assembleDebug`  
  Expected: builds cleanly.

---

## Final Verification Checklist

- [ ] Run unit tests: `./gradlew :app:testDebugUnitTest` → all pass
- [ ] Run full build: `./gradlew :app:assembleDebug` → no errors, no warnings added
- [ ] Run LSP diagnostics mentally: check all new files for unused imports, missing `@Composable`, missing Hilt annotations
- [ ] Manual smoke test — banner: open app with active conversation, verify equal spacing above/below cards
- [ ] Manual smoke test — abort fix: send message, while generating long-press → Edit, confirm generation stops
- [ ] Manual smoke test — metadata: send message, confirm faint `HH:mm · modelId` appears bottom-right
- [ ] Manual smoke test — MCP: open project three-dot menu, tap "管理 MCP", verify bottom sheet opens (empty state or list)
- [ ] Commit with message: `feat: banner fix, MCP management, message metadata, edit abort fix (closes #<N>)`
