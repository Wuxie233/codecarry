# CodeCarry 1.11.4: DSH subagent SessionAddress

## Goal

Opening a DSH subagent Chat loads history and stays live. The Host never
sees `session/follow` or `session/page` with `{ kind: "session" }` for a
child whose origin is `subagent`. Ship `1.11.4`.

## Scenario

User opens a DSH child from Chat subagents (screenshot: title
"You are the aggressive…", cwd `/root/CODE/Minecraft`). 1.11.3 follows
with `{ kind: "session", sessionId }`. Host returns
`session/agent-busy: subagent Sessions require their durable parent
address`. Chat is a red error plus Retry. Composer still works.

## In-scope behavior

1. Encode one Host `SessionAddress` for every DSH history stream and
   older-page unary:
   - ordinary session: `{ kind: "session", sessionId }`
   - subagent child: `{ kind: "subagent", parentSessionId, childSessionId, mode }`
2. `session/follow` and `session/page` use that address. A child with
   `origin == "subagent"` and a known `parentSessionId` never uses
   `kind: "session"`.
3. `mode` is `"continuable"` or `"one-shot"`. Prefer
   `projections.values.subagent.mode` when present. If origin is
   subagent and mode is still unknown, default `"continuable"` (Chat
   navigates only continuable children; Host rejects a wrong mode as
   `subagent/unauthorized`, not the screenshot error).
4. Chat prompt on a subagent child uses `subagents/prompt` with
   `parentSessionId`, `childSessionId`, `mode: "continuable"`,
   `requestId`, and the same content/timezone as `session/prompt`.
5. Chat abort on a subagent child uses `subagents/interruptByParent`
   with `mode: "continuable"`. Do not call `session/cancel`.
6. Parent/origin/mode come from `session/list` and `api-session/added`
   already folded into `DshSessionSnapshot`. Follow snapshot `header`
   (`parentSession`, `origin`) may fill missing parent/origin after
   open; a later follow reopen must then use the subagent address.
7. Ordinary (non-subagent) chats keep the 1.11.3 follow-first contract.
8. Bump to `versionName` `1.11.4`, `versionCode` `118`,
   `RELEASE_NOTES_1.11.4.md`. Land `master`, tag `v1.11.4`, trigger
   `.github/workflows/release.yml`.

## Non-goals

- OpenCode history or OpenCode subagents.
- Changing Host `session/follow` / `session/page`.
- New chat UI chrome.
- Nested-grandchild catalog UI (`subagents/list` of a child).
- Routing `session/rename`, `session/fork`, `session/selectModel`,
  `session/updateQueue`, or `session/attachment` through subagent
  delivery. Those stay session-id unary; Host already rejects some of
  them with a different `session/agent-busy` message. Only the
  screenshot path (follow) plus the same Chat send/stop that would
  immediately fail with the sibling ownership error are in this
  product.
- Persisting follow cursors across process death.

## Constraints and decisions

- Host contract (`packages/api/session-controller/src/history.ts`
  `validateAddress`): `kind: "session"` against `header.origin ===
  "subagent"` throws `session/agent-busy` with message
  `subagent Sessions require their durable parent address`. Official
  client (`session.ts` `sessionAddress()`) sends
  `{ kind: "subagent", ...address }` when a child address is retained.
- `session/list` already returns `parentSessionId` and `origin`.
  `DshEventReducer.applySessionList` already stores them. Chat never
  passed them into follow/page.
- Follow and page share one address encoder. Do not duplicate JSON
  object construction in the manager and the API client.
- Prompt/abort for children use the existing `DshApiClient.subagentPrompt`
  / `subagentInterrupt` methods. Add `mode: "continuable"` on prompt
  (Host requires it; current client omits it). Interrupt already sends
  `mode: "continuable"`.
- If parent is still unknown when Chat opens a child (list not yet
  merged), wait for the list/added snapshot before opening follow
  rather than sending `kind: "session"`. Do not invent a parent.
- Screen-level unary still uses the cookie cache from 1.11.1.
- Do not restart dsh-web, dsh-auth, OpenCode, or nginx.

## Acceptance evidence

- Opening the screenshot subagent Chat never sends follow
  `{ kind: "session" }`. The open frame is
  `{ kind: "subagent", parentSessionId, childSessionId, mode }`.
  History renders from the follow snapshot.
- Load-older for that child posts `session/page` with the same
  subagent address and the follow cut as `throughSeq`.
- Opening an ordinary DSH chat still follows `{ kind: "session", sessionId }`.
- Chat send on a subagent child posts `subagents/prompt`, not
  `session/prompt`. Abort posts `subagents/interruptByParent`.
- Ordinary DSH send/abort still use `session/prompt` / `session/cancel`.
- Focused JVM tests pin: child follow address; child page address;
  parent session follow unchanged; child prompt/interrupt methods;
  missing parent does not emit `kind: "session"` for a subagent origin.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes.
- `origin/master` has `1.11.4` / `118`, tag `v1.11.4`, release workflow
  triggered.

## Resolved decisions

- Product: fix the screenshot failure, also route Chat send/stop for
  the same child, and publish a patch release. Confirmed by the user
  request (fix + release). Send/stop are necessary mechanics of
  opening that Chat, not extra systems.
- Address source: list/added parent+origin, plus follow header fill;
  mode from `projections.values.subagent.mode` with continuable
  default. Captain decision from Host `SessionAddress` and official
  client.
- Missing parent: delay follow rather than send the forbidden session
  address. Captain decision; sending `kind: "session"` is the bug.

## Remaining assumptions

- Live DSH on this host remains the compatibility target.
- Mux already Ready when Chat opens a session from Home/Sessions or
  the Chat subagent list.
- Chat-opened DSH children are continuable (one-shot children are not
  a Chat navigation target today).

## Problem Statement

A DSH subagent Chat opens onto a red Host error instead of messages
because CodeCarry follows the child as an ordinary Session.

## Solution

Give every DSH Chat a Host `SessionAddress`. Follow and page with it.
Route child send/stop through `subagents/*`.

## User Stories

1. As a DSH user, I tap a running subagent in Chat and see its
   messages instead of the red `session/agent-busy` error.
2. As a DSH user, I tap Retry after that error on 1.11.4 and the chat
   recovers with a subagent follow.
3. As a DSH user, I scroll up in a subagent Chat and older messages
   load with the same address.
4. As a DSH user, I send a follow-up in that child and it reaches the
   child Agent.
5. As a DSH user, I stop that child turn and the Host accepts the
   interrupt.
6. As a DSH user, ordinary sessions still open, page, send, and stop
   as in 1.11.3.
7. As a maintainer, I cannot accidentally follow a subagent origin
   with `{ kind: "session" }`.

## Implementation Decisions

- Add a small `DshSessionAddress` (session vs subagent) with
  `toJson()` used by `openSessionFollow` and `sessionPage`.
- Resolve the address from `DshSessionSnapshot`: origin subagent +
  parentSessionId → subagent arm; otherwise session arm.
- Chat ViewModel passes the resolved address into follow and page.
  If origin is subagent and parent is missing, skip the follow open
  (keep loading / retry) until list/added supplies it.
- `subagentPrompt` request body includes `mode: "continuable"`.
- Version/release files are captain-owned after the behavior commit.

## Testing Decisions

- Red-capable tests: child follow open frame contains
  `kind: "subagent"` and the parent id; child page body uses the same
  address; ordinary follow remains `kind: "session"`; child send uses
  `subagents/prompt`; missing parent does not emit session-kind follow
  for a subagent origin.
- Reuse `ChatViewModelDshFollowTest` harness (list items can carry
  parent/origin). Extend `DshApiClientTest` for subagent page and
  prompt mode. Extend `DshConnectionManagerTest` follow open payload
  if the manager takes the address directly.
- Do not run the full suite on the implementation slice; captain runs
  `:app:testDebugUnitTest` and `:app:assembleDebug` once at
  integration.

## Out of Scope

Host changes, OpenCode, UI redesign, nested catalog, remaining
session-id unaries that are not Chat send/stop.

## Further Notes

1.11.3 made Chat follow-first. It still always encoded
`kind: "session"`, which is legal only for ordinary Sessions.
