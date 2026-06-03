# 1.7.9

## Pi Roundtable: Simplified Chinese localization

The Pi Roundtable feature (Roundtable Center, Persona Library, Roundtable Summary) was previously English-only while the rest of the app was localized. All user-facing text on these screens now flows through string resources and ships full Simplified Chinese (`zh-rCN`) translations, including localized count strings via Android plurals.

## Pi Roundtable: UX improvements

- Roundtable Summary no longer dumps the raw transcript (with `mermaid` code fences) by default. The full transcript is now collapsed behind an expandable toggle; Export and Share still carry the complete Markdown.
- The knowledge-network diagram is horizontally scrollable so wide graphs are fully reachable instead of being clipped.
- Top app bar titles no longer truncate: secondary actions moved into an overflow menu to give the title room.
- Roundtable Center filter and sort chip rows now have clear section labels so it is obvious which row filters and which sorts.
- Static labels (MBTI, question index, action tags, provider/model, fallback entries) use a non-interactive badge instead of a tappable-looking chip with no action.
- Roundtable Summary now has a single primary action: Export is the primary button and Share is a secondary tonal button.

## Notes

- No changes to connection/health behavior, export/share payloads, or other features.
- Other locales fall back to English for the newly added Roundtable strings.
