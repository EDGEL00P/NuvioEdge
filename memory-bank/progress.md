# Progress

## Roadmap progress

- Overall product roadmap: not yet percentage-scored; Sprints 1–6 need feature-level audits, and build success alone does not mean roadmap completion.
- Sprint 0: complete (architecture reviewed and documented; see `memory-bank/architect.md` and `memory-bank/systemPatterns.md`).
- Current focus: Sprint 1 audit and coding. Existing implementations must be verified before gaps are marked complete.
- Validation baseline: 2,482 unit tests, 0 failures, 1 skipped; both debug flavors build successfully.

## Roadmap coding checklist: Sprints 0–6

### Sprint 0 — Preparation & Research (start here)

- [x] Review the official NuvioTV repository README and the current codebase; no detailed upstream architecture manual was found.
- [x] Document playback, profiles, add-ons/plugins, and Live TV architecture.
- [x] Record technical constraints and extension points; agree on an incremental, test-first extension strategy.

### Sprint 1 — Core Playback Foundation (depends on Sprint 0)

- [ ] Implement dual-engine playback selection and multi-layer fallback/recovery.
- [ ] Improve tunneling, hardware paths, HDR/4K/8K support, and buffering behavior.
- [ ] Add playback diagnostics overlay and foundational Multi-View/PiP support.
- [ ] Validate reliability across representative devices and playback scenarios.

Sprint 1 audit snapshot (partial; not acceptance completion):

- Existing source includes ExoPlayer/libmpv selection and engine failover (`PlayerRuntimeControllerEngineFailover.kt`), bounded retry/watchdog recovery, hardware/Dolby Vision policies, configurable buffer/network controls, a live stats HUD, and Live TV Multi-View/PiP entry points.
- Parallel range/startup prefetch and next-episode source preload already exist; do not add another buffering path without measured evidence and a clear policy.
- Still unverified: device-specific codec/HDR/tunneling behavior, real TV playback reliability/performance, and whether current prefetch satisfies the roadmap's predictive-buffering expectation.
- Sprint 1 acceptance remains open until the focused device/provider checks and feature-level review are recorded.

### Sprint 2 — Advanced Audio + Display (depends on Sprint 1)

- [ ] Implement audio conversion/downmix modes and output detection/caching.
- [ ] Handle advanced audio formats, dynamic range/night mode, and per-content audio memory.
- [ ] Improve Dolby Vision profiles, frame-rate matching, anamorphic, and rotated video handling.
- [ ] Validate audio/display behavior across device connection types.

### Sprint 3 — Live TV / IPTV Core Platform (depends on Sprint 1)

- [ ] Build EPG and multiple M3U/XMLTV source merging; complete Xtream support.
- [ ] Add catch-up/restart/timeshift and overlay guide while watching.
- [ ] Complete channel/group management, recording, Live TV home presence, and unified search.
- [ ] Validate source compatibility, guide behavior, playback, and recovery.

### Sprint 4 — Automatic EPG Matching (depends on Sprint 3)

- [ ] Implement multi-stage matching, normalization, aliases, and confidence scoring.
- [ ] Add timezone handling, transparent confidence, and manual correction flow.
- [ ] Validate matching against provider/channel test corpora and correction cases.

### Sprint 5 — Sports Hub (depends on Sprints 3 and 4)

- [ ] Build Sports section with channel/event matching and rich game pages.
- [ ] Add team/league/athlete following, personalized sports, and alerts/reminders.
- [ ] Integrate spoiler-free options and Multi-View where supported.
- [ ] Validate sports data and remote-only navigation.

### Sprint 6 — Timeline, Chapters & Media Segments (depends on Sprint 1)

- [ ] Improve trickplay and chapter playback/navigation.
- [ ] Implement and validate intro, recap, and credits detection/skipping.
- [ ] Polish scene-aware navigation and playback controls.

## Done

- [x] Sprint 0 architecture review and documentation.
- [x] Focused IPTV/Live TV tests pass.
- [x] Full `fullDebug` unit tests pass: 2,482 tests, 0 failures, 1 skipped.
- [x] `:app:assembleFullDebug :app:assemblePlaystoreDebug` succeeds.

## Doing

- [ ] Audit Sprint 1 playback capabilities against the roadmap and implement the first verified gap.

## Next

- [ ] Review engine selection/failover, hardware paths, HDR formats, buffering, diagnostics, and Multi-View/PiP; separate code-supported behavior from device-only validation.
