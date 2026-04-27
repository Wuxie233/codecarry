# OC Remote v1.6.22 — Release Notes

## Highlights

### Swipe-to-archive + Inbox/Archived dual-scope session list
- Left swipe in Inbox now **archives** a session instead of deleting it (with 5s undo Snackbar).
- New top **[Inbox / Archived (N)]** segmented control as the primary scope switcher; replaces the old `Archived` filter chip.
- Inside Archived scope, left swipe **restores**; right swipe still renames (unchanged).
- Selection-mode delete and the per-row Delete menu item are preserved — archiving is a soft action, deletion remains available.
- Legacy `filter=ARCHIVED` preferences auto-migrate to `scope=ARCHIVED, filter=ALL` on first launch.
- New session creation now resets to Inbox so the new session is always visible.

### Localization integrity fix
- `values-fr/strings.xml` was internally misaligned (every key paired with the wrong content), which caused 12 lint StringFormat errors and would crash French users on notifications/retry/cost UI. Regenerated the entire French file from English source.
- Filled 74 missing keys (incl. swipe-to-archive strings) in 13 other locales: ar, de, es, id, it, ja, ko, pl, pt-rBR, ru, tr, uk, plus 5 missing in zh-rCN.
- All 14 locales are now 100 % translated.
- Tooling: regenerated via `lokit` against the OpenAI-compatible `deepseek-chat` endpoint.

### Lint cleanup
- Lint errors: **96 → 0**. Lint warnings: 211 → 166 (remaining warnings are housekeeping items deferred to a follow-up).
- `MainActivity.dispatchKeyEvent` lint `RestrictedApi` false positive suppressed with explanatory comment — overriding `dispatchKeyEvent` is required by the Termux terminal-mode hardware-key interception.

## Tests
- `:app:testDebugUnitTest` ✅
- `:app:assembleDebug` ✅
- `:app:lintDebug` ✅ (0 errors)

## Version
- `versionName`: `1.6.22`
- `versionCode`: `35`
