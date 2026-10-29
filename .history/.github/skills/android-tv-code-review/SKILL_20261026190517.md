---
name: android-tv-code-review
description: 'Review Android TV Kotlin/Compose changes for correctness, regressions, accessibility, D-pad focus, lifecycle, security, performance, and test coverage. Use for NuvioEdge code reviews or pre-merge checks.'
user-invocable: true
---

# Android TV Code Review

Review behavior and risk, not formatting preferences. Read the relevant changed code and its callers/tests before forming conclusions.

## Review Procedure
1. Identify target commit/PR/base and inspect the complete diff, repository state, instructions, and existing checks.
2. Trace modified behavior through UI, state ownership, navigation, repositories, persistence, network, and player boundaries as applicable.
3. Check Android-specific risks: lifecycle/cancellation, main-thread work, state restoration, configuration changes, resource release, and API/device compatibility.
4. Check TV UX: D-pad traversal/focus visibility and restoration, safe Back behavior, remote-only completeness, readable TV layouts, and loading/empty/error states.
5. For IPTV, inspect credential exposure, encrypted storage, input limits, redirect protections, cache/stale-data behavior, live classification, and bounded recovery.
6. Check performance claims against measurements, and tests against meaningful regressions. Do not infer pass status from test names or prior runs.
7. Report only actionable findings with severity and precise file/line references. State assumptions and coverage gaps; if none, say no findings and note what was not verified.

## Boundaries
- Do not edit code during a review unless the user asks for fixes.
- Do not approve, merge, or resolve review threads unless explicitly requested.
- Separate correctness/security findings from optional maintainability or UX suggestions.
