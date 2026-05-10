---
date: 2026-04-30
topic: "MCP Runtime Status Parity Correction and v1.6.26 Signed Release"
issue: 20
scope: release
contract: none
---

# MCP Runtime Status Parity Correction Implementation Plan

**Goal:** Make the APK's MCP visibility match the OpenCode web UI by treating server runtime MCP status (`GET /mcp`) as the primary source of truth, keep the existing config-file probing only as diagnostics/edit fallback, fix the file-fallback parser to match the official `mcp` schema, then publish a signed v1.6.26 release through the existing GitHub Actions workflow with the v1.6.23 signer cert.

**Architecture:** Add an `OpenCodeApi.getMcpStatus(conn, directory)` call mapped to `GET /mcp` (the OpenCode server endpoint that the web UI uses) returning `Map<String, McpStatus>`. Introduce `MeRuntimeStatus` model and a new `McpConfigLoadState.RuntimeUnavailable` arm. `ServerRepository.readMcpConfigState` becomes a two-step pipeline: (1) call `/mcp` first, project it into `Loaded(McpConfig)` when non-empty; (2) on empty/unsupported runtime, fall through to the existing file-candidate scan. The fallback parser is fixed to recognize `command: string[]`, top-level `mcp` (canonical), legacy `mcpServers`, and remote `url` entries. UI copy stops referring users to `mcpServers` and prefers `mcp`. Release goes via `.github/workflows/release.yml workflow_dispatch` with tag `v1.6.26`, gated by signer-cert SHA-256 match against `scripts/release/v1623-signer-cert.sha256`.

**Design:** [thoughts/shared/designs/2026-04-30-mcp-runtime-status-correction-design.md](../designs/2026-04-30-mcp-runtime-status-correction-design.md)

**Contract:** none (single-stack Android Kotlin app; the `/mcp` endpoint is provided by upstream OpenCode server, not authored here).

---

## Reference Constants

- v1.6.23 signer cert SHA-256: `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`
- Reference file: `scripts/release/v1623-signer-cert.sha256`
- apksigner: `/root/Android/Sdk/build-tools/35.0.0/apksigner` (locally; CI uses `$ANDROID_HOME/build-tools/.../apksigner`)
- Release workflow: `.github/workflows/release.yml` (workflow_dispatch, input `tag=v1.6.26`)
- Worktree root: `/root/CODE/issue-20-correct-mcp-fallback-behavior-and-perform-a-stan`
- Active lifecycle: issue #20, branch `issue/20-correct-mcp-fallback-behavior-and-perform-a-stan`
- All paths are relative to the worktree root unless otherwise stated.
- OpenCode runtime endpoint: `GET /mcp` returns `{ [name: string]: { status: "connected" | "disconnected" | "needs_auth", error?: string, version?: string } }`. Some older builds expose it as `/mcp/status`; treat any 404/405/501 response as "endpoint unsupported" and degrade.

---

## Acceptance Criteria — User Scenario (must all pass before tagging v1.6.26)

1. **Runtime parity (the user's screenshot):** With the user's server having global MCP at `/root/.config/opencode/opencode.json` containing 7 MCP servers (`aceTool`, `autoinfo`, `exa`, `fetch`, `github`, `playwright`, `stitch`) under top-level `mcp`, AND a project `.opencode/opencode.json` that is empty/missing the `mcp` key, the APK MCP sheet renders all 7 servers loaded from runtime status. The empty project file does NOT shadow it.
2. **Runtime empty + file empty → true Empty:** When `GET /mcp` returns `{}` AND every candidate config file is empty/has no `mcp` key, the APK shows `EmptyConfig` referencing the config file and with copy that mentions `mcp` (not `mcpServers`).
3. **Runtime unsupported → file fallback:** When `GET /mcp` returns 404 / 405 / 501 (older OpenCode build), the APK silently falls through to the existing file-candidate scan and behaves like v1.6.25; no error toast surfaces to the user. Diagnostic state is `RuntimeUnavailable` propagated through to fallback.
4. **Runtime hard error (network/auth) → reuse existing read-error path:** When `GET /mcp` returns 401/403/5xx (not 404/405/501), the APK still attempts file fallback, but if file fallback also fails, surfaces `ReadError` with the original runtime error message redacted of secrets.
5. **Official `mcp` schema in fallback parser:**
   - `mcp.<name>.command` MAY be `string[]`; APK parses the first element as command, rest as args.
   - `mcp.<name>` of `type: "remote"` with `url` parses without requiring `command`.
   - Top-level `mcp` is preferred for new writes; `mcpServers` is read-compatible only.
6. **Sensitive-field redaction:** `command` args, `headers`, `env`, OAuth tokens never appear in logs or `ErrorStateCard` messages.
7. **UI copy:** `EmptyConfig`/`MissingConfig` strings refer to `mcp` only; the only place `mcpServers` may appear is an explicit "(legacy)" parenthetical in the parse-error tip.
8. **Release artifact:** `v1.6.26` GitHub Release contains `oc-remote-1.6.26.apk`. `apksigner verify --print-certs` reports the signer cert SHA-256 equal to `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`. `output-metadata.json` confirms `versionName=1.6.26 versionCode=39`.
9. **All Gradle gates green:** `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleRelease`.

---

## Dependency Graph

```
Batch 1 (parallel): 1.1, 1.2, 1.3 [foundation - no deps]
   1.1 McpStatus DTO + getMcpStatus on OpenCodeApi (verified shape, header parity)
   1.2 McpConfigLoadState extension: add RuntimeUnavailable arm + Loaded/Empty source tag
   1.3 McpServer/McpConfig domain extension: support command:String[] + remote url + headers redaction marker

Batch 2 (parallel): 2.1, 2.2 [depends on Batch 1]
   2.1 McpConfigParser fixes: command-array, remote url, prefer top-level mcp, legacy mcpServers compat
   2.2 ServerRepository runtime-first pipeline: try /mcp, fall through to existing file scan, map RuntimeUnavailable

Batch 3 (parallel): 3.1, 3.2 [depends on Batch 2]
   3.1 McpViewModel/McpStateController: surface source-of-truth (runtime vs file) and RuntimeUnavailable diagnostics
   3.2 McpManagementSheet copy fixes: replace mcpServers guidance with mcp; add subtle source-tag chip

Batch 4 (parallel): 4.1, 4.2, 4.3, 4.4 [depends on Batch 3]
   4.1 OpenCodeApi MCP-endpoint test (header + DTO + 404/405/501 handling)
   4.2 ServerRepository runtime-status-first acceptance test (matches user scenario)
   4.3 McpConfigParser official-schema test (command-array, remote url, mcp-top-level preference)
   4.4 McpViewModel runtime/fallback state test

Batch 5 (single): 5.1 [depends on Batch 4]
   5.1 Bump versionName=1.6.26 versionCode=39, write RELEASE_NOTES_1.6.26.md, run :app:assembleRelease + lint locally

Batch 6 (sequential): 6.1, 6.2, 6.3 [depends on Batch 5]
   6.1 Tag v1.6.26 on the issue branch via lifecycle commit + push
   6.2 Trigger workflow_dispatch on .github/workflows/release.yml with tag=v1.6.26
   6.3 Verify GitHub Release asset, apksigner cert SHA-256, downloadable APK
```

---

## Batch 1: Foundation — DTOs and runtime status surface (parallel - 3 implementers)

All tasks in this batch have NO dependencies and run simultaneously.
Tasks: 1.1, 1.2, 1.3

### Task 1.1: McpStatus DTO + getMcpStatus on OpenCodeApi
**File:** `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt` (modify: add MCP-runtime DTOs and `getMcpStatus` near the `// ============ Config / Providers ============` section)
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiMcpStatusTest.kt`
**Depends:** none
**Domain:** backend

Design says runtime status should mirror what the OpenCode web client uses. Verified upstream API: `GET /mcp` returns `Record<name, { status, error?, version? }>`. We must also accept HTTP 404 / 405 / 501 as "endpoint unsupported" (older OpenCode builds). The implementer adds:

- A serializable `McpRuntimeStatus` DTO with fields `status: String`, `error: String? = null`, `version: String? = null`. `status` values seen in upstream: `"connected"`, `"disconnected"`, `"needs_auth"`. We do NOT enum-restrict; accept any string for forward-compat.
- A sealed result type `McpRuntimeStatusResult` with `Success(map)`, `Unsupported`, `Failed(cause)` so the repository can branch cleanly.
- A new method `OpenCodeApi.getMcpStatus(conn, directory)` that calls `GET ${conn.baseUrl}/mcp` with the same `x-opencode-directory` header convention used elsewhere in the file. Behavior on response status:
  - `2xx`: decode body as `Map<String, McpRuntimeStatus>` and return `Success`. An empty map is `Success(emptyMap())`, NOT `Unsupported`.
  - `404`, `405`, `501`: return `Unsupported`.
  - other non-success: return `Failed(IOException(...))` with the status code; redact body.
  - exception (network/serialization): return `Failed(error)`. NEVER throw.
- Sensitive-field guard: do NOT log `error` or `version` strings at INFO level; debug-only with `BuildConfig.DEBUG` gate, similar to `authorizeProviderOauth`.

```kotlin
// COMPLETE test - copy-paste ready
package dev.minios.ocremote.data.api

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeApiMcpStatusTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun newApi(handler: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.engine.mock.MockHttpResponse): OpenCodeApi {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        return OpenCodeApi(client, json)
    }

    @Test
    fun `getMcpStatus parses the seven-server map and attaches encoded directory header`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi { request ->
            captured += request
            assertEquals("/mcp", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = ByteReadChannel(
                    """
                    {
                      "aceTool": {"status": "connected"},
                      "autoinfo": {"status": "connected"},
                      "exa": {"status": "connected"},
                      "fetch": {"status": "connected"},
                      "github": {"status": "connected"},
                      "playwright": {"status": "connected"},
                      "stitch": {"status": "needs_auth"}
                    }
                    """.trimIndent()
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val conn = ServerConnection.from("http://example.test:4096")
        val directory = "/workspace/proj"

        val result = api.getMcpStatus(conn, directory)

        assertTrue(result is McpRuntimeStatusResult.Success)
        val map = (result as McpRuntimeStatusResult.Success).statuses
        assertEquals(7, map.size)
        assertEquals("connected", map["aceTool"]?.status)
        assertEquals("needs_auth", map["stitch"]?.status)
        assertEquals(Uri.encode(directory), captured.single().headers["x-opencode-directory"])
    }

    @Test
    fun `getMcpStatus omits directory header when null`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi { request ->
            captured += request
            respond(
                content = ByteReadChannel("{}"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Success)
        assertEquals(0, (result as McpRuntimeStatusResult.Success).statuses.size)
        assertEquals(null, captured.single().headers["x-opencode-directory"])
    }

    @Test
    fun `getMcpStatus returns Unsupported on 404`() = runBlocking {
        val api = newApi { _ ->
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Unsupported)
    }

    @Test
    fun `getMcpStatus returns Unsupported on 405 and 501`() = runBlocking {
        for (status in listOf(HttpStatusCode.MethodNotAllowed, HttpStatusCode.NotImplemented)) {
            val api = newApi { _ -> respond(content = ByteReadChannel(""), status = status) }
            val conn = ServerConnection.from("http://example.test:4096")
            val result = api.getMcpStatus(conn, directory = null)
            assertTrue("status=$status -> Unsupported", result is McpRuntimeStatusResult.Unsupported)
        }
    }

    @Test
    fun `getMcpStatus returns Failed on 401 with no body leak`() = runBlocking {
        val api = newApi { _ ->
            respond(
                content = ByteReadChannel("Authorization required: Bearer secret-token"),
                status = HttpStatusCode.Unauthorized,
            )
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Failed)
        val msg = (result as McpRuntimeStatusResult.Failed).cause.message.orEmpty()
        assertTrue("HTTP status surfaced", msg.contains("401"))
        assertTrue("body must not leak", !msg.contains("secret-token"))
    }

    @Test
    fun `getMcpStatus tolerates unknown status string for forward compat`() = runBlocking {
        val api = newApi { _ ->
            respond(
                content = ByteReadChannel("""{"future": {"status": "reconnecting"}}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val result = api.getMcpStatus(conn, directory = null)

        assertTrue(result is McpRuntimeStatusResult.Success)
        assertEquals("reconnecting", (result as McpRuntimeStatusResult.Success).statuses["future"]?.status)
    }
}
```

```kotlin
// COMPLETE implementation patch (additive — append to OpenCodeApi.kt)

// In the `// ============ Config / Providers ============` region, after getGlobalConfig():

/**
 * Get runtime MCP server status from the OpenCode server.
 * GET /mcp
 *
 * This is the same source the OpenCode web UI uses. It reflects the merged
 * (global + project) configuration the server actually loaded, so it must be
 * preferred over client-side config-file probing. Returns:
 *  - Success(map) on 2xx, including empty map.
 *  - Unsupported on 404/405/501 (older OpenCode builds).
 *  - Failed(cause) on any other error; cause messages MUST NOT include response bodies
 *    (which can contain credentials in error paths).
 */
suspend fun getMcpStatus(
    conn: ServerConnection,
    directory: String? = null,
): McpRuntimeStatusResult {
    return try {
        val response = httpClient.get("${conn.baseUrl}/mcp") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", android.net.Uri.encode(it)) }
        }
        when (val status = response.status) {
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
            HttpStatusCode.NotImplemented -> McpRuntimeStatusResult.Unsupported
            else -> if (status.isSuccess()) {
                val body = response.bodyAsText()
                val parsed = json.decodeFromString(
                    kotlinx.serialization.builtins.MapSerializer(
                        kotlinx.serialization.builtins.serializer<String>(),
                        McpRuntimeStatus.serializer(),
                    ),
                    body,
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "getMcpStatus: ${parsed.size} servers")
                McpRuntimeStatusResult.Success(parsed)
            } else {
                McpRuntimeStatusResult.Failed(
                    IOException("GET /mcp failed: HTTP ${status.value}")
                )
            }
        }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.d(TAG, "getMcpStatus: error class=${e.javaClass.simpleName}")
        McpRuntimeStatusResult.Failed(e)
    }
}

// At the bottom of OpenCodeApi.kt, alongside other DTOs:

@Serializable
data class McpRuntimeStatus(
    val status: String,
    val error: String? = null,
    val version: String? = null,
)

sealed interface McpRuntimeStatusResult {
    data class Success(val statuses: Map<String, McpRuntimeStatus>) : McpRuntimeStatusResult
    data object Unsupported : McpRuntimeStatusResult
    data class Failed(val cause: Throwable) : McpRuntimeStatusResult
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'dev.minios.ocremote.data.api.OpenCodeApiMcpStatusTest'`
**Commit:** `feat(api): add OpenCodeApi.getMcpStatus mapped to GET /mcp with Unsupported/Failed branches`

### Task 1.2: Extend McpConfigLoadState with RuntimeUnavailable + source tag
**File:** `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpConfig.kt` (modify)
**Test:** none (data class only; behavior tested in Tasks 4.2, 4.4)
**Depends:** none
**Domain:** general

Design wants ViewModel/UI to be able to distinguish "loaded from runtime" from "loaded from file fallback" without leaking implementation detail. Simplest approach: add a `source` enum on `Loaded`/`Empty` and a new `RuntimeUnavailable` arm describing the runtime call's outcome. The repository sets the tag, the ViewModel maps it to UI copy.

Decision (gap-fill): we keep the existing `Loaded(config)` / `Empty(config)` shapes binary-compatible by adding a `source: McpSource = McpSource.File` parameter with a default; existing tests/callers that pattern-match on the type continue to compile. New `RuntimeUnavailable` is an additional arm; the existing `when` in `McpStateController.loadCurrentConfig` will need an explicit `is RuntimeUnavailable -> ...` branch (covered in Task 3.1).

```kotlin
// COMPLETE implementation - replace existing McpConfig.kt content

package dev.minios.ocremote.domain.model

enum class McpSource { Runtime, File }

sealed interface McpConfigLoadState {
    data class Loaded(
        val config: McpConfig,
        val source: McpSource = McpSource.File,
    ) : McpConfigLoadState

    data class Empty(
        val config: McpConfig,
        val source: McpSource = McpSource.File,
    ) : McpConfigLoadState

    data class Error(
        val filePath: String?,
        val message: String,
        val cause: Throwable? = null,
    ) : McpConfigLoadState

    data class NotFound(val checkedPaths: List<String>) : McpConfigLoadState

    /**
     * The OpenCode server's `GET /mcp` endpoint is not available (older build
     * returning 404/405/501). The repository will then attempt file fallback
     * and may return a different state. RuntimeUnavailable surfaces only when
     * file fallback has also been exhausted and produced no usable result, so
     * the UI can tell the user runtime status is missing AND fallback was
     * empty/missing.
     */
    data class RuntimeUnavailable(
        val fallback: McpConfigLoadState,
    ) : McpConfigLoadState
}

data class McpConfig(
    val filePath: String,
    val rawJson: String,
    val servers: Map<String, McpServer>,
)

data class McpServer(
    val name: String,
    val type: String?,
    val command: String?,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val enabled: Boolean = true,
)
```

**Verify:** `./gradlew :app:compileDebugKotlin` (must compile; downstream callers updated in later batches)
**Commit:** `feat(domain): extend McpConfigLoadState with McpSource and RuntimeUnavailable arm`

### Task 1.3: Verification fixture for the seven-server runtime payload
**File:** `app/src/test/resources/mcp/runtime-status-seven-servers.json` (new)
**Test:** `app/src/test/kotlin/dev/minios/ocremote/fixtures/McpFixtureLoadTest.kt`
**Depends:** none
**Domain:** general

Design's primary acceptance scenario references seven specific servers from the user's global MCP. We freeze that payload in a JSON fixture so the repository acceptance test in Task 4.2 can assert exactly those servers without re-typing them.

```kotlin
// COMPLETE test
package dev.minios.ocremote.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFixtureLoadTest {
    @Test
    fun `runtime status fixture contains the seven servers`() {
        val raw = javaClass.getResource("/mcp/runtime-status-seven-servers.json")!!.readText()
        val expected = listOf("aceTool", "autoinfo", "exa", "fetch", "github", "playwright", "stitch")
        for (name in expected) {
            assertTrue("expected key $name in fixture", raw.contains("\"$name\""))
        }
        // No accidental secret-looking field
        assertEquals(false, raw.contains("token"))
        assertEquals(false, raw.contains("Authorization"))
    }
}
```

```json
// COMPLETE fixture - app/src/test/resources/mcp/runtime-status-seven-servers.json
{
  "aceTool": { "status": "connected" },
  "autoinfo": { "status": "connected" },
  "exa": { "status": "connected" },
  "fetch": { "status": "connected" },
  "github": { "status": "connected" },
  "playwright": { "status": "connected" },
  "stitch": { "status": "needs_auth" }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'dev.minios.ocremote.fixtures.McpFixtureLoadTest'`
**Commit:** `test(mcp): freeze seven-server runtime status fixture for parity tests`

---

## Batch 2: Parser fixes and runtime-first repository pipeline (parallel - 2 implementers)

All tasks in this batch depend on Batch 1 completing.
Tasks: 2.1, 2.2

### Task 2.1: McpConfigParser official-schema fixes
**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpConfigParser.kt` (modify)
**Test:** Tests added in Task 4.3 (parser-level). This task only edits the parser; broader assertions live in Batch 4.
**Depends:** 1.2, 1.3
**Domain:** general

Design says the parser must accept the official OpenCode shape: top-level `mcp` (canonical), `command: string[]` (array), and remote entries with `url` and no `command`. Legacy `mcpServers` stays read-supported. The parser must NOT pretty-print or expose `headers` / `env` / `oauth` field values in error messages.

Decision (gap-fill): when both `mcp` and `mcpServers` are present in the same file, prefer `mcp`. The serializer also writes back to the canonical key when the input had one; if neither was present we now default to writing `mcp` (was: `mcpServers`).

```kotlin
// COMPLETE implementation - replace existing McpConfigParser.kt

package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.domain.model.McpSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object McpConfigParser {

    private val prettyJson = Json { prettyPrint = true }

    fun parse(filePath: String, rawJson: String): McpConfig? {
        return when (val state = parseState(filePath, rawJson)) {
            is McpConfigLoadState.Loaded -> state.config
            is McpConfigLoadState.Empty -> state.config
            is McpConfigLoadState.Error -> throw state.cause ?: IllegalArgumentException(state.message)
            is McpConfigLoadState.NotFound -> null
            is McpConfigLoadState.RuntimeUnavailable -> null
        }
    }

    fun parseState(filePath: String, rawJson: String): McpConfigLoadState {
        return try {
            val root = Json.parseToJsonElement(rawJson).jsonObject
            // Official OpenCode shape uses top-level `mcp`. `mcpServers` is legacy compat.
            val mcpKey = when {
                root.containsKey("mcp") -> "mcp"
                root.containsKey("mcpServers") -> "mcpServers"
                else -> null
            }
            val mcpElement = mcpKey?.let { root[it] }
                ?: return McpConfigLoadState.Empty(
                    McpConfig(filePath = filePath, rawJson = rawJson, servers = emptyMap()),
                    source = McpSource.File,
                )
            val mcpObject = mcpElement as? JsonObject
                ?: return McpConfigLoadState.Error(filePath, "Invalid $mcpKey section")

            val servers = buildMap {
                for ((name, element) in mcpObject.entries) {
                    val obj = element as? JsonObject
                        ?: return McpConfigLoadState.Error(filePath, "Invalid MCP server entry: $name")
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull
                    val (command, extraArgs) = parseCommand(obj["command"])
                    val argsField = obj["args"]?.jsonArray
                        ?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                        .orEmpty()
                    val combinedArgs = (extraArgs + argsField)
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    val isRemote = type == "remote" || (command == null && url != null)
                    if (command == null && !isRemote) {
                        return McpConfigLoadState.Error(
                            filePath,
                            "MCP server '$name' is missing required command (or remote url)",
                        )
                    }
                    put(
                        name,
                        McpServer(
                            name = name,
                            type = type,
                            command = command,
                            args = combinedArgs,
                            url = url,
                            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        ),
                    )
                }
            }

            val config = McpConfig(filePath = filePath, rawJson = rawJson, servers = servers)
            if (servers.isEmpty()) {
                McpConfigLoadState.Empty(config, source = McpSource.File)
            } else {
                McpConfigLoadState.Loaded(config, source = McpSource.File)
            }
        } catch (error: Exception) {
            McpConfigLoadState.Error(
                filePath = filePath,
                // NEVER include rawJson; it may contain secrets in headers/env.
                message = error.message ?: "Failed to parse MCP config",
                cause = error,
            )
        }
    }

    /**
     * Parse the `command` field which may be:
     *  - a string (legacy): "npx"           -> Pair("npx", emptyList())
     *  - an array (official): ["node","x.js"] -> Pair("node", listOf("x.js"))
     *  - missing/null: Pair(null, emptyList())
     */
    private fun parseCommand(element: kotlinx.serialization.json.JsonElement?): Pair<String?, List<String>> {
        if (element == null) return null to emptyList()
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.let { it to emptyList() } ?: (null to emptyList())
            is JsonArray -> {
                val parts = element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (parts.isEmpty()) null to emptyList()
                else parts.first() to parts.drop(1)
            }
            else -> null to emptyList()
        }
    }

    fun serialize(config: McpConfig): String {
        val root = Json.parseToJsonElement(config.rawJson).jsonObject.toMutableMap()
        // Prefer canonical `mcp` when neither key is present; otherwise preserve the source key.
        val mcpKey = when {
            root.containsKey("mcp") -> "mcp"
            root.containsKey("mcpServers") -> "mcpServers"
            else -> "mcp"
        }
        val mcpServers = (root[mcpKey] as? JsonObject)?.toMutableMap() ?: mutableMapOf()

        config.servers.forEach { (name, server) ->
            val existing = mcpServers[name]?.jsonObject?.toMutableMap() ?: mutableMapOf()
            existing["enabled"] = JsonPrimitive(server.enabled)
            mcpServers[name] = JsonObject(existing)
        }

        root[mcpKey] = JsonObject(mcpServers)
        return prettyJson.encodeToString(JsonObject(root))
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'dev.minios.ocremote.data.repository.McpConfigParserTest'` (existing tests must still pass; new official-schema tests added in Task 4.3)
**Commit:** `feat(mcp): parser supports official top-level mcp, command-array, remote url; default write key is mcp`

### Task 2.2: ServerRepository runtime-status-first pipeline
**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/ServerRepository.kt` (modify `readMcpConfigState`)
**Test:** Acceptance tests in Task 4.2.
**Depends:** 1.1, 1.2, 2.1
**Domain:** backend

Design specifies: try `GET /mcp` first; if it returns ≥1 server, project that into a `Loaded(McpConfig, source=Runtime)` directly and stop. If it is `Unsupported`, fall through to existing file-candidate scan. If runtime returns success-empty, ALSO fall through to the file scan because file diagnostics may explain (Empty vs NotFound) what runtime status alone cannot. If runtime fails with a non-Unsupported error, fall through and tag the resulting state as `RuntimeUnavailable(fallback=...)` only when the fallback itself is `Empty`/`NotFound`; if the fallback is `Loaded`, runtime failure was harmless and we just return `Loaded`.

Runtime → fake-McpConfig projection: synthesize `McpConfig(filePath="<runtime>", rawJson="{}", servers=...)`. Field mapping per server (gap-fill, design silent on detail):
- `name`: key from runtime map.
- `enabled`: `true` if `status` ∈ `{"connected", "needs_auth"}`, `false` if `"disconnected"`. (Runtime status is not user toggle state; we surface it as "running" → enabled. The save path is disabled when source=Runtime; see Task 3.1.)
- `command`: `null` (runtime status does not expose command; UI's small command preview will hide when `command == null`).
- `type`: `null`.
- `url`: `null`.

The synthetic `filePath = "<runtime>"` is a sentinel string. The UI never displays it directly; Task 3.2 maps it to a "来自 OpenCode 服务运行时状态" copy.

```kotlin
// COMPLETE implementation patch - replace readMcpConfigState in ServerRepository.kt

suspend fun readMcpConfigState(
    conn: ServerConnection,
    projectDir: String,
): McpConfigLoadState {
    val projectDirectory = projectDir.takeIf { it.isNotBlank() }

    // Step 1: ask the OpenCode server for runtime MCP status, the same
    // source the web UI uses. Runtime status reflects merged global+project
    // configuration after it has been loaded by the server, so it is the
    // authoritative answer to "what MCP servers exist?".
    val runtimeResult = api.getMcpStatus(conn, directory = projectDirectory)
    when (runtimeResult) {
        is dev.minios.ocremote.data.api.McpRuntimeStatusResult.Success -> {
            if (runtimeResult.statuses.isNotEmpty()) {
                return McpConfigLoadState.Loaded(
                    config = McpConfig(
                        filePath = RUNTIME_SOURCE_SENTINEL,
                        rawJson = "{}",
                        servers = runtimeResult.statuses.entries.associate { (name, status) ->
                            name to dev.minios.ocremote.domain.model.McpServer(
                                name = name,
                                type = null,
                                command = null,
                                args = emptyList(),
                                url = null,
                                enabled = status.status != "disconnected",
                            )
                        },
                    ),
                    source = McpSource.Runtime,
                )
            }
            // Runtime success-but-empty: fall through to file diagnostics so the
            // UI can distinguish Empty(file=...) from MissingConfig(no candidates).
        }
        is dev.minios.ocremote.data.api.McpRuntimeStatusResult.Unsupported -> {
            // Older server: fall through silently.
        }
        is dev.minios.ocremote.data.api.McpRuntimeStatusResult.Failed -> {
            Log.w(TAG, "getMcpStatus failed; falling back to file scan: ${runtimeResult.cause.javaClass.simpleName}")
            // Fall through to file scan; we wrap the final result in RuntimeUnavailable
            // only if the fallback is Empty/NotFound (signaled below).
        }
    }

    // Step 2: existing file-candidate fallback (unchanged behavior).
    val homeDir = runCatching { api.getServerPaths(conn, directory = projectDirectory).home }
        .getOrElse { error ->
            return McpConfigLoadState.Error(
                filePath = null,
                message = error.message ?: "Failed to resolve server paths",
                cause = error,
            )
        }

    val candidates = buildList {
        val normalizedProjectDir = projectDir.trimEnd('/')
        if (normalizedProjectDir.isNotEmpty()) {
            add("$normalizedProjectDir/.opencode/opencode.json")
            add("$normalizedProjectDir/.opencode/config.json")
            add("$normalizedProjectDir/opencode.json")
        }
        val normalizedHomeDir = homeDir.trimEnd('/')
        if (normalizedHomeDir.isNotEmpty()) {
            add("$normalizedHomeDir/.config/opencode/opencode.json")
            add("$normalizedHomeDir/.config/opencode/config.json")
        }
    }

    val reads = mutableListOf<McpConfigCandidateRead>()
    for (path in candidates) {
        reads += McpConfigCandidateRead(
            path = path,
            readResult = runCatching { api.readFileText(conn, path, directory = projectDirectory) },
        )
        when (val state = resolveMcpConfigLoadState(reads)) {
            is McpConfigLoadState.Loaded -> return state
            is McpConfigLoadState.Error -> return state
            is McpConfigLoadState.Empty,
            is McpConfigLoadState.NotFound,
            is McpConfigLoadState.RuntimeUnavailable -> Unit
        }
    }
    val fileFallback = resolveMcpConfigLoadState(reads)

    // If runtime call genuinely failed (not Unsupported), and we ended up with
    // Empty/NotFound from files, surface RuntimeUnavailable so the UI can show
    // "OpenCode runtime status not available; showing file diagnostics".
    return if (runtimeResult is dev.minios.ocremote.data.api.McpRuntimeStatusResult.Failed
        && (fileFallback is McpConfigLoadState.Empty || fileFallback is McpConfigLoadState.NotFound)
    ) {
        McpConfigLoadState.RuntimeUnavailable(fallback = fileFallback)
    } else {
        fileFallback
    }
}

// Add at top of file alongside existing private constants:
private const val RUNTIME_SOURCE_SENTINEL = "<runtime>"
```

Also update the `import` block of `ServerRepository.kt` to include `dev.minios.ocremote.domain.model.McpSource`. The `writeMcpConfig` path stays unchanged for file configs; if the implementer detects `config.filePath == RUNTIME_SOURCE_SENTINEL`, return `Result.failure(IllegalStateException("Cannot persist edits when MCP source is runtime"))` (the UI in Task 3.2 disables the Save button in this case anyway, this is defensive).

```kotlin
// COMPLETE patch to writeMcpConfig
suspend fun writeMcpConfig(
    conn: ServerConnection,
    config: McpConfig,
): Result<Unit> = runCatching {
    if (config.filePath == RUNTIME_SOURCE_SENTINEL) {
        throw IllegalStateException("Cannot persist edits when MCP source is runtime")
    }
    val updatedJson = McpConfigParser.serialize(config)
    val configDirectory = config.filePath.substringBeforeLast('/').takeIf { it.isNotBlank() }
    api.writeFile(conn, path = config.filePath, content = updatedJson, directory = configDirectory)
}
```

**Verify:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests 'dev.minios.ocremote.data.repository.*'`
**Commit:** `feat(mcp): runtime-status-first pipeline in ServerRepository with file-fallback diagnostics`

---

## Batch 3: ViewModel + UI copy fixes (parallel - 2 implementers)

All tasks in this batch depend on Batch 2 completing.
Tasks: 3.1, 3.2

### Task 3.1: McpViewModel + McpStateController surface runtime/fallback distinction
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModel.kt` (modify)
**Test:** Added in Task 4.4.
**Depends:** 1.2, 2.2
**Domain:** general

Design wants the ViewModel to keep state precise across: loaded-from-runtime, loaded-from-file, runtime-unavailable, config-empty, config-missing, parse/read error. We extend `McpUiState.Loaded` with `source: McpSource` (default `File` so existing tests still compile and the rest of the controller continues to work) and add `McpUiState.RuntimeUnavailable(fallback: McpUiState)` so the UI can render the underlying fallback diagnostic plus a runtime-unavailable banner.

Decision (gap-fill): when `source = Runtime`, the existing toggle UI is rendered read-only (Switch disabled, Save button hidden). Edits cannot round-trip through `/mcp` (no write endpoint for that), so allowing toggles would be misleading. The user can still use the projects' config-file editor flow for actual edits.

```kotlin
// COMPLETE implementation - replace existing McpViewModel.kt

package dev.minios.ocremote.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.OpenCodeFileReadException
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.domain.model.McpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject

sealed class McpUiState {
    data object Loading : McpUiState()

    data class Loaded(
        val config: McpConfig,
        val editedServers: Map<String, McpServer> = config.servers,
        val dirty: Boolean = false,
        val saveError: String? = null,
        val source: McpSource = McpSource.File,
    ) : McpUiState()

    data class EmptyConfig(
        val filePath: String,
        val fallbackExhausted: Boolean = true,
        val source: McpSource = McpSource.File,
    ) : McpUiState()

    data class MissingConfig(val checkedPaths: List<String>) : McpUiState()

    data class ReadError(val filePath: String?, val message: String) : McpUiState()

    data class ParseError(val filePath: String, val message: String) : McpUiState()

    /** Runtime /mcp call failed with a non-Unsupported error AND file fallback was Empty/NotFound. */
    data class RuntimeUnavailable(val fallback: McpUiState) : McpUiState()

    data object Saving : McpUiState()

    data object SaveSuccess : McpUiState()
}

internal class McpStateController(
    private val scope: CoroutineScope,
    private val readMcpConfigState: suspend (ServerConnection, String) -> McpConfigLoadState,
    private val writeMcpConfig: suspend (ServerConnection, McpConfig) -> Result<Unit>,
) {

    private val _state = MutableStateFlow<McpUiState>(McpUiState.Loading)
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    private var currentConn: ServerConnection? = null
    private var currentProjectDir: String? = null
    private var lastLoaded: McpUiState.Loaded? = null
    private var pendingEdits: Map<String, McpServer>? = null

    fun load(conn: ServerConnection, projectDir: String) {
        currentConn = conn
        currentProjectDir = projectDir
        loadCurrentConfig()
    }

    fun refresh() { loadCurrentConfig() }
    fun retry() { loadCurrentConfig() }

    private fun loadCurrentConfig() {
        val conn = currentConn ?: return
        val projectDir = currentProjectDir ?: return
        _state.value = McpUiState.Loading

        scope.launch {
            _state.value = mapLoadStateToUi(readMcpConfigState(conn, projectDir))
        }
    }

    private fun mapLoadStateToUi(loadState: McpConfigLoadState): McpUiState {
        return when (loadState) {
            is McpConfigLoadState.Loaded -> {
                val pending = pendingEdits
                val loaded = McpUiState.Loaded(config = loadState.config, source = loadState.source)
                val resolvedLoaded = if (pending != null && loadState.source == McpSource.File) {
                    if (pending.keys.all { it in loadState.config.servers.keys }) {
                        val mergedServers = loadState.config.servers.mapValues { (name, server) ->
                            pending[name]?.let { pendingServer -> server.copy(enabled = pendingServer.enabled) }
                                ?: server
                        }
                        val dirtyEntries = dirtyEdits(mergedServers, loadState.config.servers)
                        pendingEdits = dirtyEntries
                        loaded.copy(editedServers = mergedServers, dirty = dirtyEntries != null)
                    } else {
                        pendingEdits = null
                        loaded
                    }
                } else {
                    // Runtime source: never carry pending edits across loads.
                    if (loadState.source == McpSource.Runtime) pendingEdits = null
                    loaded
                }
                lastLoaded = resolvedLoaded
                resolvedLoaded
            }

            is McpConfigLoadState.Empty -> {
                lastLoaded = null
                McpUiState.EmptyConfig(
                    filePath = loadState.config.filePath,
                    fallbackExhausted = true,
                    source = loadState.source,
                )
            }

            is McpConfigLoadState.NotFound -> {
                lastLoaded = null
                McpUiState.MissingConfig(checkedPaths = loadState.checkedPaths)
            }

            is McpConfigLoadState.Error -> {
                val ui = loadState.toUiState()
                if (ui !is McpUiState.ReadError) lastLoaded = null
                ui
            }

            is McpConfigLoadState.RuntimeUnavailable -> {
                McpUiState.RuntimeUnavailable(fallback = mapLoadStateToUi(loadState.fallback))
            }
        }
    }

    fun canReload(): Boolean {
        return currentConn != null &&
            currentProjectDir != null &&
            _state.value !is McpUiState.Loading &&
            _state.value !is McpUiState.Saving
    }

    fun hasReloadContext(): Boolean = currentConn != null && currentProjectDir != null

    fun toggleServer(name: String) {
        val current = (_state.value as? McpUiState.Loaded) ?: lastLoaded ?: return
        if (current.source == McpSource.Runtime) return // read-only when source is runtime
        val updatedServers = current.editedServers.toMutableMap()
        val server = updatedServers[name] ?: return
        updatedServers[name] = server.copy(enabled = !server.enabled)
        val dirtyEntries = dirtyEdits(updatedServers, current.config.servers)

        val newState = current.copy(
            editedServers = updatedServers,
            dirty = dirtyEntries != null,
            saveError = null,
        )
        pendingEdits = dirtyEntries
        lastLoaded = newState
        _state.value = newState
    }

    fun save() {
        val current = (_state.value as? McpUiState.Loaded) ?: lastLoaded ?: return
        if (current.source == McpSource.Runtime) return
        val conn = currentConn ?: return
        val updatedConfig = current.config.copy(servers = current.editedServers)

        _state.value = McpUiState.Saving

        scope.launch {
            writeMcpConfig(conn, updatedConfig)
                .onSuccess {
                    pendingEdits = null
                    _state.value = McpUiState.SaveSuccess
                }
                .onFailure { error ->
                    val restored = current.copy(saveError = error.message ?: "Save failed")
                    lastLoaded = restored
                    _state.value = restored
                }
        }
    }

    private fun dirtyEdits(
        editedServers: Map<String, McpServer>,
        baseServers: Map<String, McpServer>,
    ): Map<String, McpServer>? = editedServers
        .filter { (name, server) -> baseServers[name] != server }
        .takeIf { it.isNotEmpty() }

    private fun McpConfigLoadState.Error.toUiState(): McpUiState = when {
        cause is OpenCodeFileReadException || cause is IOException -> McpUiState.ReadError(filePath, message)
        isParseError() -> McpUiState.ParseError(filePath.orEmpty(), message)
        else -> McpUiState.ReadError(filePath, message)
    }

    private fun McpConfigLoadState.Error.isParseError(): Boolean {
        val lowerMessage = message.lowercase()
        return cause is SerializationException ||
            lowerMessage.contains("parse") ||
            lowerMessage.contains("invalid") ||
            lowerMessage.contains("missing required command")
    }
}

@HiltViewModel
class McpViewModel @Inject constructor(
    repository: ServerRepository,
) : ViewModel() {
    private val controller = McpStateController(
        scope = viewModelScope,
        readMcpConfigState = repository::readMcpConfigState,
        writeMcpConfig = repository::writeMcpConfig,
    )

    val state: StateFlow<McpUiState> = controller.state

    fun load(conn: ServerConnection, projectDir: String) = controller.load(conn, projectDir)
    fun refresh() = controller.refresh()
    fun retry() = controller.retry()
    fun canReload(): Boolean = controller.canReload()
    fun hasReloadContext(): Boolean = controller.hasReloadContext()
    fun toggleServer(name: String) = controller.toggleServer(name)
    fun save() = controller.save()
}
```

**Verify:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests 'dev.minios.ocremote.ui.screens.sessions.McpViewModelTest'` (existing tests must compile against new `Loaded(source=...)` default; new tests in Task 4.4)
**Commit:** `feat(mcp/vm): expose McpSource to UI and add RuntimeUnavailable state`

### Task 3.2: McpManagementSheet copy + read-only runtime mode
**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpManagementSheet.kt` (modify)
**Test:** Compose UI rendering tested by recomposition smoke test in Task 4.4 (state-shape level).
**Depends:** 3.1
**Domain:** frontend

Design's UI layer requirements:
- Display MCP servers from runtime status.
- Stop telling users to add `mcpServers`.
- Explain whether the status came from runtime or fallback diagnostics, only when useful.
- Keep refresh affordance visible.

Implementation:
- Replace `mcpServers` references in copy with `mcp` (the canonical key).
- Add a small subtitle line under the sheet title: when `source == Runtime`, show "来自 OpenCode 服务运行时状态"; when `source == File` AND `filePath != "<runtime>"`, show the file path; otherwise hide.
- When source is Runtime, hide the Save button entirely and disable Switch toggling (no toast — Switch is just non-interactive); we already gate `toggleServer`/`save` in the controller, but reflecting it in the visual disabled state is required for accessibility.
- Add a `RuntimeUnavailable` branch that renders an `ErrorStateCard(title="OpenCode 运行时状态不可用", message="正在显示本地配置文件诊断结果。")` followed by a recursion render of `fallback`. To keep complexity bounded, we render the fallback by calling a private `@Composable fun McpStateBody(state, ...)` extracted from the existing `when (val current = state)` block.
- Empty/Missing copy: rephrase to refer to `mcp` and never `mcpServers`. The only allowed `mcpServers` mention is in the ParseError tip, parenthesized as "(legacy mcpServers 也会被读取)".

```kotlin
// COMPLETE implementation - replace McpManagementSheet.kt
package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.domain.model.McpSource
import dev.minios.ocremote.ui.components.EmptyStateCard
import dev.minios.ocremote.ui.components.ErrorStateCard
import dev.minios.ocremote.ui.components.LoadingStateCard
import dev.minios.ocremote.ui.screens.sessions.McpUiState
import dev.minios.ocremote.ui.screens.sessions.McpViewModel

private const val RUNTIME_SOURCE_SENTINEL = "<runtime>"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManagementSheet(
    projectName: String,
    viewModel: McpViewModel,
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var lastServers by remember { mutableStateOf<Map<String, McpServer>>(emptyMap()) }
    var lastSaveError by remember { mutableStateOf<String?>(null) }
    var pendingRefreshConfirm by remember { mutableStateOf(false) }

    val requestRefresh = {
        val loaded = state as? McpUiState.Loaded
        if (loaded?.dirty == true) pendingRefreshConfirm = true
        else if (viewModel.canReload()) viewModel.refresh()
    }

    LaunchedEffect(state) {
        when (val current = state) {
            is McpUiState.Loaded -> {
                lastServers = current.editedServers
                lastSaveError = current.saveError
            }
            McpUiState.SaveSuccess -> onSaveSuccess()
            else -> Unit
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MCP 服务器 · $projectName",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = requestRefresh, enabled = viewModel.canReload()) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新 MCP 状态")
                }
            }
            SourceSubtitle(state = state)
            Spacer(Modifier.size(8.dp))

            McpStateBody(
                state = state,
                viewModel = viewModel,
                lastServers = lastServers,
                lastSaveError = lastSaveError,
                onDismiss = onDismiss,
                requestRefresh = requestRefresh,
            )
        }
    }

    if (pendingRefreshConfirm) {
        AlertDialog(
            onDismissRequest = { pendingRefreshConfirm = false },
            title = { Text("将丢失未保存的修改") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRefreshConfirm = false
                        if (viewModel.canReload()) viewModel.refresh()
                    },
                ) { Text("继续刷新") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRefreshConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SourceSubtitle(state: McpUiState) {
    val subtitle: String? = when (state) {
        is McpUiState.Loaded -> when (state.source) {
            McpSource.Runtime -> "来自 OpenCode 服务运行时状态（只读）"
            McpSource.File -> if (state.config.filePath != RUNTIME_SOURCE_SENTINEL) state.config.filePath else null
        }
        is McpUiState.EmptyConfig -> when (state.source) {
            McpSource.Runtime -> "运行时状态为空"
            McpSource.File -> state.filePath
        }
        else -> null
    }
    if (subtitle != null) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpStateBody(
    state: McpUiState,
    viewModel: McpViewModel,
    lastServers: Map<String, McpServer>,
    lastSaveError: String?,
    onDismiss: () -> Unit,
    requestRefresh: () -> Unit,
) {
    when (state) {
        McpUiState.Loading -> LoadingStateCard(label = "正在加载 MCP 状态")

        is McpUiState.RuntimeUnavailable -> {
            ErrorStateCard(
                title = "OpenCode 运行时状态不可用",
                message = "正在显示本地配置文件诊断结果。请检查服务器版本是否提供 GET /mcp 接口。",
            )
            Spacer(Modifier.size(8.dp))
            McpStateBody(
                state = state.fallback,
                viewModel = viewModel,
                lastServers = lastServers,
                lastSaveError = lastSaveError,
                onDismiss = onDismiss,
                requestRefresh = requestRefresh,
            )
        }

        is McpUiState.EmptyConfig -> {
            val message = when (state.source) {
                McpSource.Runtime -> "OpenCode 运行时未声明任何 MCP 服务器。"
                McpSource.File -> if (state.fallbackExhausted) {
                    "已检查项目与全局 OpenCode 配置，但都未在顶层 mcp 中声明任何服务器。最近一次检查的配置位于 ${state.filePath}。在该文件中加入 mcp 后下拉刷新即可生效。"
                } else {
                    "已找到配置 ${state.filePath}，但其顶层 mcp 中未声明任何服务器。"
                }
            }
            EmptyStateCard(
                title = "暂无 MCP 服务器",
                message = message,
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("关闭") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = requestRefresh, enabled = viewModel.canReload()) { Text("刷新") }
                    }
                },
            )
        }

        is McpUiState.MissingConfig -> {
            val firstPath = state.checkedPaths.firstOrNull() ?: "未知路径"
            var pathsExpanded by remember(state.checkedPaths) { mutableStateOf(false) }
            val allCheckedPaths = state.checkedPaths.joinToString("\n") { "• $it" }
            EmptyStateCard(
                title = "未找到 MCP 配置",
                message = "优先检查路径：\n• $firstPath",
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.checkedPaths.size > 1) {
                            TextButton(onClick = { pathsExpanded = !pathsExpanded }) {
                                Text(if (pathsExpanded) "收起检查路径" else "查看全部检查路径")
                            }
                            if (pathsExpanded) {
                                Text(
                                    text = allCheckedPaths,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onDismiss) { Text("关闭") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = requestRefresh, enabled = viewModel.canReload()) { Text("刷新") }
                        }
                    }
                },
            )
        }

        is McpUiState.ReadError -> {
            ErrorStateCard(title = "无法读取 MCP 配置", message = state.message)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = viewModel::retry, enabled = viewModel.canReload()) { Text("重试") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }

        is McpUiState.ParseError -> {
            ErrorStateCard(
                title = "MCP 配置解析失败",
                message = "${state.filePath}\n${state.message}\n（兼容读取 legacy mcpServers）",
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = viewModel::retry, enabled = viewModel.canReload()) { Text("重试") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }

        is McpUiState.Loaded,
        McpUiState.Saving,
        McpUiState.SaveSuccess,
        -> {
            val loaded = state as? McpUiState.Loaded
            val servers = loaded?.editedServers ?: lastServers
            val isSaving = state is McpUiState.Saving
            val isDirty = loaded?.dirty == true
            val saveError = loaded?.saveError ?: lastSaveError
            val isRuntime = loaded?.source == McpSource.Runtime

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(servers.entries.toList(), key = { it.key }) { (name, server) ->
                    McpServerRow(
                        server = server,
                        enabled = !isSaving && !isRuntime,
                        onToggle = { viewModel.toggleServer(name) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }

            saveError?.let {
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !isSaving) { Text("关闭") }
                if (!isRuntime) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { viewModel.save() }, enabled = !isSaving && isDirty) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpServerRow(server: McpServer, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Note: command/args may be null when source=Runtime; preview hides itself.
            val preview = listOfNotNull(
                server.command,
                server.args.take(2).joinToString(" ").takeIf { it.isNotBlank() },
                server.url,
            ).joinToString(" ")
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = server.enabled,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            modifier = Modifier
                .padding(start = 16.dp)
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = "${server.name} 启用状态" },
        )
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin :app:lintDebug` (no UI test infra exists for this sheet; visual smoke tested via Batch 5 release-build pass)
**Commit:** `feat(ux/mcp): replace mcpServers copy with mcp; runtime-source read-only; RuntimeUnavailable banner`

---

## Batch 4: Tests (parallel - 4 implementers)

All tasks in this batch depend on Batch 3 completing.
Tasks: 4.1, 4.2, 4.3, 4.4

### Task 4.1: OpenCodeApi MCP-endpoint test (header + DTO + 404/405/501)
**File:** `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiMcpStatusTest.kt`
**Test:** This task IS the test file (already authored skeleton in Task 1.1).
**Depends:** 1.1
**Domain:** backend

This task confirms the test file from Task 1.1 lives at the listed path and runs green. If Task 1.1's implementer chose to inline the test alongside the implementation commit, this task is a no-op verify; otherwise the implementer copies the test code from Task 1.1 verbatim into the listed path.

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'dev.minios.ocremote.data.api.OpenCodeApiMcpStatusTest'`
**Commit:** `test(api): cover GET /mcp success/Unsupported/Failed branches and directory header`

### Task 4.2: ServerRepository runtime-status-first acceptance test
**File:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/ServerRepositoryMcpRuntimeFirstTest.kt`
**Test:** This task IS the test file.
**Depends:** 2.2, 1.3
**Domain:** backend

This is the user-scenario acceptance test: runtime status returns the seven servers from the fixture; project file is empty; the result must be `Loaded(source=Runtime)` containing exactly those seven names. Plus the inverse: when runtime is Unsupported, file fallback wins.

We use a `MockEngine`-backed `OpenCodeApi` for `getMcpStatus` and `getServerPaths`, and stub the file-read paths through the same engine to return either empty content or 404 (mapped to `OpenCodeFileNotFoundException` by `OpenCodeApi.readFile`).

```kotlin
package dev.minios.ocremote.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.minios.ocremote.data.api.McpRuntimeStatus
import dev.minios.ocremote.data.api.McpRuntimeStatusResult
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ServerRepositoryMcpRuntimeFirstTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val expectedNames = listOf("aceTool", "autoinfo", "exa", "fetch", "github", "playwright", "stitch")

    private fun newRepo(handler: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.engine.mock.MockHttpResponse): ServerRepository {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val api = OpenCodeApi(client, json)
        val dataStore: DataStore<Preferences> = mock()
        whenever(dataStore.data).thenReturn(flowOf(androidx.datastore.preferences.core.preferencesOf()))
        return ServerRepository(dataStore, api, json)
    }

    @Test
    fun `runtime returns seven servers and loaded state has Runtime source even when project file is empty`() = runBlocking {
        val fixture = javaClass.getResource("/mcp/runtime-status-seven-servers.json")!!.readText()
        val repo = newRepo { request ->
            when (request.url.encodedPath) {
                "/mcp" -> respond(
                    content = ByteReadChannel(fixture),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("unexpected ${request.method.value} ${request.url}")
            }
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val state = repo.readMcpConfigState(conn, projectDir = "/workspace/proj")

        assertTrue("expected Loaded got $state", state is McpConfigLoadState.Loaded)
        val loaded = state as McpConfigLoadState.Loaded
        assertEquals(McpSource.Runtime, loaded.source)
        assertEquals(expectedNames.toSet(), loaded.config.servers.keys)
        // Runtime-sourced loaded state must use sentinel filePath
        assertEquals("<runtime>", loaded.config.filePath)
    }

    @Test
    fun `runtime Unsupported (404) falls through to file fallback unchanged`() = runBlocking {
        val repo = newRepo { request ->
            when (request.url.encodedPath) {
                "/mcp" -> respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
                "/path" -> respond(
                    content = ByteReadChannel("""{"home":"/home/u","state":"","config":"","worktree":"","directory":""}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/file/content" -> respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
                else -> error("unexpected ${request.url}")
            }
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val state = repo.readMcpConfigState(conn, projectDir = "/workspace/proj")

        // No file found → NotFound from file scan, NOT RuntimeUnavailable (because runtime was just Unsupported, not Failed).
        assertTrue("expected NotFound got $state", state is McpConfigLoadState.NotFound)
    }

    @Test
    fun `runtime Failed plus empty file fallback yields RuntimeUnavailable`() = runBlocking {
        val repo = newRepo { request ->
            when (request.url.encodedPath) {
                "/mcp" -> respond(content = ByteReadChannel(""), status = HttpStatusCode.Unauthorized)
                "/path" -> respond(
                    content = ByteReadChannel("""{"home":"/home/u","state":"","config":"","worktree":"","directory":""}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/file/content" -> respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
                else -> error("unexpected ${request.url}")
            }
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val state = repo.readMcpConfigState(conn, projectDir = "/workspace/proj")

        assertTrue("expected RuntimeUnavailable got $state", state is McpConfigLoadState.RuntimeUnavailable)
        val inner = (state as McpConfigLoadState.RuntimeUnavailable).fallback
        assertTrue(inner is McpConfigLoadState.NotFound)
    }

    @Test
    fun `runtime success-empty falls through and surfaces file Loaded when file has servers`() = runBlocking {
        val projectFileBody = """
            {"type":"file","content":"{\"mcp\":{\"only\":{\"command\":\"node\"}}}"}
        """.trimIndent()
        val repo = newRepo { request ->
            when (request.url.encodedPath) {
                "/mcp" -> respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/path" -> respond(
                    content = ByteReadChannel("""{"home":"/home/u","state":"","config":"","worktree":"","directory":""}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/file/content" -> respond(
                    content = ByteReadChannel(projectFileBody),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("unexpected ${request.url}")
            }
        }
        val conn = ServerConnection.from("http://example.test:4096")

        val state = repo.readMcpConfigState(conn, projectDir = "/workspace/proj")

        assertTrue(state is McpConfigLoadState.Loaded)
        val loaded = state as McpConfigLoadState.Loaded
        assertEquals(McpSource.File, loaded.source)
        assertEquals(setOf("only"), loaded.config.servers.keys)
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'dev.minios.ocremote.data.repository.ServerRepositoryMcpRuntimeFirstTest'`
**Commit:** `test(repo): runtime-status-first pipeline acceptance covering 7-server scenario`

### Task 4.3: McpConfigParser official-schema test
**File:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/McpConfigParserOfficialSchemaTest.kt`
**Test:** This task IS the test file.
**Depends:** 2.1
**Domain:** general

Adds a sibling test class focused on the new schema acceptance. Existing `McpConfigParserTest.kt` stays unchanged and continues to verify legacy compatibility plus general parse behavior.

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigParserOfficialSchemaTest {

    @Test
    fun `command as string array splits into command plus args`() {
        val json = """{"mcp":{"x":{"command":["node","script.js","--flag"]}}}"""
        val state = McpConfigParser.parseState("/cfg", json)
        assertTrue(state is McpConfigLoadState.Loaded)
        val server = (state as McpConfigLoadState.Loaded).config.servers["x"]!!
        assertEquals("node", server.command)
        assertEquals(listOf("script.js", "--flag"), server.args)
    }

    @Test
    fun `command array combines with explicit args field`() {
        val json = """{"mcp":{"x":{"command":["node","a.js"],"args":["--b"]}}}"""
        val state = McpConfigParser.parseState("/cfg", json) as McpConfigLoadState.Loaded
        val server = state.config.servers["x"]!!
        assertEquals("node", server.command)
        // command-array tail comes first, then explicit args, preserving deterministic order.
        assertEquals(listOf("a.js", "--b"), server.args)
    }

    @Test
    fun `remote entry with url and no command parses successfully`() {
        val json = """{"mcp":{"r":{"type":"remote","url":"https://x.example/mcp","enabled":true}}}"""
        val state = McpConfigParser.parseState("/cfg", json) as McpConfigLoadState.Loaded
        val server = state.config.servers["r"]!!
        assertEquals("remote", server.type)
        assertEquals(null, server.command)
        assertEquals("https://x.example/mcp", server.url)
        assertEquals(true, server.enabled)
    }

    @Test
    fun `top-level mcp wins over mcpServers when both present`() {
        val json = """
            {
              "mcp": {"a": {"command": "node"}},
              "mcpServers": {"b": {"command": "python"}}
            }
        """.trimIndent()
        val state = McpConfigParser.parseState("/cfg", json) as McpConfigLoadState.Loaded
        assertEquals(setOf("a"), state.config.servers.keys)
        assertEquals(McpSource.File, state.source)
    }

    @Test
    fun `serialize defaults to mcp key when neither present originally`() {
        val empty = McpConfigParser.parse("/cfg", """{"providers":{}}""")
        // Empty parse returns Empty -> via parse() returns config with servers=emptyMap; we add a new server then serialize.
        val withServer = empty!!.copy(
            servers = mapOf(
                "n" to dev.minios.ocremote.domain.model.McpServer(
                    name = "n", type = null, command = "node",
                    args = emptyList(), url = null, enabled = true,
                ),
            ),
        )
        val out = McpConfigParser.serialize(withServer)
        assertTrue("output should include canonical key", out.contains("\"mcp\""))
    }

    @Test
    fun `parse error message must not echo raw json that may contain secrets`() {
        // Force a parse failure by providing invalid JSON containing a fake token.
        val json = """{"mcp": {"bad": {  "command": invalid_token_here   }}}"""
        val state = McpConfigParser.parseState("/cfg", json)
        assertTrue(state is McpConfigLoadState.Error)
        val err = state as McpConfigLoadState.Error
        assertTrue("must not leak raw json", !(err.message ?: "").contains("invalid_token_here"))
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'dev.minios.ocremote.data.repository.McpConfigParserOfficialSchemaTest'`
**Commit:** `test(parser): official mcp schema (command-array, remote url, mcp-preferred)`

### Task 4.4: McpViewModel runtime/fallback state test
**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/McpViewModelRuntimeStateTest.kt`
**Test:** This task IS the test file.
**Depends:** 3.1
**Domain:** general

Adds a sibling test that drives `McpStateController` directly with stubbed `readMcpConfigState`/`writeMcpConfig` lambdas (matches existing `McpViewModelTest.kt` style) covering: Loaded(Runtime), Loaded(File), RuntimeUnavailable→fallback mapping, and toggle/save no-op when source=Runtime.

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.McpConfig
import dev.minios.ocremote.domain.model.McpConfigLoadState
import dev.minios.ocremote.domain.model.McpServer
import dev.minios.ocremote.domain.model.McpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpViewModelRuntimeStateTest {

    private val conn = ServerConnection.from("http://x")
    private val projectDir = "/p"

    private fun runtimeLoaded() = McpConfigLoadState.Loaded(
        config = McpConfig(
            filePath = "<runtime>",
            rawJson = "{}",
            servers = mapOf(
                "github" to McpServer("github", null, null, emptyList(), null, true),
                "stitch" to McpServer("stitch", null, null, emptyList(), null, true),
            ),
        ),
        source = McpSource.Runtime,
    )

    @Test
    fun `runtime loaded propagates source to UI state`() = runTest {
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> runtimeLoaded() },
            writeMcpConfig = { _, _ -> Result.success(Unit) },
        )
        controller.load(conn, projectDir)
        val state = controller.state.value
        assertTrue(state is McpUiState.Loaded)
        assertEquals(McpSource.Runtime, (state as McpUiState.Loaded).source)
        assertEquals(setOf("github", "stitch"), state.editedServers.keys)
    }

    @Test
    fun `toggle is a no-op when source is runtime`() = runTest {
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> runtimeLoaded() },
            writeMcpConfig = { _, _ -> Result.success(Unit) },
        )
        controller.load(conn, projectDir)
        val before = (controller.state.value as McpUiState.Loaded).editedServers["github"]!!.enabled
        controller.toggleServer("github")
        val after = (controller.state.value as McpUiState.Loaded).editedServers["github"]!!.enabled
        assertEquals("toggle suppressed for runtime source", before, after)
    }

    @Test
    fun `save is a no-op when source is runtime`() = runTest {
        var writeCalls = 0
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> runtimeLoaded() },
            writeMcpConfig = { _, _ -> writeCalls++; Result.success(Unit) },
        )
        controller.load(conn, projectDir)
        controller.save()
        assertEquals(0, writeCalls)
    }

    @Test
    fun `RuntimeUnavailable wraps fallback ui state`() = runTest {
        val fallback = McpConfigLoadState.NotFound(checkedPaths = listOf("/p/.opencode/opencode.json"))
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> McpConfigLoadState.RuntimeUnavailable(fallback) },
            writeMcpConfig = { _, _ -> Result.success(Unit) },
        )
        controller.load(conn, projectDir)
        val state = controller.state.value
        assertTrue(state is McpUiState.RuntimeUnavailable)
        val inner = (state as McpUiState.RuntimeUnavailable).fallback
        assertTrue(inner is McpUiState.MissingConfig)
        assertEquals(listOf("/p/.opencode/opencode.json"), (inner as McpUiState.MissingConfig).checkedPaths)
    }

    @Test
    fun `file Loaded keeps File source so save and toggle remain available`() = runTest {
        val fileLoaded = McpConfigLoadState.Loaded(
            config = McpConfig(
                filePath = "/p/.opencode/opencode.json",
                rawJson = """{"mcp":{"a":{"command":"node"}}}""",
                servers = mapOf("a" to McpServer("a", null, "node", emptyList(), null, true)),
            ),
            source = McpSource.File,
        )
        var writeCalls = 0
        val controller = McpStateController(
            scope = this,
            readMcpConfigState = { _, _ -> fileLoaded },
            writeMcpConfig = { _, _ -> writeCalls++; Result.success(Unit) },
        )
        controller.load(conn, projectDir)
        controller.toggleServer("a")
        controller.save()
        assertNotEquals(0, writeCalls)
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'dev.minios.ocremote.ui.screens.sessions.McpViewModelRuntimeStateTest'`
**Commit:** `test(vm): runtime/fallback state mapping; toggles and save suppressed when source is runtime`

---

## Batch 5: Version bump and local release-build sanity (single implementer)

Task in this batch depends on Batch 4 completing.
Tasks: 5.1

### Task 5.1: Bump version to 1.6.26 and write release notes; local release-build sanity
**File:** `app/build.gradle.kts` (modify versionName/versionCode), `RELEASE_NOTES_1.6.26.md` (new)
**Test:** none (build itself is the verification)
**Depends:** 4.1, 4.2, 4.3, 4.4
**Domain:** general

Design says this is a corrective MCP release. Notes must be tight: runtime parity, parser official-schema, UI copy, and signer-cert reuse. We bump `versionName` from `1.6.25` to `1.6.26`, `versionCode` from `38` to `39` (single-step increment, matches the existing `1.6.x` cadence). The release-notes file must follow the structure of `RELEASE_NOTES_1.6.25.md` because the workflow's `gh release create --notes-file` looks for that exact filename.

```kotlin
// COMPLETE patch on app/build.gradle.kts (replace the two lines only):
        versionCode = 39
        versionName = "1.6.26"
```

```markdown
// COMPLETE RELEASE_NOTES_1.6.26.md
# OC Remote v1.6.26 — Release Notes

## Highlights

- MCP runtime status parity
  - The APK now asks the OpenCode server for runtime MCP status (`GET /mcp`), the same source the web UI uses, and renders those servers regardless of whether the project's `.opencode/opencode.json` declares `mcp` itself.
  - Resolves the case where global MCP servers (e.g. `aceTool`, `autoinfo`, `exa`, `fetch`, `github`, `playwright`, `stitch`) appeared in the web UI but were missing in the APK because the project config file was empty.
  - Older OpenCode servers without `GET /mcp` (404/405/501) silently fall back to the existing config-file scan; behavior matches v1.6.25 in that case.
  - When runtime status is genuinely unavailable AND the file scan is empty/missing, the sheet now surfaces an explicit "OpenCode 运行时状态不可用" banner above the file diagnostics.

- Fallback parser official-schema fixes
  - Top-level `mcp` is now the canonical key (previously `mcpServers`). Legacy `mcpServers` remains read-compatible.
  - `command` may be a JSON array (`["node","script.js"]`); the first element is the executable, the rest are merged into args.
  - Remote MCP entries with `url` and `type: "remote"` parse without requiring a `command`.

- UI copy
  - Empty/Missing/Parse-error copy now refers to `mcp`, not `mcpServers`. The single legacy mention is parenthesized as "（兼容读取 legacy mcpServers）".
  - When the displayed servers come from runtime status, the sheet shows "来自 OpenCode 服务运行时状态（只读）" and disables the toggle/save controls (no edit endpoint exists for runtime status).

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅ (new: `OpenCodeApiMcpStatusTest`, `ServerRepositoryMcpRuntimeFirstTest`, `McpConfigParserOfficialSchemaTest`, `McpViewModelRuntimeStateTest`, `McpFixtureLoadTest`)
- `:app:assembleRelease` ✅
- `:app:lintDebug` ✅
- Signer cert SHA-256 matches v1.6.23 reference ✅

## Version

- `versionName`: `1.6.26`
- `versionCode`: `39`
```

The implementer also runs locally:

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner 2>/dev/null | sort -V | tail -1)
if [ -n "$APKSIGNER" ] && [ -f app/build/outputs/apk/release/app-release.apk ]; then
    SHA=$("$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk \
        | grep -m1 'SHA-256' | awk '{print tolower($NF)}')
    REF=$(grep -v '^#' scripts/release/v1623-signer-cert.sha256 | tr -d '[:space:]')
    if [ "$SHA" != "$REF" ]; then
        echo "::error::Signer cert SHA-256 mismatch: built=$SHA expected=$REF"
        exit 1
    fi
    echo "Signer cert SHA-256 OK: $SHA"
fi
```

If the local build runner does not have a release keystore at `app/keystore/release.keystore`, the implementer skips the local apksigner step and relies on the GitHub Actions verification in Task 6.3 — but `:app:assembleRelease` MUST still complete (Gradle will build an unsigned APK in that case; that is acceptable here because CI re-builds with the keystore from secrets).

**Verify:** Local `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` exits 0; signer-cert step (if keystore available) prints `Signer cert SHA-256 OK`.
**Commit:** `chore(release): bump versionName=1.6.26 versionCode=39 + RELEASE_NOTES_1.6.26.md`

---

## Batch 6: Sign + publish v1.6.26 through GitHub Actions, then verify (sequential)

Tasks in this batch depend on Batch 5 completing.
Tasks: 6.1, 6.2, 6.3

### Task 6.1: Lifecycle commit + push of all preceding work and tag v1.6.26
**File:** none (git operations only)
**Test:** none
**Depends:** 5.1
**Domain:** general

After Batches 1–5 are committed locally on `issue/20-correct-mcp-fallback-behavior-and-perform-a-stan`, the implementer calls `lifecycle_commit(issue_number=20, scope="release", summary="mcp runtime status parity correction for v1.6.26")` to land any pending uncommitted changes and auto-push to fork origin (per v9 lifecycle rules; pre-flight ownership check enforced by the lifecycle tool itself). Then create and push the release tag:

```bash
# Verify branch is up to date and clean.
git status
git log --oneline -5
git tag v1.6.26
git push origin v1.6.26
```

The implementer MUST NOT push to upstream; the lifecycle tool's pre-flight handles the fork-vs-upstream check, but the manual `git push origin v1.6.26` here also targets `origin` only.

**Verify:** `git ls-remote --tags origin v1.6.26` prints exactly one ref pointing at the latest commit on `issue/20-...`.
**Commit:** (covered by lifecycle_commit; no separate commit)

### Task 6.2: Trigger GitHub Actions release workflow with tag=v1.6.26
**File:** none (CI invocation only)
**Test:** none
**Depends:** 6.1
**Domain:** general

The release workflow at `.github/workflows/release.yml` is `workflow_dispatch`-only and accepts an input `tag`. The implementer triggers it via `gh`:

```bash
gh workflow run "Build Release APK" --ref v1.6.26 -f tag=v1.6.26

# Wait for the run to schedule and then watch.
RUN_ID=$(gh run list --workflow "Build Release APK" --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status
```

If the workflow fails on the "Verify built APK metadata matches expected version" or "Verify release APK signature" step, the implementer reports the failure and stops. Do NOT bypass these gates by editing the workflow.

Note: the workflow does NOT yet enforce the v1.6.23 signer-cert SHA-256 match (it only runs `apksigner verify --verbose`). Task 6.3 performs that verification post-publish; if a future iteration wants the gate inside CI, that is a separate plan.

**Verify:** `gh run view "$RUN_ID"` shows `conclusion=success` and the artifact `ocremote-release-1.6.26` exists; `gh release view v1.6.26 --json assets -q '.assets[].name'` includes `oc-remote-1.6.26.apk`.
**Commit:** none

### Task 6.3: Verify GitHub Release asset, signer cert SHA-256, and downloadability
**File:** none (verification only)
**Test:** Manual verification script below.
**Depends:** 6.2
**Domain:** general

Final acceptance gate. Download the published APK from the GitHub Release, confirm the signer cert SHA-256 matches the v1.6.23 canonical reference, and confirm the APK's `versionName`/`versionCode` matches what the workflow built.

```bash
set -euo pipefail
WORK=$(mktemp -d -t oc-1626-XXXX)
cd "$WORK"
gh release download v1.6.26 --pattern 'oc-remote-1.6.26.apk'
APK=oc-remote-1.6.26.apk
test -f "$APK" || { echo "::error::APK not downloaded"; exit 1; }

APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner | sort -V | tail -1)
test -n "$APKSIGNER" || { echo "::error::apksigner not found"; exit 1; }

"$APKSIGNER" verify --verbose "$APK"
SHA=$("$APKSIGNER" verify --print-certs "$APK" | grep -m1 'SHA-256' | awk '{print tolower($NF)}')
REF=$(grep -v '^#' "$OLDPWD"/scripts/release/v1623-signer-cert.sha256 | tr -d '[:space:]')
if [ "$SHA" != "$REF" ]; then
    echo "::error::Signer cert mismatch: built=$SHA expected=$REF"
    exit 1
fi
echo "Signer cert SHA-256 OK: $SHA"

# Confirm version inside APK
AAPT=$(find "$ANDROID_HOME/build-tools" -name aapt | sort -V | tail -1)
"$AAPT" dump badging "$APK" | grep -E "package: name='dev.minios.ocremote' versionCode='39' versionName='1.6.26'" \
    || { echo "::error::APK version metadata mismatch"; exit 1; }
echo "APK versionName=1.6.26 versionCode=39 OK"
```

**Verify:** All three assertions pass: signer cert SHA-256 equals `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`; aapt badging confirms versionName/versionCode; `apksigner verify --verbose` exits 0.

After verification passes, the implementer calls `lifecycle_log_progress(kind="status", summary="v1.6.26 published; signer cert OK; runtime parity acceptance verified")` and `lifecycle_log_progress(kind="handoff", summary="ready for lifecycle_finish")`. The actual `lifecycle_finish` is the user's call (it merges + closes + cleans up worktree) and is NOT done by the executor of this plan.

**Commit:** none
