# Codex slash skill picker

Typing `/` at the start of the composer opens the current conversation’s skills above the input. A single `/keyword` filters enabled skills by name or description, ignoring case. Whitespace or a second slash ends the picker query so ordinary text and paths remain editable.

Selecting a skill uses the existing skill attachment, removes the slash query only when adding succeeds, and leaves submission to the Send button. Repeated selection replaces the same attachment; attachment limits preserve the draft on failure. Existing attachment-menu access remains available.

The list shows short descriptions, loading, empty results and retryable errors. Parse warnings do not hide valid skills. Skill loading is independent of file search, and obsolete request/connection results cannot replace a newer list.

Vocabulary: [conversation inputs](../../CONTEXT.md).
