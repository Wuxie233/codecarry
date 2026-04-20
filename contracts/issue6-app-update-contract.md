# Issue #6: In-App APK Update Contract

**Date:** 2025-04-20  
**Issue:** #6 — Add manual check-for-update entry in Settings  
**Scope:** UI/state layer contract for in-app APK update feature  
**Status:** Under Development

---

## 1. Overview

This contract defines the state flow, UI synchronization, and system boundaries for in-app APK updates. The feature adds:
- Manual "Check for Updates" button in Settings
- GitHub latest release fetch (override-able for debug builds)
- APK download to app-private cache
- Handoff to system installer (with unknown-sources permission handling)

**Key Constraint:** This is a *state/UI contract*, not a network API spec. It defines how `SettingsViewModel` exposes state and methods to Compose/UI, plus integration points with `ApkInstaller` utility.

---

## 2. State Interface: `SettingsViewModel`

### 2.1 ViewModel State Fields (StateFlow)

All StateFlow fields follow the existing SettingsViewModel pattern: scope-based stateIn with WhileSubscribed(5000).

#### `debugUpdateApiUrl: StateFlow<String>`
- **Type:** `StateFlow<String>`
- **Initial Value:** `""` (empty string)
- **Persistence:** Backed by SettingsRepository (DataStore)
- **Visibility:** Debug builds only; ignored in release builds
- **Semantics:** Overrides GitHub API URL for release fetch if non-empty AND build is debug
- **Exposed to UI:** Settings screen shows this field only when `BuildConfig.DEBUG == true`
- **Storage Key:** (SettingsRepository defines; suggested `"debug_update_api_url"`)

#### `appUpdateUiState: StateFlow<AppUpdateUiState>`
- **Type:** `StateFlow<AppUpdateUiState>`
- **Initial Value:** `AppUpdateUiState.Idle`
- **Persistence:** Not persisted (ephemeral, reset on app restart)
- **Semantics:** Current state of the update check + download workflow
- **Exposed to UI:** Dialog, snackbar, and progress indicators observe this field
- **Transitions:** State machine driven by `checkForAppUpdates()` and `downloadAvailableUpdate()`

---

### 2.2 ViewModel Action Methods

#### `setDebugUpdateApiUrl(value: String): Unit`
- **Parameters:**
  - `value: String` — new API URL (can be empty)
- **Behavior:** 
  - No-op if `BuildConfig.DEBUG == false`
  - Trims and stores in SettingsRepository if debug build
  - No validation; assumes URL is valid (validation is API caller's job)
- **Side Effects:** Persists to DataStore; does not trigger check
- **Async:** Launched in viewModelScope

#### `checkForAppUpdates(): Unit`
- **Parameters:** None
- **Precondition:** Only effective if not already checking/downloading (`appUpdateUiState` not in `Checking` or `Downloading`)
- **Behavior:**
  1. Transitions `appUpdateUiState` → `Checking`
  2. Fetches current app version from BuildConfig/context (method TBD: `BuildConfig.VERSION_NAME` or computed)
  3. Resolves API URL via `AppUpdateLogic.resolveLatestReleaseApiUrl(debugUpdateApiUrl, BuildConfig.DEBUG)`
  4. Calls `AppUpdateRepository.fetchLatestRelease(resolvedUrl)`
  5. On success:
     - Uses `AppUpdateLogic.selectApkAsset(assets, preferDebug=BuildConfig.DEBUG)` to pick APK
     - Compares versions via `AppUpdateLogic.isRemoteVersionNewer(currentVersion, remoteTag)`
     - If newer: transitions to `UpdateAvailable(release)`
     - If up-to-date: transitions to `UpToDate`
  6. On error: transitions to `Error(message, cause)`
- **Side Effects:** Mutates `appUpdateUiState` only
- **Async:** Launched in viewModelScope; non-blocking

#### `downloadAvailableUpdate(): Unit`
- **Parameters:** None
- **Precondition:** Only callable from `UpdateAvailable` state
- **Behavior:**
  1. Transitions `appUpdateUiState` → `Downloading(progress=0%)`
  2. Extracts selected APK asset from current state
  3. Calls `AppUpdateRepository.downloadApkAsset(asset)`
  4. On success:
     - Transitions to `ReadyToInstall(apkFile)`
     - Does NOT call installer automatically; UI must trigger install
  5. On error: transitions to `Error(message, cause)`
- **Side Effects:** Downloads file to cache; mutates `appUpdateUiState` only
- **Async:** Launched in viewModelScope; non-blocking

#### `dismissAppUpdateDialog(): Unit`
- **Parameters:** None
- **Behavior:**
  - Transitions `appUpdateUiState` → `Idle`
  - Clears any error or pending update state
  - Does not delete downloaded APK (cleanup is periodic; see APK cache policy below)
- **Side Effects:** Mutates `appUpdateUiState` only
- **Async:** Synchronous

---

## 3. State Enum: `AppUpdateUiState`

Sealed class hierarchy with exhaustive state coverage:

```
sealed class AppUpdateUiState {
    object Idle : AppUpdateUiState
    
    object Checking : AppUpdateUiState
    
    object UpToDate : AppUpdateUiState
    
    data class UpdateAvailable(
        val release: GitHubRelease,
        val selectedAsset: GitHubRelease.Asset
    ) : AppUpdateUiState
    
    data class Downloading(
        val progressPercent: Int = 0,
        val downloadedBytes: Long = 0,
        val totalBytes: Long? = null
    ) : AppUpdateUiState
    
    data class ReadyToInstall(
        val apkFile: File,
        val release: GitHubRelease
    ) : AppUpdateUiState
    
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : AppUpdateUiState
}
```

### State Semantics

| State | Meaning | UI Action | Can Transition To |
|-------|---------|-----------|-------------------|
| `Idle` | No update check in progress | Show "Check Updates" button | `Checking` |
| `Checking` | Fetching release from GitHub | Show spinner; disable button | `UpToDate`, `UpdateAvailable`, `Error` |
| `UpToDate` | No newer version available | Show "Your app is up to date" toast/snackbar | `Idle` (dismiss) |
| `UpdateAvailable(release, asset)` | New version found; ready to download | Show dialog with version, size, download button | `Downloading`, `Idle` (cancel) |
| `Downloading(percent)` | APK download in progress | Show progress bar | `ReadyToInstall`, `Error` |
| `ReadyToInstall(apkFile, release)` | APK cached; ready to install | Show "Install" button | → ApkInstaller (UI route) or `Idle` (cancel) |
| `Error(message, cause)` | Network, parse, or other error | Show error snackbar with retry button | `Idle`, `Checking` (retry) |

---

## 4. Data Model: `GitHubRelease`

Located in: `dev.minios.ocremote.domain.model`

**Required fields:**
- `tagName: String` — release tag (e.g., "v1.6.14", "1.6.14")
- `assets: List<Asset>` — array of downloadable artifacts
- `body: String` — release notes / description (optional, for UI display)
- `publishedAt: String` — ISO 8601 timestamp (optional)

**Nested:** `Asset`
- `name: String` — filename (e.g., "app-release.apk", "app-debug.apk")
- `browserDownloadUrl: String` — HTTP(S) URL
- `size: Long` — file size in bytes
- `contentType: String` — MIME type (should be "application/vnd.android.package-archive")

**Parsing:** JSON deserialization from GitHub API `/repos/{owner}/{repo}/releases/latest`

---

## 5. Repository & Logic Layer

### 5.1 `AppUpdateRepository`

**Location:** `dev.minios.ocremote.data.repository.AppUpdateRepository`

**Existing Methods:**

#### `fetchLatestRelease(apiUrl: String = DEFAULT_URL): GitHubRelease`
- Calls GitHub API at `apiUrl`
- Returns parsed `GitHubRelease` object
- Throws on HTTP error or parse error; does not catch

#### `downloadApkAsset(asset: GitHubRelease.Asset): File`
- Downloads binary from `asset.browserDownloadUrl`
- Saves to `context.cacheDir/app-updates/{asset.name}`
- Creates directory if missing
- Throws if directory creation fails or write fails
- Returns File handle to downloaded APK

**New Methods (TBD by implementation):**
- None required by this contract; repository is complete

### 5.2 `AppUpdateLogic`

**Location:** `dev.minios.ocremote.data.repository.AppUpdateLogic`

**Existing Methods (all used by ViewModel):**

#### `resolveLatestReleaseApiUrl(debugOverrideUrl: String?, isDebugBuild: Boolean, defaultUrl: String): String`
- Returns `debugOverrideUrl` if both conditions true: `isDebugBuild == true` AND `debugOverrideUrl` non-empty/non-null
- Otherwise returns `defaultUrl`
- Does not validate URL format

#### `isRemoteVersionNewer(currentVersion: String, remoteTag: String): Boolean`
- Compares semantic version strings
- Normalizes tags (removes "v" prefix)
- Returns true if remote > current
- Handles missing/empty versions (treated as 0.0.0)

#### `selectApkAsset(assets: List<Asset>, preferDebug: Boolean): Asset?`
- Filters to `.apk` files only
- If `preferDebug == true`: returns first `*debug*.apk`, falls back to non-debug, then first `.apk`
- If `preferDebug == false`: returns first non-debug `.apk`, falls back to debug, then first `.apk`
- Returns `null` if no APK found

#### `normalizeReleaseTag(tag: String?): String?`
- Trims and removes "v"/"V" prefix
- Returns `null` if empty after normalization

---

## 6. APK Installation Boundary

### 6.1 Responsibility Division

| Component | Responsibility |
|-----------|-----------------|
| **ViewModel** | Fetch, compare, download; expose `ReadyToInstall` state |
| **ApkInstaller utility** | Launch system installer Intent; request unknown-sources permission if needed |
| **UI/Composable** | Observe state; call ViewModel methods; route to ApkInstaller when user taps "Install" |

### 6.2 `ApkInstaller` Interface (TBD)

**Location:** `dev.minios.ocremote.service.ApkInstaller` (or similar)

**Required Methods:**

#### `installApk(context: Context, apkFile: File): Unit`
- **Parameters:**
  - `context: Context` — Activity/Application context for Intent
  - `apkFile: File` — downloaded APK path
- **Behavior:**
  1. Check if `android.permission.REQUEST_INSTALL_PACKAGES` is granted (API 26+)
  2. If not granted:
     - Route to Settings (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`)
     - Return; UI must re-trigger install after permission granted
  3. If granted:
     - Create file Uri (`FileProvider` recommended; direct `file://` forbidden on API 24+)
     - Launch system installer Intent (`ACTION_INSTALL_PACKAGE`)
     - Return (async; system handles rest)
- **Permissions Required:** `REQUEST_INSTALL_PACKAGES` (AndroidManifest.xml)
- **Side Effects:** None on ViewModel; launches system Intent

### 6.3 Unknown-Sources Handling (Android 8+)

**Rules:**
- `REQUEST_INSTALL_PACKAGES` permission is mandatory on API 26+ for in-app install
- If permission missing: ApkInstaller routes to Settings, returns without error
- UI observes `ReadyToInstall` state; taps "Install" → calls ApkInstaller
- After user grants permission in Settings, UI must re-trigger `installApk()`
- ViewModel does not manage permissions; that is ApkInstaller + UI responsibility

**Permission Declaration (AndroidManifest.xml):**
```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

---

## 7. Debug Build Override Rules

### 7.1 Rules

1. **Debug URL Override:**
   - Only active if `BuildConfig.DEBUG == true`
   - If false, `debugUpdateApiUrl` is ignored; release build always uses `DEFAULT_LATEST_RELEASE_API_URL`
   - If true and `debugUpdateApiUrl` non-empty, GitHub API call uses override URL

2. **APK Asset Selection:**
   - If `BuildConfig.DEBUG == true`: prefer `*debug*.apk` (e.g., `app-debug.apk`)
   - If `BuildConfig.DEBUG == false`: prefer non-debug `.apk` (e.g., `app-release.apk`)
   - Fallback chain: preferred → opposite → any `.apk`

3. **Settings UI Visibility:**
   - "Debug Update API URL" field visible **only** when `BuildConfig.DEBUG == true`
   - Release builds show no override input; users cannot change API URL

### 7.2 BuildConfig Access

- `BuildConfig.DEBUG` — determined by build type (debug vs. release)
- `BuildConfig.VERSION_NAME` — app version (e.g., "1.6.14")
- **Current Version Retrieval (TBD by implementation):**
  - Suggested: `BuildConfig.VERSION_NAME` (simplest)
  - Alternative: PackageManager.getPackageInfo() for runtime consistency
  - Contract does not mandate; implementation can choose

---

## 8. APK Cache Policy

### 8.1 Storage Location

- **Path:** `context.cacheDir/app-updates/{asset.name}`
- **Rationale:** App-private, auto-cleanup on uninstall, system can clear if space needed

### 8.2 Retention

- Downloaded APKs remain in cache until:
  - Manual cleanup (not in scope of this feature)
  - System cache clearance
  - App uninstall
  - Device storage low (system triggered)
- **ViewModel does not delete cached APKs**

### 8.3 Lifecycle on Completion

- After installation starts (system Intent launched), ViewModel does not track installation progress
- UI may optionally poll/listen for package install completion (not in scope of this contract)
- Cached APK remains even after successful install

---

## 9. Key Symbols & Type Definitions

### State Classes
- `AppUpdateUiState` (sealed class)
  - `Idle`
  - `Checking`
  - `UpToDate`
  - `UpdateAvailable(release: GitHubRelease, selectedAsset: GitHubRelease.Asset)`
  - `Downloading(progressPercent: Int, downloadedBytes: Long, totalBytes: Long?)`
  - `ReadyToInstall(apkFile: File, release: GitHubRelease)`
  - `Error(message: String, cause: Throwable?)`

### SettingsViewModel Fields
- `debugUpdateApiUrl: StateFlow<String>`
- `appUpdateUiState: StateFlow<AppUpdateUiState>`

### SettingsViewModel Methods
- `setDebugUpdateApiUrl(value: String): Unit`
- `checkForAppUpdates(): Unit`
- `downloadAvailableUpdate(): Unit`
- `dismissAppUpdateDialog(): Unit`

### Repository Classes
- `AppUpdateRepository.fetchLatestRelease(apiUrl: String): GitHubRelease`
- `AppUpdateRepository.downloadApkAsset(asset: GitHubRelease.Asset): File`

### Logic Utilities
- `AppUpdateLogic.resolveLatestReleaseApiUrl(debugOverrideUrl: String?, isDebugBuild: Boolean, defaultUrl: String): String`
- `AppUpdateLogic.isRemoteVersionNewer(currentVersion: String, remoteTag: String): Boolean`
- `AppUpdateLogic.selectApkAsset(assets: List<Asset>, preferDebug: Boolean): Asset?`
- `AppUpdateLogic.normalizeReleaseTag(tag: String?): String?`

### Model Classes
- `GitHubRelease`
  - `tagName: String`
  - `assets: List<Asset>`
  - `body: String?`
  - `publishedAt: String?`
  - **Nested:** `Asset(name: String, browserDownloadUrl: String, size: Long, contentType: String)`

### Utility (TBD)
- `ApkInstaller.installApk(context: Context, apkFile: File): Unit`

---

## 10. Unresolved Ambiguities

### None

All boundaries, state transitions, and responsibility divisions are explicit. The following were clarified in this contract:
- ✓ ViewModel exposes state to UI; ApkInstaller handles install + permission routing
- ✓ Debug URL override only in debug builds
- ✓ APK asset selection rule (debug vs. release)
- ✓ State machine transitions and preconditions
- ✓ Cache location and retention
- ✓ Permission handling (ApkInstaller responsibility, not ViewModel)

---

## 11. Related Issues & References

- **GitHub Releases API:** `https://api.github.com/repos/{owner}/{repo}/releases/latest`
- **Android App Bundle & APK Distribution:** APK MIME type = `application/vnd.android.package-archive`
- **Unknown Sources (API 26+):** `android.permission.REQUEST_INSTALL_PACKAGES`
- **FileProvider:** Required for file Uri on API 24+

---

## 12. Version History

| Date | Version | Author | Notes |
|------|---------|--------|-------|
| 2025-04-20 | 1.0 | Contract Author | Initial SSOT for issue #6 |

