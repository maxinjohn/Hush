# Hush

Personal Android fork for my own devices (phones and car head unit). Built on [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) and updated with features, fixes, and UI from Metrolist, Vivi Music, Echo Music, and other GPL clients in this space—not a one-off cherry-pick, but an ongoing merge of the best from those codebases.

**Package:** `app.hush.music` (debug: `app.hush.music.debug`)

| | |
| --- | --- |
| [Releases](https://github.com/maxinjohn/Hush/releases/latest) | APK downloads |
| [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) | Auto-updates from GitHub |
| [Issues](https://github.com/maxinjohn/Hush/issues) | Install / packaging problems on this fork |
| [Privacy](PRIVACY.md) | What the app stores and sends |

## Upstream projects

Hush integrates code and ideas from multiple open-source YouTube Music clients. Each project keeps its license and copyright notices in source; support and bug reports for shared features should go to the upstream that owns that area when possible.

| Project | Issues | Support |
| --- | --- | --- |
| [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | [Issues](https://github.com/ArchiveTuneApp/ArchiveTune/issues) | [Donation links on their repo](https://github.com/ArchiveTuneApp/ArchiveTune) |
| [Metrolist](https://github.com/metrolistgroup/metrolist) | [Issues](https://github.com/metrolistgroup/metrolist/issues) | [Donation links on their repo](https://github.com/metrolistgroup/metrolist) |
| [Vivi Music](https://github.com/vivizzz007/vivi-music) | [Issues](https://github.com/vivizzz007/vivi-music/issues) | [Donation links on their repo](https://github.com/vivizzz007/vivi-music) |
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | [Issues](https://github.com/EchoMusicApp/Echo-Music/issues) | [Donation links on their repo](https://github.com/EchoMusicApp/Echo-Music) |

Playback, lyrics, and UI bugs usually belong on whichever upstream project owns that code—not on this repo.

**This repo:** no feature PRs, no donations. Support the upstream projects above.

Other GPL dependencies (InnerTune, ViMusic, OuterTune, BetterLyrics, …) are listed under **About → Licenses** in the app.

## Download

CI publishes **universal** release APKs (all CPU architectures in one file).

### Which APK?

| File | When to use |
| --- | --- |
| `hush-foss-mobile-universal-release.apk` | **Default for most phones** — no Google proprietary libraries in the APK. Use Obtainium or GitHub for updates. |
| `hush-gms-mobile-universal-release.apk` | You use **Chromecast** or want **in-app download + install** of updates. |
| `hush-gms-tv-universal-release.apk` | **Android TV** |

Get them from **[GitHub Releases](https://github.com/maxinjohn/Hush/releases/latest)** or **[Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/)**.

### GMS vs FOSS

Same app (`app.hush.music`). Only the **bundled libraries** differ:

| | FOSS | GMS |
| --- | :---: | :---: |
| Playback, library, lyrics, settings | ✓ | ✓ |
| Google Cast (Chromecast) | — | ✓ |
| Download + install update inside the app | — | ✓ |
| Google Play Services required | **No** | **Only for Cast** — everything else runs without it |
| YouTube Music login | WebView / cookies | WebView / cookies |

- **FOSS** — no Google Cast SDK or in-app APK installer. Smaller, fully open build. Updates: open GitHub or use Obtainium.
- **GMS** — adds Cast + in-app updater. Pick this only if you need those; Play Services is not required for normal playback.

Store builds of ArchiveTune use `moe.rukamori.archivetune`; Hush is a separate install.

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

Release builds need `app/keystore/release.keystore` and passwords in `local.properties` (see `local.properties.example`). Use `scripts/build-release.sh` — it assembles and signs the APK.

```bash
bash scripts/resign-release-apk.sh --check
bash scripts/build-release.sh foss mobile arm64
```

Gradle outputs an unsigned APK when the keystore is configured; the script signs it (same as CI). `./gradlew assemble*Release` alone is not enough for sideloading.

## Legal

Independent third-party client — not affiliated with Google or YouTube. GPL-3.0; upstream copyright notices are preserved in source.
