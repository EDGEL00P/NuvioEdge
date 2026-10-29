---
name: github-collaboration
description: 'Safely handle GitHub issues, pull requests, reviews, branches, checks, and Actions for NuvioEdge. Use for issue triage, PR review or creation, review comments, CI failures, workflow dispatch, or remote GitHub status.'
user-invocable: true
---

# GitHub Collaboration and Delivery

Use only authenticated tools or CLI capabilities actually available in the session. Distinguish reading/reviewing from creating, commenting, approving, merging, closing, dispatching, and publishing.

## Issues
1. Confirm owner/repository and search for duplicates before creating an issue.
2. Summarize relevant existing issues and clarify the desired change if materially ambiguous.
3. Draft concise title/body with reproduction, expected/actual behavior, environment, and acceptance criteria.
4. Create or edit only when explicitly requested; verify the resulting URL/state.

## Pull Requests
1. Confirm repository, source branch, base branch, and current PR state.
2. Inspect the full diff, changed files, tests, checks, conflicts, and relevant discussion.
3. Report concrete findings first with severity and file/line references; distinguish blocking defects from suggestions.
4. Create/update a PR only when asked. Do not approve, submit a review, resolve threads, or merge unless specifically requested.
5. For review comments, address each request narrowly, preserve unrelated changes, run relevant checks, then verify the updated diff and thread state.

## Actions and Remote Git
1. Identify exact workflow/run, ref, inputs, permissions, secrets requirements, and current state before dispatching or retrying.
2. Prefer validation/dry-run modes where appropriate. Do not dispatch duplicate runs if an equivalent run is active.
3. Never expose secret values from workflow configuration or logs; report only presence/absence and sanitized failure context.
4. Before push, inspect branch/upstream/status and staged diff. Stage only intended paths; never force-push, rewrite shared history, or delete refs.
5. After an operation, verify the actual remote branch, workflow, check, PR, or issue state. If tools/auth are unavailable, state the blocker and provide the exact next step without implying success.

## Output
Separate verified facts, findings/recommendations, actions actually taken, checks/results, and remaining blockers. Include relevant URLs and identifiers when available.
