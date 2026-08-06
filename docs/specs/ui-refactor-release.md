# OC Remote UI Refactor Release Spec

## Goal

Ship the next Android release with a Kimi Code-inspired information architecture
for native Chat and selected-server Sessions while preserving OC Remote's
server-scoped state and existing backend behavior.

## User Scenario

An operator opens a server, scans its project/session workspace, opens a long
running session, reads streaming output, and responds to a permission or
question request without losing the current context. The same workflow must
remain usable on a narrow phone, a tablet, and a wide landscape window.

## In Scope

1. Extract a compact Chat header/shell boundary with session title, project or
   directory context, backend/status metadata, and existing actions.
2. Make selected-server Sessions a scannable workspace surface with project
   grouping, collapse state, search/filter controls, recent work, and row
   metadata while retaining archive/restore and server ownership behavior.
3. Separate the Chat composer input row from secondary controls and provide one
   response dock slot for retry, roundtable, permission, question, and composer
   states without changing request ownership or decision ordering.
4. Add explicit `WindowSizeClass`-based mobile, compact/tablet, and expanded
   shell composition. Narrow layouts use replacement top-bar/sheet surfaces;
   large layouts may show persistent session or subagent context.
5. Strengthen the existing single scroll owner's follow-tail behavior with a
   stable new-content affordance and focused coverage for long messages and
   existing independent horizontal overflow paths.
6. Add focused JVM/Compose tests for the extracted contracts and update release
   metadata for the next version.

## Non-Goals

- No markdown parser, AST, render-plan, WebView math, or overflow pipeline
  rewrite.
- No backend protocol, server-authoritative project selection, or
  OpenCode/Pi/Codex/Roundtable lifecycle changes.
- No cross-server recent-work feed or unchecked client-supplied directory.
- No emulator, real-device, or real-session E2E in this delivery.
- No copied Kimi source, CSS, branding, fonts, or generated assets.
- No separate roundtable steering/scheduler screen; the composer remains the
  primary interaction surface.

## Constraints and Decisions

- `serverId` is the ownership key for every selected-server session/project
  derivation.
- Existing route-based navigation remains the authority on phones. Adaptive
  surfaces may open sheets, but must not introduce a second navigation graph.
- Use Compose `WindowSizeClass` and owning-container `WindowInsets`; do not use
  scattered hard-coded width checks as the public layout contract.
- Keep pending server requests independently owned by their existing state
  holders. A response card may move visually next to the composer only after
  preserving the original request ID and ordered decisions.
- The lead integration gate runs the repository-level JVM tests, Android-test
  compilation, debug build, and release build with Java 21.

## Acceptance Evidence

- Chat header and composer are separate composable boundaries with focused
  tests proving title/directory truncation and minimum-width control behavior.
- Selected-server session tests prove project grouping/search/collapse and that
  all derived session actions remain server-scoped.
- Response dock tests cover retry, roundtable awaiting states, permissions, and
  questions without changing their existing callbacks or state ownership.
- Adaptive shell tests cover at least phone, compact/tablet, and expanded
  width classes; mobile replacement controls do not overlap the composer or
  system insets.
- Follow-tail tests prove new content does not move a manually scrolled-up user
  and that the new-content affordance returns the user to the tail.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest`
  passes.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:compileDebugAndroidTestKotlin`
  passes.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug`
  and `:app:assembleRelease` pass.
- `versionName`, `versionCode`, and `RELEASE_NOTES_<version>.md` agree; master,
  the version tag, and the manually triggered release contain exactly the
  intended APK asset.

## Repository Facts

- `ChatScreen.kt` currently owns the route-level screen, top bar, bottom bar,
  composer, response cards, terminal mode, and backend branches.
- `SessionListScreen.kt` already owns selected-server session data and delegates
  the project viewport to `SessionProjectsViewport.kt`.
- Existing markdown and message-row planning files are stable boundaries and
  must remain untouched unless a focused regression requires it.
- Delivered release version is `1.8.8` / code `103`.

## Assumptions

- Existing localized strings can be reused or extended with the same locale
  fallback convention.
- Android build tooling and signing configuration are already available; a
  missing release keystore is a verification blocker to report, not a reason to
  weaken release signing.
