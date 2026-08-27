# Markdown Render Architecture

## Goal

Use one GFM AST as the structural source for chat Markdown. Convert it into an
immutable document model and a stable render plan. Chat UI code renders the
plan; it does not scan Markdown, infer block boundaries, or choose a global
fallback.

## Required Behavior

- Preserve selectable prose, headings, quotes, links, images, raw-HTML
  protection, ordered and unordered lists, code, Mermaid, GFM tables, and
  inline/display math.
- Tables use native Compose, content-adaptive 80..280dp columns, and an owned
  horizontal scroll state.
- Only a block that contains math uses the KaTeX/WebView adapter.
- Preserve exact original and parser-source reconstruction.
- Preserve assistant row eligibility, segment positions, step display,
  timeline calculations, auto-follow, and stable completed-block identity.
- Never collapse a complex document through a whole-message or maximum-chunks
  fallback.

## Source Coordinates

All ranges are half-open. Kotlin `IntRange` is not used at the architecture
boundary.

```kotlin
data class SourceRange(val start: Int, val endExclusive: Int)
```

Math preprocessing creates a parser source whose placeholder lengths differ
from the original formulas. Every placeholder therefore carries two ranges:

```kotlin
data class MathPlaceholder(
    val id: Int,
    val parserRange: SourceRange,
    val normalizedRange: SourceRange,
    val source: String,
    val display: Boolean,
    val delimiter: String,
)
```

AST semantic ranges exclude root-level trailing EOL and whitespace. The
document also owns continuous segments that cover the complete parser source:

```kotlin
sealed interface DocumentSegment {
    val parserRange: SourceRange
    data class BlockRef(val blockIndex: Int, override val parserRange: SourceRange) : DocumentSegment
    data class Trivia(override val parserRange: SourceRange) : DocumentSegment
}
```

Concatenating segment slices must reproduce the parser source exactly. Mapping
placeholders back through their dual ranges must reproduce the normalized source;
the untouched raw input remains available as `originalSource`.

## Markdown Document

`parseMarkdownDocument` is the single parsing seam. It performs, in order:

1. Existing raw-HTML document protection.
2. Existing math preprocessing with dual coordinate metadata.
3. One `MarkdownParser(GFMFlavourDescriptor())` parse.
4. AST-to-model conversion and trivia ownership.

The public model hides `ASTNode` and `IElementType`:

```kotlin
data class MarkdownDocument(
    val originalSource: String,
    val parserSource: String,
    val blocks: List<MarkdownBlock>,
    val segments: List<DocumentSegment>,
    val linkDefinitions: List<LinkDefinition>,
    val math: List<MathPlaceholder>,
)
```

`MarkdownBlock` is exhaustive: paragraph, heading, quote, list, table, code
fence, indented code, raw HTML, link definition, thematic break, and unknown.
Each block has a semantic range and an owned range. Lists carry ordering,
starting number, marker ranges, items, and nested children. Tables carry
structured header and row cells. Fences carry language, content ranges, and
closed state.

Empty GFM `CELL` nodes may have a meaningless `0..0` range. Cell slots are
derived from adjacent `TABLE_SEPARATOR` siblings, then padded or truncated to
the header width. Table alignment is not modeled because markdown-jvm 0.7.3
does not expose it and the current UI does not render it.

Parsing keeps assertions enabled. A parse failure returns a deterministic
failure value or an explicit unknown block; it must not silently reinterpret
the entire document through a second parser.

## Render Plan

The pure planner is the only routing and coalescing seam:

```kotlin
data class RenderPlan(val blocks: List<RenderBlock>)

data class RenderBlock(
    val key: String,
    val normalizedRange: SourceRange,
    val parserRange: SourceRange,
    val content: RenderContent,
    val route: RenderRoute,
    val interactionOwner: InteractionOwner,
    val context: RenderContext,
)
```

Routes are Compose and KaTeX. Interaction owners are selectable text,
horizontal scroll, WebView, and passive. Tables always use Compose plus
horizontal scroll. Fences use their existing code or Mermaid adapter. A
non-table block containing a math placeholder uses KaTeX with only its local
math metadata. Link definitions remain document context rather than duplicated
source blocks.

Adjacent small prose blocks may coalesce under the current character budget.
Tables, fences, lists, quotes, raw HTML, and math-bearing blocks own independent
boundaries. Source ranges never overlap and reconstruction never uses repeated
render-only table headers as original source.

## Streaming

markdown-jvm 0.7.3 has no incremental parser. Streaming therefore keeps a
completed prefix and only spends parser work on the open suffix:

- If the source is still a prefix-preserving append into a selectable prose,
  heading, or open fence, extend that last block in place and skip GFM.
- Otherwise parse only the open suffix, shift its ranges, and keep prefix
  block instances and keys.
- Fall back to a full document parse when the prefix no longer matches, math
  coordinates diverge, or the tail type must change (prose to table, list,
  quote, or a closed fence).
- Never reuse a key across different source content or interaction ownership.

Stable keys combine part identity with a reconciled block identity. They do not
depend on the current block index.

## Rendering Adapters

- Compose selectable adapter: prose, headings, quotes, and lists.
- Table adapter: `MarkdownTableLayout.kt`, consuming structured cells rather
  than reparsing raw table text.
- Code/Mermaid adapter: existing highlighting and Mermaid components.
- KaTeX adapter: existing WebView pool, touch arbitration, height bridge, CSS,
  and scheme filtering, receiving a single planned math-bearing block.

An exhaustive dispatcher is sufficient. Do not add a registry interface until
two real swappable adapters require one.

## Migration

1. Add the document model/parser and focused parser tests.
2. Add the pure render planner and streaming reconciler.
3. Switch `ChatMessageRowPlanner` to the new plan while preserving its external
   row and timeline contract.
4. Switch `MessageMarkdownContent` and `MarkdownMessageView` to planned blocks.
5. Change the table adapter to consume structured cells.
6. Delete the hand scanner, global route checks, placeholder rescans, table raw
   parser, link-definition duplication, and internal planning fallback.

Temporary converters are permitted only at the migration seam and must be
deleted in the same delivery.

## Acceptance

- Parser fixtures cover root trivia, headings, quotes, nested started lists,
  empty/malformed table cells, open/closed fences, indented code, raw HTML,
  reference links, images, and dual-coordinate math placeholders.
- Parser and original source reconstruct exactly.
- Planner fixtures prove route, interaction owner, structured table data, link
  context, non-overlapping ranges, and no global fallback.
- Streaming fixtures prove completed keys remain stable and only the open tail
  is replaced.
- Existing ordered-list, table gesture, math, code, Mermaid, row-planning, and
  WebView compatibility tests pass through the new seam.
- Run the JVM suite once, compile Android tests, and build debug/release APKs.
  Emulator and real-session E2E are outside this delivery.
