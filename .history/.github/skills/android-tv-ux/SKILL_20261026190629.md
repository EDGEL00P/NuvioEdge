---
name: android-tv-ux
description: 'Design, implement, or review Android TV and Compose UI/UX. Use for D-pad navigation, focus behavior, ten-foot readability, accessibility, responsive screens, dialogs, loading/error/empty states, or remote-only interaction.'
user-invocable: true
---

# Android TV UI/UX

Use this workflow when a task changes a TV-facing screen, navigation path, dialog, player control, or interaction model.

## Principles
- Treat remote-only operation as the primary path. Every essential action must be reachable by D-pad and actionable by the remote.
- Preserve clear, visible focus and sensible directional movement. Avoid focus traps, invisible focus, and traversal that depends on touch or hover.
- Keep focus stable across list updates, filter/group changes, loading transitions, and navigation. Restore focus to the originating item after dialogs or returning from detail/player screens where appropriate.
- Make Back behavior predictable and non-destructive. Confirm before losing user-entered source settings or other meaningful state.
- Use readable ten-foot typography, clear hierarchy, high contrast, and comfortable spacing at supported TV sizes and viewing distances.
- Use the existing design system, Compose patterns, strings, themes, and navigation architecture. Avoid broad redesigns for local requests.
- Include accessible labels/state descriptions and semantic actions; do not use color or iconography as the only signal.

## Procedure
1. Identify the target screen, TV size/density, navigation entry point, and expected D-pad/back behavior.
2. Inspect adjacent Compose components, navigation, view-model state, theme tokens, and relevant UI tests before editing.
3. Define expected loading, empty, partial/stale data, error/retry, and success states. Keep error copy actionable and safe.
4. Implement with stable keys, bounded rendering, explicit focus restoration, and existing state ownership. Avoid moving long work onto the main thread.
5. Add focused Compose/UI tests for remote traversal, focus restoration, state changes, and key behavior where test infrastructure supports them.
6. Run the narrowest relevant tests/build and inspect the changed screen at TV-sized layouts or an emulator/device when available.

## Acceptance Checklist
- Can a user complete the flow with a remote only?
- Is the focused item always visually apparent and traversal predictable?
- Do list refreshes, dialogs, and Back preserve useful focus and state?
- Are loading, empty, error/retry, and partial-data states understandable?
- Are text, controls, and hit/focus targets readable at TV distance?
- Were tests or device/emulator checks run, with unavailable checks called out honestly?
