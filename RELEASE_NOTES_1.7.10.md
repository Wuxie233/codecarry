# 1.7.10

## Fix: question selections no longer lost on scroll

When a server asked an interactive question in chat, the answer card lived inside the scrolling message list. Scrolling the card off-screen disposed its state, so any options you had already selected (and any half-typed custom answer) were reset when you scrolled back.

The question card now preserves its state across scrolling and recomposition:

- Selected options for multi-select and multi-question prompts are kept via `rememberSaveable`, restored per-question by the list item key.
- The multi-select checkbox state is unified onto a single source of truth, fixing a possible checkbox/selection desync.
- The "type your own answer" editing mode and in-progress text are preserved per question.

As a side benefit, in-progress answers now also survive configuration changes and process death.

## Notes

- UI-only fix in the chat question card; no visual, layout, or submission-format changes.
- Single-question single-select prompts were unaffected (they submit on tap) and behave as before.
