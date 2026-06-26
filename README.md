# Hush

Personal Android fork of [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune).

Hush exists for my own devices — Android phones and a car head unit. It is not a public product fork. I maintain it for personal use and cherry-pick fixes I need.

- **Package:** `app.hush.music` (debug: `app.hush.music.debug`)
- **Privacy:** [PRIVACY.md](PRIVACY.md)

## Contributing

Please do **not** open pull requests here for general features or fixes.

- **Code contributions:** [ArchiveTuneApp/ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune)
- **Donations / sponsorship:** upstream ArchiveTune maintainers, not this repo

You may report issues here if you use my builds, and I will look when I can. For major bugs or feature requests, please use [upstream ArchiveTune issues](https://github.com/ArchiveTuneApp/ArchiveTune/issues) — I merge useful upstream changes back into Hush over time.

## Download

| Source | Link |
| --- | --- |
| GitHub Releases | [maxinjohn/Hush releases](https://github.com/maxinjohn/Hush/releases/latest) |
| Obtainium | [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) |

Recommended for most phones: `hush-foss-mobile-arm64-release.apk`  
For in-app updates: `hush-gms-mobile-arm64-release.apk`

> [!WARNING]
> Install only from this repository unless you trust the source. Upstream ArchiveTune listings use a different package ID.

> [!IMPORTANT]
> If YouTube Music is not available in your region, use a VPN or proxy in a supported region for the first sync.

## Build

```bash
./gradlew :app:assembleFossMobileArm64Debug --no-daemon --max-workers=2
```

Output: `app/build/outputs/apk/fossMobileArm64/debug/hush-foss-mobile-arm64-debug.apk`

Copy `local.properties.example` to `local.properties` for optional API keys.  
Release builds need signing secrets in CI or a local `app/keystore/release.keystore`.

## Acknowledgments

Based on [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) and upstream projects including Metrolist, InnerTune, and BetterLyrics. GPL-3.0 license and upstream notices are preserved in the source.

## Legal

Hush is an independent third-party client, not affiliated with Google or YouTube. It does not bypass YouTube technical protections. Support artists through official channels when you can.
