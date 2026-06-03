# 1.7.11

## Fix: crash when opening a roundtable summary

Opening a roundtable from the Roundtable Center could force-close the app while the "Knowledge network" section was rendering.

Cause: 1.7.9 wrapped the knowledge-network Mermaid diagram in an extra horizontally-scrolling container. That diagram is drawn in a `WebView`, and measuring a `WebView` inside an unbounded-width scroll container crashes. The Mermaid diagram already scrolls wide graphs internally, so the extra wrapper was both redundant and unsafe.

The summary screen now renders the diagram the same way the chat screen does (no outer scroll wrapper). Wide diagrams remain horizontally scrollable inside the diagram view.

## Notes

- UI-only fix in the roundtable summary screen; no other screens or behavior changed.
