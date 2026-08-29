# CodeCarry 1.11.1

Fix DSH screens failing with authentication errors after a successful connect.

## Changes

- The Connection cookie minted during connect is now cached per server inside `DshApiClient`, so session/chat/settings calls made with a screen-built connection attach the same cookie instead of sending none and getting 401.
- A 401 drops the cached cookie and surfaces as the auth-required error; the next connect re-exchanges a fresh cookie with the saved launch token.

## Verification

- New regression tests: cookie-less screen connections reuse the cached cookie on unary calls; a 401 clears the cache and a re-exchange restores calls.
- Passed `:app:testDebugUnitTest` (611 tests) and `:app:assembleDebug`.

## Metadata

- `versionName`: `1.11.1`
- `versionCode`: `115`
