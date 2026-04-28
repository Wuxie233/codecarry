# MCP state screenshot pass — 2026-04-29

## Automated verification

- `ANDROID_HOME=/root/Android/Sdk ./gradlew :app:testDebugUnitTest` — PASS (`BUILD SUCCESSFUL`)
- `ANDROID_HOME=/root/Android/Sdk ./gradlew :app:lintDebug` — PASS (`BUILD SUCCESSFUL`)
- `ANDROID_HOME=/root/Android/Sdk ./gradlew :app:assembleDebug` — PASS (`BUILD SUCCESSFUL`)
- Debug APK confirmed at `app/build/outputs/apk/debug/app-debug.apk`.

## Screenshot status

Automated screenshot capture was not performed. The local Android check reported no connected devices:

```text
List of devices attached
```

The emulator binary was also unavailable at `/root/Android/Sdk/emulator/emulator`, so no AVD could be started from this worktree. Because there is no real device or AVD target, the OpenCode-server-backed visual scenarios could not be exercised. No placeholder screenshots were created.

## Missing screenshots

- `loaded.png`
- `empty-config.png`
- `missing-config.png`
- `parse-error.png`
- `read-error.png`
- `saving.png`
- `save-success.png`
- `refresh-while-dirty.png`
- `slash-picker-with-mcp-label.png`
- `slash-picker-amoled.png`

## Manual capture steps

1. Connect a physical Android device or provision an AVD with `adb` visible from this host.
2. Install the verified debug APK:
   ```sh
   ANDROID_HOME=/root/Android/Sdk /root/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Start or point the app at an OpenCode server with test projects covering:
   - MCP config with one or more servers (`loaded.png`).
   - Existing config with no MCP servers (`empty-config.png`).
   - Project with no MCP config (`missing-config.png`).
   - Malformed MCP config (`parse-error.png`).
   - Server or file-read failure during MCP load (`read-error.png`).
4. Exercise save flow states for `saving.png`, `save-success.png`, and `refresh-while-dirty.png`.
5. In chat, type `/` and capture the slash picker with an MCP-labeled command as `slash-picker-with-mcp-label.png`.
6. Switch to AMOLED theme, type `/`, and capture `slash-picker-amoled.png`.
7. Save all files in this directory and update this note with any visual deviations.
