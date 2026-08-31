# CodeCarry 1.11.3

Fix DSH chat opening onto `bad-request: session page through seq 9007199254740991 is past cursor …`.

## Changes

- First DSH history now comes from `session/follow` on the live mux. `session/page` runs only for older messages and only with the follow snapshot cursor (later live seqs). The JS sentinel `throughSeq` is gone.
- Retry no longer spins forever when the mux is not Ready; it surfaces the generation error and keeps the Retry button.
- If `session/follow` ends while the generation stays Ready, Chat reopens follow after a short delay instead of freezing a nonempty timeline.

## Verification

- Focused JVM tests: first open never posts `session/page`; older pages use the real cut (`12072`); unknown cursor skips page; Retry without Ready surfaces an error; follow End while Ready reopens.
- Passed `:app:testDebugUnitTest` (614 tests) and `:app:assembleDebug`.

## Metadata

- `versionName`: `1.11.3`
- `versionCode`: `117`
