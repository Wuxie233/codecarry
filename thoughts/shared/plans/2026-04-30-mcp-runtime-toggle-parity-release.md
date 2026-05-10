---
date: 2026-04-30
topic: "MCP Runtime Toggle Parity with OpenCode Web"
issue: 21
scope: app
contract: none
---

# MCP Runtime Toggle Parity Implementation Plan

**Goal:** Replace the current file-based MCP `enabled` editor in the APK MCP sheet with real-time per-project runtime connect/disconnect toggles that mirror OpenCode Web's `/mcp` runtime panel, then ship as signed v1.6.27.

**Architecture:** Single-Activity Compose / Hilt / Ktor / DataStore. Vertical slice strictly bottom-up: extend `OpenCodeApi` with `GET /mcp`, `POST /mcp/{name}/connect`, `POST /mcp/{name}/disconnect` (project-scoped via `x-opencode-directory`); add a runtime toggle transaction to `ServerRepository`; rewrite `McpViewModel`/`McpUiState` around `McpRuntimeStatus` rather than file `McpServer.enabled`; rebuild `McpManagementSheet` row semantics to match Web (checked == connected, unchecked when disabled/failed/needs_auth/needs_client_registration, per-row pending spinner, sanitized error label). File-based `McpConfigParser` path is preserved as a strict fallback for old OpenCode servers that 404 the runtime endpoints — fallback shows status only, never an interactive switch. No persistent config writes from APK in this release.

**Design:** [thoughts/shared/designs/2026-04-30-mcp-runtime-toggle-parity-design.md](../designs/2026-04-30-mcp-runtime-toggle-parity-design.md)

**Contract:** none (single-domain Android/Kotlin plan; backend is the existing OpenCode server, treated as a fixed external dependency)

**Strict batch ordering (enforced by executor):** API DTO/endpoints → repository runtime toggle transaction → ViewModel pending/error state → UI switch parity & status labels → integration tests → version & release notes → signed release publish/verify. Do NOT start Batch N until Batch N-1's implementer + reviewer cycles have all passed. This ordering is mandatory because each layer depends on the prior layer's contracts.

**Out of scope (documented follow-ups, do NOT implement here):**
- Full mobile OAuth / device-code flow for `needs_auth` MCP servers (issue: follow-up "MCP auth flow on Android"). This release only surfaces the auth-required state honestly; clicking the switch on a `needs_auth` server MUST NOT fake a connect.
- Client registration flow for `needs_client_registration` (same follow-up issue).
- Persistent MCP config editing (command/args/headers/env/oauth values) from APK — Web does not do this from its runtime panel either; remains a separate "MCP config editor" follow-up.
- Broad MCP-panel UX redesign (search, grouping, descriptions, icons) — out of scope to keep this release tight; track in a "MCP panel polish" follow-up.
- Persisting per-project MCP toggle preference across restarts — runtime is by definition session-scoped; if persistence is wanted later, that's a server-side concern.

**Hard release gate:** Signed publish (Batch 7) only proceeds if `release.yml` workflow has access to `KEYSTORE_BASE64` / `KEYSTORE_ALIAS` / `KEYSTORE_PASSWORD` GitHub Actions secrets, AND the resulting signer certificate SHA-256 matches the v1.6.23 reference fingerprint `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`. If either condition fails, stop at "release-ready unsigned APK + draft notes" and surface the gap to the user. Never push to upstream — origin is the user's fork.

---

## Acceptance Criteria (must hold for the release to ship)

These mirror Web behavior 1:1 and are verified by the integration tests in Batch 5 plus a manual smoke pass before Batch 7:

1. **Connected → checked.** A server whose runtime status is `connected` renders with the Compose `Switch` in the checked state.
2. **Click connected → disconnects.** Toggling a checked switch issues `POST /mcp/{name}/disconnect`, then refetches `GET /mcp`. On success the row reflects the new status. On HTTP failure the row reverts to the previous (connected) state and shows a sanitized inline error.
3. **Disabled / failed / needs_auth / needs_client_registration → unchecked.** Any non-`connected` runtime status renders unchecked.
4. **Click unchecked, safe-to-connect → attempts connect.** Toggling an unchecked switch when status is `disabled` or `failed` issues `POST /mcp/{name}/connect`, then refetches `GET /mcp`. Success updates the row; failure preserves prior state and shows sanitized inline error.
5. **Click unchecked, auth-required → does NOT call connect.** Toggling when status is `needs_auth` or `needs_client_registration` MUST NOT issue a connect call. Instead the row keeps its status label and shows an inline hint that auth/client-registration is required and not yet supported on Android (link/mention the follow-up).
6. **Status refetch after every successful toggle.** After any successful connect or disconnect, the sheet reissues `GET /mcp` scoped to the current project directory and re-renders.
7. **Per-row pending state.** While a single row's connect/disconnect is in flight, only that row shows a spinner and only that row's switch is disabled. Other rows remain interactive.
8. **Project scoping.** Every `GET /mcp`, `POST /mcp/{name}/connect`, `POST /mcp/{name}/disconnect` carries `x-opencode-directory` set to the active project directory (URI-encoded, same convention used by `listSessions`, `findFiles`, etc.).
9. **Old-server fallback.** If `GET /mcp` returns 404 (or 405 / "Not Found" / connection error categorised as endpoint-missing), the sheet falls back to the existing file-based parser path and renders read-only rows with a banner "运行时控制需要更新的 OpenCode 服务器；当前仅显示配置文件信息。" — no switches, no connect/disconnect calls.
10. **No leakage.** Switches and status labels MUST NOT render `command`, `args`, headers, env vars, OAuth values, or any field outside `{name, status, brief sanitized error}`.
11. **Signer parity.** Final v1.6.27 APK is verified by `apksigner verify --print-certs`; signer certificate SHA-256 matches v1.6.23 reference fingerprint.

---

## Dependency Graph

```
Batch 1 (parallel - API layer):           1.1, 1.2, 1.3                      [no deps]
Batch 2 (sequential after Batch 1):       2.1                                 [depends on 1.1, 1.2, 1.3]
Batch 3 (sequential after Batch 2):       3.1                                 [depends on 2.1]
Batch 4 (sequential after Batch 3):       4.1, 4.2                            [depends on 3.1]
Batch 5 (parallel after Batch 4):         5.1, 5.2, 5.3                       [depends on 1-4]
Batch 6 (sequential after Batch 5):       6.1, 6.2                            [depends on 5.x]
Batch 7 (sequential after Batch 6):       7.1, 7.2                            [depends on 6.x]
```

Note: Inside Batch 4, 4.1 (Sheet) and 4.2 (project-list MCP hint update) touch different files and may run in parallel. Inside Batch 5, the three test files target disjoint classes and run in parallel. Batches 6 and 7 are strictly serial because they bump version, edit release notes, and tag/publish.

---

## Batch 1: API Layer — Runtime DTO + Endpoints (parallel)

All three tasks here touch independent regions of `OpenCodeApi.kt` (DTO additions vs. method additions vs. test files) and have no dependencies among themselves.
Tasks: 1.1, 1.2, 1.3

### Task 1.1: Add MCP runtime DTOs and `McpRuntimeStatus` domain model

**File:** `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpRuntime.kt` (new file)
**Test:** `app/src/test/kotlin/dev/minios/ocremote/domain/model/McpRuntimeTest.kt` (new file)
**Depends:** none
**Domain:** general

**Why this is its own task:** the runtime model is consumed by the API DTO mapping (1.2), the repository (2.1), the ViewModel (3.1), and the UI (4.1). Defining it once in the domain layer prevents drift. Web's runtime panel exposes one of five conceptual states; we mirror them as a sealed enum so `when` matches are exhaustive.

**Implementation guidance:**
- Define a Kotlin `enum class McpRuntimeState { CONNECTED, DISABLED, FAILED, NEEDS_AUTH, NEEDS_CLIENT_REGISTRATION, UNKNOWN }`. `UNKNOWN` is the safe default if the server returns a state string we don't recognise (forward-compat).
- Define `data class McpRuntimeStatus(val name: String, val state: McpRuntimeState, val errorMessage: String? = null)`. `errorMessage` is a sanitized one-line string (caller is responsible for sanitization; see Task 1.2).
- Define `data class McpRuntimeSnapshot(val servers: List<McpRuntimeStatus>, val supportsRuntimeControl: Boolean)`. `supportsRuntimeControl` is `true` when the server returned a 2xx for `GET /mcp`, `false` when the server 404'd / 405'd the endpoint (fallback path).
- Add a top-level helper `fun parseMcpRuntimeState(raw: String?): McpRuntimeState` that maps server strings to the enum: `"connected" -> CONNECTED`, `"disabled" -> DISABLED`, `"failed" -> FAILED`, `"needs_auth" / "needsAuth" -> NEEDS_AUTH`, `"needs_client_registration" / "needsClientRegistration" -> NEEDS_CLIENT_REGISTRATION`, anything else (including null) -> `UNKNOWN`. Be tolerant of camelCase vs snake_case because OpenCode source has both styles in its TS interface.
- No serialization annotations on the domain model itself. Keep DTOs in `data.api`.

**Test (write first, must FAIL before implementation):**
- `parseMcpRuntimeState("connected")` → `CONNECTED`.
- `parseMcpRuntimeState("needs_auth")` and `parseMcpRuntimeState("needsAuth")` both → `NEEDS_AUTH`.
- `parseMcpRuntimeState("needs_client_registration")` and `parseMcpRuntimeState("needsClientRegistration")` both → `NEEDS_CLIENT_REGISTRATION`.
- `parseMcpRuntimeState(null)` → `UNKNOWN`.
- `parseMcpRuntimeState("garbage")` → `UNKNOWN`.
- `McpRuntimeStatus("foo", CONNECTED)` is equal to itself by data-class equality.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.domain.model.McpRuntimeTest"
./gradlew :app:compileDebugKotlin
```

**Commit:** `feat(mcp): add runtime status domain model`

---

### Task 1.2: Add `getMcpRuntime`, `connectMcp`, `disconnectMcp` methods to `OpenCodeApi`

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiMcpRuntimeTest.kt` (new file)
**Depends:** none (consumes only `McpRuntimeStatus`/`McpRuntimeSnapshot` from 1.1; 1.1 + 1.2 share an implementer dependency on the domain model file existing first, but the executor can run them as parallel implementer tasks because both write disjoint files — the compile step at the end of Batch 1 catches any drift)
**Domain:** general

**Why these three methods:** the design specifies `GET /mcp` for status, `POST /mcp/{name}/connect` and `POST /mcp/{name}/disconnect` for control, all project-scoped. They follow the existing pattern (e.g. `listSessions`, `findFiles`) of accepting `directory: String? = null` and forwarding it as `x-opencode-directory`.

**Implementation guidance:**

Add a new region `// ============ MCP Runtime ============` placed AFTER the existing `// ============ Commands ============` region and BEFORE `// ============ Files ============`.

Add these DTOs in the DTO section at the bottom of the file (alongside `CommandInfo`, `AgentInfo`, etc.):

```kotlin
@Serializable
data class McpRuntimeServerDto(
    val name: String? = null,
    val state: String? = null,
    @SerialName("status") val statusFallback: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class McpRuntimeListDto(
    val servers: List<McpRuntimeServerDto> = emptyList(),
)
```

The dual `state` / `statusFallback` and `error` / `message` fields exist because the OpenCode server's TypeScript source uses `state` in some builds and `status` in others; same for error message. Using `@SerialName` and a fallback field lets us tolerate both.

Add three suspend methods to `OpenCodeApi`:

```kotlin
/**
 * GET /mcp — list MCP servers and their runtime state for the active project.
 *
 * Returns null when the server does not expose runtime MCP endpoints
 * (404 / 405). Callers should fall back to file-based config parsing in that case.
 *
 * Throws on other transport / 5xx errors so the repository can preserve previous state.
 */
suspend fun getMcpRuntime(
    conn: ServerConnection,
    directory: String? = null,
): List<McpRuntimeStatus>? {
    val response = httpClient.get("${conn.baseUrl}/mcp") {
        conn.authHeader?.let { header("Authorization", it) }
        directory?.let { header("x-opencode-directory", android.net.Uri.encode(it)) }
    }
    if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed) {
        return null
    }
    if (!response.status.isSuccess()) {
        throw IOException("getMcpRuntime failed: ${response.status}")
    }
    val body = response.bodyAsText()
    val parsed = runCatching {
        // Try wrapped {servers: [...]} first
        json.decodeFromString(McpRuntimeListDto.serializer(), body).servers
    }.recoverCatching {
        // Then try bare list [...]
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(McpRuntimeServerDto.serializer()), body)
    }.recoverCatching {
        // Then try map {name: {state: ...}, ...}
        val obj = json.parseToJsonElement(body).jsonObject
        obj.entries.map { (name, value) ->
            val nested = (value as? JsonObject) ?: JsonObject(emptyMap())
            McpRuntimeServerDto(
                name = name,
                state = nested["state"]?.jsonPrimitive?.contentOrNull,
                statusFallback = nested["status"]?.jsonPrimitive?.contentOrNull,
                error = nested["error"]?.jsonPrimitive?.contentOrNull,
                message = nested["message"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrElse { emptyList() }

    return parsed.map { dto ->
        McpRuntimeStatus(
            name = dto.name.orEmpty(),
            state = parseMcpRuntimeState(dto.state ?: dto.statusFallback),
            errorMessage = sanitizeMcpError(dto.error ?: dto.message),
        )
    }.filter { it.name.isNotBlank() }
}

/**
 * POST /mcp/{name}/connect — request runtime connect for one MCP server.
 *
 * Returns true on 2xx. Returns false on 404 / 405 (server lacks endpoint).
 * Throws on other transport errors.
 */
suspend fun connectMcp(
    conn: ServerConnection,
    name: String,
    directory: String? = null,
): Boolean {
    val encoded = java.net.URLEncoder.encode(name, "UTF-8")
    val response = httpClient.post("${conn.baseUrl}/mcp/$encoded/connect") {
        conn.authHeader?.let { header("Authorization", it) }
        directory?.let { header("x-opencode-directory", android.net.Uri.encode(it)) }
    }
    if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed) {
        return false
    }
    if (!response.status.isSuccess()) {
        throw IOException("connectMcp failed: ${response.status}")
    }
    return true
}

/**
 * POST /mcp/{name}/disconnect — same shape as [connectMcp] but disconnects.
 */
suspend fun disconnectMcp(
    conn: ServerConnection,
    name: String,
    directory: String? = null,
): Boolean {
    val encoded = java.net.URLEncoder.encode(name, "UTF-8")
    val response = httpClient.post("${conn.baseUrl}/mcp/$encoded/disconnect") {
        conn.authHeader?.let { header("Authorization", it) }
        directory?.let { header("x-opencode-directory", android.net.Uri.encode(it)) }
    }
    if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed) {
        return false
    }
    if (!response.status.isSuccess()) {
        throw IOException("disconnectMcp failed: ${response.status}")
    }
    return true
}

private fun sanitizeMcpError(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // One line, capped, with secret-shaped tokens stripped. Matches the
    // existing convention from issue #19 of NOT echoing command/args/env.
    val oneLine = trimmed.lineSequence().firstOrNull().orEmpty()
    val redacted = oneLine.replace(Regex("(?i)(token|key|secret|password|authorization)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
    return redacted.take(200)
}
```

Add the necessary imports (`kotlinx.serialization.builtins.ListSerializer`, `java.net.URLEncoder`, `dev.minios.ocremote.domain.model.McpRuntimeStatus`, `dev.minios.ocremote.domain.model.parseMcpRuntimeState`).

**Test (write first, must FAIL before implementation):**

Use `MockEngine` (the same pattern as `OpenCodeApiMcpHeaderTest`). Cover:

1. `getMcpRuntime` with `directory = "/workspace/proj"` sends `GET /mcp` carrying header `x-opencode-directory = Uri.encode("/workspace/proj")`.
2. `getMcpRuntime` decodes wrapped `{"servers":[{"name":"fs","state":"connected"}]}` → one `McpRuntimeStatus("fs", CONNECTED)`.
3. `getMcpRuntime` decodes bare list `[{"name":"fs","state":"failed","error":"boom"}]` → one status with `state=FAILED` and `errorMessage="boom"`.
4. `getMcpRuntime` decodes map shape `{"fs":{"state":"needs_auth"}}` → one status with `NEEDS_AUTH`.
5. `getMcpRuntime` returns `null` when the server responds 404.
6. `getMcpRuntime` returns `null` when the server responds 405.
7. `getMcpRuntime` throws `IOException` on 500.
8. `connectMcp("a/b name")` POSTs to `/mcp/a%2Fb+name/connect` (URL-encoded; the `+` for space is acceptable since `URLEncoder` produces it; if test fails on `%20` adjust expected to match `URLEncoder.encode`).
9. `connectMcp` returns `false` on 404, returns `true` on 200, throws on 500.
10. `disconnectMcp` mirrors `connectMcp` cases (one happy + one 404 + one 500).
11. `sanitizeMcpError("token=abc123 boom")` → `"token=<redacted> boom"` (assert via reflection or by exposing as `internal fun` in the same package for test access).

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.api.OpenCodeApiMcpRuntimeTest"
./gradlew :app:compileDebugKotlin
```

**Commit:** `feat(mcp): add runtime list/connect/disconnect API methods`

---

### Task 1.3: Extend `OpenCodeApiMcpHeaderTest` to cover new methods' header parity

**File:** `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiMcpHeaderTest.kt` (extend existing)
**Test:** self
**Depends:** none (writes only to a test file; will compile-fail until 1.2 lands the new methods, which is fine — Batch 1 is gated on full compile after all three implementers finish)
**Domain:** general

**Why this is its own task:** issue #19 introduced this test as the canonical place asserting `x-opencode-directory` is forwarded. Keeping new MCP runtime methods covered here guarantees the header parity invariant doesn't regress. Splitting from 1.2 lets a different implementer focus only on header assertions.

**Implementation guidance:**

Append three new `@Test` methods to the existing class (do not modify existing tests):

```kotlin
@Test
fun `getMcpRuntime forwards directory header`() = runBlocking {
    val captured = mutableListOf<HttpRequestData>()
    val api = newApiForMcpRuntime(captured, body = """{"servers":[]}""")
    api.getMcpRuntime(testConn, directory = "/workspace/proj")
    val req = captured.single { it.url.encodedPath == "/mcp" }
    assertEquals("Uri.encode(\"/workspace/proj\")", Uri.encode("/workspace/proj"), req.headers["x-opencode-directory"])
}

@Test
fun `connectMcp forwards directory header and url-encodes name`() = runBlocking {
    val captured = mutableListOf<HttpRequestData>()
    val api = newApiForMcpRuntime(captured, body = "")
    api.connectMcp(testConn, name = "weird name", directory = "/workspace/proj")
    val req = captured.single { it.url.encodedPath.startsWith("/mcp/") && it.url.encodedPath.endsWith("/connect") }
    assertEquals(Uri.encode("/workspace/proj"), req.headers["x-opencode-directory"])
    assertTrue(req.url.encodedPath.contains("weird")) // URL-encoded form
}

@Test
fun `disconnectMcp omits directory header when null`() = runBlocking {
    val captured = mutableListOf<HttpRequestData>()
    val api = newApiForMcpRuntime(captured, body = "")
    api.disconnectMcp(testConn, name = "fs", directory = null)
    val req = captured.single { it.url.encodedPath == "/mcp/fs/disconnect" }
    assertNull(req.headers["x-opencode-directory"])
}
```

Add a `private fun newApiForMcpRuntime(captured: MutableList<HttpRequestData>, body: String): OpenCodeApi` helper that mirrors the existing `newApi` factory but routes any `/mcp*` path to `respondJson(body, HttpStatusCode.OK)`.

**Test (write first, must FAIL before implementation):** the three tests above must initially fail because 1.2's methods do not exist; they pass once 1.2 lands.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.api.OpenCodeApiMcpHeaderTest"
```

**Commit:** `test(mcp): extend header parity coverage to runtime endpoints`

---

## Batch 2: Repository Runtime Toggle Transaction

This task wraps the API in a single repository entrypoint that the ViewModel can call without knowing about HTTP. It is sequential because the Sheet test fixtures depend on this exact contract.
Tasks: 2.1

### Task 2.1: Add `loadMcpRuntime` and `toggleMcpRuntime` to `ServerRepository`

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/ServerRepository.kt` (extend existing)
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryMcpRuntimeTest.kt` (new file)
**Depends:** 1.1, 1.2, 1.3
**Domain:** general

**Why this layer exists:** the ViewModel must not call HTTP directly and must not know about the file-fallback path. The repository owns: (a) calling `getMcpRuntime`, (b) on `null` (endpoint missing), falling back to file-based parsing and marking `supportsRuntimeControl = false`, (c) executing the runtime toggle transaction (decide connect-or-disconnect from current state, call the API, refetch status, return the new snapshot OR a precise error preserving the previous snapshot).

**Implementation guidance:**

Add at the bottom of `ServerRepository` (before the `// ============ Private ============` line, alongside the existing `readMcpConfigState` / `readMcpConfig` / `writeMcpConfig`):

```kotlin
// ============ MCP Runtime ============

/**
 * Load runtime MCP status for the current project, with file-config fallback
 * for older OpenCode servers that do not expose /mcp.
 *
 * Returns a snapshot whose [McpRuntimeSnapshot.supportsRuntimeControl] tells
 * the UI whether to render interactive switches or read-only file rows.
 */
suspend fun loadMcpRuntime(
    conn: ServerConnection,
    projectDir: String,
): Result<McpRuntimeSnapshot> = runCatching {
    val directory = projectDir.takeIf { it.isNotBlank() }
    val runtime = api.getMcpRuntime(conn, directory = directory)
    if (runtime != null) {
        return@runCatching McpRuntimeSnapshot(servers = runtime, supportsRuntimeControl = true)
    }
    // Fallback: file-based config, mapped to UNKNOWN-state read-only rows.
    val fallbackServers = when (val state = readMcpConfigState(conn, projectDir)) {
        is McpConfigLoadState.Loaded -> state.config.servers.values.map {
            McpRuntimeStatus(name = it.name, state = McpRuntimeState.UNKNOWN, errorMessage = null)
        }
        is McpConfigLoadState.Empty -> emptyList()
        is McpConfigLoadState.NotFound -> emptyList()
        is McpConfigLoadState.Error -> throw state.cause ?: IllegalStateException(state.message)
    }
    McpRuntimeSnapshot(servers = fallbackServers, supportsRuntimeControl = false)
}

/**
 * Runtime toggle transaction:
 *   1. Inspect current [previousState] for [name].
 *   2. CONNECTED → call disconnect; anything safe-to-connect (DISABLED, FAILED) → call connect.
 *   3. After the API succeeds, refetch the full runtime list.
 *   4. On any failure, return the original snapshot unchanged so the UI can revert.
 *
 * Auth-required states (NEEDS_AUTH, NEEDS_CLIENT_REGISTRATION) are rejected
 * here with a typed error rather than calling connect — the UI is responsible
 * for not even reaching this method for those states, but defence-in-depth.
 */
suspend fun toggleMcpRuntime(
    conn: ServerConnection,
    projectDir: String,
    name: String,
    previous: McpRuntimeSnapshot,
): Result<McpRuntimeSnapshot> {
    val directory = projectDir.takeIf { it.isNotBlank() }
    val target = previous.servers.firstOrNull { it.name == name }
        ?: return Result.failure(IllegalStateException("Unknown MCP server: $name"))
    return when (target.state) {
        McpRuntimeState.CONNECTED -> performToggle(conn, directory, projectDir, name, previous) {
            api.disconnectMcp(conn, name, directory = directory)
        }
        McpRuntimeState.DISABLED, McpRuntimeState.FAILED, McpRuntimeState.UNKNOWN -> performToggle(
            conn, directory, projectDir, name, previous,
        ) {
            api.connectMcp(conn, name, directory = directory)
        }
        McpRuntimeState.NEEDS_AUTH, McpRuntimeState.NEEDS_CLIENT_REGISTRATION ->
            Result.failure(McpAuthRequiredException(state = target.state, name = name))
    }
}

private suspend fun performToggle(
    conn: ServerConnection,
    directory: String?,
    projectDir: String,
    name: String,
    previous: McpRuntimeSnapshot,
    action: suspend () -> Boolean,
): Result<McpRuntimeSnapshot> = runCatching {
    val supported = action()
    if (!supported) {
        // Endpoint disappeared between load and toggle — surface as fallback.
        throw McpRuntimeUnsupportedException()
    }
    val refreshed = api.getMcpRuntime(conn, directory = directory)
        ?: throw McpRuntimeUnsupportedException()
    McpRuntimeSnapshot(servers = refreshed, supportsRuntimeControl = true)
}.recoverCatching { error ->
    // Preserve previous snapshot — the caller decides how to display the error.
    throw McpToggleException(name = name, previous = previous, cause = error)
}
```

Add (in the same file, near the existing `OpenCodeFileNotFoundException`):

```kotlin
class McpAuthRequiredException(
    val state: McpRuntimeState,
    val name: String,
) : RuntimeException("MCP server '$name' requires ${state.name.lowercase()}")

class McpRuntimeUnsupportedException :
    RuntimeException("OpenCode server does not support runtime MCP control")

class McpToggleException(
    val name: String,
    val previous: McpRuntimeSnapshot,
    cause: Throwable,
) : RuntimeException("Failed to toggle MCP server '$name': ${cause.message}", cause)
```

Required imports: `McpRuntimeSnapshot`, `McpRuntimeState`, `McpRuntimeStatus`, `McpConfigLoadState`.

**Test (write first, must FAIL before implementation):**

Create `ServerRepositoryMcpRuntimeTest.kt` using a hand-rolled fake `OpenCodeApi` (extend the existing pattern in `ServerRepositoryTest.kt`; if no fakes exist there yet, create a minimal `FakeMcpApi` that exposes `getMcpRuntime`, `connectMcp`, `disconnectMcp` as overridable lambdas). Cover:

1. **Connected → toggle disconnects.** Initial snapshot has one `connected` server. `toggleMcpRuntime` issues `disconnectMcp(name)`, then `getMcpRuntime`, returns refreshed snapshot. Assert disconnect was called exactly once and connect was not called.
2. **Disabled → toggle connects.** Mirror of #1 but `disabled` initial state, `connectMcp` called.
3. **Failed → toggle connects.** Same as #2 with `failed` initial state.
4. **needs_auth → toggle returns `Result.failure(McpAuthRequiredException)` and never calls connect/disconnect.**
5. **needs_client_registration → same as #4.**
6. **Connect API throws → result is `Result.failure(McpToggleException)` carrying `previous` snapshot identical to the input.**
7. **`getMcpRuntime` returns null on initial load → fallback path returns snapshot with `supportsRuntimeControl = false` and rows mapped from file config (use a stubbed `readMcpConfigState`).**
8. **Fallback path with file-config error throws — `loadMcpRuntime` returns `Result.failure` with the original cause.**
9. **`getMcpRuntime` returns null mid-toggle (after a successful connect) → `Result.failure(McpToggleException)` whose cause is `McpRuntimeUnsupportedException`.**
10. **Project directory is forwarded.** Assert that the captured `directory` argument on every API call equals the trimmed `projectDir`.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.ServerRepositoryMcpRuntimeTest"
./gradlew :app:compileDebugKotlin
```

**Commit:** `feat(mcp): add runtime toggle transaction in ServerRepository`

---

## Batch 3: ViewModel — Per-Server Pending & Error State

The ViewModel is rewritten around runtime status, with per-server pending toggles and a fallback flag for old servers. Sequential because Batch 4 (UI) consumes this state shape.
Tasks: 3.1

### Task 3.1: Rewrite `McpViewModel` around runtime status with per-server pending and inline error

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt` (rewrite)
**Test:** existing `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelTest.kt` is updated to cover new state shape (lives in the same file, but the rewrite is the implementation; new test class for runtime semantics is `McpRuntimeViewModelTest.kt` added in Batch 5 task 5.2)
**Depends:** 2.1
**Domain:** general

**Why a rewrite, not an additive patch:** the current `McpUiState` is keyed off `McpConfig` + `editedServers` for a save-button workflow. Runtime parity has fundamentally different semantics: there is no Save button, every toggle is an immediate transaction, errors are per-row, and there are two read-only "old server fallback" and "no MCP servers" branches that map to entirely different UI affordances. Trying to bolt the new states onto the old `Loaded` data class will produce dead fields and confusing toggles. The cleanest move is to replace `McpUiState` with a runtime-shaped sealed hierarchy and update the Sheet (Task 4.1) accordingly.

**Implementation guidance:**

Replace the `McpUiState` sealed class and the `McpStateController` body with the following shape (preserve the existing `@HiltViewModel` constructor signature so DI wiring at the call site does not change):

```kotlin
sealed class McpUiState {
    data object Loading : McpUiState()

    /** Successful runtime load. Per-server pending names tracks in-flight toggles. */
    data class Runtime(
        val snapshot: McpRuntimeSnapshot,
        val pendingNames: Set<String> = emptySet(),
        val rowErrors: Map<String, String> = emptyMap(),
        val sheetError: String? = null,
    ) : McpUiState()

    /** Server lacks /mcp endpoints; fallback to read-only file config rows. */
    data class FallbackReadOnly(
        val snapshot: McpRuntimeSnapshot, // supportsRuntimeControl == false
    ) : McpUiState()

    data class LoadError(val message: String) : McpUiState()
    data object Empty : McpUiState() // 0 servers and runtime control supported
}

internal class McpRuntimeController(
    private val scope: CoroutineScope,
    private val loadRuntime: suspend (ServerConnection, String) -> Result<McpRuntimeSnapshot>,
    private val toggleRuntime: suspend (ServerConnection, String, String, McpRuntimeSnapshot) -> Result<McpRuntimeSnapshot>,
) {
    private val _state = MutableStateFlow<McpUiState>(McpUiState.Loading)
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    private var conn: ServerConnection? = null
    private var projectDir: String? = null

    fun load(conn: ServerConnection, projectDir: String) {
        this.conn = conn
        this.projectDir = projectDir
        loadInternal()
    }

    fun refresh() = loadInternal()

    private fun loadInternal() {
        val c = conn ?: return
        val p = projectDir ?: return
        _state.value = McpUiState.Loading
        scope.launch {
            loadRuntime(c, p)
                .onSuccess { snapshot ->
                    _state.value = when {
                        !snapshot.supportsRuntimeControl -> McpUiState.FallbackReadOnly(snapshot)
                        snapshot.servers.isEmpty() -> McpUiState.Empty
                        else -> McpUiState.Runtime(snapshot = snapshot)
                    }
                }
                .onFailure { error ->
                    _state.value = McpUiState.LoadError(error.message ?: "Failed to load MCP runtime")
                }
        }
    }

    /**
     * Web-parity toggle:
     * - CONNECTED → disconnect
     * - DISABLED / FAILED → connect
     * - NEEDS_AUTH / NEEDS_CLIENT_REGISTRATION → set row error, do NOT call API
     */
    fun toggle(name: String) {
        val c = conn ?: return
        val p = projectDir ?: return
        val current = (_state.value as? McpUiState.Runtime) ?: return
        val target = current.snapshot.servers.firstOrNull { it.name == name } ?: return

        if (target.state == McpRuntimeState.NEEDS_AUTH || target.state == McpRuntimeState.NEEDS_CLIENT_REGISTRATION) {
            _state.value = current.copy(
                rowErrors = current.rowErrors + (name to authRequiredHint(target.state)),
            )
            return
        }
        if (name in current.pendingNames) return // ignore double-tap

        _state.value = current.copy(
            pendingNames = current.pendingNames + name,
            rowErrors = current.rowErrors - name,
            sheetError = null,
        )

        scope.launch {
            toggleRuntime(c, p, name, current.snapshot)
                .onSuccess { refreshed ->
                    val nextState = (state.value as? McpUiState.Runtime) ?: McpUiState.Runtime(refreshed)
                    _state.value = McpUiState.Runtime(
                        snapshot = refreshed,
                        pendingNames = nextState.pendingNames - name,
                        rowErrors = nextState.rowErrors - name,
                        sheetError = null,
                    )
                }
                .onFailure { error ->
                    val previous = (error as? McpToggleException)?.previous ?: current.snapshot
                    val message = (error as? McpToggleException)?.cause?.message
                        ?: error.message
                        ?: "Toggle failed"
                    val safeMessage = sanitizeForUi(message)
                    val baseline = (state.value as? McpUiState.Runtime) ?: McpUiState.Runtime(previous)
                    _state.value = baseline.copy(
                        snapshot = previous,
                        pendingNames = baseline.pendingNames - name,
                        rowErrors = baseline.rowErrors + (name to safeMessage),
                    )
                }
        }
    }

    fun dismissRowError(name: String) {
        val current = (_state.value as? McpUiState.Runtime) ?: return
        _state.value = current.copy(rowErrors = current.rowErrors - name)
    }

    fun canReload(): Boolean =
        conn != null && projectDir != null && _state.value !is McpUiState.Loading

    private fun authRequiredHint(state: McpRuntimeState): String = when (state) {
        McpRuntimeState.NEEDS_AUTH ->
            "需要 OAuth 授权，目前移动端暂不支持，请在 Web 端完成授权后刷新。"
        McpRuntimeState.NEEDS_CLIENT_REGISTRATION ->
            "需要客户端注册，目前移动端暂不支持，请在 Web 端完成后刷新。"
        else -> ""
    }

    private fun sanitizeForUi(raw: String): String =
        raw.lineSequence().firstOrNull().orEmpty().take(160)
}

@HiltViewModel
class McpViewModel @Inject constructor(
    repository: ServerRepository,
) : ViewModel() {
    private val controller = McpRuntimeController(
        scope = viewModelScope,
        loadRuntime = repository::loadMcpRuntime,
        toggleRuntime = repository::toggleMcpRuntime,
    )

    val state: StateFlow<McpUiState> = controller.state

    fun load(conn: ServerConnection, projectDir: String) = controller.load(conn, projectDir)
    fun refresh() = controller.refresh()
    fun retry() = controller.refresh()
    fun toggleServer(name: String) = controller.toggle(name)
    fun dismissRowError(name: String) = controller.dismissRowError(name)
    fun canReload(): Boolean = controller.canReload()

    /** Kept for source compatibility with the existing call site; no longer dirty-tracking. */
    fun hasReloadContext(): Boolean = controller.canReload()
}
```

Delete the old `save()` method and any `dirty` / `editedServers` references. Remove the obsolete `dirtyEdits`, `pendingEdits`, and parse-error branches — those were file-editor concerns.

**Update the existing `McpViewModelTest.kt`:** the file currently asserts file-config behaviour. Replace its entire body with tests against the new `McpRuntimeController`. Cover the bare minimum here (the broader runtime tests live in 5.2):

1. `load` with `Result.success(snapshot{supportsRuntimeControl=true, servers=[connected]})` produces `McpUiState.Runtime`.
2. `load` with `Result.success(snapshot{supportsRuntimeControl=false, ...})` produces `McpUiState.FallbackReadOnly`.
3. `load` with empty servers + supportsRuntimeControl=true → `McpUiState.Empty`.
4. `load` with `Result.failure` → `McpUiState.LoadError`.
5. `toggle` on `NEEDS_AUTH` row sets `rowErrors[name]` non-empty and never calls the toggle lambda.

(The richer "pending → success → cleared", "pending → failure → revert + rowError" paths are covered in 5.2.)

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.McpViewModelTest"
./gradlew :app:compileDebugKotlin
```

**Commit:** `feat(mcp): rewrite McpViewModel for runtime control parity`

---

## Batch 4: UI — Switch Parity, Status Labels, Pending Indicators

The Sheet is rewritten to render runtime status rows and project-list hints update to match. 4.1 and 4.2 touch independent files and may run in parallel within this batch.
Tasks: 4.1, 4.2

### Task 4.1: Rewrite `McpManagementSheet` for runtime status rows, status labels, and per-row pending

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt` (rewrite)
**Test:** Compose UI parity covered by 5.3 (`McpManagementSheetRuntimeTest`)
**Depends:** 3.1
**Domain:** frontend

**Why a rewrite:** the existing Sheet has Save/Cancel buttons, a dirty-confirm dialog, and a Switch wired to file `enabled`. None of that survives. New shape: header row (title + refresh icon), one row per `McpRuntimeStatus`, no Save/Cancel buttons, dismiss-by-outside-tap. Add a fallback banner when `FallbackReadOnly` is rendered.

**Implementation guidance:**

Replace the file body with the following structure (preserving the public `@Composable fun McpManagementSheet(projectName, viewModel, onDismiss, onSaveSuccess)` signature for source compatibility — `onSaveSuccess` is now unused but kept to avoid touching call sites; the rewrite ignores it):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManagementSheet(
    projectName: String,
    viewModel: McpViewModel,
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit, // unused; kept for ABI; consider removing in a later cleanup
) {
    val state by viewModel.state.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            McpSheetHeader(
                projectName = projectName,
                onRefresh = { if (viewModel.canReload()) viewModel.refresh() },
                refreshEnabled = viewModel.canReload(),
            )

            when (val current = state) {
                McpUiState.Loading -> LoadingStateCard(label = "正在加载 MCP 运行时状态")

                McpUiState.Empty -> EmptyStateCard(
                    title = "暂无 MCP 服务器",
                    message = "当前项目没有运行时 MCP 服务器。",
                    action = { CloseButton(onDismiss) },
                )

                is McpUiState.LoadError -> ErrorWithRetry(
                    title = "无法加载 MCP 运行时状态",
                    message = current.message,
                    onRetry = viewModel::retry,
                    retryEnabled = viewModel.canReload(),
                    onDismiss = onDismiss,
                )

                is McpUiState.FallbackReadOnly -> FallbackReadOnlyContent(
                    snapshot = current.snapshot,
                    onDismiss = onDismiss,
                )

                is McpUiState.Runtime -> RuntimeListContent(
                    state = current,
                    onToggle = viewModel::toggleServer,
                    onDismissRowError = viewModel::dismissRowError,
                    onClose = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun McpSheetHeader(
    projectName: String,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "MCP 服务器 · $projectName",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新 MCP 状态")
        }
    }
}

@Composable
private fun RuntimeListContent(
    state: McpUiState.Runtime,
    onToggle: (String) -> Unit,
    onDismissRowError: (String) -> Unit,
    onClose: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
    ) {
        items(state.snapshot.servers, key = { it.name }) { server ->
            McpRuntimeRow(
                status = server,
                pending = server.name in state.pendingNames,
                rowError = state.rowErrors[server.name],
                onToggle = { onToggle(server.name) },
                onDismissError = { onDismissRowError(server.name) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
    state.sheetError?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) { CloseButton(onClose) }
}

@Composable
private fun FallbackReadOnlyContent(
    snapshot: McpRuntimeSnapshot,
    onDismiss: () -> Unit,
) {
    Text(
        text = "运行时控制需要更新的 OpenCode 服务器；当前仅显示配置文件中声明的 MCP 服务器。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(snapshot.servers, key = { it.name }) { server ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "—", // no live status in fallback
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) { CloseButton(onDismiss) }
}

@Composable
private fun McpRuntimeRow(
    status: McpRuntimeStatus,
    pending: Boolean,
    rowError: String?,
    onToggle: () -> Unit,
    onDismissError: () -> Unit,
) {
    val isConnected = status.state == McpRuntimeState.CONNECTED
    val authRequired = status.state == McpRuntimeState.NEEDS_AUTH ||
        status.state == McpRuntimeState.NEEDS_CLIENT_REGISTRATION
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    StatusDot(state = status.state)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusLabel(status.state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.errorMessage?.let { msg ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).padding(end = 12.dp),
                    strokeWidth = 2.dp,
                )
            }
            Switch(
                checked = isConnected,
                onCheckedChange = { onToggle() },
                enabled = !pending, // auth-required rows still react to clicks (handled in VM)
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        rowError?.let { msg ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun StatusDot(state: McpRuntimeState) {
    val color = when (state) {
        McpRuntimeState.CONNECTED -> MaterialTheme.colorScheme.primary
        McpRuntimeState.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
        McpRuntimeState.FAILED -> MaterialTheme.colorScheme.error
        McpRuntimeState.NEEDS_AUTH, McpRuntimeState.NEEDS_CLIENT_REGISTRATION ->
            MaterialTheme.colorScheme.tertiary
        McpRuntimeState.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier.size(8.dp).clip(CircleShape).background(color),
    )
}

private fun statusLabel(state: McpRuntimeState): String = when (state) {
    McpRuntimeState.CONNECTED -> "已连接"
    McpRuntimeState.DISABLED -> "未启用"
    McpRuntimeState.FAILED -> "连接失败"
    McpRuntimeState.NEEDS_AUTH -> "需要授权"
    McpRuntimeState.NEEDS_CLIENT_REGISTRATION -> "需要注册"
    McpRuntimeState.UNKNOWN -> "状态未知"
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("关闭") }
}

@Composable
private fun ErrorWithRetry(
    title: String,
    message: String,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    ErrorStateCard(title = title, message = message)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onRetry, enabled = retryEnabled) { Text("重试") }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}
```

Required imports include `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.ui.draw.clip`, the new domain types, and existing `EmptyStateCard` / `ErrorStateCard` / `LoadingStateCard`.

Delete the old `McpServerRow` composable. Delete the `pendingRefreshConfirm` dialog block — it was for unsaved-edits, no longer applicable.

**Test (write first, must FAIL before implementation):** rendering tests live in 5.3.

**Verify:**
```
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
```

**Commit:** `feat(mcp): rewrite MCP sheet for runtime control parity`

---

### Task 4.2: Update project-list MCP hint to read from runtime snapshot when available

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeaderMcpHint.kt` (extend existing) — or whatever the current hint composable is named; locate via `grep -r "ProjectGroupHeaderMcpHint" app/src/main` if the file path differs.
**Test:** existing `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupHeaderMcpHintTest.kt` (extend, do not rewrite)
**Depends:** 3.1
**Domain:** frontend

**Why this is its own task:** issue #19 added a "MCP server count" hint on the project group header that today reads from the file-config parser. After this release the source of truth for "is this project's MCP up?" is the runtime endpoint, not file config. We update the hint to count `connected` runtime servers when available; fall back to existing file-config count for older OpenCode servers.

**Implementation guidance:**

- Find the current hint reader. It almost certainly calls `repository.readMcpConfig` or `readMcpConfigState`. Add a parallel call site `repository.loadMcpRuntime(conn, projectDir)` and prefer its result when `supportsRuntimeControl == true`. Count = `snapshot.servers.count { it.state == McpRuntimeState.CONNECTED }`.
- When `supportsRuntimeControl == false` OR `loadMcpRuntime` fails, fall back to the existing file-config count (preserve previous behaviour exactly so issue #19's coverage does not regress).
- Hint string updates: keep "MCP: N" if N > 0, hide when 0 and runtime supported, retain previous "MCP" plain badge when in fallback (so users in fallback mode still see something).

**Test (extend existing test):**

Add three new tests:
1. Runtime supported, 2 connected + 1 disabled → hint shows "MCP: 2".
2. Runtime supported, 0 connected → hint hidden.
3. Runtime unsupported (fallback path) → behaviour matches the existing pre-runtime tests.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.components.ProjectGroupHeaderMcpHintTest"
./gradlew :app:compileDebugKotlin
```

**Commit:** `feat(mcp): project-list hint reads runtime snapshot when supported`

---

## Batch 5: Tests — Acceptance Coverage

These three test files cover repository toggle transaction edges, ViewModel pending semantics, and Sheet switch rendering. They target disjoint test classes and run in parallel.
Tasks: 5.1, 5.2, 5.3

### Task 5.1: Acceptance tests — repository runtime toggle edges

**File:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryMcpRuntimeAcceptanceTest.kt` (new file, separate from 2.1's unit tests so it can be reviewed independently)
**Test:** self
**Depends:** 2.1
**Domain:** general

**Why this is its own test file:** keeps the per-method unit tests from 2.1 clean while concentrating end-to-end Web-parity assertions in one place that maps 1:1 to the Acceptance Criteria list at the top of this plan.

**Implementation guidance:**

For each Acceptance Criterion that maps to repository behavior, add one named `@Test` whose name encodes the criterion:

```kotlin
@Test
fun `AC2_clicking_connected_disconnects_then_refetches`() = runTest { ... }

@Test
fun `AC4_clicking_disabled_or_failed_calls_connect`() = runTest { ... }

@Test
fun `AC5_auth_required_states_never_call_connect`() = runTest { ... }

@Test
fun `AC6_status_refetched_after_successful_toggle`() = runTest { ... }

@Test
fun `AC8_project_directory_forwarded_on_every_call`() = runTest { ... }

@Test
fun `AC9_old_server_falls_back_to_read_only_snapshot`() = runTest { ... }

@Test
fun `AC10_no_command_args_or_secrets_leak_to_runtime_status`() = runTest { ... }
```

Use the same `FakeMcpApi` introduced in 2.1's test (extract to a shared `testFixtures/FakeMcpApi.kt` if not already; if extraction is too invasive, duplicate the minimal fake here and note the duplication in the commit message — duplication is acceptable for one release).

For AC10: feed an `McpRuntimeServerDto` with `error = "token=abc123 connection refused at /home/user/.opencode/mcp.sock"` and assert the resulting `McpRuntimeStatus.errorMessage` does not contain `abc123` (it should be replaced with `<redacted>`) and is capped at 200 chars.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.ServerRepositoryMcpRuntimeAcceptanceTest"
```

**Commit:** `test(mcp): repository acceptance coverage for Web-parity toggles`

---

### Task 5.2: Acceptance tests — ViewModel pending and error semantics

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpRuntimeViewModelTest.kt` (new file)
**Test:** self
**Depends:** 3.1
**Domain:** general

**Implementation guidance:**

Test the `McpRuntimeController` directly (constructor takes lambdas; same testing style as the existing `McpStateController` test pattern). Cover:

1. `toggle("fs")` while `fs` is `connected`: state transitions Loading-or-Runtime → `Runtime{pendingNames=[fs]}` → `Runtime{pendingNames=[], snapshot=refreshed}`.
2. `toggle("fs")` failure: state ends at `Runtime{snapshot=previous, pendingNames=[], rowErrors[fs] != null}`.
3. `toggle("fs")` while `fs` is `needs_auth`: NO call to the toggle lambda (assert lambda invocation count is 0); `rowErrors[fs]` is non-empty and contains "OAuth" or "授权".
4. Two parallel toggles on different rows: `pendingNames` contains both names simultaneously, neither blocks the other.
5. `dismissRowError("fs")` clears that row's error but leaves others' errors untouched.
6. `toggle("fs")` while already pending → second call is a no-op (no extra lambda invocation).
7. After successful toggle: `sheetError` is cleared.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.McpRuntimeViewModelTest"
```

**Commit:** `test(mcp): ViewModel pending and error semantics`

---

### Task 5.3: Acceptance tests — Sheet renders Web-parity switch states

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheetRuntimeTest.kt` (new file)
**Test:** self
**Depends:** 4.1
**Domain:** frontend

**Implementation guidance:**

Use Robolectric + ComposeTestRule (this project already runs Compose UI tests under `:app:testDebugUnitTest` if `ProjectGroupHeaderMcpHintTest` does — confirm with a `grep -r "createComposeRule" app/src/test`; if Robolectric Compose is not configured, downgrade these to `@RunWith(AndroidJUnit4::class)` instrumented tests under `app/src/androidTest` and note that they only run on a connected device. For this plan default to **unit-test Robolectric** if configured; otherwise instrumented).

Drive the Sheet by feeding a stub `McpViewModel` whose `state: StateFlow<McpUiState>` emits each of these in turn and assert:

1. `Runtime{servers=[connected]}` → row's Switch has `assertIsOn()`.
2. `Runtime{servers=[disabled]}` → Switch `assertIsOff()`, status label says "未启用".
3. `Runtime{servers=[failed]}` → Switch `assertIsOff()`, status label "连接失败".
4. `Runtime{servers=[needs_auth]}` → Switch `assertIsOff()`, status label "需要授权".
5. `Runtime{pendingNames=["fs"]}` → row contains a `CircularProgressIndicator`, Switch is disabled.
6. `Runtime{rowErrors={"fs":"boom"}}` → row contains "boom" Text and a "知道了" button.
7. `FallbackReadOnly{...}` → fallback banner text is rendered AND no Switch nodes exist on screen.
8. `Empty` → empty-state card with "暂无 MCP 服务器".
9. `LoadError` → "重试" button is rendered.
10. `Runtime{servers=[]}` should never be observed (VM normalises to Empty); add a defensive assertion that if it is, no crash and no Switch.

**Verify:**
```
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.components.McpManagementSheetRuntimeTest"
```

**Commit:** `test(mcp): Sheet Web-parity switch and label coverage`

---

## Batch 6: Version Bump & Release Notes

Sequential: bump `versionName` / `versionCode`, then write `RELEASE_NOTES_1.6.27.md`. Notes file references the version bumped in 6.1.
Tasks: 6.1, 6.2

### Task 6.1: Bump `versionName` to `1.6.27` and `versionCode` to `40`

**File:** `app/build.gradle.kts`
**Test:** none (build-config change; release workflow validates the value matches the tag)
**Depends:** 5.1, 5.2, 5.3
**Domain:** general

**Why 1.6.27 and 40:** the design and the request both specify v1.6.27 as the next patch. Current values are `versionName = "1.6.24"`, `versionCode = 37`. Two intermediate patches (1.6.25, 1.6.26) reserve `versionCode` 38 and 39 — assume the convention "one versionCode per patch" used since v1.6.x. If 1.6.25 / 1.6.26 already shipped from another branch with different versionCodes, the implementer MUST inspect git tags (`git tag --list 'v1.6.2*'`) before bumping and choose `max(existing) + 1`. Document the chosen `versionCode` in the commit message.

**Implementation guidance:**

In `app/build.gradle.kts`, replace exactly:

```kotlin
versionCode = 37
versionName = "1.6.24"
```

with:

```kotlin
versionCode = 40
versionName = "1.6.27"
```

If existing tags reveal that `versionCode 40` is already taken, increment to the next free integer and update this task and 6.2 + 7.1 to use the chosen value. Do not skip ahead by more than 5 — versionCodes are monotonic and a large gap is a signal of mistaken bookkeeping.

**Verify:**
```
./gradlew :app:assembleDebug
grep -E 'versionCode|versionName' app/build.gradle.kts | head -2
git tag --list 'v1.6.27' # must be empty
```

**Commit:** `chore(release): bump version to 1.6.27`

---

### Task 6.2: Write `RELEASE_NOTES_1.6.27.md`

**File:** `RELEASE_NOTES_1.6.27.md` (new file at repo root, alongside the other `RELEASE_NOTES_*.md`)
**Test:** none
**Depends:** 6.1
**Domain:** general

**Implementation guidance:**

Mirror the structure of `RELEASE_NOTES_1.6.24.md` exactly (Highlights, Tests, Version, Known limitations, Artifact). Concrete content:

```markdown
# OC Remote v1.6.27 — Release Notes

## Highlights

- Brought the APK MCP panel to runtime parity with OpenCode Web: each MCP server now shows its live `connected` / `disabled` / `failed` / `needs_auth` / `needs_client_registration` state for the active project (issue #21).
- Switch behavior matches Web: clicking a connected server runtime-disconnects it; clicking a disabled or failed server runtime-connects it; status is refetched after every successful toggle so the row reflects ground truth.
- Auth-required servers are surfaced honestly with a "需要授权" / "需要注册" label; the Android client does not yet implement OAuth or client-registration flows, so toggling those rows shows an inline hint instead of pretending the toggle worked.
- Per-row pending spinner so one in-flight toggle never blocks other rows.
- Old OpenCode servers without `/mcp` runtime endpoints fall back to a read-only file-config view with a banner explaining that runtime control needs a newer server build.
- No persistent MCP config editing from the APK in this release; `command`, `args`, headers, env vars, and OAuth values continue to be neither displayed nor written.

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅ (incl. new `OpenCodeApiMcpRuntimeTest`, `ServerRepositoryMcpRuntimeTest`, `ServerRepositoryMcpRuntimeAcceptanceTest`, `McpRuntimeViewModelTest`, `McpManagementSheetRuntimeTest`)
- `:app:lintDebug` ✅
- `:app:assembleDebug` ✅
- `:app:assembleRelease` ✅ signed release build

## Version

- `versionName`: `1.6.27`
- `versionCode`: `40`

## Known limitations

- Mobile OAuth / device-code flow for `needs_auth` servers and client registration for `needs_client_registration` servers are tracked separately. For now, complete authorization on Web and refresh the panel.
- The runtime list is not pushed via SSE in this release; the panel refreshes on open and after every toggle. Live push will be considered in a follow-up.

## Artifact

- Artifact: `release-apks/oc-remote-1.6.27.apk`
- SHA-256: <fill in after build>
- Signature verification: `apksigner verify --verbose --print-certs` ✅ (`v2` scheme verified, 1 signer)
- Signer certificate SHA-256 digest: `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc` (matches v1.6.23 reference)
```

The implementer MUST leave the SHA-256 placeholder as `<fill in after build>`; Task 7.2 patches it with the real digest after the workflow completes.

**Verify:**
```
test -f RELEASE_NOTES_1.6.27.md
```

**Commit:** `docs(release): add 1.6.27 release notes`

---

## Batch 7: Signed Release Publish & Verify

Sequential: dispatch the canonical GitHub Actions release workflow against the v1.6.27 tag, then verify the published APK signature matches the v1.6.23 reference fingerprint.
Tasks: 7.1, 7.2

### Task 7.1: Tag v1.6.27 on the user's fork and dispatch the canonical release workflow

**File:** none (workflow dispatch only)
**Test:** none (verification happens in 7.2)
**Depends:** 6.1, 6.2
**Domain:** general

**Why this task is sequential and remote-write:** the canonical signing flow is `.github/workflows/release.yml`, which is `workflow_dispatch` triggered with the tag as input. It validates the tag matches `versionName`, decodes the keystore from secrets, builds `:app:assembleRelease`, runs `apksigner verify --verbose`, and creates/updates the GitHub Release with the APK and `RELEASE_NOTES_1.6.27.md`. We must NOT bypass this workflow with a local signed build — the requirement is "canonical GitHub Actions signing".

**Pre-flight (mandatory; abort if any fails):**

1. `git remote -v` and `gh repo view --json nameWithOwner,isFork,parent,owner,viewerPermission` — confirm `origin` points to the user's fork (case A in AGENTS.md). Abort if `origin` resolves to upstream.
2. `gh secret list --repo <origin>` — confirm `KEYSTORE_BASE64`, `KEYSTORE_ALIAS`, `KEYSTORE_PASSWORD` are set. If any is missing, stop and surface the gap; do not attempt the workflow.
3. Confirm the lifecycle issue (#21) is on the fork.

**Implementation guidance:**

```sh
# Inside the worktree, with all Batch 1-6 commits already pushed via lifecycle_commit
git tag v1.6.27
git push origin v1.6.27

# Trigger canonical release workflow against the tag
gh workflow run release.yml --repo <origin-owner/repo> -f tag=v1.6.27

# Watch the run
gh run watch --repo <origin-owner/repo>
```

The workflow will: validate tag format, validate `versionName` parity, decode keystore, `assembleRelease`, verify metadata, `apksigner verify`, upload the APK as a release asset, and create the GitHub Release using `RELEASE_NOTES_1.6.27.md`.

**If the workflow fails:**
- Tag-version mismatch → 6.1 was wrong; fix versionName/Code, retag, retrigger.
- Missing signing secrets → stop; surface to user.
- `apksigner verify` failure → stop; do NOT publish; investigate keystore vs. v1.6.23 signer mismatch.

**Verify:**
```sh
gh run list --workflow=release.yml --branch=v1.6.27 --limit=1
gh release view v1.6.27 --repo <origin-owner/repo>
```

**Commit:** none for this task (workflow dispatch only).

---

### Task 7.2: Verify signed APK signer matches v1.6.23 reference fingerprint and patch SHA into release notes

**File:** `RELEASE_NOTES_1.6.27.md` (patch the SHA-256 placeholder)
**Test:** none (manual verification step)
**Depends:** 7.1
**Domain:** general

**Why this is mandatory and not optional:** Acceptance Criterion 11 requires signer parity with v1.6.23. If the new APK's signer cert SHA-256 does not equal `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`, every existing user's installed v1.6.x will fail to update because Android refuses cross-signer upgrades. We catch that BEFORE telling users 1.6.27 is available.

**Implementation guidance:**

```sh
# Download the published APK
gh release download v1.6.27 --repo <origin-owner/repo> -D release-apks/

# Locate apksigner
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner | sort -V | tail -1)

# Verify and capture signer cert SHA-256
"$APKSIGNER" verify --verbose --print-certs release-apks/oc-remote-1.6.27.apk

# Compute APK SHA-256 for release notes
APK_SHA=$(sha256sum release-apks/oc-remote-1.6.27.apk | awk '{print $1}')
echo "APK SHA-256: $APK_SHA"
```

Extract the line `Signer #1 certificate SHA-256 digest: <hex>` from the apksigner output. Compare to `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`.

- **Match:** patch `RELEASE_NOTES_1.6.27.md` — replace `SHA-256: <fill in after build>` with `SHA-256: <APK_SHA>` and confirm the signer cert line is correct (already correct in 6.2's template). Commit and push the docs patch. Update the GitHub Release notes with `gh release edit v1.6.27 --notes-file RELEASE_NOTES_1.6.27.md`.
- **Mismatch:** STOP. Delete the GitHub Release (`gh release delete v1.6.27 -y`), delete the tag locally and on origin, and surface the signer mismatch to the user — this is a keystore problem, not a code problem, and must be resolved before the release goes live.

**Verify:**
```sh
grep "fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc" RELEASE_NOTES_1.6.27.md
gh release view v1.6.27 --repo <origin-owner/repo>
```

**Commit:** `docs(release): record 1.6.27 APK SHA-256 and confirm signer parity`
