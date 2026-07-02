# OC Remote 1.7.35

Chat link-copy improvements for shared sessions and web session links.

## Changes

- Add a "Copy share link" action next to "Remove shared session link" in the chat overflow menu, so an already-shared session's link can be re-copied without unsharing.
- Change the chat "Open in Web" action to "Copy web link": it now copies the session's server Web UI URL to the clipboard instead of opening the in-app WebView, so the link can be opened in the user's own browser.
- Add English and Simplified Chinese labels for the two new copy actions; other locales fall back to English until the next lokit pass.

## Verification

- Added `WebSessionLinkTest` (url-safe/no-padding encoding, trailing-slash trimming, empty directory, and no embedded credentials); all pass.
- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- Confirmed the new menu labels are packaged in the debug APK for both English and Simplified Chinese via `aapt2 dump strings`.
- Notification deep-link WebView routing is unchanged.
