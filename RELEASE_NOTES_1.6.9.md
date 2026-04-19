# OC Remote v1.6.9 — Release Notes

## Bugfixes from upstream (crim50n/oc-remote)

- **Markdown crash fix**: Guard code block syntax highlighting against reversed span ranges — opening sessions with specific fenced code blocks no longer crashes the app.
- **Duplicate patch card fix**: Consecutive assistant patch cards with the same hash are now collapsed, reducing noise in long sessions.
- **Custom provider settings**: Server settings remain accessible on the home screen even when a custom provider has no published model list yet.
- **Initial load user messages**: New user messages sent via SSE are no longer hidden while the session history is still loading.
- **Session busy status**: A session's `Busy` status is now preserved when a `session.created` event arrives after processing has already started; stale statuses are also cleaned up on reconnect.

## Notes

- This release uses the same temporary test signing key as v1.6.8.
  If you already have v1.6.8 installed with the same key, you can upgrade in-place.
  If you have an older version signed differently, uninstall first.

## Version

- `versionName`: `1.6.9`
- `versionCode`: `22`
