# Hush

Personal Android fork of [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune).

- **Package:** `app.hush.music` (debug: `app.hush.music.debug`)
- **Privacy:** [PRIVACY.md](PRIVACY.md)
- **Contributing:** [CONTRIBUTING.md](CONTRIBUTING.md)

> [!IMPORTANT]
> If YouTube Music is not available in your region, use a VPN or proxy in a supported region for the first sync.

## Download

| Source | Link |
| --- | --- |
| GitHub Releases | [maxinjohn/Hush releases](https://github.com/maxinjohn/Hush/releases/latest) |
| Obtainium | [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) |

Recommended for most phones: `hush-foss-mobile-arm64-release.apk`  
For in-app updates: `hush-gms-mobile-arm64-release.apk`

> [!WARNING]
> Install only from this repository unless you trust the source. Upstream ArchiveTune listings use a different package ID.

## Build

```bash
./gradlew :app:assembleFossMobileArm64Debug --no-daemon --max-workers=2
```

Output: `app/build/outputs/apk/fossMobileArm64/debug/hush-foss-mobile-arm64-debug.apk`

Copy `local.properties.example` to `local.properties` for optional API keys.  
Release builds need `app/keystore/release.keystore` and signing env vars (`STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## Features

**Playback**
- Multiple accounts, background listening, local files
- Playlists, liked songs, and subscriptions after sign-in

**Audio**
- Loudness normalization, tempo/pitch/speed, crossfade, system EQ

**Lyrics & discovery**
- Synced lyrics, translation, romanization, music recognition, listening stats

**Integrations**
- YouTube Music, Spotify playlist import, Last.fm, ListenBrainz

**Interface**
- Material 3, dynamic colors, multiple player layouts and backgrounds

**Customization**
- Deep settings for playback, gestures, animations, and themes

## Acknowledgments

Based on [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) and upstream projects including Metrolist, SimpMusic, and BetterLyrics. GPL-3.0 license and upstream notices are preserved in the source.

## Legal

Hush is an independent third-party client, not affiliated with Google or YouTube. It does not bypass YouTube technical protections. Support artists through official channels when you can.
