# Hush

Personal Android fork for my own devices (phones and car head unit). Based on [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune); may cherry-pick from other GPL clients in the same space over time.

**Package:** `app.hush.music` (debug: `app.hush.music.debug`)

| | |
| --- | --- |
| [Releases](https://github.com/maxinjohn/Hush/releases/latest) | APK downloads |
| [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) | Auto-updates from GitHub |
| [Issues](https://github.com/maxinjohn/Hush/issues) | Install / packaging problems on this fork |
| [Privacy](PRIVACY.md) | What the app stores and sends |

## Upstream projects

| Project | Issues | Donate |
| --- | --- | --- |
| [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | [Issues](https://github.com/ArchiveTuneApp/ArchiveTune/issues) | [GitHub Sponsors](https://github.com/sponsors/rukamori) |
| [Metrolist](https://github.com/metrolistgroup/metrolist) | [Issues](https://github.com/metrolistgroup/metrolist/issues) | [Donation links on their repo](https://github.com/metrolistgroup/metrolist) |
| [Vivi Music](https://github.com/vivizzz007/vivi-music) | [Issues](https://github.com/vivizzz007/vivi-music/issues) | [Donation links on their repo](https://github.com/vivizzz007/vivi-music) |
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | [Issues](https://github.com/EchoMusicApp/Echo-Music/issues) | [Donation links on their repo](https://github.com/EchoMusicApp/Echo-Music) |

Playback, lyrics, and UI bugs usually belong on whichever upstream project owns that code—not on this repo.

**This repo:** no feature PRs, no donations. Support the upstream projects above.

Other GPL dependencies (InnerTune, ViMusic, OuterTune, BetterLyrics, …) are listed under **About → Licenses** in the app.

## Download

CI publishes **universal** release APKs only (all CPU architectures in one file):

| File | Use |
| --- | --- |
| `hush-foss-mobile-universal-release.apk` | Phone — no Google Play Services |
| `hush-gms-mobile-universal-release.apk` | Phone — Cast and in-app updates |
| `hush-gms-tv-universal-release.apk` | Android TV |

Get them from **[GitHub Releases](https://github.com/maxinjohn/Hush/releases/latest)** or **[Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/)**.

Store builds of ArchiveTune use package `moe.rukamori.archivetune`; Hush uses `app.hush.music` — they are separate apps.

### Installing

If update or sideload fails with **package conflicts** or **invalid package**:

1. **Backup** — Settings → Backup and restore → Backup (keep the `.backup` file).
2. **Uninstall** old Hush or ArchiveTune.
3. **Install** the new APK from [Releases](https://github.com/maxinjohn/Hush/releases/latest).
4. **Restore** from the backup file in Settings → Backup and restore.

Prefer USB or `adb install -r <apk>` over file-share apps if the APK looks corrupt after transfer.

First-time YouTube Music sync may need a VPN if YTM is not available in your region.

## Build (local)

```bash
bash scripts/build-release.sh list                    # all variants
bash scripts/build-release.sh foss mobile arm64       # typical phone build
bash scripts/build-release.sh gms mobile universal    # matches CI
bash scripts/build-release.sh gms tv universal
```

Shortcuts: `build-foss-mobile-release.sh`, `build-gms-mobile-release.sh`, `build-tv-release.sh`.

Local release builds need `app/keystore/release.keystore` plus passwords in `local.properties`, then:

```bash
bash scripts/resign-release-apk.sh --check
bash scripts/build-release.sh foss mobile arm64
```

Gradle outputs an unsigned APK when the keystore is configured; the script signs it (same as CI). `./gradlew assemble*Release` alone is not enough for sideloading.

See `local.properties.example` for `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

## Legal

Independent third-party client — not affiliated with Google or YouTube. GPL-3.0; upstream copyright notices are preserved in source.
