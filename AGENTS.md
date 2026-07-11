# OC Remote Agent Notes

## Communication

- Prefer concise Chinese for user-facing updates and final summaries.
- Keep code symbols, commands, file paths, version tags, and API names in their original spelling.
- Do not ask code-level questions. Make technical decisions from the codebase; only ask when product behavior or user intent is genuinely unclear.
- The user prefers `vibe talking`: natural chat-first UX, minimal visible control panels, and no unnecessary explanation.

## Pi Roundtable Product Preferences

- Treat the chat composer as the primary Pi Roundtable interaction surface.
- Avoid reintroducing a separate “roundtable steering/scheduler” main UI unless explicitly requested.
- `@` suggestions in Pi Roundtable are input helpers only. They should insert role mentions or natural-language requests; they must not directly send control commands.
- Streaming is a product requirement: thinking states and each agent’s live turn should appear as soon as possible, including placeholders before the first text delta.
- Preserve current service semantics: registry `paused` means internal `awaiting_command`; registry `awaiting` means internal `awaiting_skip`.
- Send `可` / continue only when the domain status is `Roundtable.Status.AwaitingCommand`; never send continue for `AwaitingSkip`.

## Release Workflow

- Releases are manual-only and should follow `README.md`: bump `versionName` and `versionCode`, add `RELEASE_NOTES_<version>.md`, verify locally, push `master`, create/push tag, then manually trigger `.github/workflows/release.yml` with the tag.
- Tag pushes alone do not publish releases for this repo.
- Use `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` for local Gradle verification in this environment.
- If Gradle/Hilt/Kotlin generated cache errors appear, run a clean build/test before treating them as code failures.

## Safety

- Do not restart OpenCode services or processes from this repo work.
- Do not force-push or clobber existing tags unless the user explicitly requests it.
- Keep `.codegraph/`, `.kotlin/`, and local OpenCode cache/config artifacts out of commits.

## Gotchas & Decisions

- Chat markdown has two render paths: normal markdown uses Compose in `ChatMarkdownRenderer.kt`; KaTeX/math markdown uses the WebView renderer in `MarkdownMessageRenderer.kt`. Keep code blocks, tables, display math, and reasoning/plain text with long unbreakable ASCII tokens independently horizontally scrollable; normal prose should still wrap to the bubble width.
- Connection setup is multi-stage. `OpenCodeConnectionService.connectionPhases` must reflect the real health check, workspace/session sync, activity restore, live-event setup, and retry wait; Home should render those phases in the existing server card instead of reducing them to a generic spinner or fake percentage.
