---
description: 'Fix a NuvioEdge bug or failing check, implement the repair, and verify it without stopping at a plan.'
argument-hint: 'Describe the bug, failing test, or error; include reproduction steps or relevant files.'
agent: 'NuvioEdge Code'
---

Fix the issue described in the accompanying request or attached diagnostics. Follow the [workspace guidance](../../AGENTS.md) and its relevant task-specific skill.

## Execution

1. Establish the expected and actual behavior. Inspect relevant source, diagnostics, and existing tests before editing. If no issue is identified in the request or attachments, ask which issue to fix rather than choosing unrelated work.
2. Identify the root cause using evidence. Keep any checklist in the current agent; do not stop after analysis or automatically hand off to Plan/Architect.
3. Implement the smallest justified fix. Preserve unrelated working-tree changes and add or update regression tests when practical.
4. Run focused checks first, inspect diagnostics, and address regressions introduced by the fix. Follow the workspace guidance for Windows commands, active-build checks, and flavor validation.
5. Continue until the requested fix is verified or a genuine blocker prevents progress. Do not claim unexecuted tests, builds, or device checks passed.

Respect explicit scope restrictions, required approvals, and tool permissions. Ask a focused question only when missing information materially blocks safe progress. Do not publish, push, or perform destructive operations as part of this repair unless explicitly requested.

## Final report

- **Fixed:** Brief summary and relevant files.
- **Verified:** Checks actually run and their outcomes.
- **Remaining:** Any blocker or unverified behavior; omit if none.
