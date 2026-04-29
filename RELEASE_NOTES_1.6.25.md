# OC Remote v1.6.25 — Release Notes

## Highlights

- MCP correctness fix
  - Project `.opencode/opencode.json` that exists but declares no MCP servers no longer shadows the global config. The APK now falls through to global fallback config (`~/.config/opencode/opencode.json` and `~/.config/opencode/config.json`) and shows those servers.
  - Hard read/parse/auth errors remain terminal and visible.
  - Empty / Missing / Read / Parse states all have clearer copy.

- Standards-guided UX audit
  - Conducted a global audit using NN/g 10 heuristics, Material 3, WCAG 2.2 AA mobile, Android Core/Adaptive App Quality, and Baymard-style severity ranking.
  - Audit artifact: `thoughts/shared/audits/2026-04-29-global-ux-audit.md` (in repo).
  - Implemented P0/P1 quick wins in this release: F-001, F-002, F-003, F-004, F-005, F-006, F-007.
  - Larger redesigns deferred with rationale in the audit artifact for F-008, F-009, and F-010.

## Tests

- `:app:compileDebugKotlin` ✅
- `:app:testDebugUnitTest` ✅
- `:app:assembleRelease` ✅
- `:app:lintDebug` ✅
- Signer cert SHA-256 matches v1.6.23 reference ✅

## Release artifact verification

- APK: `oc-remote-1.6.25.apk`
- APK SHA-256: `8d2b1a558a9d91b9c51ce66f0e46e81a84534b7797e5a8d700e192ccf1624c38`
- Signer certificate SHA-256: `fac3107e3e646a1ea9a5022d1da48480e5988c715bf4400f90a236f9f219a4dc`
- Built by GitHub Actions release workflow using repository signing secrets.

## Version

- `versionName`: `1.6.25`
- `versionCode`: `38`
