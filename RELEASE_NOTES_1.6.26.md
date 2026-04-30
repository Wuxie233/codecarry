# OC Remote v1.6.26 — Release Notes

## Highlights

- MCP runtime status parity
  - The APK now asks the OpenCode server for runtime MCP status (`GET /mcp`), the same source the web UI uses, and renders those servers regardless of whether the project's `.opencode/opencode.json` declares `mcp` itself.
  - Resolves the case where global MCP servers (e.g. `aceTool`, `autoinfo`, `exa`, `fetch`, `github`, `playwright`, `stitch`) appeared in the web UI but were missing in the APK because the project config file was empty.
  - Older OpenCode servers without `GET /mcp` (404/405/501) silently fall back to the existing config-file scan; behavior matches v1.6.25 in that case.
  - When runtime status is genuinely unavailable AND the file scan is empty/missing, the sheet now surfaces an explicit "OpenCode 运行时状态不可用" banner above the file diagnostics.

- Fallback parser official-schema fixes
  - Top-level `mcp` is now the canonical key (previously `mcpServers`). Legacy `mcpServers` remains read-compatible.
  - `command` may be a JSON array (`["node","script.js"]`); the first element is the executable, the rest are merged into args.
  - Remote MCP entries with `url` and `type: "remote"` parse without requiring a `command`.

- UI copy
  - Empty/Missing/Parse-error copy now refers to `mcp`, not `mcpServers`. The single legacy mention is parenthesized as "（兼容读取 legacy mcpServers）".
  - When the displayed servers come from runtime status, the sheet shows "来自 OpenCode 服务运行时状态（只读）" and disables the toggle/save controls (no edit endpoint exists for runtime status).

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅ (new: `OpenCodeApiMcpStatusTest`, `ServerRepositoryMcpRuntimeFirstTest`, `McpConfigParserOfficialSchemaTest`, `McpViewModelRuntimeStateTest`, `McpFixtureLoadTest`)
- `:app:assembleRelease` ✅
- `:app:lintDebug` ✅
- Signer cert SHA-256 matches v1.6.23 reference ✅

## Version

- `versionName`: `1.6.26`
- `versionCode`: `39`
