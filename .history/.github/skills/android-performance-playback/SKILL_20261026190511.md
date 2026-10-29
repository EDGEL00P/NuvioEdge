---
name: android-performance-playback
description: 'Measure and optimize Android TV performance and playback. Use for UI jank, slow startup, Compose recomposition, guide/list latency, tune or zap latency, buffering, first-frame time, dropped frames, memory pressure, or battery/network efficiency.'
user-invocable: true
---

# Android TV Performance and Playback

Never promise universally “lag-free” behavior. Establish a reproducible baseline, find the actual bottleneck, change one cause at a time, and report measured results and limits.

## Procedure
1. Capture the symptom precisely: device/TV profile, OS/API, screen or stream, cold/warm start, network/provider conditions, reproduction steps, and expected behavior.
2. Choose a relevant metric: frame/jank distribution, time-to-first-frame, tune/zap latency, buffering/rebuffer ratio, dropped frames, memory/GC, CPU, or guide input-to-render latency.
3. Reproduce before optimizing. Use Android Studio profiler, Macrobenchmark/benchmark, Compose recomposition tools, Media3/player diagnostics, system traces, or repository instrumentation/tests as available.
4. Trace expensive work and allocations. Check main-thread I/O/parsing, repeated filtering/sorting, unstable Compose keys, state churn, unnecessary player recreation, unbounded parallelism, cache misses, and retained IPTV/EPG collections.
5. Make one focused change while preserving lifecycle cancellation, playback recovery, provider constraints, input bounds, security limits, and low-memory behavior.
6. Add a focused regression test or benchmark when practical. Repeat the same scenario under comparable conditions and compare the same metric.
7. Report measured before/after values, device/setup, variance or limits, tests run, and any tradeoffs. If no device/profiler is available, state that and use the best reproducible static/test evidence instead.

## Android TV and IPTV Checks
- Verify cold and warm screen/playback paths on constrained and representative TV hardware when available.
- For guides and large channel lists, consider stable keys, visible-window/incremental rendering, derived-state memoization, bounded parsing, and releasing large collections when screens/player sessions end.
- For playback, separate app startup, tune request, manifest/segment fetch, decoder initialization, and first rendered frame; do not label all delays “buffering” without evidence.
- Keep retries/failovers bounded. Preserve live-window classification and do not trade safety/resource caps for apparent speed.
- Avoid claiming an improvement from a single run if timing noise can explain it; repeat measurements or qualify the result.

## Output
Summarize the baseline, identified bottleneck, change, measurement method/results, tests, and remaining limits. Do not claim a speedup or no-jank result without evidence.
