# CodeCarry 1.11.1

Fix DSH screens failing with authentication errors after a successful connect.

## Changes

- The Connection cookie minted during connect is now cached per server inside `DshApiClient`, so session/chat/settings calls made with a screen-built connection attach the same cookie instead of sending none and getting 401.
- A 401 drops the cached cookie and surfaces as the auth-required error; the next connect re-exchanges a fresh cookie with the saved launch token.

## Verification

- New regression tests: cookie-less screen connections reuse the cached cookie on unary calls; a 401 clears the cache and a re-exchange restores calls.
- Passed `:app:testDebugUnitTest` (611 tests) and `:app:assembleDebug`.

## APK integrity

If the installer reports a parse error, the download is truncated — verify what landed on the device:

- Size: exactly `7,285,723` bytes (≈6.95 MB)
- SHA256: `1759688e50ea432c0a86f2a20cbf12ffbe422692d6f7aecf7cbaa40f64835240`

The GitHub release asset is byte-identical to the CI artifact and passes `apksigner verify` (v2) and `zipalign -c 4`.

## Metadata

- `versionName`: `1.11.1`
- `versionCode`: `115`
