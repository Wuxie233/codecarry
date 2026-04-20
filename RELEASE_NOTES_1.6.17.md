# OC Remote v1.6.17 — Release Notes

## Bug fixes

- **Session list**: projects could show 0 sessions even when sessions existed in the web view. Fixed by capturing root sessions from per-project API calls in addition to the global fetch.
- **Markdown tables**: wide tables are now horizontally scrollable — swipe left/right to see columns that were previously cut off.

## Improvements

- **In-app update**: the download dialog now shows a real-time progress bar with bytes downloaded, total file size, and download speed (KB/s or MB/s) instead of a plain spinner.

## Version

- `versionName`: `1.6.17`
- `versionCode`: `30`
