---
name: nuvioedge-release-delivery
description: 'Prepare, validate, build, and publish NuvioEdge Android releases. Use for versionCode/versionName bumps, release notes, metadata checks, signing, release APKs, Git pushes, GitHub Actions dry-runs/drafts/publish, or verifying release assets.'
user-invocable: true
---

# NuvioEdge Release Delivery

Follow the repository’s release workflow; do not invent a parallel tagging or APK-publication process.

## Preflight
1. Inspect branch, remote/upstream, worktree/staged changes, recent version history, release workflow, scripts, existing target tag, and existing GitHub release.
2. Preserve unrelated changes. Verify the target version is valid, unique, and has `versionCode` greater than the previous release.
3. Read `.github/workflows/android-release.yml`, `scripts/release-metadata.sh`, release-note tooling, and the app signing configuration before changing release state.
4. Keep the version bump isolated. Stage only intended release files, inspect the staged diff, run metadata validation and relevant tests before commit/push.

## Build and Workflow
- The canonical stable workflow is manually dispatched from a branch and supports `dry-run`, `draft`, and `publish`.
- It validates the release state, generates notes, runs full-release updater tests, assembles the `fullRelease` APKs, uploads artifacts, and creates the release/tag itself. Do not manually create the tag when this workflow owns it.
- Use dry-run for metadata/note review. Prefer draft when publication level is unclear. Only use `publish` when the user explicitly authorizes public publication.
- Full release requires configured signing secrets/properties. Never substitute debug signing for production signing unless the user explicitly approves a non-production artifact.
- Do not print, commit, or expose signing credentials, `local.properties`, keystore contents, tokens, or secrets in logs.

## Verify and Report
1. Confirm validation and updater tests completed successfully.
2. Confirm all expected APKs were generated and uploaded; report exact artifact names/paths and variant.
3. Confirm the workflow completed, tag/target commit match metadata, and the GitHub release has the expected draft/published state and assets.
4. Verify pushed branch state separately from workflow/release state. A push is not a release; a local APK is not a published artifact.
5. If any check/auth/secret/build prerequisite is unavailable, stop at that boundary and state the precise blocker and next action. Never claim release completion based on a queued run or local build alone.
