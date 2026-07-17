# OC Remote 1.7.46

Refine the core mobile workspace for faster navigation, clearer conversations, and more reliable Codex requests.

## Workspace

- Prioritize active work and real connection state on Home while keeping server management compact.
- Improve session hierarchy so pending questions, approvals, unread responses, and live work are easier to find.
- Establish a quieter visual system across Light, Dark, AMOLED, and dynamic color themes.

## Conversations

- Present assistant prose as a continuous reading flow with compact, independently expandable reasoning, tool, diff, approval, and error units.
- Keep composer actions stable while progressively disclosing model, agent, and variant controls.
- Preserve streaming placeholders, drafts, scroll behavior, Markdown scrolling, and backend-specific protocol semantics.

## Codex and Accessibility

- Prevent duplicate Codex request submissions and support ordered multi-select user input.
- Improve pending-request state handling so fields and actions remain disabled while a response is submitted.
- Add consistent 48dp touch targets, text-backed status cues, and TalkBack state semantics across core flows.

## Verification

- Passed `:app:testDebugUnitTest` and `:app:assembleDebug`.
- Passed Light, Dark, large-text, landscape, and server-form emulator checks.
