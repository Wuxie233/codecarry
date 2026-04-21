# OC Remote v1.6.18 — Release Notes

## New features

- **Unread session indicators**: main session now shows a blue dot when new messages arrive, making it easier to spot active conversations at a glance (closes #10).
- **Live tool output**: expanded tool cards now display running commands and retry output in real-time, giving better visibility into long-running operations (closes #10).
- **MCP management**: new dedicated MCP management sheet with project-grouped server list, project icons, and inline MCP menu options (closes #8).
- **Pin projects**: the session FAB now opens a directory browser to quickly pin a project without creating a session (closes #8).
- **Session abort on edit**: editing a running session now immediately aborts the current operation and clears the draft state (closes #8).
- **Model and time metadata**: assistant messages now display the model used and message send/receive timestamps (closes #8).

## Bug fixes

- **Picker dialogs**: agent and thinking mode selection now use consistent picker dialogs instead of inline toggles, improving usability on small screens and fixing selection edge cases (closes #2).
- **Filter state**: session list filters are now properly reset when switching between views, preventing stuck filter states (closes #2).
- **DataStore tests**: preferences tests now run reliably in background scope, improving test stability and CI reliability (closes #9).
- **MCP server list**: expand management sheet now shows full server name width without truncation (closes #8).
- **New session row**: empty project groups now show a dedicated new-session row, making it clear how to start a conversation in an empty group (closes #8).

## Version

- `versionName`: `1.6.18`
- `versionCode`: `31`
