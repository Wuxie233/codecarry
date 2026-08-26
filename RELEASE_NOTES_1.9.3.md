# CodeCarry 1.9.3

Make the DSH password optional. Stock DSH has no auth.

## Changes

- Public DSH URLs no longer require a password to save or connect.
- Fill the optional DSH password only when a fronting proxy such as dsh-auth sits in front of DSH.
- A 401 is still reported as authentication failure.

## Verification

- Passed `:app:testDebugUnitTest` (577 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.9.3`
- `versionCode`: `108`
