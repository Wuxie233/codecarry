# CodeCarry 1.10.4

Fix DSH question cards that looked stuck after submit.

## Changes

- Custom answers now go in `custom`, not `selected`. Single-select custom and option labels stay mutually exclusive, matching the DSH `/api/respond` contract the Web GUI already used.
- A rejected receipt (`accepted: false`) throws, shows an error, and unlocks the card so the same question can be retried.
- A successful answer or reject removes the pending question immediately instead of waiting for mux.

## Verification

- Passed focused JVM tests: `DshSessionMappingTest`, `DshApiClientTest`, `DshEventReducerTest`, `ChatResponseDockTest`.
- Passed `:app:testDebugUnitTest` (606 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.10.4`
- `versionCode`: `113`
