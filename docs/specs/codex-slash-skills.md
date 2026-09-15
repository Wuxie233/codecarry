# Codex slash skill picker

The Skill picker resolves `/query` at the current composer cursor, including the
start of a draft, later lines, and insertion into existing prose. The composer
retains its text, cursor, selection and IME composition as one editing value.
Moving the cursor into a query filters by the query prefix before the cursor;
selection consumes the entire current slash token, preserving all surrounding
text and placing the cursor at the removed token's start.

ASCII word and URL/path prefixes are not invocation boundaries. Multi-segment
paths and filename-like slash tokens are excluded. Chinese prose may directly
precede a query. A single `/name` at a valid boundary is inherently ambiguous
with a single-segment absolute path; showing candidates never changes that text.
Non-collapsed selections do not open the picker. IME composition within the
query can show candidates and ends on explicit selection; unrelated composition
must not be consumed. An obsolete UI selection cannot modify a newer editing value.

Search ignores case and supports ordered subsequences of skill names:
`tospec` matches `to-spec`. Rank exact name, prefix, contiguous name, subsequence,
and contiguous description matches in that order, keeping catalog order within
each rank. Empty queries retain catalog order. Disabled skills are excluded and
paths deduplicate candidates. Short and full descriptions remain searchable.

Selecting a Skill creates the existing Skill attachment, removes only the active
query after successful attachment, and leaves submission to Send. Repeated
selection replaces the same attachment; capacity failures preserve the draft.
The attachment-menu entry remains available.

Loading, empty results and retryable errors remain visible. Parse warnings do
not hide valid skills. Skill loading is independent of file search, and obsolete
request/connection results cannot replace a newer list.

Verification covers candidate results, real ViewModel attachment/no-send behavior,
and Compose cursor movement, typing and candidate selection. JVM interaction
checks do not substitute for a physical phone/keyboard check.

Vocabulary: [conversation inputs](../../CONTEXT.md).
Evidence: [client behavior investigation](../research/codex-skill-completion-behavior.md).
Tickets: [#50](https://github.com/Wuxie233/codecarry/issues/50),
[#51](https://github.com/Wuxie233/codecarry/issues/51).
