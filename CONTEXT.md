# OC Remote Product Language

OC Remote is a mobile workspace for resuming, following, and steering remote AI coding conversations across supported backends.

## Language

**Work entry**:
The home surface that prioritizes active and recent conversations while keeping connection management secondary.
_Avoid_: Server dashboard, control center

**Server**:
A configured OpenCode, Codex, or Pi Roundtable endpoint that owns conversations and connection state.
_Avoid_: Account, workspace

**Conversation**:
A user-visible stream of prompts, assistant responses, and bounded work units. OpenCode sessions, Codex threads, and Pi roundtables are backend-specific conversation types.
_Avoid_: Chat room, task

**Active conversation**:
A conversation with current agent work, pending user action, unread completion, or another live state that deserves priority in the work entry.
_Avoid_: Running session

**Work unit**:
A bounded technical event inside an assistant response, such as reasoning, a tool call, a diff, an approval, or an error. Work units may expand independently without turning the full response into a card.
_Avoid_: Sub-card, step card

**Composer**:
The persistent conversation input surface containing the prompt field, attachments, send/stop action, and progressively disclosed model or agent controls.
_Avoid_: Input box, command bar

**Connection state**:
The user-visible health and synchronization state between OC Remote and a server, including its real connection phase and recovery path.
_Avoid_: Online flag
