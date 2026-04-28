---
date: 2026-04-29
topic: "APK MCP Visibility Parity, UX Optimization, and Release"
status: validated
---

## Problem Statement

The APK currently reports no MCP servers for a project where the web UI can see active MCP servers. This is a functional parity issue, not just a presentation issue, because users cannot trust the Android client to reflect the same OpenCode server capabilities.

After MCP parity is restored, the app needs a focused UX optimization pass. The UX pass should improve clarity, recoverability, and mobile usability without turning into a broad risky rewrite.

## Constraints

- MCP correctness comes first; broad UX work must not mask or complicate the functional fix.
- Preserve existing Android architecture: single Activity, Compose Navigation, Material 3 theming, Hilt, DataStore, and the OpenCode REST/SSE/WebSocket model.
- Avoid major rewrites of the large chat and session screens unless the implementation plan can split them safely into small independently reviewed tasks.
- Keep web/API contracts compatible with the current OpenCode server unless planner confirms a server-side contract change already exists.
- Treat release as “verified release-ready artifact and version metadata” unless repository automation and signing credentials support a full publish step.
- Remote writes must target the user fork/origin only; never push to upstream.

## Approach

We will use a staged delivery sequence: fix MCP visibility first, harden MCP-specific UX second, then apply safe app-wide UX improvements, and finally produce a release-ready version.

**Chosen approach:** vertical slice through MCP read/display first, then incremental UX batches.

- It gives us a clear success signal: APK and web report the same MCP state for the same server/project.
- It avoids designing around a broken empty state.
- It keeps release risk manageable because each UX improvement can be verified independently.

**Rejected alternative: full UX redesign first.** That would be higher risk because MCP state is currently unreliable and any MCP panel redesign could be based on the wrong data.

**Rejected alternative: do MCP and global UX as one mixed refactor.** That would make failures hard to attribute and could turn a contained bug fix into a broad regression surface.

## Architecture

The MCP path should be treated as a small vertical subsystem:

- **Remote source:** OpenCode server file/path APIs and command metadata APIs.
- **Repository layer:** resolves candidate MCP config locations, reads/parses config, and returns explicit load states.
- **ViewModel layer:** owns refresh, stale/error state, and save transitions for the selected server/project.
- **UI layer:** shows MCP servers, explains empty/error cases, and provides a reliable refresh affordance.

For UX optimization, we will avoid a “redesign everything” approach. The app should gain shared UI primitives and targeted screen-level improvements while preserving current navigation and behavior.

## Components

### MCP Repository Responsibility

The repository should make MCP config resolution observable and explainable. It should distinguish:

- No config file found
- Config found but no MCP servers declared
- Config found and MCP servers loaded
- Read denied or authentication failure
- Network/timeout failure
- Parse failure

This gives UI enough information to avoid the misleading “暂无 MCP 服务器” message when the real issue is path, permission, auth, or parsing.

### MCP ViewModel Responsibility

The ViewModel should expose a refreshable state for the active project and server. It should avoid relying on a stale one-shot load when users explicitly tap refresh or reopen the sheet.

It should also keep save state separate from load state so a failed save does not erase the last known valid server list.

### MCP UI Responsibility

The MCP sheet should become diagnostic enough for normal users:

- Show loaded MCP server names when available.
- Show separate empty, missing config, read error, parse error, and network error states.
- Offer a visible refresh action.
- Keep toggles/save behavior predictable.
- Avoid hiding actionable error details behind a generic empty state.

### Chat/Command Surface Responsibility

If MCP commands are available through the server command list, the chat surface should expose them consistently with other commands. MCP command visibility should not depend only on the session list project menu.

### Shared UX Components

The UX pass should introduce or reuse shared primitives where safe:

- Loading / empty / error state cards
- Compact list rows and badges
- Bottom sheet header/action patterns
- Reusable segmented controls and filter chips
- Consistent spacing, shape, and color treatment for dark/AMOLED mode

## Data Flow

### MCP Load Flow

1. User opens MCP management for a project or refreshes the sheet.
2. App resolves server home and project directory context.
3. Repository checks known config candidates and records which candidate produced the final state.
4. Parser converts the config into MCP server models with enabled/disabled state.
5. ViewModel publishes a typed load state.
6. UI renders server list or the correct diagnostic state.

### MCP Command Flow

1. Chat screen requests available server commands when a conversation/server context is active.
2. Commands are grouped by source where supported.
3. MCP-sourced commands become discoverable in the same command entry flow as normal commands.
4. If command metadata is unavailable, UI degrades gracefully without blocking chat.

### UX Optimization Flow

1. Establish shared state and visual primitives first.
2. Apply MCP-specific UX improvements.
3. Apply low-risk session list improvements.
4. Apply low-risk chat surface improvements.
5. Verify dark/AMOLED, small-screen, and release builds.

## Error Handling

MCP error handling should be explicit and user-actionable.

**Missing config:** explain that no supported MCP config file was found for this project/server.

**Empty config:** explain that config exists but contains no MCP servers.

**Permission/auth failure:** explain that the APK could not read the config and suggest refreshing/rechecking connection credentials.

**Parse failure:** explain that the config was found but cannot be parsed.

**Network failure:** allow retry and preserve the last known good state when available.

**Save failure:** keep unsaved edits visible and offer retry rather than collapsing back to an empty state.

## Testing Strategy

### MCP Functional Tests

- Config exists with MCP servers and APK displays them.
- Config exists but has no MCP servers and APK shows an empty-config state.
- No config exists and APK shows a missing-config state.
- First candidate path fails while another valid candidate exists, and behavior matches the intended resolution policy.
- Parse error produces a parse diagnostic, not a generic empty state.
- Refresh re-queries remote state rather than reusing stale UI state.

### UX Verification

- MCP sheet screenshots/preview states for loading, loaded, empty, missing, error, saving, and saved.
- Session list remains usable with active conversations, archived conversations, filters, search, and project grouping.
- Chat input and command surfaces remain usable on small screens and dark/AMOLED mode.
- Accessibility basics: labels, touch targets, contrast, and non-ambiguous empty/error copy.

### Release Verification

- Unit tests pass.
- Android build passes.
- Release artifact is generated with the repository’s existing versioning and signing flow where available.
- Release notes mention MCP parity and UX improvements.

## Open Questions

- Full external publishing depends on existing signing/store credentials and repository automation. If unavailable, executor should stop at a verified release artifact and document the manual publish step.
- If web and APK differ because the server exposes web-only MCP state not available through current APIs, planner should isolate the required API contract and mark that task blocked rather than guessing.
