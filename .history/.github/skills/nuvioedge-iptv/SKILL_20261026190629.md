---
name: nuvioedge-iptv
description: 'Implement, debug, or review NuvioEdge Live TV/IPTV. Use for M3U or Xtream source setup, XMLTV/EPG parsing and matching, channel groups/favorites/search, guide behavior, live playback, zapping, failover, or IPTV security and performance.'
user-invocable: true
---

# NuvioEdge IPTV

Use the existing IPTV pipeline. Read neighboring implementation and tests before changing behavior; do not create parallel models, parsers, storage, or playback flows.

## Architecture and Contracts
- Reuse models, parsers, repository, encrypted storage/cache, live UI/view-model, navigation, player, and established tests under `data/iptv`, `ui/screens/live`, and `ui/screens/player`.
- Preserve M3U and Xtream source validation and compatibility, multiple sources, groups, favorites, stable channel identities, channel numbers, logos, request headers, and source enable/disable behavior.
- Preserve XMLTV EPG URL precedence, bounded parsing, matching by guide ID with normalized name fallback, time-window/now-next behavior, stale-cache fallback, and refresh semantics.
- Keep channel selection and CH+/CH− zapping in the existing live navigation/session model; preserve in-place player switching, last-channel behavior, number entry, and bounded backup/reconnect recovery.
- Live classification must use player live-window/content-type policy. Do not infer live solely from `.m3u8`, which may also represent VOD.

## Security and Resource Boundaries
- Treat playlist and EPG URLs, stream URLs, headers, provider usernames/passwords, and raw provider responses as sensitive.
- Preserve encrypted-at-rest source/cache behavior, redirect protections, URL validation, request-size limits, and channel caps. Never log credentials, credential-bearing URLs, playlist bodies, or unsanitized provider exceptions.
- Keep network I/O and parsing off the main thread. Preserve cancellation, bounded concurrency, deduplicated loads, cache/stale-data behavior, low-heap limits, and cleanup of large guide/channel collections.
- Do not weaken a security or size limit as a performance shortcut. Explain tradeoffs before changing external compatibility or data-retention behavior.

## Procedure
1. Identify the source type, user-facing flow, affected device/resource constraints, and expected compatibility behavior.
2. Trace the path from source model/validation through fetch/cache/parse/matching, UI state, navigation, and player behavior.
3. Locate relevant parser, repository, view-model, player policy, and regression tests. State the behavior hypothesis before editing.
4. Make the smallest change in the existing boundary. Ensure cancellation and stale/partial/error states remain correct.
5. Add tests for valid/malformed input, source-specific behavior, matching/time edges, security limits, retries/failover, or UI state as relevant.
6. Run the narrowest tests, then relevant full/build checks. Report device/network/provider-dependent behavior that could not be verified.

## Acceptance Checklist
- M3U and Xtream behavior remains compatible for unaffected cases.
- XMLTV matches and now/next results are correct around programme boundaries and missing metadata.
- Refresh/cancellation preserves usable stale data where intended and never leaves stuck loading state.
- Credentials and URLs remain protected in storage, redirects, logs, diagnostics, and error UI.
- Zapping and recovery remain bounded; no player recreation or retry loop is introduced unnecessarily.
- Large sources remain within explicit memory, input-size, and concurrency limits.
