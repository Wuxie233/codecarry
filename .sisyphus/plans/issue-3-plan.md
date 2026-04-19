# Issue #3: Upstream PR Sync Task Graph

## Overview

Sync high-value bugfixes from `crim50n/oc-remote` upstream into `Wuxie233/oc-remote` fork.

**Decision Matrix:**
- PR #5, #6, #3, #2: **Cherry-pick viable** (clean, no conflicts, isolated changes)
- PR #7: **Manual porting required** (delete/modify conflict on EventReducerTest.kt; gated on P1-P4 completion)
- PR #1, #8: **Out of scope** (documentation & unrelated)

---

## Wave 1: Chat Screen Fixes (P1 + P4)

Focus: Fix markdown highlighting and patch card visibility issues in ChatViewModel.

### Task 1.1: Cherry-pick PR #5 - Fix markdown highlighting crash

**Objective:** Apply upstream commit `4446e97` to guard against reversed span ranges in markdown highlighting.

**Changes:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` (+6 lines)
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/SafeMarkdownHighlighting.kt` (new, +155 lines)
- `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/SafeMarkdownHighlightingTest.kt` (new, +23 lines)

**Cherry-pick command:**
```bash
git cherry-pick -x 4446e973d33698f85a34ebf7e3af7fc8bd7b416c
```

**QA:**
```bash
./gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.SafeMarkdownHighlightingTest"
./gradlew assembleDebug
# Smoke: Open session with fenced code blocks; verify no crash
```

**Expected outcome:** Test passes, app builds, crash on specific markdown blocks is fixed.

**Category:** `fix/chat-markdown` | **Skills:** `verification-before-completion`

---

### Task 1.2: Cherry-pick PR #2 - Hide duplicate patch cards

**Objective:** Apply upstream commit `94fd7bf` to collapse consecutive duplicate patch hashes.

**Changes:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` (+4 lines)
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/PatchVisibilityResolver.kt` (new, +33 lines)
- `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/PatchVisibilityResolverTest.kt` (new, +128 lines)

**Cherry-pick command:**
```bash
git cherry-pick -x 94fd7bf62f83a4288457f4469c341f9c36bd0507
```

**QA:**
```bash
./gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.PatchVisibilityResolverTest"
./gradlew assembleDebug
# Smoke: Chat with unchanged session diffs; verify patch cards collapse
```

**Expected outcome:** Test passes, patch visibility logic correctly deduplicates consecutive identical hashes.

**Category:** `fix/chat-patch-visibility` | **Skills:** `verification-before-completion`

---

## Wave 2: Home & Chat Screen Fixes (P2 + P3)

Focus: Keep UI elements visible during loading states.

### Task 2.1: Cherry-pick PR #6 - Keep server settings visible for custom providers

**Objective:** Apply upstream commit `e6933ab` to ensure server settings remain accessible.

**Changes:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeViewModel.kt` (+33 lines)
- `app/src/test/kotlin/dev/minios/ocremote/ui/screens/home/HomeViewModelTest.kt` (+89 lines)

**Cherry-pick command:**
```bash
git cherry-pick -x e6933ab6e18803d24f4203fae46ed5cb9ec150d8
```

**QA:**
```bash
./gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.home.HomeViewModelTest"
./gradlew assembleDebug
# Smoke: MCP test with custom provider (no published models); verify server settings visible
```

**Expected outcome:** Test passes, home screen shows server settings even for custom providers without model lists.

**Category:** `fix/home-settings-visibility` | **Skills:** `verification-before-completion`

---

### Task 2.2: Cherry-pick PR #3 - Keep new user messages visible during initial load

**Objective:** Apply upstream commit `7bae762` to prevent hiding SSE-delivered messages during REST snapshot load.

**Changes:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` (+5 lines, -2 lines)

**Cherry-pick command:**
```bash
git cherry-pick -x 7bae76214d4af90f6e2ef08c199cf9dc79d74673
```

**QA:**
```bash
# No tests added; verify the logic change (3 lines diff)
./gradlew assembleDebug
# Smoke: Send message while history is loading; verify message appears immediately
```

**Expected outcome:** App builds, new user messages remain visible during initial history load.

**Category:** `fix/chat-message-visibility` | **Skills:** `verification-before-completion`

---

## Wave 3: EventReducer Gate (P5 - Conditional)

### Task 3.0: Verification Gate - All P1-P4 PRs green

**Objective:** Ensure all upstream bugfixes integrate cleanly before attempting manual port of PR #7.

**Gate conditions:**
1. ✓ PR #5 cherry-picked, tests pass, no regressions
2. ✓ PR #2 cherry-picked, tests pass, no regressions
3. ✓ PR #6 cherry-picked, tests pass, no regressions
4. ✓ PR #3 cherry-picked, builds, smoke passes
5. ✓ Full test suite passes: `./gradlew testDebugUnitTest`
6. ✓ No integration conflicts in ChatViewModel or HomeViewModel

**Verification commands:**
```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
# Manual smoke test on emulator/device
```

**Decision point:** If all green, proceed to Task 3.1. If any regression, escalate and investigate root cause.

---

### Task 3.1: Manual port PR #7 - Fix session list status preservation

**Objective:** Port upstream commit `7ccf299` (and `3e35d7d` if needed) to fix EventReducer session lifecycle.

**Note:** Cherry-pick will fail due to deleted EventReducerTest.kt in our fork.

**Root cause:** Our fork has aggressive test cleanup or structural refactoring that removed the test file.

**Manual porting steps:**
1. Review upstream diff:
   ```bash
   git diff upstream/master...upstream/pr/7 -- app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt
   ```
2. Apply logic changes to our EventReducer.kt (likely small, ~14 lines touched per git show)
3. **Decide on test restoration:**
   - Option A: Restore EventReducerTest.kt from upstream and adapt to our test structure
   - Option B: Add equivalent tests as inline comments if tests were intentionally removed
   - Option C: Skip test porting if EventReducer is covered by integration tests
4. Manual verification:
   ```bash
   ./gradlew testDebugUnitTest --tests "*EventReducer*"
   # If tests don't exist, rely on session-list integration tests
   ```

**Changes expected:**
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt` (~14 lines)
- `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt` (restore or skip)

**QA:**
```bash
./gradlew assembleDebug
# Integration smoke: Create new session → verify status updates correctly → disconnect/reconnect
```

**Expected outcome:** Session status-first and idle-first lifecycle edge cases are fixed; no stale statuses after disconnect.

**Category:** `fix/session-lifecycle-manual-port` | **Skills:** `systematic-debugging`, `verification-before-completion`

---

## Parallel Execution Strategy

### Execution Model: Nested Waves + Local Sequencing

**Wave 1 (ChatViewModel fixes):**
- Task 1.1 and 1.2 can run in **parallel** (different files, same test suite category)
- Both must complete before Wave 2 to avoid conflicts in ChatViewModel

**Wave 2 (Home + Chat visibility):**
- Task 2.1 and 2.2 can run in **parallel** (separate view models, disjoint changes)
- Both should complete before Wave 3 gate

**Wave 3 (EventReducer):**
- Task 3.0 is a **verification gate**, not parallel (depends on Wave 1 + Wave 2)
- Task 3.1 is **gated on Task 3.0 passing**; only proceeds if gate is green

### Branching structure:
```
chore/issue3-upstream-pr-sync (base)
├─ 1.1: PR#5 cherry-pick → PR/fix-markdown-crash
├─ 1.2: PR#2 cherry-pick → PR/fix-patch-visibility
├─ (Wave 1 merge back to base)
├─ 2.1: PR#6 cherry-pick → PR/fix-home-settings
├─ 2.2: PR#3 cherry-pick → PR/fix-message-visibility
├─ (Wave 2 merge back to base)
└─ (Gate: full test suite)
   └─ 3.1: Manual port PR#7 → PR/fix-session-lifecycle
```

---

## Build & Test Commands Reference

### Unit Tests (per module):
```bash
# Chat screen fixes
./gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.*"

# Home screen fixes
./gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.home.*"

# EventReducer (for PR #7 gate)
./gradlew testDebugUnitTest --tests "dev.minios.ocremote.data.repository.*"

# Full suite
./gradlew testDebugUnitTest
```

### Build:
```bash
./gradlew assembleDebug
```

### Integration smoke (emulator):
```bash
./gradlew installDebugAndroidTest
adb shell am instrument -w dev.minios.ocremote.test/androidx.test.runner.AndroidJUnitRunner
```

---

## Success Criteria

### Wave 1: ✓ Both PRs applied, tests pass
- `SafeMarkdownHighlightingTest` passes
- `PatchVisibilityResolverTest` passes
- No regressions in ChatViewModel

### Wave 2: ✓ Both PRs applied, tests pass
- `HomeViewModelTest` passes
- PR #3 builds and smoke test passes
- No regressions in home/chat interaction

### Wave 3 Gate: ✓ Full suite green
- `./gradlew testDebugUnitTest` exits with code 0
- `./gradlew assembleDebug` succeeds
- No conflicting changes in downstream branches

### Wave 3.1 (if gate passes): ✓ EventReducer ported
- EventReducer logic matches upstream logic
- Tests restored or documented as intentionally omitted
- Session lifecycle integration smoke passes

---

## Out of Scope

- **PR #1** (documentation): Skip; user will handle separately
- **PR #8**: Not listed in requirements; out of scope
- **Test framework changes**: Only cherry-pick test content, don't refactor test structure
- **Unrelated refactoring**: Don't touch neighboring code beyond PR scope

---

## Notes for Implementation

1. **EventReducerTest.kt deletion:** Investigate why it was deleted in fork (check commit history or CLAUDE.md)
2. **Conflict resolution:** PR #7 conflict is a **delete/modify** on test file; requires manual review of whether test was intentional removal or oversight
3. **Cherry-pick -x flag:** Automatically adds `(cherry picked from commit ...)` footer for traceability
4. **Staging strategy:** Use feature branches per Wave to allow parallel work and easy rollback
5. **Emulator testing:** Requires Android emulator or physical device; smoke tests verify real behavior beyond unit tests

---

## Commit Messages

### Wave 1 commits:
```
fix(chat): guard markdown highlighting against reversed span ranges

Cherry-picked from upstream PR#5 (4446e97)
```

```
fix(chat): hide duplicate patch cards for unchanged session diffs

Cherry-picked from upstream PR#2 (94fd7bf)
```

### Wave 2 commits:
```
fix(home): keep server settings available for custom providers

Cherry-picked from upstream PR#6 (e6933ab)
```

```
fix(chat): keep new user messages visible during initial load

Cherry-picked from upstream PR#3 (7bae762)
```

### Wave 3 commits:
```
fix(session-list): preserve busy status for new sessions [manual port]

Ported from upstream PR#7 (7ccf299); cherry-pick had conflict on deleted
EventReducerTest.kt in fork. Applied logic changes to EventReducer.kt and
restored test coverage to match upstream behavior.
```

---

**Plan created:** 2026-03-13  
**Target branch:** `chore/issue3-upstream-pr-sync`  
**Session:** OpenCode planning session
