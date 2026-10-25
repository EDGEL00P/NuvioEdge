# Modifications

NuvioEdge is a modified version of [NuvioTV](https://github.com/NuvioMedia/NuvioTV) (GPL-3.0),
imported at commit `d5a8a69`. All changes below were made in October 2026.

The M3U, Xtream and XMLTV code under `app/src/main/java/com/nuvio/tv/data/iptv/` is ported from
[VesperTV](https://github.com/NoXqc/VesperTV) by NoXqc (GPL-3.0); each ported file says so at the top.

## Changed NuvioTV files

- `README.md`: NuvioEdge documentation
- `app/build.gradle.kts`: Application id, update repository, version
- `app/src/debug/res/values/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/AndroidManifest.xml`: Backup and device-transfer rules
- `app/src/main/java/com/nuvio/tv/MainActivity.kt`: Live TV sidebar entry; ticker and channel-number overlays; ambient mode off during multiview
- `app/src/main/java/com/nuvio/tv/core/diagnostics/SentryNetworkBreadcrumbInterceptor.kt`: Removes IPTV logins from crash-report URLs, paths and messages
- `app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt`: Live TV and multiview destinations; in-player channel changes
- `app/src/main/java/com/nuvio/tv/ui/navigation/Screen.kt`: Live TV, multiview routes
- `app/src/main/java/com/nuvio/tv/ui/screens/collection/CollectionManagementScreen.kt`: Fix crash exporting collections on Android 7-9
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`: No certificate-check bypass or https-to-http redirect for IPTV servers
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`: Saved playback diagnostics redact IPTV logins
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`: Live channel switch handling
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt`: Live TV keys (channel up/down, numbers, last channel); stall recovery; in-player channel switch
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerUiState.kt`: Live channel switch event
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/LicensesAttributionsScreen.kt`: VesperTV credit
- `app/src/main/res/drawable/ic_launcher.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-hdpi/ic_launcher.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-hdpi/ic_launcher_arctic_blue.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-hdpi/ic_launcher_copper.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-hdpi/ic_launcher_emerald.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-hdpi/ic_launcher_graphite.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-hdpi/ic_launcher_rose_gold.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-mdpi/ic_launcher.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-mdpi/ic_launcher_arctic_blue.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-mdpi/ic_launcher_copper.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-mdpi/ic_launcher_emerald.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-mdpi/ic_launcher_graphite.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-mdpi/ic_launcher_rose_gold.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/banner.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/banner_arctic_blue.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/banner_copper.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/banner_emerald.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/banner_graphite.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/banner_rose_gold.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/ic_launcher.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/ic_launcher_arctic_blue.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/ic_launcher_copper.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/ic_launcher_emerald.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/ic_launcher_graphite.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xhdpi/ic_launcher_rose_gold.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxhdpi/ic_launcher_arctic_blue.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxhdpi/ic_launcher_copper.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxhdpi/ic_launcher_emerald.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxhdpi/ic_launcher_graphite.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxhdpi/ic_launcher_rose_gold.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher_arctic_blue.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher_copper.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher_emerald.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher_graphite.png`: NuvioEdge artwork
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher_rose_gold.png`: NuvioEdge artwork
- `app/src/main/res/values-ar/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-b+es+419/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-b+sr+Latn/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-bg/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-bn/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-bs/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-cs/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-da/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-de/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-el/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-es/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-fr/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-hi/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-hr/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-hu/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-in/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-it/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-iw/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-ja/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-lt/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-nl/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-no/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-pl/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-pt-rBR/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-pt-rPT/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-ro/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-ru/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-sk/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-sl/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-sq/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-sv/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-ta/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-tr/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-uk/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-vi/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-zh-rCN/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values-zh-rTW/strings.xml`: Live TV, multiview, guide, sports text; app name
- `app/src/main/res/values/strings.xml`: Live TV, multiview, guide, sports text; app name
- `release_test_cases.csv`: Live TV and sports test cases

## Removed NuvioTV files

- `.github/workflows/close-stale-issues.yml`
- `.github/workflows/close-unlabeled-issues.yml`
- `.github/workflows/pr-template-check.yml`
- `.github/workflows/stale-needs-info.yml`
- `.github/workflows/triage-needs-info.yml`

## New files

43 files are new in NuvioEdge, mainly under `data/iptv`, `data/sports`,
`ui/screens/live`, `ui/components/ticker` and their tests.
