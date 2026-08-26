# CodeCarry 1.9.2

Require the dsh-auth password before connecting to a public DSH host.

## Changes

- Public DSH URLs cannot be saved or connected without a password.
- Authentication failures tell you to edit the server and save the dsh-auth password, instead of a bare `host.describe` 401.

## Verification

- Passed `:app:testDebugUnitTest` (577 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.9.2`
- `versionCode`: `107`
