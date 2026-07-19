# OC Remote 1.7.50

This release fixes navigation and pending-action regressions in the OpenCode workspace.

## OpenCode Chat

- Open the exact child conversation selected from the subagent drawer.
- Preserve the current server and the child session directory when navigating.
- Keep same-session navigation single-top while giving each child conversation its own back-stack entry and ViewModel.

## Pending Actions

- Keep question and permission requests isolated to their owning OpenCode server.
- Propagate child-session questions and permissions to the owning root conversation in Activity.
- Show the root conversation under Pending immediately while preserving question-over-permission priority.
- Prevent duplicate session and request IDs on another server from affecting the selected server.
- Resolve child-to-root relationships from the selected server's session topology.

## Verification

- Passed focused navigation, session activity, and reducer regression tests.
- Passed the full debug unit test suite.
- Built the debug and Android test APKs successfully.
