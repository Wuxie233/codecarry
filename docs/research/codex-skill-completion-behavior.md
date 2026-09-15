# Codex Skill Completion Behavior

## Scope and evidence

Research date: 2026-09-15. This is a bounded source/documentation investigation,
not a desktop or Android interaction test. CodeCarry baseline:
`820af4330898d8b7229aa1351c206374a6a5e956` (1.14.11).
OpenAI CLI source snapshot: `fc269b66adc37f3c855df222ad80b02733355c46`.

Desktop app documentation and open-source CLI behavior are separate evidence
classes. The old OpenAI Codex app documentation URL now redirects to ChatGPT
Learn desktop documentation. It is useful current product documentation, but
cannot establish the exact behavior of the user's installed Codex app version.
No desktop composer source or running desktop build was inspected.

## Verified desktop documentation

The official [desktop slash-command reference](https://learn.chatgpt.com/docs/reference/slash-commands)
says to type `/` in the composer, select a command or continue typing to filter;
enabled skills also appear in that list. It also documents explicit skill
invocation with `$`.

The reference does **not** specify whether `/` triggers after existing prose,
how cursor movement affects the active query, whether `tospec` matches
`to-spec`, ranking, or the selected token's replacement boundaries. Those
behaviors must not be presented as independently verified desktop facts.
The user's report supplies the desired compatibility examples, not an exact
algorithm or a version-pinned desktop test result.

The official [skill guide](https://learn.chatgpt.com/docs/build-skills) separately
documents `/skills` or `$` for the CLI/IDE extension. That is not evidence that
all surfaces have identical slash behavior.

## Verified open-source CLI behavior

| Concern | Source-observed behavior | Evidence |
| --- | --- | --- |
| Trigger/context | The legacy skill mention path resolves a `$`-prefixed token around the cursor, including existing multi-token drafts. It does not require the whole draft to be a skill query. New mention functionality has a separate `@` path. | [Composer target resolution](https://github.com/openai/codex/blob/fc269b66adc37f3c855df222ad80b02733355c46/codex-rs/tui/src/bottom_pane/chat_composer.rs#L2767-L2844), [cursor-neighborhood parser](https://github.com/openai/codex/blob/fc269b66adc37f3c855df222ad80b02733355c46/codex-rs/tui/src/bottom_pane/chat_composer/completion_target.rs#L67-L107) |
| Matching | Mention matching first tries the display name, then distinct search terms. For skills those terms are the canonical name and display name; description is displayed but is not included in this search-term list. | [Mention matcher](https://github.com/openai/codex/blob/fc269b66adc37f3c855df222ad80b02733355c46/codex-rs/tui/src/bottom_pane/skill_popup.rs#L40-L58), [skill candidate construction](https://github.com/openai/codex/blob/fc269b66adc37f3c855df222ad80b02733355c46/codex-rs/tui/src/bottom_pane/chat_composer.rs#L4292-L4314) |
| Fuzzy semantics | Case-insensitive ordered-subsequence matching; contiguous characters are not required. Prefix matches receive a bonus; fewer skipped characters score better. Thus `tospec` matches `to-spec` by reading this algorithm, without requiring a separate alias. This is a source-derived result, not a run of the desktop app. | [Matcher implementation and tests](https://github.com/openai/codex/blob/fc269b66adc37f3c855df222ad80b02733355c46/codex-rs/utils/fuzzy-match/src/lib.rs#L1-L110) |
| Selection | Selection deletes only the resolved token range, inserts an atomic mention at its start, binds the mention to its exact path, and advances past a separator. It does not clear the whole draft or send it. | [Mention insertion](https://github.com/openai/codex/blob/fc269b66adc37f3c855df222ad80b02733355c46/codex-rs/tui/src/bottom_pane/chat_composer.rs#L2868-L2916) |

CLI token parsing has additional shell-syntax and existing-element guards.
Those rules are useful precedent, not a requirement to copy every terminal
editor behavior into Android. In particular, CLI `$` completion does not prove
that desktop `/` uses that same parser or matcher.

## Proposed CodeCarry acceptance behavior

These are product proposals derived from the user's examples and the verified
precedent, not claims about undocumented desktop internals:

1. Resolve the active `/query` near the composer cursor while editing an
   existing draft, including later lines and insertion between existing words.
2. At minimum, `/tospec` must retain `to-spec` as a candidate when that skill is
   present in the daemon catalog. Prefer deterministic case-insensitive matching
   that tolerates separators; exact and contiguous name matches should rank
   ahead of weaker matches. Preserve useful description search already offered
   by CodeCarry rather than removing it merely to copy the CLI implementation.
3. Selecting a skill keeps surrounding draft text, attaches the selected exact
   skill identity/path, and removes/replaces only the active query. It does not
   submit. The explicit Send button remains the submission action.
4. Distinguish normal URLs/paths and non-active slash text from an active skill
   query. Moving the cursor or changing selection must not consume stale ranges.
5. Keep empty/loading/error/retry states and existing attachment limits. If
   selection cannot attach the skill, preserve the draft and query.

## Validation boundary

Prefer tests at the existing composer behavior boundary: draft plus selection,
visible candidates, skill selection, resulting draft/attachments, and no send.
Cases should include beginning/middle/later-line queries, `tospec` versus
`to-spec`, surrounding text preservation, stale cursor selection, ordinary
URLs/paths, and attachment refusal. A focused Compose interaction check should
validate cursor changes and Android IME behavior. No app source changes,
application tests, or desktop runtime verification were performed for this note.
