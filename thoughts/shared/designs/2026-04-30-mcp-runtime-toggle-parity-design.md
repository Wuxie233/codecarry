---
date: 2026-04-30
topic: "MCP Runtime Toggle Parity with OpenCode Web"
status: validated
---

## Problem Statement

The Android MCP panel now reads OpenCode runtime MCP status, but it presents runtime servers as read-only. This still does not match OpenCode Web: the web UI shows each MCP runtime state and lets users connect or disconnect MCP servers for the active project context.

The user does not need full persistent MCP config editing from the APK, but does need real-time control over whether each MCP is active for the current project/session.

## Constraints

- Match Web runtime behavior, not persistent config editing.
- Do not write MCP command/header/env/OAuth config from Android in this release.
- Do not leak MCP command arguments, headers, environment, OAuth values, or full config contents.
- Preserve fallback config diagnostics for older OpenCode servers that do not expose runtime MCP endpoints.
- Release must be signed by the canonical GitHub Actions signing flow and signer certificate must match v1.6.23.
- Remote writes target only the user's fork, never upstream.

## Approach

The chosen approach is **runtime control parity**:

- Use `GET /mcp` for status display.
- Use `POST /mcp/{name}/connect` when the current runtime state is not connected.
- Use `POST /mcp/{name}/disconnect` when the current runtime state is connected.
- Refetch `GET /mcp` after each successful toggle.
- Surface failed/auth-required/client-registration-required states with clear labels.

I considered restoring file-based `enabled` writes, but rejected that as the primary interaction because Web switches do not persistently edit `opencode.json`. Runtime toggles are faster, safer, project-scoped, and match user expectation from the web panel.

## Architecture

### API Layer

The API layer exposes runtime control endpoints:

- Runtime status: `GET /mcp`
- Connect: `POST /mcp/{name}/connect`
- Disconnect: `POST /mcp/{name}/disconnect`

Requests should carry the active project directory/workspace scope using the same convention as other OpenCode project-scoped calls.

### Repository Layer

The repository owns the runtime toggle transaction:

1. Determine the current runtime state.
2. Call connect or disconnect based on state.
3. Refetch status.
4. Return a precise state or error.

Fallback file config parsing remains available only when runtime endpoint support is missing.

### ViewModel Layer

The ViewModel tracks per-server pending toggle state so one switch can show progress without blocking the whole sheet. It should preserve the last loaded runtime list during toggle failure and expose an inline error for that server or the sheet.

### UI Layer

Switch semantics should match Web:

- Checked when status is `connected`.
- Unchecked when status is `disabled`, `failed`, `needs_auth`, or `needs_client_registration`.
- Enabled for states where connect/disconnect is meaningful.
- Show status labels/dots so users understand why a switch is off.
- For auth-required states, do not fake success; show that authentication/client registration is needed.

## Components

### MCP Runtime Status Rows

Each row should show:

- Server name.
- Status label: connected, disabled, failed, needs auth, needs client registration.
- Optional non-sensitive error summary for failed states.
- Switch for connect/disconnect.
- Loading indicator while a toggle is in flight.

### Runtime Toggle Error Handling

Errors should be actionable but concise:

- Network failure: keep previous state and offer refresh/retry.
- Server failure: show sanitized message.
- Auth needed: show auth-required status; full OAuth flow can be a later issue if not already supported.

## Data Flow

1. User opens MCP sheet for project `P`.
2. APK calls `GET /mcp` scoped to `P`.
3. UI renders runtime statuses.
4. User toggles server `S`.
5. If `S` is connected, APK calls `POST /mcp/S/disconnect`; otherwise `POST /mcp/S/connect`.
6. APK refetches `GET /mcp` scoped to `P`.
7. UI updates row states and clears pending indicator.

## Error Handling

Runtime toggle failures must not change local UI state as if the operation succeeded. The app should keep the previous status, display an inline/sheet-level error, and allow refresh.

Fallback config parsing should not show interactive runtime switches unless runtime control is supported. If only file fallback is available, UI should explain that runtime control requires a newer OpenCode server.

## Testing Strategy

- API test: `GET /mcp` sends project scope and decodes all status variants.
- API test: connect/disconnect endpoints encode MCP names safely and send scope.
- Repository test: connected server toggles via disconnect then refresh.
- Repository test: disabled/failed/auth-needed server toggles via connect then refresh.
- Repository test: connect failure preserves previous state and exposes error.
- ViewModel test: per-server pending state during toggle.
- UI test: runtime switches are enabled and checked only for connected state.
- Release test: signed APK signer certificate matches v1.6.23 reference.

## Open Questions

- OAuth auth flow endpoints exist upstream, but this release should not implement a full mobile OAuth flow unless the existing architecture already supports it safely. For now, auth-required states should be visible and non-misleading.
