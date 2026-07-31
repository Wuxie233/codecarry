# OC Remote 1.8.3

This patch restores horizontal dragging for later and deep rows in Markdown tables.

## Markdown Tables

- Keep every table horizontally draggable when a math-enabled message contains multiple GFM tables.
- Split oversized tables at complete row boundaries so deep rows remain inside bounded top-level chat items.
- Repeat the original table header and divider in each rendered chunk while preserving the message source exactly.
- Combine large sets of small tables into bounded chunks instead of falling back to one oversized WebView.

## Verification

- Passed the focused Markdown chunking, table parsing, and chat row planning unit tests.
- Passed all 11 `MessageMarkdownHorizontalDragTest` device tests, including physical finger drags on a later table and a deep row in an oversized table.
- Passed the full debug unit test suite and built the debug and release APKs successfully.
