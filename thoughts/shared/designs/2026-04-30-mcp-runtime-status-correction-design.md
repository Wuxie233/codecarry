---
date: 2026-04-30
topic: "MCP Runtime Status Parity Correction"
status: validated
---

## Problem Statement

The user still cannot see MCP servers in the APK even though global OpenCode MCP is configured and the web UI can see it. Server inspection confirms the global config exists at `/root/.config/opencode/opencode.json` with a top-level `mcp` map containing multiple MCP servers.

The earlier fallback fix was incomplete because it kept the APK's source of truth as client-side config file probing. The web UI does not work that way: it asks the OpenCode server for runtime MCP status after configuration has been loaded and merged.

## Constraints

- Do not treat user configuration as broken: global OpenCode MCP is present and uses the official top-level `mcp` schema.
- Make APK visibility match web visibility by using the same conceptual source: server runtime MCP status.
- Keep config-file parsing as diagnostics/edit fallback only.
- Preserve legacy compatibility with `mcpServers`, but official copy and newly generated config should refer to `mcp`.
- Do not leak MCP server command args, headers, tokens, or environment values in logs/UI.
- Publish only signed artifacts whose signer certificate matches the historical v1.6.23 signer.

## Approach

The chosen approach is **runtime status first, file diagnostics second**.

**Why:** Web UI displays MCP from server runtime state, so the APK should not attempt to reconstruct that state by guessing paths and schemas. Runtime status naturally includes global/project/merged configuration and avoids shadowing bugs caused by empty project files.

I considered continuing to improve file probing, but rejected it as the primary fix because it can never perfectly match server-side merged state, org/global inheritance, remote runtime status, or future config schema changes.

## Architecture

### Runtime MCP Source

Add or use an OpenCode API endpoint for MCP status, mirroring the web client behavior. The API result should become the primary data source for whether MCP servers exist and what their connection/enabled state is.

The APK should request status using the active server/project directory context so the server applies the same merged config rules as web.

### File Config Fallback

The existing config-file path probing remains useful for:

- Explaining where configuration was found.
- Editing local config when the app supports toggles/save.
- Diagnosing missing/empty/parse-error states if runtime status is unavailable.

It must not override a successful runtime MCP status response.

### Official Schema Compatibility

The parser should prefer the official OpenCode shape:

- Top-level `mcp` is canonical.
- `mcpServers` is legacy compatibility only.
- Local MCP command can be an array, not just a string.
- Remote MCP uses `url` and may include headers/oauth/timeout/enabled.

UI copy should point users to `mcp`, not `mcpServers`.

## Components

### OpenCode API Layer

Responsibilities:

- Expose MCP runtime status call.
- Send the same directory context mechanism used by other project-scoped endpoints.
- Avoid leaking sensitive MCP fields in DTOs where not needed.

### Repository Layer

Responsibilities:

- Try runtime MCP status first.
- If runtime status returns servers, return loaded state immediately.
- If runtime status is unavailable or unsupported, fall back to file diagnostics.
- Preserve diagnostics so UI can distinguish runtime unavailable from truly empty configuration.

### ViewModel Layer

Responsibilities:

- Refresh runtime status reliably.
- Keep user-facing state clear: loaded from runtime, loaded from config fallback, runtime unavailable, config empty, config missing, parse/read error.

### UI Layer

Responsibilities:

- Display MCP servers from runtime status.
- Stop telling users to add `mcpServers`.
- Explain whether the status came from runtime or fallback diagnostics only when useful.
- Keep refresh affordance visible.

## Data Flow

1. User opens MCP sheet for a project.
2. ViewModel asks repository for MCP state.
3. Repository calls runtime MCP status with active directory.
4. If status contains MCP servers, UI renders them.
5. If runtime status is empty, repository can still inspect config files for diagnostics.
6. If runtime status call fails due unsupported endpoint, repository falls back to existing config discovery.
7. UI renders either loaded servers or a precise diagnostic empty/error state.

## Error Handling

Runtime endpoint failures should not become a misleading “no MCP” state. They should either:

- Fall back to config diagnostics if available, or
- Show runtime status unavailable with refresh/retry guidance.

Config parser errors remain diagnostics, not the primary visibility source. Sensitive fields must be redacted from all messages.

## Testing Strategy

- Runtime status with seven servers returns a loaded MCP state even if project config is empty.
- Runtime status empty plus config empty returns a true empty diagnostic.
- Runtime status unsupported falls back to config discovery.
- Official `mcp` entries with `command: string[]` parse successfully in fallback mode.
- Official remote entries with `url` parse successfully in fallback mode.
- Legacy `mcpServers` remains supported.
- UI no longer contains user-facing `mcpServers` guidance except where explicitly labeled legacy.
- Release signing guard verifies signer certificate equals the v1.6.23 reference.

## Open Questions

- Exact MCP status endpoint path and DTO should be verified against the current OpenCode server/API implementation before coding. If the server endpoint is absent in the deployed OpenCode version, the implementation should fall back gracefully and document the version requirement.
