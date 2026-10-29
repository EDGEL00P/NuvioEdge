# NuvioEdge System Patterns

## Architectural Patterns

- Organize feature code by responsibility: `core/` infrastructure and policies, `data/` persistence/network/repositories, `domain/` contracts and models, and `ui/` navigation/screens/components.
- Keep distribution-specific implementations behind the existing `full` and `playstore` source sets and build flags. Shared code must remain buildable for both flavors.
- Keep playback lifecycle and engine-specific work in the player feature; pass typed navigation arguments and expose UI state instead of coupling screens directly to engine internals.
- Scope user settings and credentials through `ProfileManager` and `ProfileDataStoreFactory`; profile deletion must remove profile-owned local data and credentials.
- Extend the existing IPTV source → validation/fetch/cache → parser → matcher/repository → Live TV UI/player pipeline. Do not fork data models or source loading paths.

## Design and Reliability Patterns

- Put deterministic decisions (engine failover eligibility, URL safety, matching, time-window selection, route changes) in pure policy/helper functions where possible, then unit-test edge cases.
- Keep network and parsing work off the main thread; use cancellation, bounded concurrency and input sizes, and explicit stale/error states.
- Treat IPTV URLs, credentials, headers, raw provider responses, and playback diagnostics as sensitive. Redact before logs/telemetry and preserve encrypted-at-rest source storage.
- Use bounded automatic retries/recovery. Preserve the active player/session where possible instead of rebuilding it for route or channel changes.
- For TV UI, support D-pad/Back as the primary input, maintain visible stable focus, and preserve loading/empty/error states and focus restoration.
- Treat codec, HDR/Dolby Vision, audio-route, timing, memory, and performance claims as device-level acceptance checks; unit tests and APK compilation alone do not prove them.

## Test and Build Idioms

- Mirror unit-test packages under `app/src/test/java`; put Compose/device checks in `app/src/androidTest`.
- Run focused tests first, then `:app:testFullDebugUnitTest`, then assemble both `fullDebug` and `playstoreDebug` for shared changes.
- Preserve the local Nuvio Media3 AARs and stock Media3 exclusions in `app/build.gradle.kts`; consult `app/libs/README.md` before editing the playback dependency graph.
- Keep unrelated existing worktree changes intact. Do not claim a device test, provider test, or performance improvement unless it was actually run and measured.
