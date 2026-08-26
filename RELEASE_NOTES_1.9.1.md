# CodeCarry 1.9.1

Connect public DSH hosts that sit behind dsh-auth.

## Changes

- DSH server cards accept a password. Unary `/api`, `/api/respond`, and mux/host WebSocket handshakes send HTTP Basic (`:<password>`).
- A passworded public host is treated like loopback because dsh-auth rewrites `Host` to `127.0.0.1:18790`, so directory picker, credentials, and preset authoring stay available.
- A 401 is reported as authentication failure and does not retry forever.
- Passwordless LAN still uses `trustedHosts` and still hides loopback-only methods.

## Verification

- Passed `:app:testDebugUnitTest` (577 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.9.1`
- `versionCode`: `106`
