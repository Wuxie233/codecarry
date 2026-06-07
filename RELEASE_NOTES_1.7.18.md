# 1.7.18

## Roundtable command hotfix

Pi Roundtable command failures no longer leave the chat stuck in a not-ready state:

- Rejected Pi commands now preserve the server rejection detail from `effect` or `message` instead of collapsing to a generic failure.
- A rejected `inject` command no longer permanently disables the composer with “Session not ready yet” / “会话尚未就绪”.
- Successful Pi chat sends and steering commands clear the previous transient command error.
- The existing Pi command protocol remains unchanged so this can ship as a focused client hotfix.

## Tests

- `PiTransportTest` covers surfacing a rejected command effect from the Pi service.
- `ChatViewModelRoundtableSteeringTest` covers transient command errors clearing after the next successful command.
- Verified locally with focused Pi tests, full debug unit tests, and a debug APK build.

## Version

- `versionName`: `1.7.18`
- `versionCode`: `62`
