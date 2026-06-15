# OC Remote 1.7.29

Fix for horizontal scrolling of markdown tables in chat messages.

## Fixes

- Fix only the first markdown table being horizontally draggable when a message
  contains multiple tables. Tables (and code blocks) were wrapped in a custom
  `horizontalDragGuard()` modifier built on `pointerInteropFilter` — an Android
  View interop boundary that is stateful and mutates the shared parent's
  touch-interception flag while declining the gesture stream. In a pure-Compose
  message list this made only the first sibling reliably receive horizontal drag
  events, so every table after the first could not be scrolled. The guard is
  removed; each table and code block now uses Compose-native `horizontalScroll`,
  which disambiguates horizontal vs. vertical drags on its own, so all tables in
  a message are independently draggable.

## Verification

- Passed `:app:testDebugUnitTest`.
- Passed `:app:assembleDebug`.
- On-device drag verification of multi-table messages is left to manual e2e.
