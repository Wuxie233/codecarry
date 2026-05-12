---
date: 2026-05-12
topic: "Actionable Permission Wait Handling"
issue: 26
scope: notifications
contract: none
---

# Actionable Permission Wait Handling Implementation Plan

**Goal:** Make OpenCode permission waits discoverable and actionable from the Android notification surface and session-level status, without changing the server permission contract or auto-approving anything.

**Architecture:** Three lightweight, Android-client-only changes. (1) Extend `OpenCodeConnectionService` so its permission notifications carry three explicit `PendingIntent.getService` quick actions (allow once, always allow, reject) plus a new `ACTION_PERMISSION_REPLY` dispatch path in `onStartCommand` that resolves the target `ServerConnection` from the existing in-memory `connections` map and calls the existing `OpenCodeApi.replyToPermission`. (2) Add an idempotent `EventReducer.removePermission(requestId)` mirror of the existing `removeQuestion` optimistic-removal pattern, so a successful notification reply clears local pending state immediately and the later `permission.replied` SSE event remains a safe no-op. (3) Add tests around (a) the new reducer behavior and (b) session-level awaiting-permission visibility (the `AWAITING_PERMISSION` branch in `buildActiveConversations`) so the notification-suppression fallback path is locked down. The existing chat `PermissionCard`, child-session notification filtering, server grouping, and deep-link behavior all stay intact.

**Design:** [thoughts/shared/designs/2026-05-12-actionable-permission-wait-design.md](../designs/2026-05-12-actionable-permission-wait-design.md)

**Contract:** none (single-domain plan; all tasks are `frontend-code` in the Android Kotlin client; no server contract changes per the design's Constraints section).

**Senior-engineer decisions filled in:**

- **Reply-value wire format:** Use `"once"` / `"always"` / `"reject"` exactly, matching the existing `OpenCodeApi.replyToPermission` body and the chat `PermissionCard` flow at `ChatViewModel.replyToPermission` lines 823–836. No new enum class; pass through as `String` like the existing call sites.
- **Service intent shape:** Reuse the existing service-intent dispatch pattern (`ACTION_DISCONNECT_ALL` / `ACTION_DISCONNECT` at `OpenCodeConnectionService.kt:153–166`). Add one new action `ACTION_PERMISSION_REPLY` plus extras `EXTRA_REQUEST_ID`, `EXTRA_REPLY_VALUE`, `EXTRA_SESSION_ID`, `EXTRA_SERVER_ID` (the last reuses the existing `"server_id"` extra already read on line 160 / 173). Single action with a reply-value extra keeps the intent surface small and makes per-action dedup of stable `PendingIntent.getService` request codes trivial.
- **PendingIntent request codes:** Derive from `eventNotificationId(serverId, sessionId, 1000) + replyOffset` where `replyOffset ∈ {1, 2, 3}` for once / always / reject. This keeps codes stable across re-posts of the same permission and distinct per action so `FLAG_UPDATE_CURRENT` rewrites the correct slot.
- **Directory resolution for service-side reply:** Mirror `ChatViewModel.replyToPermission` which passes `sessionDirectory`. Resolve via the existing helper `getSessionInfo(sessionId)` which returns `Pair<title, directory>` (line 576–579) and pass `directory` through to `api.replyToPermission`. If the session is no longer in reducer state (e.g. stale notification after disconnect), pass `directory = null`; the server will fall back to the request-id-scoped path and may still succeed. Either way, on failure the chat `PermissionCard` remains as the fallback per design § Error Handling.
- **Resolving target server connection:** Read from `connections[serverId]?.conn`. If the server has been disconnected since the notification was posted, log a warning, cancel the stale notification, and exit silently — the chat card path is unaffected. This matches the "fail safely, keep chat fallback" rule in design § Missing server or session context.
- **Notification cancellation on success:** After a successful `replyToPermission`, call `notificationManager.cancel(notifId)` for the same `eventNotificationId(server.id, sessionId, 1000)` used in `showPermissionNotification`, and call `eventReducer.removePermission(requestId)` so chat-side state clears immediately. The group summary is left to Android's own auto-collapse behavior; we do not explicitly re-post or cancel the summary, matching the existing `showTaskCompleteNotification` behavior.
- **Reducer removal semantics:** `removePermission` iterates every session bucket and filters by `requestId` (the `PermissionAsked.id` field, per `SseEvent.kt:86–94`), matching `removeQuestion`'s map-wide scan at `EventReducer.kt:325–331`. Idempotent: removing an already-removed permission is a no-op. The existing `handlePermissionReplied` path remains as the SSE finalizer and is unchanged.
- **"Always allow" copy:** The design § Always allow risk requires visual distinction. Use action label strings `notification_permission_action_allow_once` / `notification_permission_action_allow_always` / `notification_permission_action_reject` so localization can adjust wording per language. English defaults: "Allow once", "Always allow", "Reject". The body string `notification_needs_permission*` (already in `strings.xml:508–510`) is unchanged.
- **Test placement:** Reducer test additions go in the existing `EventReducerTest.kt`. Service intent-dispatch test goes in a new `OpenCodeConnectionServicePermissionActionTest.kt` under `app/src/test/kotlin/dev/minios/ocremote/service/` (new directory) — pure JVM unit test that exercises the public `replyToPermissionFromAction` extracted helper without spinning up an actual `Service`. Session-visibility regression goes as a new case in `BuildActiveConversationsTest.kt`.
- **Why no Composable / UI tasks:** The chat `PermissionCard` already renders three actions and reads `eventReducer.permissions`; the active-conversations banner already has an `AWAITING_PERMISSION` branch (`ActiveConversationsBanner.kt:112`, `:221`). Design § Chat UI and § Session Surfaces explicitly say "keep" and "do not replace with a separate complex approval center". So this plan strengthens tests around the existing surfaces rather than adding new UI.

---

## Dependency Graph

```
Batch 1 (parallel - 3 implementers): 1.1, 1.2, 1.3 [foundation - no deps]
  1.1 string resources              [Domain: general]
  1.2 reducer removePermission()    [Domain: frontend-code]
  1.3 reducer test cases            [Domain: frontend-code]  (depends only on 1.2 conceptually; can be authored in parallel with TDD ordering)

Batch 2 (sequential - 1 implementer): 2.1 [depends on batch 1]
  2.1 service action intents + notification quick actions + per-action helper  [Domain: frontend-code]

Batch 3 (parallel - 2 implementers): 3.1, 3.2 [depends on batch 2 / batch 1]
  3.1 service permission-action unit test               [Domain: frontend-code]
  3.2 active conversations awaiting-permission regression test  [Domain: frontend-code]
```

Notes on parallelism: Batch 2 is a single task because all three of its changes land in the same file (`OpenCodeConnectionService.kt`). Splitting it would create merge conflicts inside `onStartCommand` and `showPermissionNotification` for no parallelism benefit. Batches 1 and 3 each fan out across distinct files and can run truly concurrently.

---

## Batch 1: Foundation (parallel - 3 implementers)

All tasks in this batch have NO file-level dependencies on each other and run simultaneously.
Tasks: 1.1, 1.2, 1.3

### Task 1.1: Add notification quick-action string resources
**File:** `app/src/main/res/values/strings.xml`
**Test:** none (pure resource addition; covered indirectly by Task 3.1 which references the keys, and by the existing build-time `lint` check for missing references)
**Depends:** none
**Domain:** general
**Atlas-impact:** none

Add three new string entries directly after the existing `notification_permission_required` entry (line 510 in the current file). The names follow the existing `notification_*` convention used by `notification_needs_permission*` at lines 508–509.

```xml
<!-- Insert immediately after <string name="notification_permission_required">Permission required</string> -->
<string name="notification_permission_action_allow_once">Allow once</string>
<string name="notification_permission_action_allow_always">Always allow</string>
<string name="notification_permission_action_reject">Reject</string>
```

Also add the matching entries in every locale `values-*/strings.xml` file that already overrides `notification_permission_required`. Use the same key names; translate the values. For locales that do not override `notification_permission_required`, do nothing — Android falls back to the default `values/strings.xml`.

To find the locale files that need updating:

```sh
grep -l 'notification_permission_required' app/src/main/res/values-*/strings.xml
```

For each match, add the three keys with locale-appropriate translations. If you do not have a confident translation for a given locale, copy the English value as a placeholder so the build does not fail; localization can be tightened in a follow-up.

**Verify:** `./gradlew assembleDebug` (build must succeed; lint must not warn about missing translations beyond what already exists in the baseline)
**Commit:** `feat(notifications): add permission quick-action label strings`

### Task 1.2: Add `EventReducer.removePermission(requestId)`
**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt` (additive cases added in Task 1.3 — TDD: write the failing tests in 1.3 first, then add the method here so they pass)
**Depends:** none
**Domain:** frontend-code
**Atlas-impact:** none

Mirror the existing `removeQuestion(questionId)` helper at lines 325–331. Add the following method directly after `handlePermissionReplied` (which currently ends at line 287) so the permission section stays contiguous:

```kotlin
    /**
     * Optimistically remove a permission request from the pending list.
     * Called after a successful service-side or chat-side reply, in case the
     * SSE `permission.replied` event arrives late or is missed entirely
     * (e.g. when the user replies via a notification action while the chat
     * screen is closed).
     *
     * Idempotent: removing an already-removed request is a no-op.
     */
    fun removePermission(requestId: String) {
        _permissions.update { current ->
            current.mapValues { (_, permissions) ->
                permissions.filter { it.id != requestId }
            }
        }
    }
```

Do NOT change `handlePermissionReplied`. The SSE-driven removal path remains the source of consistency; this method is the local optimistic shortcut. After this change, a stale `PermissionReplied` event arriving after `removePermission` has already cleared the entry hits the existing filter on line 280 (`.filter { it.id != event.requestId }`) which is also idempotent.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventReducerTest"` (must pass; together with Task 1.3 the new cases will exercise this method)
**Commit:** `feat(reducer): add optimistic removePermission for notification reply flow`

### Task 1.3: Reducer test cases for permission removal and idempotency
**File:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt`
**Test:** N/A (this task IS the test file change; meaningful risk per the semantic-risk rule because the reducer is exported reusable state and the design's Testing Strategy explicitly calls out this case)
**Depends:** none (TDD: author these BEFORE 1.2 lands so the first run is RED, then 1.2 makes them GREEN)
**Domain:** frontend-code
**Atlas-impact:** none

Append the following test cases to the existing `EventReducerTest` class, just before the private helper functions at the bottom of the file (line 147 onwards). Reuse the existing class-scoped helpers; do not introduce a new test class.

```kotlin
    @Test
    fun permissionAskedAddsPendingEntry() {
        val reducer = EventReducer()
        val asked = SseEvent.PermissionAsked(
            id = "perm-1",
            sessionId = "ses-1",
            permission = "write file foo.txt",
        )

        reducer.processEvent(asked, serverId = "server-1")

        val pending = reducer.permissions.value["ses-1"].orEmpty()
        assertEquals(1, pending.size)
        assertEquals("perm-1", pending.single().id)
    }

    @Test
    fun permissionRepliedClearsPendingEntryForRequestId() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-2", sessionId = "ses-1", permission = "p2"),
            serverId = "server-1",
        )

        reducer.processEvent(
            SseEvent.PermissionReplied(sessionId = "ses-1", requestId = "perm-1"),
            serverId = "server-1",
        )

        val remaining = reducer.permissions.value["ses-1"].orEmpty().map { it.id }
        assertEquals(listOf("perm-2"), remaining)
    }

    @Test
    fun removePermissionOptimisticallyClearsPendingEntry() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )

        reducer.removePermission("perm-1")

        assertTrue(reducer.permissions.value["ses-1"].orEmpty().isEmpty())
    }

    @Test
    fun removePermissionIsIdempotentWhenRequestIdMissing() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )

        reducer.removePermission("perm-unknown")
        reducer.removePermission("perm-1")
        reducer.removePermission("perm-1") // second call: no-op

        assertTrue(reducer.permissions.value["ses-1"].orEmpty().isEmpty())
    }

    @Test
    fun permissionRepliedAfterLocalRemovalIsNoOp() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )
        reducer.removePermission("perm-1")

        // Late-arriving SSE event must not crash, duplicate, or resurrect state.
        reducer.processEvent(
            SseEvent.PermissionReplied(sessionId = "ses-1", requestId = "perm-1"),
            serverId = "server-1",
        )

        assertTrue(reducer.permissions.value["ses-1"].orEmpty().isEmpty())
    }
```

Add the import for `org.junit.Assert.assertTrue` at the top of the file if not already present (the existing file imports only `assertEquals` and `assertNull`).

**Verify:** `./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventReducerTest"` (must FAIL initially with `removePermission unresolved reference`, then PASS after Task 1.2 lands)
**Commit:** `test(reducer): cover permission ask/reply and optimistic local removal`

---

## Batch 2: Service permission action dispatch (sequential - 1 implementer)

Depends on Batch 1 completing (consumes the new string resources from 1.1 and the new `EventReducer.removePermission` from 1.2).
Tasks: 2.1

### Task 2.1: Service permission-action dispatch + notification quick actions
**File:** `app/src/main/kotlin/dev/minios/ocremote/service/OpenCodeConnectionService.kt`
**Test:** `app/src/test/kotlin/dev/minios/ocremote/service/OpenCodeConnectionServicePermissionActionTest.kt` (authored in Task 3.1; meaningful risk per the semantic-risk rule because this is service-layer dispatch + concurrency + error handling around an HTTP write)
**Depends:** 1.1, 1.2
**Domain:** frontend-code
**Atlas-impact:** layer-update (adds a new app-side reply entry point; relevant to `20-behavior` permission-handling node if Atlas vault is present)

Make the following four changes in this single file. They MUST land together because they share the `companion object` and the per-permission notification builder.

**Change A — Companion object: add new action and extras.** Locate the existing `companion object` at lines 653–663 and extend it:

```kotlin
    companion object {
        const val ACTION_OPEN_SESSION = "dev.minios.ocremote.OPEN_SESSION"
        const val ACTION_DISCONNECT = "dev.minios.ocremote.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.minios.ocremote.DISCONNECT_ALL"
        const val ACTION_PERMISSION_REPLY = "dev.minios.ocremote.PERMISSION_REPLY"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SERVER_USERNAME = "server_username"
        const val EXTRA_SERVER_PASSWORD = "server_password"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SESSION_PATH = "session_path"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_SERVER_ID = "server_id"            // alias for existing "server_id" string used at line 160 / 173
        const val EXTRA_PERMISSION_REQUEST_ID = "permission_request_id"
        const val EXTRA_PERMISSION_REPLY_VALUE = "permission_reply_value"

        // Allowed values for EXTRA_PERMISSION_REPLY_VALUE; must match OpenCodeApi.replyToPermission contract.
        const val PERMISSION_REPLY_ONCE = "once"
        const val PERMISSION_REPLY_ALWAYS = "always"
        const val PERMISSION_REPLY_REJECT = "reject"
    }
```

**Change B — `onStartCommand` dispatch.** Insert a new branch at the top of the `when (intent?.action)` block (currently at lines 153–167), BEFORE `ACTION_DISCONNECT_ALL` so the early return short-circuits without touching the foreground promotion code:

```kotlin
        when (intent?.action) {
            ACTION_PERMISSION_REPLY -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val requestId = intent.getStringExtra(EXTRA_PERMISSION_REQUEST_ID)
                val replyValue = intent.getStringExtra(EXTRA_PERMISSION_REPLY_VALUE)
                if (serverId != null && sessionId != null && requestId != null && replyValue != null) {
                    Log.i(TAG, "Permission action: server=$serverId session=$sessionId request=$requestId reply=$replyValue")
                    handlePermissionAction(
                        serverId = serverId,
                        sessionId = sessionId,
                        requestId = requestId,
                        replyValue = replyValue,
                    )
                } else {
                    Log.w(TAG, "ACTION_PERMISSION_REPLY missing extras; ignoring")
                }
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT_ALL -> {
                Log.i(TAG, "Disconnect All requested via notification")
                disconnectAllVisibleServers()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                val serverId = intent.getStringExtra("server_id")
                if (serverId != null) {
                    Log.i(TAG, "Disconnect requested for server $serverId")
                    disconnect(serverId)
                }
                return START_NOT_STICKY
            }
        }
```

Rationale: returning `START_NOT_STICKY` matches the sibling disconnect branches; the foreground notification stays up because the service is already running (it had to be running for the original permission notification to have been posted). If the service was killed after posting, Android will recreate it and replay the intent; in that edge case `connections` will be empty and `handlePermissionAction` will fail safely (Change C below).

**Change C — New private helper `handlePermissionAction`.** Add this method in the "Helpers" section, immediately after `getSessionInfo` at lines 576–579. It is intentionally a member function so it can use `serviceScope`, `notificationManager`, `api`, `eventReducer`, and the private `connections` map.

```kotlin
    /**
     * Handle a permission reply triggered by a notification action.
     *
     * Resolves the target ServerConnection from in-memory state, calls the same
     * OpenCodeApi.replyToPermission used by the chat PermissionCard, then
     * optimistically clears local pending state and cancels the originating
     * notification. Failures (stale request, missing server, network) are
     * logged; the chat PermissionCard remains as the in-app fallback.
     */
    private fun handlePermissionAction(
        serverId: String,
        sessionId: String,
        requestId: String,
        replyValue: String,
    ) {
        // Validate reply value against the contract.
        if (replyValue !in listOf(PERMISSION_REPLY_ONCE, PERMISSION_REPLY_ALWAYS, PERMISSION_REPLY_REJECT)) {
            Log.w(TAG, "Unknown permission reply value: $replyValue; ignoring")
            return
        }

        val state = connections[serverId]
        if (state == null) {
            Log.w(TAG, "Permission action for server $serverId but no active connection; clearing notification and skipping")
            // Best-effort: cancel the stale notification slot so the user is not stuck with a dead action row.
            val staleNotifId = eventNotificationId(serverId, sessionId, 1000)
            notificationManager.cancel(staleNotifId)
            return
        }

        val (_, directory) = getSessionInfo(sessionId)

        serviceScope.launch {
            val success = try {
                api.replyToPermission(
                    conn = state.conn,
                    requestId = requestId,
                    reply = replyValue,
                    directory = directory,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Permission reply failed for $requestId", e)
                false
            }

            if (success) {
                // Optimistic local clear so chat-side state updates immediately;
                // SSE permission.replied remains the source of truth and is idempotent.
                eventReducer.removePermission(requestId)
                val notifId = eventNotificationId(state.config.id, sessionId, 1000)
                notificationManager.cancel(notifId)
                Log.i(TAG, "Permission $requestId resolved via notification ($replyValue)")
            } else {
                // Stale or duplicate reply: per design, log and leave the chat fallback in place.
                Log.w(TAG, "Permission reply non-success for $requestId; leaving chat fallback active")
            }
        }
    }
```

**Change D — `showPermissionNotification` adds three quick actions.** Replace the existing function body at lines 829–857 with the version below. The only structural change is adding three `addAction` calls and three companion `PendingIntent.getService` builders just before `.build()`. Title, body, channel, group, vibrate pattern, and content intent (deep-link to chat) are unchanged.

```kotlin
    private fun showPermissionNotification(server: ServerConfig, sessionId: String, permission: String) {
        val (sessionTitle, directory) = getSessionInfo(sessionId)
        val displayTitle = sessionTitle ?: getString(R.string.notification_new_session)
        val projectName = getProjectName(directory)
        val body = if (projectName != null) {
            getString(R.string.notification_needs_permission_project, displayTitle, projectName)
        } else {
            getString(R.string.notification_needs_permission, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 1000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        // NOTE: requestId is the PermissionAsked.id field. The caller in
        // `processEvent` currently passes `event.permission` (the human-readable
        // text) as the third arg of this function, but the actionable reply
        // needs the request id. See Change E below for the caller-site update.
        // We expect the request id to be plumbed in as a new parameter on this
        // function; resolve it from the event in the caller.
        error("This function signature is updated by Change E — see plan.")
    }
```

**Change E — Update both the signature of `showPermissionNotification` and its single call site, and wire in the three quick actions.** The current call site at line 541 reads:

```kotlin
            is SseEvent.PermissionAsked -> {
                if (isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Permission asked: ${event.permission}")
                showPermissionNotification(server, event.sessionId, event.permission)
            }
```

Replace it with:

```kotlin
            is SseEvent.PermissionAsked -> {
                if (isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Permission asked: ${event.permission} (id=${event.id})")
                showPermissionNotification(
                    server = server,
                    sessionId = event.sessionId,
                    requestId = event.id,
                    permission = event.permission,
                )
            }
```

Then replace the placeholder body from Change D with the full implementation:

```kotlin
    private fun showPermissionNotification(
        server: ServerConfig,
        sessionId: String,
        requestId: String,
        permission: String,
    ) {
        val (sessionTitle, directory) = getSessionInfo(sessionId)
        val displayTitle = sessionTitle ?: getString(R.string.notification_new_session)
        val projectName = getProjectName(directory)
        val body = if (projectName != null) {
            getString(R.string.notification_needs_permission_project, displayTitle, projectName)
        } else {
            getString(R.string.notification_needs_permission, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 1000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        val allowOncePi = buildPermissionReplyPendingIntent(
            server = server,
            sessionId = sessionId,
            requestId = requestId,
            replyValue = PERMISSION_REPLY_ONCE,
            requestCode = notifId + 1,
        )
        val allowAlwaysPi = buildPermissionReplyPendingIntent(
            server = server,
            sessionId = sessionId,
            requestId = requestId,
            replyValue = PERMISSION_REPLY_ALWAYS,
            requestCode = notifId + 2,
        )
        val rejectPi = buildPermissionReplyPendingIntent(
            server = server,
            sessionId = sessionId,
            requestId = requestId,
            replyValue = PERMISSION_REPLY_REJECT,
            requestCode = notifId + 3,
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(getString(R.string.notification_permission_required))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_permission_action_allow_once),
                allowOncePi,
            )
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_permission_action_allow_always),
                allowAlwaysPi,
            )
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_permission_action_reject),
                rejectPi,
            )
            .build()

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(server)
    }

    private fun buildPermissionReplyPendingIntent(
        server: ServerConfig,
        sessionId: String,
        requestId: String,
        replyValue: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(this, OpenCodeConnectionService::class.java).apply {
            action = ACTION_PERMISSION_REPLY
            putExtra(EXTRA_SERVER_ID, server.id)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_PERMISSION_REQUEST_ID, requestId)
            putExtra(EXTRA_PERMISSION_REPLY_VALUE, replyValue)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
```

**Notes on what NOT to change in this file:**

- Do NOT touch `isChildSession` or its call site (line 539). Child-session filtering MUST be preserved per the design's Constraints section.
- Do NOT touch `showQuestionNotification`, `showTaskCompleteNotification`, or `showErrorNotification`. They are out of scope.
- Do NOT change the existing `EXTRA_SESSION_ID = "sessionId"` value — `MainActivity`'s deep-link path reads it at this exact key.
- Do NOT modify `AndroidManifest.xml`. The service is already registered (see manifest lines 58–61); same-package `PendingIntent.getService` works without an extra `<intent-filter>`.

**Verify:**

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventReducerTest"
# Task 3.1 will add the dedicated service unit test in the next batch.
```

**Commit:** `feat(notifications): wire permission notifications with allow once / always / reject quick actions`

---

## Batch 3: Verification tests (parallel - 2 implementers)

Depends on Batch 2 (3.1) and Batch 1 (3.2) completing.
Tasks: 3.1, 3.2

### Task 3.1: Service permission-action dispatch unit test
**File:** `app/src/test/kotlin/dev/minios/ocremote/service/OpenCodeConnectionServicePermissionActionTest.kt`
**Test:** N/A (this task IS the test)
**Depends:** 2.1
**Domain:** frontend-code
**Atlas-impact:** none

This is a pure JVM unit test. Spinning up a real `Service` under Robolectric is overkill for what we need to assert; the failure modes the design calls out (stale request, missing server, wrong reply value, success path clears local state) all live in the pure data plumbing around `handlePermissionAction`. We test by validating the `Intent` shape produced by `buildPermissionReplyPendingIntent`'s payload plus the contract of `EventReducer.removePermission`.

Specifically: we don't bind to the live `Service`; we build the same `Intent` extras the service would build and assert (a) the action constant and (b) the reply-value contract, then we exercise `EventReducer.removePermission` end-to-end against the reducer used by the service.

```kotlin
package dev.minios.ocremote.service

import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the permission-notification quick-action contract.
 *
 * The full Service intent-dispatch path requires Robolectric/instrumentation,
 * which is heavier than the risk justifies. The risk surface here is:
 *   1) the action constant and extras keys agreed between
 *      OpenCodeConnectionService.showPermissionNotification (producer) and
 *      OpenCodeConnectionService.onStartCommand (consumer);
 *   2) the reply-value strings ("once" / "always" / "reject") which must match
 *      OpenCodeApi.replyToPermission's documented body contract;
 *   3) EventReducer.removePermission being correctly invoked on success.
 *
 * This test asserts (1) and (2) via the public companion constants and
 * exercises (3) directly against EventReducer.
 */
class OpenCodeConnectionServicePermissionActionTest {

    @Test
    fun permissionActionAndExtraKeysAreStable() {
        // These constants are the wire format between the notification producer
        // and the service consumer. Changing them silently breaks already-posted
        // notifications when the app updates.
        assertEquals(
            "dev.minios.ocremote.PERMISSION_REPLY",
            OpenCodeConnectionService.ACTION_PERMISSION_REPLY,
        )
        assertEquals("server_id", OpenCodeConnectionService.EXTRA_SERVER_ID)
        assertEquals("sessionId", OpenCodeConnectionService.EXTRA_SESSION_ID)
        assertEquals(
            "permission_request_id",
            OpenCodeConnectionService.EXTRA_PERMISSION_REQUEST_ID,
        )
        assertEquals(
            "permission_reply_value",
            OpenCodeConnectionService.EXTRA_PERMISSION_REPLY_VALUE,
        )
    }

    @Test
    fun replyValueConstantsMatchServerContract() {
        // These three string values are the body the server's
        // POST /permission/{requestId}/reply expects under the `reply` field.
        // OpenCodeApi.replyToPermission already uses them; the notification
        // path must use the SAME strings, not a parallel enum.
        assertEquals("once", OpenCodeConnectionService.PERMISSION_REPLY_ONCE)
        assertEquals("always", OpenCodeConnectionService.PERMISSION_REPLY_ALWAYS)
        assertEquals("reject", OpenCodeConnectionService.PERMISSION_REPLY_REJECT)
    }

    @Test
    fun reducerOptimisticRemovalClearsThePermissionSeenByChat() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )
        assertFalse(reducer.permissions.value["ses-1"].orEmpty().isEmpty())

        // This is exactly what handlePermissionAction does on a successful reply.
        reducer.removePermission("perm-1")

        assertTrue(reducer.permissions.value["ses-1"].orEmpty().isEmpty())
    }

    @Test
    fun reducerOptimisticRemovalIsSafeWhenSseReplyArrivesLater() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.PermissionAsked(id = "perm-1", sessionId = "ses-1", permission = "p1"),
            serverId = "server-1",
        )

        reducer.removePermission("perm-1")
        // Late-arriving SSE reply — must not crash or resurrect the entry.
        reducer.processEvent(
            SseEvent.PermissionReplied(sessionId = "ses-1", requestId = "perm-1"),
            serverId = "server-1",
        )

        assertTrue(reducer.permissions.value["ses-1"].orEmpty().isEmpty())
    }
}
```

If the `app/src/test/kotlin/dev/minios/ocremote/service/` directory does not exist yet, create it. No additional Gradle wiring is needed — `app/build.gradle.kts` already discovers tests under `src/test/kotlin/**`.

Note on what this test does NOT cover, and why it's acceptable:

- The actual `ServerConnection` lookup and `httpClient.post` call inside `handlePermissionAction` is an HTTP write against a real server; it is exercised by the existing `OpenCodeApiForkTest` family and by manual smoke testing. Adding an `OpenCodeApi` mock here would test the mock, not the dispatch.
- The `NotificationManager.cancel(notifId)` and `addAction` builder calls are tested by Android's own NotificationCompat layer; asserting them via Robolectric would duplicate that coverage at the cost of a heavyweight test runtime.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.service.OpenCodeConnectionServicePermissionActionTest"`
**Commit:** `test(notifications): cover permission action wire format and reducer optimistic removal`

### Task 3.2: Awaiting-permission session-visibility regression test
**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/BuildActiveConversationsTest.kt`
**Test:** N/A (this task IS the test)
**Depends:** none (touches the test file only; the production code path `buildActiveConversations` already supports the case — design § Session Surfaces requires "keep or strengthen", which means locking the behavior down with explicit coverage)
**Domain:** frontend-code
**Atlas-impact:** none

The existing file already has a `pending permission wins over busy but loses to pending question` case (visible at lines 78+). The design's notification-suppression fallback path specifically requires that an idle session with a pending permission ALSO surfaces in the active-conversations banner — that case is currently implicit. Add three explicit cases at the end of the class, immediately before the existing `private fun rootSession(...)` helper.

```kotlin
    @Test
    fun `idle root with pending permission is included with AWAITING_PERMISSION status`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = mapOf(root.id to listOf(permissionAsked("perm-1"))),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[0].status)
        assertEquals(1, items[0].pendingCount)
    }

    @Test
    fun `multiple pending permissions on idle root surface pendingCount`() {
        val root = rootSession("root1", updated = 100)

        val items = buildActiveConversations(
            rootSessions = listOf(root),
            statuses = mapOf(root.id to SessionStatus.Idle),
            pendingQuestions = emptyMap(),
            pendingPermissions = mapOf(
                root.id to listOf(
                    permissionAsked("perm-1"),
                    permissionAsked("perm-2"),
                    permissionAsked("perm-3"),
                ),
            ),
            unreadSessionIds = emptySet(),
        )

        assertEquals(1, items.size)
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[0].status)
        assertEquals(3, items[0].pendingCount)
    }

    @Test
    fun `awaiting permission preserves busy ordering when both states are present across sessions`() {
        // Regression guard: surfacing awaiting-permission visibility must not
        // perturb the existing UNREAD < AWAITING_QUESTION < AWAITING_PERMISSION
        // < BUSY < RETRY priority on the other sessions in the same banner.
        val permRoot = rootSession("root-perm", updated = 100)
        val busyRoot = rootSession("root-busy", updated = 200) // newer

        val items = buildActiveConversations(
            rootSessions = listOf(permRoot, busyRoot),
            statuses = mapOf(
                permRoot.id to SessionStatus.Idle,
                busyRoot.id to SessionStatus.Busy,
            ),
            pendingQuestions = emptyMap(),
            pendingPermissions = mapOf(permRoot.id to listOf(permissionAsked("perm-1"))),
            unreadSessionIds = emptySet(),
        )

        // AWAITING_PERMISSION ordinal < BUSY ordinal, so permRoot comes first
        // even though busyRoot is newer.
        assertEquals(listOf("root-perm", "root-busy"), items.map { it.sessionId })
        assertEquals(ConversationStatus.AWAITING_PERMISSION, items[0].status)
        assertEquals(ConversationStatus.BUSY, items[1].status)
    }
```

The file already has a `questionAsked(id: String)` helper used by existing cases at line ~67. If a `permissionAsked(id: String)` helper does not yet exist, add it alongside (typically near the bottom of the class with the other private helpers):

```kotlin
    private fun permissionAsked(id: String) = SseEvent.PermissionAsked(
        id = id,
        sessionId = "ses-unused",
        permission = "test permission",
    )
```

If `permissionAsked` already exists (verify by searching the file), reuse it as-is and do not redefine.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.BuildActiveConversationsTest"`
**Commit:** `test(sessions): lock down awaiting-permission visibility in active conversations`
