# 1.7.13

## Roundtable live view: cleaner, chat-first redesign

The live discussion screen was cluttered: a large "现场调度" steering panel was pinned above the input, could not be dismissed, and crammed every control onto one screen. It is redesigned around the conversation:

- The discussion now fills the screen. You mostly just type in one input — **"对全桌发言…"** — and your message is shared with every persona at the table.
- All the steering controls (continue / stop / go deeper / introduce a persona / switch cadence / @mention / jump to a round) moved into a **dismissible bottom sheet**, opened from a control on the input bar. No more always-on panel eating half the screen.
- A **compact roster + status** strip shows who's at the table and whether the round is running or paused.
- Context-aware actions appear only when relevant: a slim **Continue** button when the round is paused, and a **Skip** prompt when a persona is waiting to be skipped.

## Notes

- This changes the Pi Roundtable live view only; the normal OpenCode chat (model/agent pickers, slash commands, @file mentions, shell mode) is unchanged.
- Inline "@" typing in the main input is not in this build yet; @mention is available in the steering sheet for now.
