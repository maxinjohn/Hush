# Hush

Personal Android fork of [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune).

Hush exists for my own devices — Android phones and a car head unit. It is not a public product fork. I maintain it for personal use and cherry-pick fixes and features I need from upstream and sibling projects.

- **Package:** `app.hush.music` (debug: `app.hush.music.debug`)
- **Privacy:** [PRIVACY.md](PRIVACY.md)

## Contributing & donations

Please do **not** open pull requests here for general features or fixes.

Hush is a downstream fork. If you want to support development:

| Project | Role | Contribute / donate |
| --- | --- | --- |
| [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | Primary base app | [Issues](https://github.com/ArchiveTuneApp/ArchiveTune/issues) · [Sponsor](https://github.com/sponsors/rukamori) |
| [Metrolist](https://github.com/metrolistgroup/metrolist) | Playback & library features | [Issues](https://github.com/metrolistgroup/metrolist/issues) |
| [Vivi Music](https://github.com/vivizzz007/vivi-music) | Playback reliability | [Issues](https://github.com/vivizzz007/vivi-music/issues) |
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | Settings & networking | [Issues](https://github.com/EchoMusicApp/Echo-Music/issues) |

You may report issues on this repo if you use my builds. For upstream bugs or feature requests, prefer the project where the feature originated (see release notes for attribution).

## Download

| Source | Link |
| --- | --- |
| GitHub Releases | [maxinjohn/Hush releases](https://github.com/maxinjohn/Hush/releases/latest) |
| Obtainium | [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) |

Recommended for most phones: `hush-foss-mobile-universal-release.apk`  
For in-app updates: `hush-gms-mobile-universal-release.apk`

> [!WARNING]
> Install only from this repository unless you trust the source. Upstream ArchiveTune listings use a different package ID.

> [!IMPORTANT]
> If YouTube Music is not available in your region, use a VPN or proxy in a supported region for the first sync.

## Build

```bash
./gradlew :app:assembleFossMobileUniversalDebug --no-daemon --max-workers=2
```

Output: `app/build/outputs/apk/fossMobileUniversal/debug/hush-foss-mobile-universal-debug.apk`

Copy `local.properties.example` to `local.properties` for optional API keys.  
Release builds need signing secrets in CI or a local `app/keystore/release.keystore`.

## Acknowledgments

Hush is based on [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) and adapts ideas or code from:

- **[ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune)** — core app, playback pipeline, lyrics stack, extractor integration
- **[Metrolist](https://github.com/metrolistgroup/metrolist)** — music alarm, loudness normalization levels, playlist export, Android Auto settings
- **[Vivi Music](https://github.com/vivizzz007/vivi-music)** — stream URL prefetch, automatic backup
- **[Echo Music](https://github.com/EchoMusicApp/Echo-Music)** — settings search, IPv4/IPv6 network preference
- **InnerTune, ViMusic, OuterTune, BetterLyrics, and other GPL projects** in the dependency tree (see About → Licenses in the app)

GPL-3.0 license and upstream notices are preserved in the source.

## Legal

Hush is an independent third-party client, not affiliated with Google or YouTube. It does not bypass YouTube technical protections. Support artists through official channels when you can.
