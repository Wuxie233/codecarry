# Fork Session Project Context Implementation Plan

**Goal:** Make fork-session creation inherit the source session's project directory by propagating it through `ChatViewModel` → `OpenCodeApi.forkSession` → `x-opencode-directory` header, and merge the returned forked session into the local reducer immediately.

**Architecture:**
- `OpenCodeApi.forkSession` gains an optional `directory: String?` parameter and attaches the existing `x-opencode-directory` header (URL-encoded), exactly mirroring `createSession`, `executeCommand`, etc. No new transport, no new endpoint.
- `ChatViewModel.forkSession` waits for `sessionLoaded`, resolves the effective directory with the same fallback chain already used by `executeCommand` (in-memory `sessionDirectory` → reducer-snapshot lookup → null), passes it into the API, and on success calls `eventReducer.setSessions(serverId, listOf(session))` — identical to `createNewSession`.
- The directory resolution is extracted into a pure helper `ForkDirectoryResolver.resolve(...)` so it can be unit-tested without instantiating `ChatViewModel` (which has no existing test scaffolding and no DI mocking framework on classpath).
- Header-level coverage uses Ktor `MockEngine` (added to `testImplementation`) to assert the encoded header is present on the fork POST and absent when no directory is supplied.

**Design:** [thoughts/shared/designs/2026-04-28-fork-session-project-context-design.md](../designs/2026-04-28-fork-session-project-context-design.md)

**Contract:** none (single-domain Android client — every task is `Domain: general`; the OpenCode server protocol is unchanged and already documented as the existing `x-opencode-directory` header pattern reused throughout `OpenCodeApi.kt`)

---

## Planner-stage decisions (gap-filling, locked)

These decisions are made now so implementers don't have to guess:

1. **Header encoding.** Use `android.net.Uri.encode(directory)` — identical to every other `directory?.let { header(...) }` line in `OpenCodeApi.kt` (see lines 124, 149, 193, 293, 314, 338, 430, 456, 555, 589, 603, 625, 646, 659, 858, 895). Do NOT roll a different encoding for fork.
2. **Fallback chain for `effectiveDirectory`.** Mirror exactly the chain in `executeCommand` (`ChatViewModel.kt:1129-1134`):
   ```
   sessionDirectory                                       // primary, populated by loadSession()
     ?: eventReducer.sessions.value
          .firstOrNull { it.id == sessionId }
          ?.directory
          ?.takeIf { it.isNotBlank() }                    // reducer-cache fallback
     ?: null                                              // explicit null → API sends no header
   ```
   When the result is `null`, log at WARN (`Log.w`) so the fallback is observable in logcat — this is the "deliberate fallback rather than accidental null" the design calls for.
3. **Wait for `sessionLoaded` before reading directory.** Fork must `if (!sessionLoaded.isCompleted) sessionLoaded.await()` first, like `executeCommand` does. This eliminates the race where a user taps Fork while `loadSession()` is still in flight.
4. **Reducer merge.** Call `eventReducer.setSessions(serverId, listOf(session))` after a successful fork — byte-identical to `createNewSession` (`ChatViewModel.kt:1252`). `setSessions` already does an upsert+sort by `time.updated` desc (`EventReducer.kt:352-371`), so feeding it the freshly forked session is safe and preserves any existing state.
5. **Log line format.** Keep the existing `"Forked session $sessionId -> ${session.id}"` debug log; append `(directory=$effectiveDirectory)` so logcat shows what context was used. Same pattern as `executeCommand`'s log at `ChatViewModel.kt:1155`.
6. **Pure resolver location.** Place `ForkDirectoryResolver` under `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/` (next to `ChatViewModel.kt`). It is a screen-local helper, not domain logic. Mirrors how `PatchVisibilityResolver` lives next to its consumer (`app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/` — see `PatchVisibilityResolverTest.kt`).
7. **MockEngine dependency.** Add `testImplementation("io.ktor:ktor-client-mock:$ktorVersion")` to `app/build.gradle.kts`. The version variable already exists at `ktorVersion = "2.3.11"`. No version bump.
8. **Test naming convention.** Match existing tests — backticked Kotlin function names with descriptive sentences (`HomeViewModelTest`, `EventReducerTest` style).
9. **No `ChatScreen.kt` change.** Both fork callsites (`ChatScreen.kt:1535` menu, `:1842` slash command) already call `viewModel.forkSession { session -> ... }` — they receive the new behavior automatically. Verifying this is part of Task 5.1.
10. **No new strings.** Existing `R.string.chat_fork_failed` is sufficient. The design explicitly says "Fork failures should continue using the existing user-visible error path."
11. **No `Log.e` on null directory.** A null directory is a *fallback*, not an error. WARN level only. Fork still proceeds (server may still create the session, just under root — strictly no worse than today's bug).
12. **Reducer update on null directory.** The reducer merge happens regardless of whether a directory was sent. The returned `session.directory` is what gets stored — we never override it client-side (design constraint: "Do not fake or override the service-returned session directory on the client").

---

## Dependency Graph

```
Batch 1 (parallel, 2 tasks): foundation — independent files
  1.1 ForkDirectoryResolver pure helper
  1.2 build.gradle.kts — add ktor-client-mock testImplementation

Batch 2 (parallel, 2 tasks): API + helper tests (depends on 1.1, 1.2)
  2.1 OpenCodeApi.forkSession — add directory parameter + header
  2.2 ForkDirectoryResolverTest — pure-function unit tests

Batch 3 (sequential, 1 task): ViewModel wiring (depends on 2.1, 1.1)
  3.1 ChatViewModel.forkSession — sessionLoaded.await + resolver + API call + reducer merge

Batch 4 (parallel, 2 tasks): integration + regression tests (depends on 3.1, 2.1)
  4.1 OpenCodeApiForkTest — MockEngine HTTP-level header assertions
  4.2 ChatScreen fork-call sites — verification only (no code change expected)
```

Total: 6 micro-tasks across 4 batches. Batches 1, 2, 4 run in parallel.

---

## Batch 1: Foundation (parallel — 2 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: Add `ForkDirectoryResolver` pure helper

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ForkDirectoryResolver.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ForkDirectoryResolverTest.kt` (created in Task 2.2)
**Depends:** none
**Domain:** general

Create a pure helper that encapsulates the fork-time directory resolution chain. This is what makes the bug regression-testable without spinning up a `ChatViewModel`.

```kotlin
package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Session

/**
 * Resolves the directory context that should be sent on a fork-session request.
 *
 * The chain mirrors [ChatViewModel.executeCommand]'s fallback so that fork and
 * command execution use the same project-context behaviour:
 *
 *   1. The in-memory [sessionDirectory] populated by `loadSession()`.
 *   2. The directory of the matching session in the reducer snapshot (covers the
 *      case where `loadSession()` failed but SSE has already delivered the session).
 *   3. `null` — explicit, deliberate fallback. Caller is expected to log a warning
 *      and let the server fall back to its own default project context.
 *
 * Pure function — no Android, no coroutines, no side effects. Testable in isolation.
 */
object ForkDirectoryResolver {

    /**
     * @param sessionDirectory current in-memory directory loaded from the source session
     * @param sessionId        id of the source session being forked
     * @param reducerSessions  current snapshot of `eventReducer.sessions.value`
     * @return the directory string to attach to the fork request, or `null` to send no header
     */
    fun resolve(
        sessionDirectory: String?,
        sessionId: String,
        reducerSessions: List<Session>,
    ): String? {
        sessionDirectory?.takeIf { it.isNotBlank() }?.let { return it }
        return reducerSessions
            .firstOrNull { it.id == sessionId }
            ?.directory
            ?.takeIf { it.isNotBlank() }
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin` (the file must compile in isolation; tests come in 2.2)
**Commit:** `feat(chat): add ForkDirectoryResolver helper`

---

### Task 1.2: Add ktor-client-mock to test dependencies

**File:** `app/build.gradle.kts`
**Test:** none (build config)
**Depends:** none
**Domain:** general

Locate the existing test dependencies block (currently around the `testImplementation("junit:junit:4.13.2")` line) and add the Ktor MockEngine artifact, reusing the existing `ktorVersion` variable (already defined as `"2.3.11"` higher in the file).

Find this block:
```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

Replace with:
```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
```

**Verify:**
```sh
./gradlew :app:dependencies --configuration testRuntimeClasspath | grep ktor-client-mock
```
Expect a single line confirming `io.ktor:ktor-client-mock:2.3.11`.

**Commit:** `chore(deps): add ktor-client-mock for API HTTP-level tests`

---

## Batch 2: API change + resolver test (parallel — 2 implementers)

Both tasks depend on Batch 1: 2.1 needs nothing from 1.1/1.2 directly but is grouped here as the implementation half of the API change; 2.2 depends on 1.1 (resolver exists) and 1.2 (mock dep present, though 2.2 itself doesn't use the mock).

### Task 2.1: Add `directory` parameter and header to `OpenCodeApi.forkSession`

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiForkTest.kt` (created in Task 4.1)
**Depends:** none (signature change — caller in 3.1 will adopt it)
**Domain:** general

Locate the existing `forkSession` block at lines 265-277:

```kotlin
    /**
     * Fork a session (create a new session from a message point).
     * POST /session/{sessionId}/fork
     */
    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String? = null): Session {
        val body = buildMap<String, String> {
            messageId?.let { put("messageID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session/$sessionId/fork") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }
```

Replace with:

```kotlin
    /**
     * Fork a session (create a new session from a message point).
     * POST /session/{sessionId}/fork
     *
     * @param directory The source session's project directory, sent as
     *   `x-opencode-directory` so the server creates the forked session in
     *   the correct project context. When `null`, the server falls back to
     *   its default/root context (legacy behaviour).
     */
    suspend fun forkSession(
        conn: ServerConnection,
        sessionId: String,
        messageId: String? = null,
        directory: String? = null,
    ): Session {
        val body = buildMap<String, String> {
            messageId?.let { put("messageID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session/$sessionId/fork") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", android.net.Uri.encode(it)) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }
```

Notes for the implementer:
- The `directory` parameter is added LAST and defaulted to `null`, so existing callers (none currently outside `ChatViewModel`) keep compiling without modification.
- The header line is a one-liner copy of the same pattern used in `createSession` (line 149), `executeCommand` (line 293), and many others — keep the parameter order identical so a future grep across these methods stays clean.

**Verify:**
```sh
./gradlew :app:compileDebugKotlin
```

**Commit:** `fix(api): forkSession sends x-opencode-directory header`

---

### Task 2.2: Unit-test `ForkDirectoryResolver`

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ForkDirectoryResolverTest.kt`
**Test:** self
**Depends:** 1.1 (resolver class exists)
**Domain:** general

Pure-JUnit tests — no Android dependencies, no coroutines, no mocks needed. Mirrors the style of `PatchVisibilityResolverTest` and `BuildActiveConversationsTest`.

```kotlin
package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkDirectoryResolverTest {

    @Test
    fun `returns in-memory sessionDirectory when populated`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = "/home/user/projectA",
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/wrong")),
        )
        assertEquals("/home/user/projectA", result)
    }

    @Test
    fun `falls back to reducer session directory when in-memory is null`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/projectA")),
        )
        assertEquals("/home/user/projectA", result)
    }

    @Test
    fun `falls back to reducer session directory when in-memory is blank`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = "  ",
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/projectA")),
        )
        assertEquals("/home/user/projectA", result)
    }

    @Test
    fun `returns null when in-memory and reducer match are both blank`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "")),
        )
        assertNull(result)
    }

    @Test
    fun `returns null when reducer has no matching session id`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_missing",
            reducerSessions = listOf(testSession("ses_other", directory = "/home/user/projectA")),
        )
        assertNull(result)
    }

    @Test
    fun `returns null when reducer is empty and in-memory is null`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = null,
            sessionId = "ses_1",
            reducerSessions = emptyList(),
        )
        assertNull(result)
    }

    @Test
    fun `prefers in-memory over reducer even when both populated`() {
        val result = ForkDirectoryResolver.resolve(
            sessionDirectory = "/home/user/inmem",
            sessionId = "ses_1",
            reducerSessions = listOf(testSession("ses_1", directory = "/home/user/reducer")),
        )
        assertEquals("/home/user/inmem", result)
    }

    private fun testSession(id: String, directory: String) = Session(
        id = id,
        directory = directory,
        time = Session.Time(created = 1L, updated = 1L, archived = null),
    )
}
```

**Verify:**
```sh
./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.ui.screens.chat.ForkDirectoryResolverTest
```
All 7 tests must pass.

**Commit:** `test(chat): cover ForkDirectoryResolver fallback chain`

---

## Batch 3: ViewModel wiring (sequential — 1 implementer)

This task is the only true serial step: it consumes the resolver from Batch 1 and the new API signature from Batch 2.

### Task 3.1: Wire fork directory + reducer merge in `ChatViewModel.forkSession`

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`
**Test:** covered indirectly by `ForkDirectoryResolverTest` (Task 2.2) and `OpenCodeApiForkTest` (Task 4.1); no new ViewModel-level test (no test scaffolding for `ChatViewModel` exists in the project and adding one is out of scope per the design's "Keep the fix minimal" constraint)
**Depends:** 1.1 (resolver), 2.1 (new API parameter)
**Domain:** general

Locate the existing `forkSession` block at lines 1089-1101:

```kotlin
    /** Fork the current session. Returns the new session or null. */
    fun forkSession(onResult: (Session?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = api.forkSession(conn, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Forked session $sessionId -> ${session.id}")
                onResult(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fork session", e)
                onResult(null)
            }
        }
    }
```

Replace with:

```kotlin
    /**
     * Fork the current session.
     *
     * Inherits the source session's project directory via the existing
     * `x-opencode-directory` header so the forked session lands in the same
     * project rather than the server's root context. The returned session is
     * merged into [eventReducer] so the session list updates immediately,
     * mirroring [createNewSession].
     *
     * Returns the new session or `null` on failure.
     */
    fun forkSession(onResult: (Session?) -> Unit) {
        viewModelScope.launch {
            try {
                if (!sessionLoaded.isCompleted) {
                    sessionLoaded.await()
                }
                val effectiveDirectory = ForkDirectoryResolver.resolve(
                    sessionDirectory = sessionDirectory,
                    sessionId = sessionId,
                    reducerSessions = eventReducer.sessions.value,
                )
                if (effectiveDirectory == null) {
                    Log.w(
                        TAG,
                        "Forking session $sessionId without directory context — server will use its default project"
                    )
                }
                val session = api.forkSession(
                    conn = conn,
                    sessionId = sessionId,
                    directory = effectiveDirectory,
                )
                eventReducer.setSessions(serverId, listOf(session))
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Forked session $sessionId -> ${session.id} (directory=$effectiveDirectory)"
                    )
                }
                onResult(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fork session", e)
                onResult(null)
            }
        }
    }
```

Notes for the implementer:
- Do NOT touch any other method in this file. The diff is purely additive within the body of `forkSession`.
- The `sessionLoaded.await()` block is identical to the one in `executeCommand` (line 1121-1123). Copy-paste it verbatim — do NOT introduce a new latching primitive.
- `eventReducer.setSessions(serverId, listOf(session))` matches `createNewSession` at line 1252. The reducer's existing upsert-by-id semantics handle the case where SSE has already delivered a `SessionCreated` event for the same id.
- If the API throws, both the directory-context branch and the reducer-merge branch are skipped — same failure surface as before. `onResult(null)` is preserved.
- The new `Log.w` line uses the existing `TAG` constant (`"ChatViewModel"`); do NOT introduce a new tag.

**Verify:**
```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
```

**Commit:** `fix(chat): forkSession inherits source directory and merges into reducer`

---

## Batch 4: Integration + regression tests (parallel — 2 implementers)

Both tasks depend on Batch 3 having landed (so the production behaviour is testable end-to-end).

### Task 4.1: HTTP-level regression test for fork directory header

**File:** `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiForkTest.kt`
**Test:** self
**Depends:** 2.1 (API change), 1.2 (MockEngine on classpath)
**Domain:** general

Use Ktor `MockEngine` to assert the fork POST sends the encoded `x-opencode-directory` header when a directory is supplied, and omits it otherwise. Mirrors the pattern in Ktor's own client docs and stays inside the project's existing dependency footprint (no mockk, no Robolectric).

```kotlin
package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeApiForkTest {

    private val responseJson = """
        {
          "id": "ses_forked",
          "directory": "/home/user/projectA",
          "time": { "created": 1, "updated": 2, "archived": null }
        }
    """.trimIndent()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun newApi(captured: MutableList<HttpRequestData>): OpenCodeApi {
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = ByteReadChannel(responseJson),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return OpenCodeApi(client, json)
    }

    @Test
    fun `forkSession attaches encoded x-opencode-directory header when directory is supplied`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        val result: Session = api.forkSession(
            conn = conn,
            sessionId = "ses_source",
            directory = "/home/user/My Project",
        )

        assertEquals("ses_forked", result.id)
        assertEquals(1, captured.size)
        val request = captured.single()
        assertEquals("http://example.test:4096/session/ses_source/fork", request.url.toString())
        // android.net.Uri.encode replaces space with %20, not '+', and percent-encodes '/'
        // The exact expected value matches Uri.encode("/home/user/My Project") on Android.
        assertEquals(
            android.net.Uri.encode("/home/user/My Project"),
            request.headers["x-opencode-directory"],
        )
    }

    @Test
    fun `forkSession omits x-opencode-directory header when directory is null`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        api.forkSession(conn = conn, sessionId = "ses_source", directory = null)

        assertEquals(1, captured.size)
        assertNull(captured.single().headers["x-opencode-directory"])
    }

    @Test
    fun `forkSession defaults directory to null and sends no header`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        // Call without the directory parameter at all — exercises the default.
        api.forkSession(conn = conn, sessionId = "ses_source")

        assertEquals(1, captured.size)
        assertNull(captured.single().headers["x-opencode-directory"])
    }

    @Test
    fun `forkSession forwards messageID in body when supplied`() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val api = newApi(captured)
        val conn = ServerConnection.from("http://example.test:4096")

        api.forkSession(
            conn = conn,
            sessionId = "ses_source",
            messageId = "msg_123",
            directory = "/p",
        )

        val body = (captured.single().body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
            .bytes()
            .toString(Charsets.UTF_8)
        // body should contain messageID; we don't assert exact JSON shape because the
        // serializer may emit either {"messageID":"msg_123"} or with whitespace.
        assert(body.contains("\"messageID\"")) { "messageID missing from body: $body" }
        assert(body.contains("msg_123")) { "messageID value missing from body: $body" }
    }
}
```

Notes for the implementer:
- `android.net.Uri.encode` is available in unit tests because the project already depends on Android stubs at compile time; if at runtime the test fails with `Method encode not mocked`, the simplest fix is to wrap the assertion with a `try/catch` matching the existing `EventReducerTest` pattern (search `android.util.Log not mocked` in `EventReducerTest.kt:175-191`). Do NOT introduce Robolectric.
- If `Uri.encode` proves un-mockable in plain JVM tests, fall back to a substring assertion: `assert(request.headers["x-opencode-directory"]!!.contains("My%20Project"))`. Document the fallback inline.
- `ServerConnection.from(...)` with no password produces `authHeader = null`, so no Authorization header noise in the captured request.

**Verify:**
```sh
./gradlew :app:testDebugUnitTest --tests dev.minios.ocremote.data.api.OpenCodeApiForkTest
```
All 4 tests must pass.

**Commit:** `test(api): regression tests for forkSession directory header`

---

### Task 4.2: Verify `ChatScreen` fork callsites still route through `viewModel.forkSession`

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` — read-only verification, no edit expected
**Test:** none
**Depends:** 3.1
**Domain:** general

This task is a deliberate sanity check, not a code change. The design states both fork entry points (menu and slash command) must continue to share the same view model path. After Batch 3, both should be picking up the new behaviour automatically — the implementer verifies this with two `grep` commands and confirms no UI code touched.

**Verification commands:**

```sh
# 1. Both fork callsites still call viewModel.forkSession — exactly two matches expected.
grep -n "viewModel.forkSession" app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
# Expected output: two lines, around lines 1535 and 1842, identical signatures.

# 2. ChatScreen.kt was NOT modified in this batch.
git diff --stat HEAD~1 -- app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
# Expected output: empty (file unchanged) — the design's "Menu fork and slash-command
# fork continue sharing the same view model path" coverage target is met by sharing
# the call site, not by parallel test surfaces.
```

If either expectation fails, STOP and escalate — it means an upstream task accidentally edited `ChatScreen.kt` or the dual-callsite assumption broke and the design needs to be revisited.

**Verify:** the two grep commands above produce the documented output.

**Commit:** none — this task is a verification gate; if it passes, no commit is needed for it. If it fails, the offending earlier commit must be corrected, not patched here.

---

## End-to-end verification (post-merge smoke)

After all six tasks are merged, run:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Both must succeed. The unit test count should go up by exactly 11 (`ForkDirectoryResolverTest`: 7 + `OpenCodeApiForkTest`: 4) compared to the baseline immediately before Batch 1.

Manual smoke (optional, on a device with a configured OpenCode server):

1. Open a session in a non-root project (e.g. one whose `directory` is `/home/user/projectA`).
2. Tap menu → Fork (or type `/fork`).
3. Confirm the new session appears under the same `projectA` group in the session list, not under root.
4. Confirm the new session is reachable immediately (reducer merge), not only after pull-to-refresh.
