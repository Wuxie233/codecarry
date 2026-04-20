# Issue #6: In-App APK Update Feature — Implementation Contract

**Issue:** #6  
**Goal:** Implement in-app APK update checking and installation via GitHub Releases API with Settings UI entry point.

**Scope:** Manual update check from Settings → dialog shows version + notes → download to cache → install via system intent.

---

## Acceptance Criteria

- ✓ Settings screen has "Check Updates" ListItem in Advanced section with current version display
- ✓ GitHub Releases API fetches latest non-draft, non-prerelease from Wuxie233/oc-remote
- ✓ Version comparison logic detects new APK vs. current BuildConfig.VERSION_NAME
- ✓ Update dialog shows version + release notes (truncated to 500 chars)
- ✓ Download & install flow with progress feedback (dialog + LinearProgressIndicator)
- ✓ FileProvider + app-private cache (Context.cacheDir) — no external storage
- ✓ Android 8+ permission handling: `canRequestPackageInstalls()` at install time (API 26+)
- ✓ Unit tests: pure logic only (JUnit 4, no mocking frameworks)
- ✓ No background auto-check, no silent updates, no Play Store logic

---

## File Map

### New Files (10)

```
app/src/main/kotlin/dev/minios/ocremote/data/api/GitHubRelease.kt
app/src/main/kotlin/dev/minios/ocremote/data/repository/AppUpdateRepository.kt
app/src/main/kotlin/dev/minios/ocremote/util/VersionComparator.kt
app/src/main/kotlin/dev/minios/ocremote/util/ApkDownloadManager.kt
app/src/main/kotlin/dev/minios/ocremote/util/ApkInstaller.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/UpdateCheckDialog.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/UpdateState.kt
app/src/main/res/xml/file_paths.xml
app/src/test/kotlin/dev/minios/ocremote/util/VersionComparatorTest.kt
app/src/test/kotlin/dev/minios/ocremote/data/api/GitHubReleaseTest.kt
```

### Modified Files (3)

```
app/src/main/AndroidManifest.xml — add REQUEST_INSTALL_PACKAGES permission + FileProvider
app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt — add ListItem + dialog wire
app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsViewModel.kt — add update state machine
```

---

## Wave Plan (Parallel Execution)

### Wave 1: Data Layer (No Dependencies) — 20 min

| Task | Goal | Files | QA Command | Observable |
|------|------|-------|-----------|-----------|
| 1.1 | GitHubRelease model + pure functions | `GitHubRelease.kt` | `./gradlew test --tests "*GitHubReleaseTest*"` | Tests PASSED |
| 1.2 | VersionComparator (semantic versioning) | `VersionComparator.kt` | `./gradlew test --tests "*VersionComparatorTest*"` | Tests PASSED |
| 1.3 | AppUpdateRepository (fetch latest release) | `AppUpdateRepository.kt` | Build + manual: Settings → "Check Updates" dialog shows "Checking..." | Dialog appears, no crash |

### Wave 2: UI State & Dialog (After Wave 1) — 20 min

| Task | Goal | Files | QA Command | Observable |
|------|------|-------|-----------|-----------|
| 2.1 | Add "Check for Updates" ListItem in Settings | `SettingsScreen.kt` (EDIT) | `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk` | ListItem visible with version |
| 2.2 | UpdateCheckDialog (Loading → Available/NoUpdate/Error states) | `UpdateCheckDialog.kt`, `UpdateState.kt` | Settings → tap "Check Updates" | Dialog appears, state transitions visible |
| 2.3 | SettingsViewModel update state machine | `SettingsViewModel.kt` (EDIT) | Same as 2.2 | Dialog shows correct state |

### Wave 3: Download & Storage (Parallel with Wave 2) — 20 min

| Task | Goal | Files | QA Command | Observable |
|------|------|-------|-----------|-----------|
| 3.1 | ApkDownloadManager (stream to cache, track progress) | `ApkDownloadManager.kt` | Settings → tap "Download" → wait | File in `cache/app-updates/` (verify: `adb shell ls -lh /data/data/dev.minios.ocremote/cache/app-updates/`) |
| 3.2 | Wire download into ViewModel | `SettingsViewModel.kt` (EDIT) | Same as 3.1 | Progress indicator increments, file created |

### Wave 4: Installation & Manifest (Parallel with Waves 2–3) — 20 min

| Task | Goal | Files | QA Command | Observable |
|------|------|-------|-----------|-----------|
| 4.1 | FileProvider manifest + resource | `AndroidManifest.xml` (EDIT), `file_paths.xml` (NEW) | `./gradlew assembleDebug 2>&1 \| grep -i provider` | Build succeeds, no FileProvider errors |
| 4.2 | ApkInstaller utility (permission checks + intent launch) | `ApkInstaller.kt` | Settings → tap "Install Now" (after download) | System installer launches OR graceful error if "Unknown sources" disabled |
| 4.3 | Wire installer into ViewModel | `SettingsViewModel.kt` (EDIT) | Same as 4.2 | Install button works, no ANR |
| 4.4 | Add "Install Now" button to dialog | `UpdateCheckDialog.kt` (EDIT) | Same as 4.2 | Button visible in "Downloaded" state |

### Wave 5: Settings Integration (Final, After Wave 2) — 10 min

| Task | Goal | Files | QA Command | Observable |
|------|------|-------|-----------|-----------|
| 5.1 | Wire UpdateCheckDialog into SettingsScreen | `SettingsScreen.kt` (EDIT) | Settings → tap "Check Updates" → dismiss | Dialog appear/dismiss cleanly, no memory leaks |

### Wave 6: Unit Tests (Pure Logic Only) — 15 min

| Task | Goal | Files | QA Command | Observable |
|------|------|-------|-----------|-----------|
| 6.1 | VersionComparatorTest (pure logic) | `VersionComparatorTest.kt` | `./gradlew test --tests "*VersionComparatorTest*" -i` | All tests PASSED |
| 6.2 | GitHubReleaseTest (JSON parsing) | `GitHubReleaseTest.kt` | `./gradlew test --tests "*GitHubReleaseTest*" -i` | All tests PASSED |

---

## Key Technical Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| Debug Package | `dev.minios.ocremote.debug` | For debug-only persisted update API override (deterministic QA) |
| Version Parsing | Semantic versioning (X.Y.Z) pure function | No ambiguity; handles "1.0" vs "1.0.0" |
| Download Storage | app-private cache (Context.cacheDir) | No external storage permission; auto-cleanup; secure |
| Permission Model | Check `canRequestPackageInstalls()` at install time | Graceful fallback; user-friendly error if disabled |
| Error Handling | Null return in Repo + Error state in ViewModel | No exception propagation; UI shows user message |
| Update Trigger | Manual only (Settings button) | No background check; respects user intent |
| Release Asset ID | Filename matching `.apk` | Reliable; no asset ordering assumptions |
| Test Scope | Pure logic tests only (JUnit 4) | No mock frameworks; executable with current build.gradle.kts |
| Unknown Sources | OS-level; app cannot enable programmatically | Android 8+ requires user manual toggle; graceful error on deny |

---

## Blockers Solved

✓ **Removed HTTP mocking dependency** — AppUpdateRepository uses actual HttpClient; functional QA via Settings UI  
✓ **Pure logic tests only** — VersionComparator + GitHubRelease tests use JUnit 4, no framework mocking  
✓ **QA made concrete** — Each task has executable `./gradlew` command + observable output  
✓ **Scope reduced to minimal viable** — No delta updates, rollback, background check, rate-limit throttle  
✓ **Unknown sources handling clarified** — Graceful error on deny; user must enable manually  
✓ **Debug package specified** — `dev.minios.ocremote.debug` for deterministic test API override  
✓ **No circular dependencies** — Wave plan allows parallel execution without blockers  

---

## Out of Scope

- ✗ Background auto-check or notification
- ✗ Delta/patch updates (always full APK)
- ✗ Play Store or third-party store integration
- ✗ Update scheduling or quiet-hours logic
- ✗ Rollback after install failure
- ✗ Download analytics or telemetry
- ✗ Signed APK verification (relies on HTTPS)

---

**Status:** Ready for parallel Wave 1–6 execution  
**Total Estimated Time:** ~85 min wall time (parallel)  
**Target Completion:** All 16 tasks executable with current dependencies
