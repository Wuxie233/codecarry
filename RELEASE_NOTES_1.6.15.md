# OC Remote v1.6.15 — Release Notes

## Session list UX

- **Filter empty state fix**: switching to a non-ALL filter tab when no sessions match now shows a relevant empty state with the filter tabs still visible, so you can switch back without getting trapped.
- **Two-tier subagent disclosure**: each session row now separates running subagents (Busy / Retry, expanded by default) from historical idle subagents (collapsed secondary section).
- **Active conversations banner**: the top banner now shows root sessions only — not subagents — prioritised by pending decision (question / permission), then running, then retrying. Idle sessions are excluded. Each state has a distinct icon.

## Chat

- **User message long-press menu**: long-pressing a user message bubble now shows a quick menu with Copy and Edit (revert to input). This is a parallel entry point to the existing swipe-to-revert gesture.
- **Model selection persistence fix**: selecting Claude (or any model) is now reliably used for follow-up sends in the same session. Previously the model could silently fall back to the server default on follow-up messages if the picker hadnu2019t been explicitly re-opened.

## Settings

- **Check for updates**: Settings u2192 Advanced now includes a u201cCheck for updatesu201d entry. Tapping it queries the GitHub Releases API, shows the available version and release notes, and lets you download and install the APK directly from the app via the system installer.
- Debug builds show an u201cUpdate API overrideu201d field for QA testing against a different release endpoint.

## Version

- `versionName`: `1.6.15`
- `versionCode`: `28`
