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

## Version

- `versionName`: `1.6.24`
- `versionCode`: `37`

## Known limitations

- Release-signed APK requires `app/keystore/signing.properties`; builds are debug-signed or unsigned unless that file is present at build time.
- Screenshots were not captured in this environment because no connected device or AVD was available. See `screenshots/2026-04-29-mcp-states/NOTES.md` for the screenshot blocker and manual capture steps.

## Artifact

- Artifact: `release-apks/oc-remote-1.6.24-unsigned.apk`
- SHA-256: `3cb55b05b08a1b7bb4a0d3f5362b6eff2e486907029399029e6c563d5f077304`
- Signing credentials: absent (`app/keystore/signing.properties` was not present), so this is an unsigned release build and must not be published as the final release APK.

**Manual publish step required.** Drop a valid `app/keystore/signing.properties` (matching the existing repo convention) into the worktree, then re-run `./gradlew :app:assembleRelease` and rename the output to `release-apks/oc-remote-1.6.24.apk`.
