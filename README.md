# Hush

**YouTube Music, but actually yours.** Personal Android player for my phone and car — built on open source, tuned for daily listening.

`app.hush.music` · [Get the APK](https://github.com/maxinjohn/Hush/releases/latest) · [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/)

| | |
| --- | --- |
| [Releases](https://github.com/maxinjohn/Hush/releases/latest) | Latest APKs |
| [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) | Auto-update from GitHub |
| [Issues](https://github.com/maxinjohn/Hush/issues) | Install / packaging on this fork |
| [Privacy](PRIVACY.md) | What gets stored and sent |
| [Changelog](CHANGELOG.md) | Per-version release notes |

## What is this?

Hush is my fork of the YouTube Music client ecosystem — not a store app, not official Google anything. It starts from [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) and pulls in the best bits from other GPL clients in this space ([Metrolist](https://github.com/metrolistgroup/metrolist), [Vivi Music](https://github.com/vivizzz007/vivi-music), [Echo Music](https://github.com/EchoMusicApp/Echo-Music), and more).

**What you get:** library sync, playback, lyrics, downloads, Android Auto, Cast (GMS build), local files, backups, podcasts, dynamic themes — the full listening stack, without the fluff I didn't want.

**What this repo is not:** a contribution hub or donation jar. Bugs in shared upstream code belong on the project that owns that feature. I maintain this for myself; you're welcome to use the builds.

## Where features came from

Honest map of what got ported from where. This table only changes when something new lands — version-by-version details live in [CHANGELOG](CHANGELOG.md) and [release notes](release_notes/).

| Source | In Hush |
| --- | --- |
| **[ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune)** | Core shell, YT login & sync, playback, queue & downloads, crossfade, tempo/pitch, Chromecast, Music Together, Last.fm / ListenBrainz, local files, backup & restore, multi-provider lyrics, podcasts, Android Auto, dynamic theme & canvas art, onboarding, stream-source picker, custom extractor, hi-res / lossless paths |
| **[Metrolist](https://github.com/metrolistgroup/metrolist)** | Wake-up **music alarms**, **loudness** presets, **playlist export** (CSV / M3U), **Android Auto** settings |
| **[Vivi Music](https://github.com/vivizzz007/vivi-music)** | Playlist **view-count prefetch**, **auto-backup before in-app update** |
| **[Echo Music](https://github.com/EchoMusicApp/Echo-Music)** | **Settings search**, **IPv4 / IPv6 / Auto** network mode |
| **[ViMusic](https://github.com/vfsfitvnm/ViMusic)** | InnerTube foundations, bottom-sheet patterns, KuGou lyrics client |
| **[OuterTune](https://github.com/OuterTune/OuterTune)** | Player carousel snap / parallax, network connectivity observer |
| **[BetterLyrics](https://github.com/boidu-dev/BetterLyrics)** | Word-synced TTML lyrics module |

Lots of small UI and playback fixes are blended across sources. Full license credits are in the app under **About → Licenses**.

## Upstream

| Project | Issues |
| --- | --- |
| [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | [Report](https://github.com/ArchiveTuneApp/ArchiveTune/issues) |
| [Metrolist](https://github.com/metrolistgroup/metrolist) | [Report](https://github.com/metrolistgroup/metrolist/issues) |
| [Vivi Music](https://github.com/vivizzz007/vivi-music) | [Report](https://github.com/vivizzz007/vivi-music/issues) |
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | [Report](https://github.com/EchoMusicApp/Echo-Music/issues) |

## Download

CI ships **universal** APKs — one file, all CPU archs.

### Pick your build

| APK | Use when |
| --- | --- |
| `hush-foss-mobile-universal-release.apk` | **Default.** No Google libs. Obtainium or GitHub for updates. |
| `hush-gms-mobile-universal-release.apk` | You want **Chromecast** or **in-app update install**. |
| `hush-gms-tv-universal-release.apk` | **Android TV** |

### FOSS vs GMS

Same app. Different bundled libs.

| | FOSS | GMS |
| --- | :---: | :---: |
| Playback, library, lyrics, settings | ✓ | ✓ |
| Chromecast | — | ✓ |
| In-app APK updater | — | ✓ |
| Play Services required | No | Only for Cast |

Store ArchiveTune uses `moe.rukamori.archivetune` — Hush is a separate install (`app.hush.music`).

### Sideload trouble?

Package conflict or invalid APK:

1. **Backup** — Settings → Backup and restore
2. **Uninstall** old Hush or ArchiveTune
3. **Install** fresh from [Releases](https://github.com/maxinjohn/Hush/releases/latest)
4. **Restore** your backup

USB / `adb install -r <apk>` beats sketchy file-share apps. First YT Music sync might need a VPN if YTM isn't in your region.

## Build locally

```bash
bash scripts/build-release.sh list                    # all variants
bash scripts/build-release.sh foss mobile arm64       # typical phone
bash scripts/build-release.sh gms mobile universal    # matches CI
bash scripts/build-release.sh gms tv universal
```

Needs `app/keystore/release.keystore` + `local.properties` (see `local.properties.example`). `./gradlew assemble*Release` alone won't sign — use `scripts/build-release.sh`.

## Legal

Unofficial third-party client. Not Google, not YouTube. GPL-3.0 — upstream copyrights stay in source.
