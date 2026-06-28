# Hush

## Music for your mood.

Android YouTube Music player for my phone and car. Unofficial. Sideloaded. Zero corporate energy — just playback that actually slaps.

Instead of hopping between a pile of forks, I combined the best from the whole open-source stack: **[ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune)** as the base, then pulled in the good stuff from **[Metrolist](https://github.com/metrolistgroup/metrolist)**, **[Vivi Music](https://github.com/vivizzz007/vivi-music)**, **[Echo Music](https://github.com/EchoMusicApp/Echo-Music)**, and the shared libs behind **[ViMusic](https://github.com/vfsfitvnm/ViMusic)**, **[OuterTune](https://github.com/OuterTune/OuterTune)**, and **[BetterLyrics](https://github.com/boidu-dev/BetterLyrics)**. One app. Most of the features. No fork roulette.

**Package:** `app.hush.music` · debug: `app.hush.music.debug`

| | |
| --- | --- |
| [Releases](https://github.com/maxinjohn/Hush/releases/latest) | Fresh APKs |
| [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) | Auto-updates, no cap |
| [Issues](https://github.com/maxinjohn/Hush/issues) | Broken install on *this* fork |
| [Privacy](PRIVACY.md) | What the app keeps & sends |
| [Changelog](CHANGELOG.md) | What shipped each version |

---

## So what is Hush?

Personal fork. I build it for me. You're welcome to the APKs.

Think: YT library sync, queue, downloads, lyrics (multi-provider), Android Auto, Cast on GMS builds, local files, backups, podcasts, dynamic themes, canvas art, crossfade, tempo/pitch — the full session, not a demo.

Not a PR factory. Not taking donations. If something's broken in code that came from Metrolist or ArchiveTune, hit *their* issues when you can. **Hush-specific bugs** — weird on *this* build, this fork, your install — [open an issue here](https://github.com/maxinjohn/Hush/issues); I'll see what I can do.

---

## Loot table — who donated what

Real talk on what got ported from where. This table only moves when I add something new. Patch notes for each version → [Changelog](CHANGELOG.md).

| Source | What landed in Hush |
| --- | --- |
| **[ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune)** | Core app, YT login & sync, playback engine, queue & downloads, crossfade, tempo/pitch, Chromecast, Music Together, Last.fm / ListenBrainz, local files, backup & restore, multi-provider lyrics, podcasts, Android Auto, dynamic theme & canvas art, onboarding, stream-source picker, custom extractor, hi-res / lossless |
| **[Metrolist](https://github.com/metrolistgroup/metrolist)** | Wake-up **music alarms**, **loudness** presets, **playlist export** (CSV / M3U), **Android Auto** settings |
| **[Vivi Music](https://github.com/vivizzz007/vivi-music)** | Playlist **view-count prefetch**, **auto-backup before in-app update** |
| **[Echo Music](https://github.com/EchoMusicApp/Echo-Music)** | **Settings search**, **IPv4 / IPv6 / Auto** network mode |
| **[ViMusic](https://github.com/vfsfitvnm/ViMusic)** | InnerTube foundations, bottom-sheet UI patterns, KuGou lyrics client |
| **[OuterTune](https://github.com/OuterTune/OuterTune)** | Player carousel snap / parallax, network connectivity observer |
| **[BetterLyrics](https://github.com/boidu-dev/BetterLyrics)** | Word-synced TTML lyrics module |

Tiny fixes and UI polish are mixed in from everywhere. Full license wall → **About → Licenses** in the app.

---

## Shoutout the upstream homies

| Project | Bugs go here |
| --- | --- |
| [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | [Issues](https://github.com/ArchiveTuneApp/ArchiveTune/issues) |
| [Metrolist](https://github.com/metrolistgroup/metrolist) | [Issues](https://github.com/metrolistgroup/metrolist/issues) |
| [Vivi Music](https://github.com/vivizzz007/vivi-music) | [Issues](https://github.com/vivizzz007/vivi-music/issues) |
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | [Issues](https://github.com/EchoMusicApp/Echo-Music/issues) |

---

## Get the app

CI drops **universal** APKs — one file, every CPU arch, no guessing.

| APK | When |
| --- | --- |
| `hush-foss-mobile-universal-release.apk` | **Default.** Clean build, no Google libs. GitHub or Obtainium for updates. |
| `hush-gms-mobile-universal-release.apk` | You need **Chromecast** or **tap-to-update** inside the app. |
| `hush-gms-tv-universal-release.apk` | **Android TV** couch mode. |

### FOSS vs GMS — 10 sec version

Same Hush. Different sauce in the APK.

| | FOSS | GMS |
| --- | :---: | :---: |
| Play music, sync library, lyrics, settings | ✓ | ✓ |
| Chromecast | — | ✓ |
| In-app updater | — | ✓ |
| Google Play Services | **Nah** | **Only for Cast** |

Play Store ArchiveTune = `moe.rukamori.archivetune`. Hush = `app.hush.music`. Different package. Both can vibe on one phone if you're into that.

### Install said "nah"?

Package conflict / invalid APK — the usual sideload ritual:

1. **Backup** → Settings → Backup and restore  
2. **Yeet** the old Hush or ArchiveTune install  
3. **Install** from [Releases](https://github.com/maxinjohn/Hush/releases/latest)  
4. **Restore** your backup  

`adb install -r your.apk` > random WhatsApp file forward. First YT Music sync might need VPN if YTM's blocked where you live.

---

## Build it yourself

```bash
bash scripts/build-release.sh list                    # see everything
bash scripts/build-release.sh foss mobile arm64       # phone FOSS
bash scripts/build-release.sh gms mobile universal    # what CI ships
bash scripts/build-release.sh gms tv universal        # TV
```

You'll want `app/keystore/release.keystore` + secrets in `local.properties` (`local.properties.example` has the template). Raw `./gradlew assemble*Release` gives you unsigned — `scripts/build-release.sh` signs it proper.

---

## Legal (sorry, required)

Unofficial third-party client. Not Google. Not YouTube. GPL-3.0. Upstream copyrights live in source where they belong.
