# 1.7.21

## Markdown and math rendering

Chat messages now render more complete Markdown/math content without relying on the network:

- Added offline MathJax-backed rendering for inline and display LaTeX formulas.
- Recognizes `$...$`, `$$...$$`, `\(...\)`, and `\[...\]` formulas while leaving code spans and code fences untouched.
- Keeps formulas visible with a LaTeX-source fallback if WebView rendering fails or times out.

## Draft recovery and resend

Sending is safer for long prompts and image attachments:

- Chat input now clears only after a send succeeds.
- Failed normal sends and Pi Roundtable supplements keep the draft text and attachments in the composer.
- User history messages now have a restore-to-input action for quick edit-and-resend.

## Tests

- `MarkdownMathRendererTest` covers formula segmentation, code skipping, currency-like dollar text, and unmatched delimiters.
- `MessageCardActionTest` covers the restore-to-input message action visibility.
- Verified locally with focused chat tests and a debug APK build before release preparation.

## Version

- `versionName`: `1.7.21`
- `versionCode`: `65`
