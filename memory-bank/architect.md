# NuvioEdge: System Architecture

## Overview

NuvioEdge is an Android TV Kotlin application derived from NuvioTV. Shared application code is under `app/src/main/java/com/nuvio/tv`; distribution-specific behavior remains in the `full` and `playstore` source sets and build flavors. The upstream README describes the Kotlin, Jetpack Compose, TV Material 3, and Media3 foundation. The local codebase adds the NuvioEdge playback integrations, profile data isolation, plugins, and Live TV/IPTV subsystem.

## Component Boundaries

- `core/`: application services, dependency injection, profiles, plugin/runtime infrastructure, sync, diagnostics, and shared policies.
- `data/`: local persistence, remote clients, provider integrations, and repository implementations.
- `domain/`: content models, repository contracts, and pure business rules.
- `ui/navigation/`: route definitions and destination wiring.
- `ui/screens/`: feature-owned Compose screens and ViewModels; `ui/components/` contains reusable UI.
- Unit tests mirror feature packages under `app/src/test/java`; Android UI/instrumentation tests live under `app/src/androidTest`.

## Playback

- Playback is owned by the player feature in `ui/screens/player/`, with navigation arguments and UI state kept separate from runtime controller responsibilities.
- The internal engine setting supports ExoPlayer, libmpv, and automatic selection. The player runtime can switch engines on configured errors; engine-specific events and recovery remain in the player subsystem.
- Player settings are persisted by `data/local/PlayerSettingsDataStore.kt` and are profile-scoped through `ProfileDataStoreFactory`.
- Playback diagnostics, stream/network policies, chapters, frame-rate handling, subtitle processing, and skip-segment behavior live in the player/core boundaries and have focused unit tests.
- Local Nuvio Media3 AARs replace stock Media3 ExoPlayer/common/datasource/HLS/extractor modules. Preserve the exclusions and local AAR set in `app/build.gradle.kts`; read `app/libs/README.md` before changing decoder/player artifacts.

## Profiles and Add-ons

- `core/profile/ProfileManager.kt` owns active-profile state and profile lifecycle, while `data/local/ProfileDataStoreFactory.kt` provides profile-specific stores.
- Profile deletion clears scoped credentials, preference stores, plugin code, and profile-specific Continue Watching caches. Preserve that cleanup and profile isolation when adding persistence.
- Add-on contracts are in `domain/repository/AddonRepository.kt`; add-on UI/navigation and data implementations remain in their feature packages.
- Plugins/runtime integration is a `full`-flavor capability. The Play Store flavor disables plugins and selected integrations through `BuildConfig` feature flags. Keep policy enforcement and source-set boundaries rather than exposing full-only behavior through shared code.

## Live TV / IPTV

- IPTV models, source storage, safety rules, playlist/Xtream clients, XMLTV parser, matcher, and repository are grouped under `data/iptv/`.
- `IptvRepository` coordinates bounded source fetches, cache/stale-data behavior, channel loading, guide matching, and playback URLs. `IptvSourcesStore` stores profile-specific provider data encrypted; URLs, credentials, and raw provider responses are sensitive.
- Live TV UI and ViewModels live under `ui/screens/live/`; navigation and in-player channel switching integrate with `ui/navigation/` and the existing player session.
- Keep validation, redirects, request/response bounds, channel caps, cancellation, stale-cache semantics, and existing M3U/Xtream/XMLTV compatibility intact. Extend these boundaries rather than creating parallel pipelines.
- EPG mapping persistence has a compatibility/migration boundary in `IptvSourcesStore`; model-only compatibility fields must not bypass encrypted storage or migration tests.

## Build and Platform Constraints

- `app/build.gradle.kts` targets `minSdk 24`, `compileSdk/targetSdk 36`, Java/Kotlin JVM 11, and separate `full` and `playstore` distribution flavors.
- Keep local properties, credentials, signing files, and authenticated stream URLs private. Optional native Dolby Vision configuration is property-gated.
- Do not alter native/player dependencies, ABI packaging, or stock Media3 exclusions without checking the AAR integration notes and validating both flavors.
- Large product/architecture changes require an approved feature request before a PR, per `CONTRIBUTING.md`.

## Roadmap Extension Strategy

Extend the established feature boundary and its mirrored tests. Prefer pure policy helpers for matching, fallback, and eligibility decisions; keep I/O in repositories/controllers; keep UI state and navigation in the existing Compose/ViewModel layer. Implement roadmap items as small, independently testable increments, preserve both flavors, and validate focused tests before full tests/builds. Treat hardware codec, HDR/Dolby Vision, output-device, provider, and performance claims as device/provider validation requirements—not facts established by compilation alone.

Roadmap status and sprint acceptance are tracked in `memory-bank/progress.md`.

