# CodeCarry

CodeCarry is an independently maintained Android client that connects developers to remote coding environments so they can keep developing anywhere. It supports [OpenCode](https://github.com/anomalyco/opencode) and [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) backends with native chat and session management. The canonical repository is [Wuxie233/codecarry](https://github.com/Wuxie233/codecarry).

**CodeCarry is based on OC Remote. It is an independent project and is not affiliated with the OpenCode team.**

## Screenshots

<p align="center">
  <img src="screenshots/01_home.jpg" width="200" alt="Home screen" />
  <img src="screenshots/02_chat_light.jpg" width="200" alt="Chat — light theme" />
  <img src="screenshots/03_chat_dark.jpg" width="200" alt="Chat — dark theme" />
</p>
<p align="center">
  <img src="screenshots/04_session_menu.jpg" width="200" alt="Session menu" />
  <img src="screenshots/05_settings.jpg" width="200" alt="Settings" />
  <img src="screenshots/06_notifications.jpg" width="200" alt="Notifications" />
</p>

## Features

### Native UI
- **Full chat interface** — native Material 3 UI with markdown rendering (code blocks, tables, syntax highlighting)
- **Message streaming** — real-time text streaming with auto-scroll
- **Smart scroll behavior** — manual scroll disables auto-scroll; automatically re-enables when scrolled to bottom
- **File mentions** — `@file` autocomplete with server-backed path search and quick insert
- **Image support** — inline base64 images in chat
- **Process rows** — Think, Skill, and tool calls are independent timeline rows with expandable details; assistant prose has no Response bubble chrome
- **Image preview & save** — open sent and draft images in fullscreen preview and save to device storage
- **Shell output copy** — bash output blocks support text selection and one-tap copy (command + output)
- **HTML error fallback modes** — switch long HTML error payloads between rendered page view and raw code view
- **Slash commands** — `/new`, `/fork`, `/compact`, `/share`, `/rename`, `/undo`, `/redo`, `/shell`
- **Swipe to revert** — swipe user messages to undo (with confirmation dialog)
- **OpenCode early-send queue** — submissions made before native send readiness are held in an in-memory per-chat FIFO and sent when transport and routing are ready; a failed head remains available for retry

### Terminal Mode
- **Termux-like terminal mode** — full-screen terminal UI with dedicated extra keys and mobile-first interactions
- **Server-scoped terminal tabs** — tabs are shared across sessions for the same server and managed from a drawer
- **PTY over WebSocket** — low-latency interactive I/O for CLI/TUI apps
- **Reliable PTY resize** — rows/cols update with viewport changes and IME transitions
- **TUI rendering improvements** — better full-grid rendering behavior for terminal UIs
- **Terminal shortcuts** — Ctrl/Alt latching, volume-key virtual modifiers (Ctrl/Fn), and `Ctrl+Alt+V` paste
- **Selection toolbar paste** — terminal selection menu includes paste action integrated with terminal input

### Session Management  
- **Multi-session** — switch between sessions, view history
- **Session actions** — create, fork, compact, review changes, share/unshare, rename via dropdown menu
- **Terminal mode shortcut** — open the current session in terminal mode from the chat top bar
- **Responsive OpenCode history** — available messages remain visible while REST history or other initialization is still loading; send readiness does not wait for full history
- **Load older messages** — limit-based history loading refetches a larger full page; initial batch size is configurable (25-200)
- **Large-session stability** — `largeHeap`, bounded message history loading, and OOM fallback retry with smaller limits
- **Session export** — export full session as JSON file with streaming progress notification
- **Multi-select in sessions** — long-press to enter selection mode, select multiple sessions, and delete in one action
- **Draft persistence** — input text, image attachments, and @file mentions saved per session; survives navigation, app restart, and WebUI detours

### Model & Agent Selection
- **Model picker** — select provider and model with variant support; provider icons shown in headers
- **Agent selector** — tap to cycle through agents; each agent colored with its TUI theme color (blue, purple, green…)
- **Reliable agent mode persistence** — explicit Plan/Build choice is preserved correctly between UI state and sent commands
- **Provider icons** — 74 vector icons for AI providers shown in model picker and next to assistant responses
- **Token usage** — displays total tokens and cost in toolbar subtitle
- **Context window** — percentage display above input, color-coded (normal < 70%, warning 70-90%, critical > 90%)
- **Compact layout** — horizontally scrollable toolbar prevents overflow on long translations

### Localization
- **15 locales** — English (source), Russian, German, Spanish, French, Italian, Portuguese (BR), Indonesian, Japanese, Korean, Chinese (Simplified), Ukrainian, Turkish, Arabic, Polish
- **Localization workflow** — locale files are maintained with `lokit` during development
- **Settings** — language and theme selection in Settings screen

### Settings
- **Language** — 15 locales (system default, English, Russian, German, Spanish, French, Italian, Portuguese BR, Indonesian, Japanese, Korean, Chinese Simplified, Ukrainian, Turkish, Arabic, Polish)
- **Reconnect mode** — aggressive (1–5s), normal (1–30s), or conservative (1–60s) backoff strategy
- **Theme** — light, dark, or system default
- **Dynamic colors** — Material You dynamic color support (Android 12+)
- **AMOLED dark mode** — pure black surfaces with accent borders across chat bubbles, cards, menus, dialogs, and input blocks (works with both static and dynamic colors)
- **Chat font size** — small, medium, or large text in chat messages and code blocks
- **Code word wrap** — toggle horizontal scrolling vs. word wrap in code blocks and tool outputs
- **Compact messages** — reduce spacing between messages for denser layout
- **Auto-expand tool results** — show tool card contents expanded by default
- **Initial message count** — configure how many messages to load per session (25–200)
- **Confirm before send** — optional confirmation dialog before sending messages
- **Haptic feedback** — optional send confirmation haptics (API 30+ `CONFIRM`, older `CONTEXT_CLICK`) and swipe feedback on revert gestures
- **Keep screen on** — prevents sleep while the chat screen is open
- **Notifications** — toggle task completion notifications
- **Silent notifications** — suppress sound and vibration for task notifications
- **Image optimization controls** — tune max image side (keep original or 720–2560 px) and WebP quality for attachments

### Connection
- **Multi-server** — connect to multiple OpenCode and DSH servers
- **DSH** — native sessions and chat over `/api` RPC plus mux/host WebSockets; remaining host surfaces live under Server Settings
- **DSH new conversation** — Sessions + browses the host filesystem, registers the chosen directory as a workspace, reuses a blank session when one already belongs there, or starts No Repo without a project folder
- **Local runtime via Termux** — set up and run OpenCode directly on-device from the Home screen (setup/start/stop/sessions)
- **Local runtime launch options** — configure LAN binding (`0.0.0.0`), optional server username/password auth, background launch mode, auto-start (background-only), startup timeout, and proxy/`NO_PROXY` from the app
- **Provider OAuth flow** — browser OAuth, headless fallback handling, and provider-state refresh on resume
- **SSE event stream** — real-time session status, permissions, questions
- **WebSocket transport** — used for terminal PTY streams
- **Auto-reconnect** — exponential backoff starting at 1s, with max delay based on reconnect mode (5s/30s/60s)
- **Background service** — foreground service keeps connections alive when app is minimized

## Requirements

- Android 8.0+ (API 26)
- OpenCode or DSH endpoint accessible over the network. DSH LAN access needs the serving authority in `trustedHosts`.

## Setup

1. Start the OpenCode server with network access:

```bash
opencode serve --port 4096 --hostname 0.0.0.0
```

2. In the app, tap **+** and enter the server URL (e.g. `http://192.168.0.10:4096`), username, and optional password.

3. Tap **Connect** on the server card.

### DeepSeek Harness

Add a **DSH** server with an HTTP(S) URL (for example `http://192.168.1.8:3080`). There is no token field. Stock DSH has no password. If a fronting auth proxy such as dsh-auth is in the path, fill the optional DSH password; CodeCarry then sends HTTP Basic on unary POST and on the mux/host WebSocket handshakes. Passwordless LAN still needs the serving authority in `trustedHosts`, and loopback-locked methods stay hidden there. A passworded public host is treated like loopback when the proxy rewrites `Host` to `127.0.0.1:18790`.

## Building

### Android Studio

1. Open the project
2. Sync Gradle
3. Run on a device or emulator

### Command line

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release

Releases are **manual-only**.

1. Update `versionName`, `versionCode`, and `RELEASE_NOTES_<version>.md`
2. Verify:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

3. Push `master`
4. Create and push the release tag
5. Manually trigger `.github/workflows/release.yml` and provide `tag` (e.g. `v1.6.18`)
6. Confirm the GitHub Release has exactly one uploaded APK `codecarry-<version>.apk` and that install metadata matches the tag version

Tag pushes must **not** be relied on to auto-publish releases.

## License

MIT
