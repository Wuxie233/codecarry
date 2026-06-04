# 1.7.15

## Roundtable: casting drafts stay in the center

Roundtable casting is now recoverable instead of being a one-screen flow:

- Newly created casting chats appear immediately in the Roundtable Center as `casting draft` / `选角草稿` cards.
- Leaving the casting chat no longer cancels it. You can go back to the center and tap `Continue casting` / `继续组建` to resume.
- Confirming a casting draft removes it from the draft list and creates the real roundtable.

## Persona localization

- Built-in roundtable personas now use Chinese display names, including `证伪官`, `假设绘图师`, and `机制工程师`.
- The previous `ljg`-prefixed persona presets have been renamed to Chinese-first names.
- Casting and lineup prompts now ask for Chinese-facing reasons and role text.

## Notes

- Requires the paired Pi Roundtable service update that adds `GET /casting`.
- Web-search tools are not enabled yet; the current Pi stack supports tool-style integration, but no reliable search backend is wired in this release.
