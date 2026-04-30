# OC Remote v1.6.27 — Release Notes

## Highlights

- Brought the APK MCP panel to runtime parity with OpenCode Web: each MCP server now shows its live `connected` / `disabled` / `failed` / `needs_auth` / `needs_client_registration` state for the active project (issue #21).
- Switch behavior matches Web: clicking a connected server runtime-disconnects it; clicking a disabled or failed server runtime-connects it; status is refetched after every successful toggle so the row reflects ground truth.
- Auth-required servers are surfaced honestly with a "需要授权" / "需要注册" label; the Android client does not yet implement OAuth or client-registration flows, so toggling those rows shows an inline hint instead of pretending the toggle worked.
- Per-row pending spinner so one in-flight toggle never blocks other rows.
- Old OpenCode servers without `/mcp` runtime endpoints fall back to a read-only file-config view with a banner explaining that runtime control needs a newer server build.
- No persistent MCP config editing from the APK in this release; `command`, `args`, headers, env vars, and OAuth values continue to be neither displayed nor written.

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅ (incl. new `OpenCodeApiMcpRuntimeTest`, `ServerRepositoryMcpRuntimeTest`, `ServerRepositoryMcpRuntimeAcceptanceTest`, `McpRuntimeViewModelTest`, `McpManagementSheetRuntimeTest`)
- `:app:lintDebug` ✅
- `:app:assembleDebug` ✅
- `:app:assembleRelease` ✅ signed release build

## Version

- `versionName`: `1.6.27`
- `versionCode`: `40`

## Known limitations

- Mobile OAuth / device-code flow for `needs_auth` servers and client registration for `needs_client_registration` servers are tracked separately. For now, complete authorization on Web and refresh the panel.
- The runtime list is not pushed via SSE in this release; the panel refreshes on open and after every toggle. Live push will be considered in a follow-up.

## Artifact

- Artifact: `release-apks/oc-remote-1.6.27.apk`
- SHA-256: `f73a1d61658657a237077ac1c243eefd766b7b34cc7ed938f7fc3da1662f8228`
- Signature verification: `apksigner verify --verbose --print-certs` ✅ (`v2` scheme verified, 1 signer)
- Signer certificate SHA-256 digest: `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc` (matches v1.6.23 reference)
