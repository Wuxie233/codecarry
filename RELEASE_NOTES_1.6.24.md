# OC Remote v1.6.24 — Release Notes

## Highlights

- Restored MCP visibility parity for issue #19 so the APK resolves project-scoped MCP config like the web UI.
- Added clearer MCP diagnostic states for empty config, missing config, read errors, and parse errors.
- Surfaced MCP-labelled slash commands in chat so server-provided MCP commands are easier to identify.
- Polished the AMOLED slash command picker contrast for better readability on pure-black themes.
- Added a project group MCP server count hint so projects with configured MCP servers are visible from the session list menu.

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅
- `:app:lintDebug` ✅
- `:app:assembleDebug` ✅
- `:app:assembleRelease` ✅ signed release build

## Version

- `versionName`: `1.6.24`
- `versionCode`: `37`

## Known limitations

- Screenshots were not captured in this environment because no connected device or AVD was available. See `screenshots/2026-04-29-mcp-states/NOTES.md` for the screenshot blocker and manual capture steps.

## Artifact

- Artifact: `release-apks/oc-remote-1.6.24.apk`
- SHA-256: `7e6b3e9368526904cf98bb3bdeea2d9672c0d114226ada056713b712aac6f843`
- Signature verification: `apksigner verify --verbose --print-certs` ✅ (`v2` scheme verified, 1 signer)
- Signer certificate SHA-256 digest: `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`
- Previous unsigned build retained for traceability: `release-apks/oc-remote-1.6.24-unsigned.apk`
