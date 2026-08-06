# Kimi Code Web UI Research

## Scope and Evidence

This report reviews the official `MoonshotAI/kimi-code` repository and maps its
browser UI patterns to OC Remote. The source snapshot used for component-level
claims is commit `e7d5a0aee74e7f116cca0273c416ece9139a78a0`, the last source
commit before the web app was moved out of this repository. The current
`main` snapshot reviewed is `476787fec9b777955a8ce057caf53ee428c47e63`.

Important repository history: commit
[`541ddd2d898c4880a312874b1c539f85888bf0c1`](https://github.com/MoonshotAI/kimi-code/commit/541ddd2d898c4880a312874b1c539f85888bf0c1)
(`chore(web): replace apps/kimi-web with the code-app web bundle`) removes the
`apps/kimi-web` source and commits only the built `apps/kimi-code/dist-web`
bundle. Its commit message says the source now lives in the separate code-app
repository. Therefore, historical `apps/kimi-web` paths below are source
evidence from `e7d5a0a`; they are not claimed to be present in current `main`.
The current repository still documents the runtime contract in
[`docs/en/reference/kimi-command.md`](https://github.com/MoonshotAI/kimi-code/blob/476787fec9b777955a8ce057caf53ee428c47e63/docs/en/reference/kimi-command.md):
`kimi web` serves REST, WebSocket, and the web UI from one origin, binds to
loopback by default, and authenticates the UI through a URL-fragment token.

The repository is public and the root [`LICENSE`](https://github.com/MoonshotAI/kimi-code/blob/476787fec9b777955a8ce057caf53ee428c47e63/LICENSE)
is MIT, copyright Moonshot AI 2026. Reuse of ideas is low risk; copying source,
icons, fonts, or product branding still requires preserving the license and
avoiding proprietary assets. This report recommends behavioral/layout patterns,
not a code copy.

## Source Map

| Concern | Kimi source evidence | Observed contract |
| --- | --- | --- |
| App shell and routing | `apps/kimi-web/src/App.vue` | One Vue shell owns transport-derived state, desktop/mobile branching, overlays, and the three-column desktop grid. There is no client router or Pinia; `useKimiWebClient` exposes computed view props and actions. |
| Desktop navigation | `apps/kimi-web/src/components/Sidebar.vue`, `WorkspaceGroup.vue`, `SessionRow.vue` | A single session sidebar groups sessions by workspace. Workspace headers own collapse, reorder, rename, path menu, and create-in-workspace. A pinned footer opens settings. |
| Search and pagination | `Sidebar.vue`, `SearchSessionsDialog.vue` | `Cmd/Ctrl+K` opens Spotlight-style session search. Workspace groups initially show a page and expose explicit show-more/show-less controls. |
| Chat context header | `apps/kimi-web/src/components/chat/ChatHeader.vue` | A 48px context bar shows workspace/session breadcrumb, git branch and ahead/behind/diff status, PR state, and a kebab menu for copy, rename, fork, export, and archive. |
| Message timeline | `apps/kimi-web/src/components/chat/ConversationPane.vue`, `ChatPane.vue`, `Markdown.vue` | The transcript is a bounded reading column. The pane owns follow-bottom state, a new-messages pill, older-message sentinel loading, anchor-based scroll restoration, per-turn rendering, and an optional conversation outline rail. |
| Composer | `apps/kimi-web/src/components/chat/Composer.vue`, `ChatDock.vue` | The composer is a bottom sibling of the scroll area. It auto-grows, persists drafts per session, handles slash/@ menus, attachments, queue/steer, model/thinking/permission/mode controls, context meter, stop, and send. Pending question/approval cards replace the composer in the same dock slot. |
| Mobile shell | `App.vue`, `MobileTopBar.vue`, `MobileSwitcherSheet.vue`, `MobileSettingsSheet.vue`, `BottomSheet.vue`, `useIsMobile.ts` | At `max-width: 640px`, the grid becomes one column. A 50px top bar opens a workspace/session switcher sheet; sliders open settings. Desktop sidebar/header are replaced rather than squeezed. |
| Adaptive behavior | `App.vue`, `style.css`, `ChatDock.vue`, `Composer.vue` | `visualViewport` drives `--app-height`/`--app-top` for iOS keyboard panning. Safe-area variables are centralized. `ResizeObserver` measures dock height. Narrow composer rules preserve context/send controls and move secondary controls into mobile settings. |
| Settings | `apps/kimi-web/src/components/settings/SettingsDialog.vue`, `MobileSettingsSheet.vue` | Desktop settings are an overlay; mobile settings are a bottom sheet with session controls, app preferences, archived-session restore, account, and server version. |

## Observed Interaction Patterns

### Home, Workspace, and Session Selection

Kimi treats the workspace as the primary navigation grouping. `Sidebar.vue`
renders one scroll container of workspace groups rather than a separate global
workspace rail plus an unrelated session feed. Each group header exposes its
folder/name/path context and local actions. A session row shows title, time,
running/failed state, attention count, and a row menu. This keeps the user's
working directory visible while scanning sessions.

The sidebar also has stable outer regions: brand/collapse header, New chat,
search, scrollable workspace/session list, and settings footer. The source uses
fixed-width sidebar content clipped by a width transition when collapsed, so
the conversation does not jump to a different grid arrangement. A resize handle
persists the sidebar width. This is a useful distinction from simply hiding a
navigation composable.

On mobile, `MobileSwitcherSheet.vue` reuses the same workspace grouping model.
Selecting a session closes the sheet immediately. The active session remains
visible even if it is beyond the first loaded page, which avoids a deep-link
selection that appears to vanish.

### Chat Header and Timeline

`ChatHeader.vue` keeps context compact and actionable. The breadcrumb is
ellipsized, while branch and PR metadata use semantic colors and remain clickable
to open the relevant change surface. Session actions are consolidated in one
menu, rather than consuming permanent horizontal space.

`ConversationPane.vue` separates the reading column from the bottom dock. The
desktop reading column is capped at `760px`; the mobile column removes that cap.
`ChatPane.vue` renders the actual turns and uses a top `IntersectionObserver`
sentinel for older history. `ConversationPane.vue` tracks whether the user is
following the tail, shows a new-messages pill after manual scroll-up, and restores
the previous viewport with stable turn/tool anchors after prepending history.
These behaviors are more important than the visual card treatment for long agent
sessions.

The optional `ConversationToc.vue` is a narrow rail keyed by user queries. It
tracks the query occupying the viewport and hides itself when a wide table would
intercept its hit area. This is a focused navigation aid for long conversations,
not a second message list.

### Composer and Blocked-Agent States

`ChatDock.vue` makes the bottom interaction surface a single slot. Work pills
open Bash/subagent/todo panels above it; a goal strip can occupy the same dock;
pending question and approval cards replace the composer while the agent waits
for the user. This keeps the response-required state close to the input without
duplicating prompts in the transcript.

`Composer.vue` has a minimal input row with a separate toolbar. It supports
session-scoped draft persistence, auto-growing textarea, an explicit expanded
editor, slash command and file mention menus, drag/paste/file attachments,
queued prompts, Ctrl/Cmd+S steering, IME-safe Enter handling, model switching,
thinking segments, permission mode, plan/swarm/goal modes, context usage, stop,
and send. The toolbar deliberately sheds controls below 980px; at 640px,
permission and mode controls move to `MobileSettingsSheet` while attach/context/
model/send remain visible.

### Mobile and Adaptive Behavior

The mobile shell is a real alternate composition. `useIsMobile.ts` uses a
reactive `matchMedia('(max-width: 640px)')` query. `App.vue` switches from the
desktop sidebar and resize handle to `MobileTopBar`; `ConversationPane` receives
`mobile=true`; and the sheets expose navigation/settings that no longer fit in
the header.

The visual viewport handling is unusually concrete. `App.vue` mirrors
`visualViewport.height` and `offsetTop` into CSS variables and pins the shell with
`position: fixed`, addressing iOS keyboard behavior where `dvh` alone does not
follow the panned visual viewport. `style.css` defines `--safe-top`,
`--safe-right`, `--safe-bottom`, and `--safe-left`; `MobileTopBar` consumes
top/inline insets; `ChatDock` owns inline inset
variables once; and `BottomSheet` reserves the bottom safe area. `ChatDock` also
uses `ResizeObserver` to publish `--dock-h`, so floating feedback does not cover a
multi-line composer.

## OC Remote Mapping

| Kimi pattern | OC Remote surface | Reuse level | Recommendation and risk |
| --- | --- | --- | --- |
| Workspace-grouped session navigation with collapse, search, and local row actions | `HomeScreen.kt` server cards; `SessionListScreen.kt`; `SessionProjectsViewport.kt`; `ProjectGroupHeader.kt`; `SessionWorkspaceViewControl.kt`; `SessionRecentWork.kt` | Adapt | Keep Android's server-authoritative model. Adopt grouped, scannable project/session hierarchy and per-row overflow actions. Do not introduce a cross-server recent feed; project/session IDs must stay scoped to the selected server. |
| Single compact chat context header with breadcrumb and action menu | `ChatScreen.kt` message scaffold and chat top-bar code | Adapt | Consolidate session title, project/directory, backend/status, and high-frequency actions into a stable top bar. Risk: `ChatScreen.kt` is large and owns many backends/roundtable branches; extract only the header boundary before visual changes. |
| Bounded reading column plus tail-follow/new-message behavior | `ChatScreen.kt`, `ChatMessageRowPlanner.kt`, `ChatOverflowPolicy.kt` | Reuse concept | Preserve the existing Compose markdown/overflow architecture. Add or refine follow-tail state and a new-content affordance only at the scroll owner. Risk: nested horizontal drags and table/code scrolling are already sensitive; do not add a parent gesture recognizer casually. |
| One bottom dock slot for composer and pending approvals/questions | `ChatScreen.kt` composer and approval/question UI | Adapt | Keep server-request ownership and ordered decisions intact. Place response-required cards adjacent to the composer only if the current state model can retain them independently of screen collection. Risk: Codex/OpenCode/Pi approval semantics differ and must not be collapsed. |
| Composer draft persistence, slash/@ suggestions, attachment chips, model/context controls | `ChatScreen.kt`, `ChatViewModel.kt`, `ChatBackendCapabilities.kt` | Adapt selectively | Preserve the chat composer as the primary interaction surface. Borrow the separation of input row and compact control toolbar, but map only capabilities supported by each backend. Risk: `@` suggestions are input helpers, not control commands; do not copy Kimi's slash behavior without backend routing. |
| Mobile replacement shell: top bar plus switcher/settings sheets | `HomeScreen.kt`, `SessionListScreen.kt`, `ChatScreen.kt`, `NavGraph.kt` | Adapt | Use a compact chat header and bottom sheets for project/session switching and chat settings on narrow Android widths. Keep native back behavior and server selection explicit. Risk: Android navigation is route-based and stateful; avoid adding a second navigation graph inside ChatScreen. |
| Settings split by global app preferences and server/session controls | `SettingsScreen.kt`, `ServerSettingsScreen.kt`, `ServerProvidersScreen.kt`, `ServerModelFilterScreen.kt` | Reuse concept | Keep global settings separate from selected-server settings. Borrow sectioned rows, concise summaries, and segmented/toggle controls. Do not merge server credentials, provider settings, and chat controls into one generic sheet. |
| Stable adaptive dimensions and safe interaction zones | Compose `WindowInsets`, `Scaffold`, `LazyColumn`, existing `ChatOverflowPolicy.kt` | Adapt | Define explicit min/max dimensions for toolbar/icon/message surfaces and use `WindowInsets` at the owning container. Verify tables/code/plain-text overflow separately at narrow widths. Risk: changing padding in the monolithic chat screen can regress selection, drag, or WebView math paths. |

## Phased Refactor Backlog

### Phase 1: Chat Shell Contract

1. Extract a small chat-header composable boundary from `ChatScreen.kt`.
2. Define which title, directory/project, backend status, and actions are
   available for OpenCode, Pi Stack, Roundtable, and Codex.
3. Add narrow-width layout tests for header truncation and composer minimum
   controls. Do not change markdown rendering in this phase.

### Phase 2: Session Navigation Scanability

1. Improve `SessionListScreen.kt` project groups and row metadata using Kimi's
   hierarchy and collapse affordances.
2. Keep `serverId` as the ownership key for every derived session/project view.
3. Add recent-work/session search only inside the selected server's control
   surface. Preserve current archive/restore undo behavior.

### Phase 3: Composer And Response Dock

1. Separate the message input row from secondary controls in `ChatScreen.kt`.
2. Preserve current approval/question/Codex request lifecycle and render
   server-required state next to the composer only after state ownership is
   verified.
3. Add session-scoped draft restoration and attachment-chip states with focused
   tests before visual polish.

### Phase 4: Mobile Shell

1. Introduce a compact top-bar contract for selected server/project/session.
2. Add bottom sheets for session/project switching and chat settings, with native
   back handling and safe-area insets.
3. Validate 375dp, 600dp, tablet, landscape, keyboard-open, and large-font
   layouts. Keep the current WebView path untouched unless a separate migration
   is approved.

### Phase 5: Long-Conversation Ergonomics

1. Add a new-content affordance and robust follow-tail state at the single
   scroll owner.
2. Consider an optional query outline only after message identity and streaming
   reconciliation are stable.
3. Exercise Markdown tables, fenced code, long ASCII tokens, blockquotes, and
   math through their existing independent overflow paths.

## Risks and Non-Goals

- Kimi's historical web source is Vue and browser-oriented; OC Remote is native
  Compose with multiple backend contracts. Reuse information architecture and
  interaction contracts, not component code or CSS.
- The current Kimi `main` tree no longer contains `apps/kimi-web`; claims about
  source files are pinned to `e7d5a0a` and the move is explicitly recorded above.
- No emulator, browser E2E, or real-session E2E was run for this research task.
- This report does not recommend a broad Android rewrite. The first valuable
  boundary is the chat shell/header plus selected-server session navigation.
- Kimi's MIT license permits reuse subject to its notice and disclaimer, but
  branding, fonts, and generated bundles should not be copied as if they were
  OC Remote assets.

## References

- [MoonshotAI/kimi-code](https://github.com/MoonshotAI/kimi-code)
- [Source snapshot `e7d5a0a`](https://github.com/MoonshotAI/kimi-code/tree/e7d5a0aee74e7f116cca0273c416ece9139a78a0/apps/kimi-web)
- [Web source move commit `541ddd2`](https://github.com/MoonshotAI/kimi-code/commit/541ddd2d898c4880a312874b1c539f85888bf0c1)
- [Current snapshot `476787f`](https://github.com/MoonshotAI/kimi-code/tree/476787fec9b777955a8ce057caf53ee428c47e63)
- [`apps/kimi-web/README.md` at source snapshot](https://github.com/MoonshotAI/kimi-code/blob/e7d5a0aee74e7f116cca0273c416ece9139a78a0/apps/kimi-web/README.md)
- [`apps/kimi-web/AGENTS.md` at source snapshot](https://github.com/MoonshotAI/kimi-code/blob/e7d5a0aee74e7f116cca0273c416ece9139a78a0/apps/kimi-web/AGENTS.md)
- [`kimi web` command reference](https://github.com/MoonshotAI/kimi-code/blob/476787fec9b777955a8ce057caf53ee428c47e63/docs/en/reference/kimi-command.md)
- [Kimi sessions guide](https://github.com/MoonshotAI/kimi-code/blob/476787fec9b777955a8ce057caf53ee428c47e63/docs/en/guides/sessions.md)
- [Kimi web/mobile release history](https://github.com/MoonshotAI/kimi-code/blob/476787fec9b777955a8ce057caf53ee428c47e63/docs/en/release-notes/changelog.md)
- [Kimi mobile safe-area PR #1459](https://github.com/MoonshotAI/kimi-code/pull/1459)
- [OC Remote HomeScreen.kt](../../app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt)
- [OC Remote SessionListScreen.kt](../../app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt)
- [OC Remote ChatScreen.kt](../../app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt)
- [OC Remote NavGraph.kt](../../app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt)
- [OC Remote SettingsScreen.kt](../../app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt)
