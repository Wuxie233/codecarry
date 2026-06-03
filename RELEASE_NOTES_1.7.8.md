# OC Remote v1.7.8 - Release Notes

## Highlights

- Fixed Pi Roundtable server connection preflight on the Home screen.
- Pi Roundtable servers now validate connectivity through the authenticated `/roundtables` endpoint with the saved Bearer token instead of the OpenCode-only `/global/health` endpoint.
- OpenCode servers continue to use the existing `/global/health` health check path.
- Improved JVM-safe directory header encoding used by OpenCode API calls, preserving Android behavior while keeping unit tests stable.

## Tests

- Added regression coverage for Pi Roundtable health checks using Bearer auth and avoiding `/global/health`.
- Added coverage that OpenCode health checks still use `/global/health` with existing auth behavior.
- Release verification target: `:app:testDebugUnitTest` and `:app:assembleDebug`.
- GitHub Actions release workflow verifies tag/version alignment, signed release APK creation, APK metadata, and APK signature.

## Version

- `versionName`: `1.7.8`
- `versionCode`: `52`

## Artifact

- Artifact: pending release workflow upload as `oc-remote-1.7.8.apk`.
- Signature verification: pending GitHub Actions release workflow verification with `apksigner`.
