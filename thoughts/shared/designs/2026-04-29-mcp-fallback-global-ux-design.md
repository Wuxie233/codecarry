---
date: 2026-04-29
topic: "MCP Empty-Config Fallback and Standards-Guided Global UX Audit"
status: validated
---

## Problem Statement

The previous MCP release improved diagnostics but did not actually solve the user's observed parity case. The APK now finds `/root/CODE/oc-remote/.opencode/opencode.json`, sees no MCP servers there, and stops, while the web UI can still show MCP servers from another configuration source.

The UX request was also broader than the previous implementation: it is not just MCP-adjacent polish. We need a global UX audit of every design and interaction surface, guided by mature community standards, then implement the safe high-value fixes and document larger redesigns.

## Constraints

- Do not mark MCP fixed until an existing-but-empty project config falls through to later global/fallback config sources.
- Preserve release signing continuity with historical releases; do not publish with the mismatched local keystore.
- Do not push to upstream. Remote writes target the user's fork only after ownership pre-flight.
- Do not leak signing secrets or commit keystore/signing files.
- Keep architecture stable: single Activity, Compose Navigation, Material 3, Hilt, DataStore, and current OpenCode server API contracts.
- UX implementation should prioritize low-risk P0/P1 findings. Large structural redesigns must be captured as audited findings with acceptance criteria rather than rushed into a fragile release.

## Approach

I'm splitting this corrective release into two tracks that converge in one signed APK.

**Track A — MCP correctness:** change config resolution semantics so `NotFound` and `Empty` candidates continue to later candidates, while `Loaded` and hard `Error` still stop. This matches the user screenshot: an empty project config should not shadow global MCP servers.

**Track B — Global UX audit:** use established standards to inspect all app surfaces and produce a ranked audit. Then implement the high-confidence quick wins that do not require invasive rewrites.

I considered simply adding another candidate path, but rejected it because the bug is semantic: any existing empty project config can shadow later valid config. I also considered merging every config, but that is a larger behavior change and could surprise users; for this release, fallback-on-empty is the safer fix.

## Architecture

### MCP Resolution

The repository layer owns the resolution policy. It should evaluate candidates in priority order, but treat “file exists without MCP declarations” as a non-terminal candidate.

Resolution states become operationally distinct:

- **Loaded:** terminal success.
- **Error:** terminal hard failure for read/auth/permission/parse issues.
- **Empty:** non-terminal during search; remembered as the best explanation if no later candidate loads.
- **NotFound:** non-terminal during search.

The UI can still show the precise empty path if all candidates are empty/not found, but it should not show empty until the resolver has exhausted fallbacks.

### Global UX Audit

The audit framework combines:

- **NN/g 10 usability heuristics:** system status, match to real world, user control, consistency, error prevention/recovery, recognition over recall, flexibility, minimalist design, error diagnosis, help.
- **Android Core / Adaptive App Quality:** navigation, lifecycle, large screens, performance, platform expectations.
- **Material Design 3:** component usage, hierarchy, color, motion, accessibility.
- **WCAG mobile / Android accessibility:** content labels, touch target size, contrast, reading order, text scaling.
- **Baymard-style UX audit practice:** task-based severity, evidence, reproducibility, prioritized remediation.

## Components

### MCP Repository

Responsibilities:

- Continue past empty candidate files.
- Preserve the most useful empty diagnostic if no candidate loads.
- Keep hard failures explicit and actionable.
- Add regression tests for project-empty plus global-loaded.

### MCP UI

Responsibilities:

- Show loaded global/fallback MCP servers when a project config is empty.
- If no candidate has MCP servers, explain which config was found empty and that no fallback provided servers.
- Keep refresh visible and useful.

### UX Audit Artifact

Responsibilities:

- Cover every major app surface: startup, server management, sessions, chat, input, tools, permissions, questions, model/agent selection, settings, terminal, MCP/LSP/plugins, empty/error/loading states, accessibility, adaptive layout, notification/update/release flows.
- Record findings with page, flow, evidence, violated heuristic, severity, priority, suggested fix, and acceptance criteria.
- Separate immediate implementation items from larger follow-up redesigns.

### UX Implementation Batch

Responsibilities:

- Implement safe P0/P1 fixes from the audit.
- Avoid major ChatScreen/SessionList rewrites in this corrective release unless the change is tightly scoped.
- Add tests or previews where the codebase already supports them.

## Data Flow

### MCP Candidate Resolution Flow

1. Build candidate list from project-specific and global config locations.
2. For each candidate:
   - Missing file: continue.
   - Existing blank/no-MCP config: remember empty diagnostic and continue.
   - Loaded MCP servers: return loaded.
   - Hard read/auth/permission/parse error: return error.
3. If no loaded candidate exists, return the remembered empty diagnostic if present; otherwise return not found.

### UX Audit Flow

1. Define core user tasks and app surfaces.
2. Walk each flow once naturally, then again with the checklist.
3. Record findings by severity and priority.
4. Implement quick safe fixes.
5. Document larger redesigns as follow-up items with acceptance criteria.

## Error Handling

For MCP, error handling should be conservative: true read/auth/permission/parse errors remain visible and should not be silently masked by later fallback sources. Empty configs are not errors and should not block fallback discovery.

For UX, error states should prefer actionable recovery over transient-only Snackbar feedback. Where possible, important failures should have persistent context, retry affordances, and clear explanation.

## Testing Strategy

### MCP Tests

- Project config exists but contains no MCP servers; global config contains MCP servers; resolver returns global loaded state.
- Project config is blank; global config contains MCP servers; resolver returns global loaded state.
- Project config contains MCP servers; resolver returns project loaded state and does not fall through.
- All candidates are missing; resolver returns not found.
- One or more candidates are empty and no later loaded config exists; resolver returns the most useful empty diagnostic.
- Hard read/auth/permission/parse errors remain terminal.

### UX Audit Verification

- Audit artifact exists and covers all listed surfaces.
- Findings use P0/P1/P2/P3 priority and include acceptance criteria.
- Implemented quick fixes are mapped back to audit findings.
- Dark/AMOLED, small-screen, accessibility labels/touch targets, and major empty/error states are checked.

### Release Verification

- Unit tests pass.
- Lint/build pass.
- Signed APK is generated through the same signing source as previous compatible releases.
- APK signer certificate SHA-256 matches v1.6.23.
- GitHub Release asset is present and downloadable.

## Open Questions

- If OpenCode server has an official merged MCP config API, we should eventually prefer that over client-side file fallback. For now, file fallback semantics are the minimal compatible fix.
- Larger UX redesigns discovered by the audit should become follow-up lifecycle issues instead of being rushed into this corrective release.
