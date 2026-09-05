# Mobile control surfaces (1.13)

## Product contract

Keep chat as the primary workflow. Frequently used configuration belongs near
the composer; detailed controls belong in sheets. DSH and Codex may share
presentation conventions, but retain independent transports and state ownership.

## DSH presets

- Select a preset from the current chat without entering an agent/session ID.
- Show the current preset, searchable names and descriptions, Host default,
  and broken presets. Broken entries cannot be selected.
- Selection is scoped to the current ordinary, idle session. Never silently
  change a running task, another session, or the Host global default.
- Treat the Host receipt as authoritative. A failure preserves the previous
  selection, surfaces an error, and permits retry. Reload affected model state.
- New conversations may specify a preset before their first prompt. The
  workspace flow must preserve that choice even when reusing a blank session.
- Reconnection and external changes must restore the actual session preset.
- Current selection comes from `projections.values.agentPreset`, not the
  creation header. Explicit null means no associated preset; a missing
  projection means the state has not been received. Neither implies the Host
  global default. A late selection receipt must not replace newer live state.

Existing wire contracts: `agentPresets/list`, `agentPresets/select` with
`agentId` and `agentPreset`, and `session/create` with optional `agentPreset`.

## Codex chat

- User messages remain bubbles. Assistant prose has no response bubble;
  reasoning and tools have independent disclosure controls.
- Streaming follows the tail only while the user is following it. Reading
  history exposes a return-to-latest affordance instead of stealing the scroll.
- Approvals and questions occupy a response dock above the composer. Multiple
  requests remain discoverable; a pending reply cannot be submitted twice.
- Clearly distinguish a new turn, steering the active turn, interruption,
  disconnection, and an unconfirmed send. Preserve the existing authoritative
  turn receipt and uncertain-send safeguards.
- Attachments support image selection/capture, preview and removal, Skills,
  and references to files in the remote workspace. Android local image paths
  are never sent as daemon-local paths.
- Image attachments remain in ViewModel memory across rotation, not process
  death. Text drafts use saved state. Keep binary data out of Android Bundles;
  limit the combined attachment payload below the bridge frame limit.
- Plan steps, changed files/diffs, and child-agent navigation have structured
  presentation. A child navigation retains the same server identity.
- A different child thread gets its own navigation entry and ViewModel; Back
  returns to the parent's draft and scroll state. Opening a running thread uses
  the full `thread/resume` snapshot without requiring a second disk-history read.
- A compact status sheet contains goal progress, memory mode, token usage and
  context compaction. Memory mode is not a memory-content editor.
- Use the native chat header and process disclosure presentation. Align the
  composer, user bubble geometry, spacing, and AMOLED treatment with the
  DSH/OpenCode chat; detailed controls remain behind explicit actions.

## Codex sessions

Preserve directory grouping, search, archive/restore, rename, fork and delete.
Add running and awaiting-response filters driven by server-scoped state. New
threads offer recent directories and full remote directory browsing. Match
native session row typography, card spacing, and optional search controls.

## Protocol evidence and compatibility

The implementation is checked against locally generated Codex 0.153.4
app-server bindings and the [official app-server documentation](https://learn.chatgpt.com/docs/app-server).
Generate bindings with `codex app-server generate-ts --experimental --out <dir>`.
Do not check generated bindings into the Android project.

Plan, diff and usage updates have independent lifetimes from streamed items.
Do not replace a turn's item list when consuming their notifications. Keep
thread/turn keys and reconnect generation boundaries explicit. Optional
capabilities must report unsupported states without breaking basic chat;
unknown usage is not zero usage and empty data is not proof of support.

## Acceptance

Verify preset success/failure, new-session selection and blank-session reuse;
plan/diff/usage event reduction; attachment wire shapes and send recovery;
request reply locking; same-server child navigation; combined list filters;
and physical scrolling while streaming. Run the complete JVM suite and debug
build after integration, then verify the signed release APK and tag metadata.
