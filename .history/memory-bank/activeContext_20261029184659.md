# Active Context

## Current Goals

- Sprint 0 architecture review is documented in `memory-bank/architect.md` and `memory-bank/systemPatterns.md`.
- Sprint 1 is the next roadmap phase; first audit existing playback support and list unverified gaps before adding behavior.
- Preserve existing IPTV/EPG and diagnostics work in the worktree; it passes focused tests and both debug flavor builds.

## Validation Baseline

- Focused IPTV/Live TV tests: passed.
- Full `fullDebug` unit tests: 2,482 tests, 0 failures, 1 skipped (100% successful).
- `:app:assembleFullDebug :app:assemblePlaystoreDebug`: passed.
- Builds emit D8 startup-profile and existing Kotlin/Moshi/Gradle warnings; no build errors were reported.
- Device/TV playback, provider integration, and performance benchmarks have not been run in this session.

## Constraints

- Preserve `full`/`playstore` boundaries, local Media3 AARs, and stock Media3 exclusions.
- Keep profile data isolated and IPTV credentials encrypted/redacted.
- Avoid changing unrelated local modifications.
