# CodeCarry 1.11.2

Fix chat history failing with `bad-request: throughSeq must be an integer greater than or equal to -1`.

## Changes

- `session/page` "newest cut" sentinel changed from `Long.MAX_VALUE` to the JS safe-integer ceiling (`9,007,199,254,740,991`). The Host validates `throughSeq` as a JSON integer; 2⁶³−1 is not representable as one and every first history load was rejected with the bad-request above.
- Reproduced against a live Host: `Long.MAX_VALUE` fails with exactly that message, the safe ceiling passes validation.

## Verification

- Updated the page-envelope test to pin the safe sentinel.
- Passed `:app:testDebugUnitTest` and `:app:assembleDebug`.

## APK integrity

If the installer reports a parse error, the download is truncated — verify what landed on the device:

- Size and SHA256 are published on the release page of each version.

## Metadata

- `versionName`: `1.11.2`
- `versionCode`: `116`
