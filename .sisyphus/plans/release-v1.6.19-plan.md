# Release v1.6.19 Plan

> Issue: #13
> User request: "搞个新版本release一下"

**Goal:** Cut and publish release `v1.6.19` from the current `master`, including version bump, release notes, tag, workflow dispatch, and GitHub Release verification.

**Architecture:** Follow the repository's manual-only release flow in `README.md` and `.github/workflows/release.yml`. Use `app/build.gradle.kts` as the single source of truth for `versionName`/`versionCode`, create a matching `RELEASE_NOTES_1.6.19.md`, then verify, push, tag, dispatch the workflow, and validate the resulting GitHub Release artifact.

**Tech Stack:** Android Gradle, GitHub Actions, GitHub Releases, gh CLI, git.

---

## Contract

### What to do
1. Bump `app/build.gradle.kts` from `versionName = "1.6.18"` / `versionCode = 31` to `versionName = "1.6.19"` / `versionCode = 32`.
2. Add `RELEASE_NOTES_1.6.19.md` in the existing repository style, summarizing release-worthy changes since `v1.6.18`.
3. Run release preflight verification:
   - `./gradlew :app:testDebugUnitTest`
   - `./gradlew :app:assembleDebug`
4. Commit only the release-related changes.
5. Push `master`.
6. Create and push tag `v1.6.19`.
7. Trigger `.github/workflows/release.yml` with `tag=v1.6.19`.
8. Verify the GitHub Release for `v1.6.19` exists, has exactly one APK asset named `oc-remote-1.6.19.apk`, and that release metadata matches the version.

### What not to do
- Do not include unrelated `tmp/` artifacts.
- Do not force-push.
- Do not change non-release product behavior.
- Do not rely on tag push auto-publishing; manual workflow dispatch is required.

### Acceptance criteria
- `app/build.gradle.kts` and `RELEASE_NOTES_1.6.19.md` agree on `versionName = 1.6.19` and `versionCode = 32`.
- Release notes cover the shipped work since `v1.6.18`, centered on issue `#15` and the release-flow hardening that affects publishing.
- Both Gradle verification commands succeed.
- `master` and tag `v1.6.19` are present on `origin`.
- The GitHub workflow run for `.github/workflows/release.yml` completes successfully for `v1.6.19`.
- The resulting GitHub Release has exactly one APK asset named `oc-remote-1.6.19.apk`.

### Boundary conditions / assumptions
- Repo-local evidence strongly favors a patch release: tags advance through `v1.6.18`, and no `1.7.0` milestone evidence exists.
- Current GitHub Releases are behind the tag history; this release should make the latest shipped code visible again as the newest public release.
- Local untracked `tmp/` is archival residue from prior work and must remain uncommitted.

### Verification evidence to collect
- `git status --short --branch` before commit and before push.
- Output of `./gradlew :app:testDebugUnitTest`.
- Output of `./gradlew :app:assembleDebug`.
- `git rev-parse HEAD` after release commit.
- `git rev-parse v1.6.19` after tag creation.
- `gh run view` / `gh release view v1.6.19` evidence showing successful release publication.

---

## Task Breakdown with QA Scenarios

### Task 1: Update release version metadata

**Files:**
- Modify: `app/build.gradle.kts`

**QA scenario:**
- Tool: `bash`
- Command: `grep -n 'versionCode = \|versionName = ' app/build.gradle.kts`
- Expected result: `versionCode = 32` and `versionName = "1.6.19"` are both present exactly once in `defaultConfig`.

### Task 2: Add release notes

**Files:**
- Create: `RELEASE_NOTES_1.6.19.md`

**QA scenario:**
- Tool: `bash`
- Command: `grep -n 'versionName\|versionCode\|# OC Remote v1.6.19' RELEASE_NOTES_1.6.19.md`
- Expected result: the file exists, has the `OC Remote v1.6.19` title, and contains explicit `versionName` / `versionCode` lines matching `1.6.19` / `32`.

### Task 3: Run release preflight verification

**Files:**
- No file edits; verification only

**QA scenario:**
- Tool: `bash`
- Commands:
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:assembleDebug`
- Expected result: both commands exit 0 with `BUILD SUCCESSFUL`.

### Task 4: Commit and push release changes

**Files:**
- Release-only diff from Tasks 1-2

**QA scenario:**
- Tool: `bash`
- Commands:
  - `GIT_MASTER=1 git status --short --branch`
  - `GIT_MASTER=1 git log -1 --oneline`
  - `GIT_MASTER=1 git push origin master`
- Expected result: only release files are committed, local `master` pushes successfully, and `git status` no longer shows the branch ahead of `origin/master`.

### Task 5: Create and push release tag

**Files:**
- Git tag only

**QA scenario:**
- Tool: `bash`
- Commands:
  - `GIT_MASTER=1 git tag -a v1.6.19 -m 'Release v1.6.19'`
  - `GIT_MASTER=1 git push origin v1.6.19`
  - `GIT_MASTER=1 git rev-parse v1.6.19`
- Expected result: tag `v1.6.19` exists locally and remotely, and resolves to the release commit SHA.

### Task 6: Dispatch and verify GitHub release workflow

**Files:**
- No repository file edits; GitHub workflow/release only

**QA scenario:**
- Tool: `bash` + `gh`
- Commands:
  - `gh workflow run .github/workflows/release.yml -f tag=v1.6.19`
  - `gh run list --workflow release.yml --limit 5`
  - `gh run watch <run-id> --exit-status`
  - `gh release view v1.6.19 --json tagName,name,assets`
- Expected result: the workflow run completes successfully, the release exists for `v1.6.19`, and the asset list contains exactly one APK named `oc-remote-1.6.19.apk`.
