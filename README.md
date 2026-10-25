# NuvioEdge

NuvioEdge is a fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV) for Android TV, Google TV and Fire TV. It adds Live TV (IPTV), multiview, a TV guide grid and live sports scores.

It is not affiliated with or endorsed by the Nuvio or VesperTV projects.

## Live TV

Live TV has its own sidebar tab.

- **Sources:** M3U playlists and Xtream Codes logins, added from **Live TV → Sources**. Each source is checked before it is saved; Cancel or Back stops the check.
- **Channels:** grouped by the playlist's groups, plus "All channels" and "Favourites". Hold Select on a channel to add it to Favourites.
- **Guide:** XMLTV from the playlist's `url-tvg`, Xtream's `xmltv.php`, or a URL you enter (plain or gzip). The channel list shows now and next; **Guide** switches to a timeline of three hours at a time. Channels match on `tvg-id`, or on their name.
- **Changing channel:** while watching, Up/Down, CH+/CH−, Page Up/Down or Next/Previous change channel inside the running player. Typing a number jumps to that channel (the playlist's numbers, or the channel's position when it has none). Last Channel flips back.
- **Recovery:** when a stream fails or stalls for 15 seconds, the player switches to another copy of the same channel if there is one, otherwise reconnects, at most 3 times in 5 minutes.
- **Multiview:** 1, 2, 4 or 6 channels at once. Only the highlighted tile plays sound. The maximum is the lowest of: the TV's memory (2 tiles under 1.5 GB or on low-RAM devices, 4 under 2.5 GB, otherwise 6), what its video decoders declare, and your Xtream account's connection limit. If the TV runs out of decoders or keeps dropping frames, the layout steps down. Tiles stop when you leave the app.
- **Search:** channel names and programme titles that haven't finished.
- **HTTP headers:** a playlist's `#EXTVLCOPT` User-Agent, Referrer and Origin are sent with the stream.
- **Limits:** up to 250,000 channels per source (50,000 on devices with a small app memory limit). Films and series in Xtream `m3u_plus` playlists are skipped. RTMP streams aren't supported by the bundled player.

## Sports

- **Ticker:** a strip along the bottom of every screen flips through live, upcoming and recent games across 44 leagues: American football, basketball, baseball, hockey, soccer (EPL, LaLiga, Serie A, Bundesliga, Ligue 1, UCL, UEL, MLS, A-League, World Cup), AFL, NRL, rugby union, tennis, golf, F1, IndyCar, NASCAR, UFC, PFL, lacrosse and college volleyball. Start times are shown in the TV's time zone. Odds (line, over/under, moneyline) appear where the feed includes them.
- **Scores:** **Live TV → Scores** lists today's games. For team sports it shows the box score, standings, injuries and headlines, and **On your channels** lists channels in your guide that are showing the game. The ticker's on/off switch is there too.
- **Data:** ESPN's public scoreboard and summary feeds. They need no key but are unofficial and can change without notice. Each league is refreshed every minute while it has a live game, every 5 minutes before a start, otherwise every 30 minutes. Cricket and boxing aren't available from these feeds.

## Security and privacy

- **Saved sources:** playlist URLs, Xtream logins and guide URLs are stored encrypted (AES-256-GCM) with a non-exportable Android Keystore key, tied to the profile. If a device's Keystore doesn't work, an app-private key in no-backup storage is used instead; that key is not hardware-protected.
- **Cache:** downloaded playlists and guides are stored encrypted in 64 KiB chunks (AES-256-GCM), with tampering and truncation detected. File names are HMAC-SHA256 values.
- **Network:** Live TV skips the shared HTTP disk cache, request logging and Sentry breadcrumbs. Redirects from https to http are refused. The player never retries an IPTV server without certificate checks. Crash reports remove Xtream logins from URLs, paths and error messages, and the player's saved playback diagnostics keep only a redacted URL for live channels.
- **Device only:** Live TV settings aren't part of Nuvio cloud sync, and the app opts out of cloud backup and device-to-device transfer.
- **HTTP sources** are labelled "HTTP address (not encrypted)". Many providers only offer HTTP; the login then travels unencrypted.

## Build

```
./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.data.iptv.*' --tests 'com.nuvio.tv.data.sports.*' --tests 'com.nuvio.tv.ui.screens.live.*'
./gradlew :app:assembleFullDebug
```

Upstream's build signs debug builds with the release configuration, so set `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD` and `NUVIO_RELEASE_STORE_PASSWORD` first (any throwaway keystore works for debug). Release builds of the `full` flavour install as `com.edgeloop.nuvioedge`, next to the official Nuvio app. See the [upstream README](https://github.com/NuvioMedia/NuvioTV#readme) for `local.properties`.

**CI:** `.github/workflows/nuvioedge-ci.yml` runs the NuvioEdge tests and builds debug APKs on every push. The full upstream suite also runs but doesn't block, because two upstream Trakt tests fail on unmodified NuvioTV. To build a signed release, add `NUVIOEDGE_KEYSTORE_BASE64`, `NUVIOEDGE_KEY_ALIAS`, `NUVIOEDGE_KEY_PASSWORD` and `NUVIOEDGE_STORE_PASSWORD` as repository secrets, then run the workflow manually with **release** ticked.

## Licence and credits

GPL-3.0, inherited from both upstream projects. See [LICENSE](LICENSE) and [MODIFICATIONS.md](MODIFICATIONS.md).

- [NuvioTV](https://github.com/NuvioMedia/NuvioTV) by NuvioMedia: the base app.
- [VesperTV](https://github.com/NoXqc/VesperTV) by NoXqc: the M3U, Xtream and XMLTV handling was ported from its Dart code to Kotlin. The VesperTV name and logo are not used.
