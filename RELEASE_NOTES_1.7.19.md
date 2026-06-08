# 1.7.19

## Roundtable usability hotfix

Pi Roundtable now handles ended and full transcripts as explicit states instead of surfacing raw command failures:

- Ended, archived, errored, and unknown roundtables no longer appear in the Active filter.
- The Roundtable Center `Continue` action is only sent when a table is actually waiting for `可` / continue.
- Ended roundtable cards open the summary path instead of sending a rejected continue command.
- Transcript-full `inject` rejections now show a clear localized message telling the user to start a new roundtable or view the summary.

## Tests

- `RoundtableCenterViewModelTest` covers active filtering and continue gating for awaiting-command vs ended tables.
- `ChatViewModelRoundtableSteeringTest` covers product-facing guidance for `maxTranscriptBytes` transcript-full rejections.
- Verified locally with focused Pi tests, full debug unit tests, and a debug APK build.

## Version

- `versionName`: `1.7.19`
- `versionCode`: `63`
