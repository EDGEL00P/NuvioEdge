# NuvioEdge

NuvioEdge is a fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV) for Android TV, Google TV and Fire TV. It adds built-in Live TV (IPTV).

It is not affiliated with or endorsed by the Nuvio or VesperTV projects.

## Live TV

Live TV has its own sidebar tab and supports:

- **Sources:** M3U playlists, Xtream Codes logins, or both, added from **Live TV → Sources**. Each source is checked before it's saved.
- **Channel list:** channels are grouped by the playlist's groups, plus an "All channels" list and a "Favourites" list. Hold Select on a channel to add it to Favourites.
- **TV guide:** an XMLTV guide shows what's on now and next, with a progress bar. The guide comes from the playlist's `url-tvg`, from Xtream's `xmltv.php`, or from a guide URL you enter. Plain and gzip guides both work. Channels match on `tvg-id`, or on their name if there's no ID.
- **Playback:** channels play in Nuvio's own player. **CH+ / CH−**, or Next/Previous, changes channel without leaving the player.
- **HTTP headers:** a playlist's `#EXTVLCOPT` User-Agent, Referrer and Origin lines are passed to the player.

### Encryption and privacy

- **Saved sources:** playlist URLs, Xtream usernames and passwords, and guide URLs are stored as AES-256-GCM ciphertext. The key is a non-exportable Android Keystore key, and each profile's data is tied to that profile.
- **Cached files:** downloaded playlists, Xtream channel lists and guides are cached in encrypted 64 KiB chunks (AES-256-GCM). Truncation and tampering are detected. The cache key is random and stored only after being encrypted by the Keystore key. Cache file names are HMAC-SHA256 values, so no URL or login appears on disk.
- **Network:** Live TV requests skip the shared HTTP disk cache, request logging and Sentry breadcrumbs. Sentry also removes Xtream login details from stream paths.
- **Device only:** Live TV settings aren't part of Nuvio cloud sync, and the app has backups turned off.
- **HTTPS:** your provider's https:// addresses are used when you enter them. Plain `http://` sources are labelled "Not encrypted (HTTP)", because many providers only offer HTTP.

## Build

The Live TV tests and a debug build:

```
./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.data.iptv.*'
./gradlew :app:assembleFullDebug
```

Release builds of the `full` flavour install as `com.edgeloop.nuvioedge`, so they can sit next to the official Nuvio app (debug builds keep upstream's `com.nuviodebug.com`). The in-app updater checks this repository's releases. Upstream's build signs debug builds with the release key, so set `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD` and `NUVIO_RELEASE_STORE_PASSWORD` before building.

See the [upstream NuvioTV README](https://github.com/NuvioMedia/NuvioTV#readme) for general build setup, such as `local.properties`.

## Licence and credits

GPL-3.0, inherited from both upstream projects. See [LICENSE](LICENSE).

- [NuvioTV](https://github.com/NuvioMedia/NuvioTV) by NuvioMedia: the base app.
- [VesperTV](https://github.com/NoXqc/VesperTV) by NoXqc: the M3U, Xtream and XMLTV handling was ported from its Dart code to Kotlin. The VesperTV name and logo are not used.
