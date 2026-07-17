---
name: OC Remote
description: A calm, precise Android workspace for remote AI coding conversations.
colors:
  ink-indigo: "#525985"
  ink-indigo-dark: "#BCC3F5"
  signal-teal: "#2F6F69"
  signal-teal-dark: "#8FD3C9"
  canvas-light: "#F8F8F6"
  surface-light: "#FFFFFF"
  surface-subtle-light: "#F0F1EE"
  ink-light: "#20211F"
  ink-muted-light: "#5F625D"
  border-light: "#D9DBD5"
  canvas-dark: "#111310"
  surface-dark: "#191B18"
  surface-subtle-dark: "#222520"
  ink-dark: "#E8EAE5"
  ink-muted-dark: "#B7BBB3"
  border-dark: "#3D413A"
  user-wash-light: "#E8EAF4"
  user-wash-dark: "#2A2D3C"
  success: "#2F704B"
  warning: "#8A5B13"
  destructive: "#B3261E"
typography:
  headline:
    fontFamily: "sans-serif"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "28sp"
    letterSpacing: "0sp"
  title:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: "22sp"
    letterSpacing: "0sp"
  body:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
    letterSpacing: "0sp"
  label:
    fontFamily: "sans-serif"
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: "18sp"
    letterSpacing: "0sp"
  code:
    fontFamily: "monospace"
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: "20sp"
    letterSpacing: "0sp"
rounded:
  sm: "6dp"
  md: "10dp"
  lg: "14dp"
  pill: "50%"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "24dp"
  xxl: "32dp"
components:
  button-primary:
    backgroundColor: "{colors.ink-indigo}"
    textColor: "{colors.surface-light}"
    rounded: "{rounded.md}"
    height: "48dp"
    padding: "12dp 16dp"
  button-secondary:
    backgroundColor: "{colors.surface-subtle-light}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.md}"
    height: "48dp"
    padding: "12dp 16dp"
  user-message:
    backgroundColor: "{colors.user-wash-light}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.lg}"
    padding: "12dp 14dp"
  work-unit:
    backgroundColor: "{colors.surface-subtle-light}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.md}"
    padding: "10dp 12dp"
---

# Design System: OC Remote

## 1. Overview

**Creative North Star: "The Quiet Workbench"**

OC Remote is a focused mobile work surface, not a miniature desktop IDE and not a decorative AI chat. The interface stays quiet while technical content, live state, and the next useful action remain obvious. Familiar Android behavior provides trust; disciplined spacing and a restrained material vocabulary provide identity.

The system is content-first and moderately dense. It uses one calm accent, neutral surfaces, and bounded containers only when a region has its own state or interaction. Motion is responsive and interruptible, limited to navigation continuity, expansion, sending, streaming, and state feedback.

**Key Characteristics:**
- Continuous assistant reading flow with lightweight user prompts.
- A work entry organized around recent and active conversations.
- Compact, independently expandable technical work units.
- Restrained tonal hierarchy instead of card stacks and shadow depth.
- Adaptive controls that remain reachable with large text and on larger screens.

## 2. Colors

The palette combines neutral canvas tones with a muted ink-indigo action color and teal operational signal. Accent color occupies less than ten percent of a typical screen.

### Primary
- **Ink Indigo**: Primary actions, selected controls, focus, and active navigation only. It must not tint entire screen sections.

### Secondary
- **Signal Teal**: Connected, synchronized, and active operational states when a semantic success color would overstate completion.

### Neutral
- **Quiet Canvas**: App background with almost no chroma, reducing glare without becoming cream or beige.
- **Clear Surface**: Inputs, sheets, menus, and bounded work units when separation is necessary.
- **Graphite Ink**: Primary text and icons.
- **Muted Graphite**: Secondary metadata that still meets contrast requirements.
- **Hairline Border**: Structural separation used only where spacing and tonal change are insufficient.

### Named Rules

**The Ten Percent Rule.** Accent color is functional and rare. If a screen reads as indigo, the accent has been overused.

**The Two-Signal Rule.** Status always combines color with text, icon, progress, or placement. Color alone never carries meaning.

## 3. Typography

**Display Font:** Android system sans-serif
**Body Font:** Android system sans-serif
**Label/Mono Font:** Android system monospace for code and terminal content only

**Character:** One native family keeps the product familiar and renders every locale reliably. Hierarchy comes from weight, line height, and spacing rather than display type or letter spacing.

### Hierarchy
- **Headline** (600, 22sp, 28sp): Screen identity and dialog titles; never used for routine list rows.
- **Title** (600, 16sp, 22sp): Conversation names, server names, and work-unit summaries.
- **Body** (400, 16sp, 24sp): Chat prose and primary explanations, capped to a readable measure on tablets.
- **Label** (500, 13sp, 18sp): Metadata, compact controls, and status labels; no forced uppercase.
- **Code** (400, 13sp, 20sp): Code, shell output, paths, and terminal text with horizontal scrolling where required.

### Named Rules

**The Zero Tracking Rule.** Product text uses zero letter spacing. Technical content and translated labels must not be stretched for decoration.

**The Large Text Rule.** Text wraps before it truncates unless the full value is available immediately through expansion or another accessible view.

## 4. Elevation

The system is flat by default. Depth comes from neutral surface steps and spacing. Shadows are reserved for transient elements that physically cover content: menus, modal sheets, dialogs, and the raised composer edge while the keyboard is open. AMOLED uses borders and tonal steps instead of shadows.

### Named Rules

**The Bounded State Rule.** A container is earned only by independent state or interaction. Assistant prose, page sections, and static settings groups do not become cards.

## 5. Components

### Buttons
- **Shape:** Gently curved rectangle (10dp), never an oversized capsule for text actions.
- **Primary:** One primary action per region, 48dp minimum height, solid Ink Indigo.
- **Focus / Press:** Material state layer with immediate feedback; bounds never shift.
- **Secondary / Ghost:** Tonal or text treatment, preserving a 48dp touch target.

### Chips
- **Style:** Compact segmented or filter controls only. Pills are reserved for tags and true compact selections, not general commands.
- **State:** Selection uses tonal fill, icon/check where useful, and semantic selected state.

### Cards / Containers
- **Corner Style:** 10dp for work units and rows; 14dp for user prompts and modal surfaces.
- **Background:** Neutral tonal step; no decorative gradients or transparent glass.
- **Shadow Strategy:** Flat at rest. Only transient overlays receive elevation.
- **Border:** One hairline when a tonal step cannot establish the boundary.
- **Internal Padding:** 12-16dp, reduced to 8-12dp in compact technical units.

### Inputs / Fields
- **Style:** Filled neutral composer with a stable outline or tonal edge and 14dp maximum radius.
- **Focus:** Clear cursor and primary focus treatment without glow.
- **Error / Disabled:** Error appears near the source with a recovery action; disabled state remains legible and semantically disabled.

### Navigation
- Top bars use concise titles and at most two visible actions before overflow.
- Home opens on the work entry. Recent and active conversations precede compact server management.
- Back navigation preserves drafts, scroll position, filters, and conversation context.
- Tablets may use list-detail layouts or a navigation rail; phone UI must not merely stretch.

### Conversation Flow
- User prompts use a lightweight tinted bubble aligned consistently.
- Assistant prose has no outer card and follows a readable continuous measure.
- Reasoning, tools, diffs, approvals, and errors are independent work units with a summary row, live state, disclosure control, and accessible expanded state.
- The composer keeps prompt, attachment, and send/stop visible. Model, agent, variant, and context details use progressive disclosure without becoming hidden gestures.

## 6. Do's and Don'ts

### Do:
- **Do** make active and recent conversations the first useful content on Home.
- **Do** use 48dp minimum touch targets and at least 8dp between adjacent compact actions.
- **Do** render streaming placeholders before the first text delta and keep live state next to the affected response.
- **Do** preserve Light, Dark, AMOLED, dynamic color, reduced motion, large text, and TalkBack behavior.
- **Do** use icon plus text for unfamiliar or high-impact actions and provide accessible labels for icon-only controls.
- **Do** keep long code, tables, math, paths, and tool output independently scrollable without forcing prose to scroll.

### Don't:
- **Don't** create a Claude visual clone or borrow another product's branding.
- **Don't** make the product feel like a terminal-themed interface where density and darkness substitute for hierarchy.
- **Don't** ship a generic Material demo made from large tinted cards, oversized pills, and default component spacing.
- **Don't** build a dashboard that exposes every status and control at the same visual priority.
- **Don't** use decorative gradients, glass surfaces, neon accents, or motion without state meaning.
- **Don't** nest cards, wrap assistant prose in a large response card, or turn full-width page sections into floating containers.
- **Don't** hide critical actions behind gesture-only interactions or encode status with color alone.
