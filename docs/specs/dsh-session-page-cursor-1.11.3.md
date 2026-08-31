# CodeCarry 1.11.3: DSH history follow cursor

## Goal

Opening a DSH chat loads history and stays live. The Host never sees
`session/page` with a JS sentinel `throughSeq`. Ship `1.11.3`.

## Scenario

User opens a long idle DSH session (screenshot: Minecraft, cursor `12072`).
1.11.2 still pages with `throughSeq = 9007199254740991` and the Host returns
`bad-request: session page through seq … is past cursor 12072`. Chat is a red
error plus Retry. Composer still works.

## In-scope behavior

1. First history for an open DSH chat comes from `session/follow` on the live
   mux. Apply the opening snapshot, then live event items, through the existing
   reducer.
2. `session/page` runs only after a follow snapshot has supplied a real log
   cut, and only to load older messages. `throughSeq` is that cut (updated by
   later live seqs). `beforeSeq` is the smallest held event seq.
3. Retry and Ready reconnect reopen follow. They must not page with a sentinel.
4. Foreground resume does not re-page while follow is live.
5. Unknown cursor (no snapshot yet) means do not call `session/page`. Host
   empty-log cursor `-1` is valid only after follow reported it.
6. Remove the `THROUGH_SEQ_LATEST` sentinel so it cannot regress.
7. Bump to `versionName` `1.11.3`, `versionCode` `117`,
   `RELEASE_NOTES_1.11.3.md`. Land `master`, tag `v1.11.3`, trigger
   `.github/workflows/release.yml`.

## Non-goals

- OpenCode history.
- Changing Host `session/page` / `session/follow`.
- New chat UI chrome.
- Persisting follow cursors across process death.

## Constraints and decisions

- Host contract (`packages/api/session-controller`): `throughSeq` is the
  inclusive cut from the follow opening frame; `throughSeq > sourceCursor` is
  `gateway/bad-request`. Official client pages with the journal's current
  cursor, never a sentinel. Empty log uses `-1`.
- `DshConnectionManager.openSessionFollow` already exists; Chat never called
  it. Production wiring belongs in `ChatViewModel`; cancel the follow job in
  `onCleared` and restart it when the generation becomes Ready.
- `DshSessionSnapshot.lastSeq` defaults to `-1` before follow. That default
  must not be treated as a known empty-log cut.
- Screen-level unary still uses the cookie cache from 1.11.1.
- Do not restart dsh-web, dsh-auth, OpenCode, or nginx.

## Acceptance evidence

- Opening a DSH chat with a real cursor (e.g. `12072`) never sends
  `throughSeq = 9007199254740991`. History renders from the follow snapshot.
- Load-older sends `throughSeq` equal to the known follow/live cut and a
  `beforeSeq` strictly inside that window.
- Retry after a follow failure reopens follow; it does not page first.
- Focused JVM tests pin: no sentinel on first load; page only with a known
  cut; follow snapshot/events still fold; Ready reconnect reopens follow.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes.
- `origin/master` has `1.11.3` / `117`, tag `v1.11.3`, release workflow
  triggered.

## Resolved decisions

- Product: fix the screenshot failure and publish a patch release. Confirmed
  by the user request.
- Transport: follow-first, page-only-for-older. Captain decision from Host
  source and the official client.

## Remaining assumptions

- Live DSH on this host remains the compatibility target.
- Mux already Ready when Chat opens a session from Home/Sessions.

## Problem Statement

A DSH chat opens onto a red Host error instead of messages because CodeCarry
asks for a history page past the session cursor.

## Solution

Subscribe to `session/follow` for the first window and live updates. Use
`session/page` only to walk backwards from that real cursor.

## User Stories

1. As a DSH user, I open a long existing session and see its recent messages.
2. As a DSH user, I tap Retry after a follow blip and the chat recovers
   without the past-cursor error.
3. As a DSH user, I scroll up and older messages load from the real cut.
4. As a DSH user, I leave the app and return and the live follow is still the
   source of truth, not a sentinel page.
5. As a DSH user, I send a prompt while history is live and new events appear.
6. As a maintainer, I cannot accidentally send `MAX_SAFE_INTEGER` as
   `throughSeq`.

## Implementation Decisions

- Chat owns the follow job: start on DSH init and whenever the generation is
  Ready; cancel on clear; apply snapshot (`replace`) then live events.
- Keep `hasOlderMessages` from the follow snapshot / older page `hasMore`.
- Extract a tiny cursor helper if it keeps the ViewModel testable; otherwise
  pin behavior through reducer + manager tests plus a Chat-level test if the
  existing ViewModel harness can take a fake manager.
- `sessionHistory` / `sessionPage` require an explicit `throughSeq`; drop the
  latest-sentinel default.
- Version/release files are captain-owned after the behavior commit.

## Testing Decisions

- Red-capable tests: first history path must not emit the sentinel; older
  pages must use the follow cursor; unknown cursor skips page.
- Reuse `DshConnectionManagerTest` follow demux, `DshEventReducerTest`
  snapshot cursor, `DshApiClientTest` page envelope.
- Add a Chat-level test only if the existing ViewModel test harness can host
  a fake mux/manager without a new architecture.

## Out of Scope

Host changes, OpenCode, UI redesign, durable cursor storage.

## Further Notes

1.11.2 only made the Host accept the integer; it did not stop asking for a
cut past the log.
