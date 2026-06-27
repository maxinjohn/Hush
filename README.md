# Hush

Personal Android fork for my own devices — phones and a car head unit. **Not a public product fork.** I maintain it for personal use and cherry-pick fixes and features from the open-source clients below.

## Parent projects

Hush is based on **[ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune)** and adapts code or ideas from these sibling projects:

| Project | Repository | Adapted in Hush |
| --- | --- | --- |
| **[ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune)** | [ArchiveTuneApp/ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune) | Core app, playback pipeline, lyrics stack, YouLyPlus lyrics, download progress, backup/restore hardening, HTTP timeouts, extractor integration |
| **[Metrolist](https://github.com/metrolistgroup/metrolist)** | [metrolistgroup/metrolist](https://github.com/metrolistgroup/metrolist) | Music alarm, loudness normalization levels, playlist export (CSV/M3U), Android Auto settings |
| **[Vivi Music](https://github.com/vivizzz007/vivi-music)** | [vivizzz007/vivi-music](https://github.com/vivizzz007/vivi-music) | Next-track stream URL prefetch, automatic backup |
| **[Echo Music](https://github.com/EchoMusicApp/Echo-Music)** | [EchoMusicApp/Echo-Music](https://github.com/EchoMusicApp/Echo-Music) | Settings search, IPv4/IPv6 network preference |

Hush also depends on **InnerTune, ViMusic, OuterTune, BetterLyrics**, and other GPL-licensed libraries in the dependency tree (see **About → Licenses** in the app).

- **Package:** `app.hush.music` (debug: `app.hush.music.debug`)
- **Privacy:** [PRIVACY.md](PRIVACY.md)

## Contributing & donations

Please **do not**:

- open pull requests on **this repo** for general features or fixes
- donate or sponsor **this repo** — Hush is personal maintenance only

Support belongs with the projects that wrote the code:

| Project | Send code / bug reports | Donate / sponsor |
| --- | --- | --- |
| **ArchiveTune** | [Issues](https://github.com/ArchiveTuneApp/ArchiveTune/issues) | [GitHub Sponsors (rukamori)](https://github.com/sponsors/rukamori) |
| **Metrolist** | [Issues](https://github.com/metrolistgroup/metrolist/issues) | via that project’s repo |
| **Vivi Music** | [Issues](https://github.com/vivizzz007/vivi-music/issues) | via that project’s repo |
| **Echo Music** | [Issues](https://github.com/EchoMusicApp/Echo-Music/issues) | via that project’s repo |

**Where to report issues**

- **Hush builds** (install conflicts, wrong APK, this fork’s packaging): [maxinjohn/Hush issues](https://github.com/maxinjohn/Hush/issues)
- **Upstream features** (playback, lyrics, alarms, backup, etc.): open an issue on the **parent project** that owns that feature (see table above or release notes)

Release notes include an **Upstream & adapted features** table for each version.

## Download (GitHub — universal only)

CI builds and publishes **universal** APKs only (all CPU architectures in one file):

| Artifact | Filename |
| --- | --- |
| GMS mobile (in-app updates) | `hush-gms-mobile-universal-release.apk` |
| FOSS mobile | `hush-foss-mobile-universal-release.apk` |
| GMS TV | `hush-gms-tv-universal-release.apk` |

| Source | Link |
| --- | --- |
| GitHub Releases | [maxinjohn/Hush releases](https://github.com/maxinjohn/Hush/releases/latest) |
| Obtainium | [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) |

> [!WARNING]
> Install only from this repository unless you trust the source. Upstream ArchiveTune store listings use package ID `moe.rukamori.archivetune`, not `app.hush.music`.

> [!IMPORTANT]
> If YouTube Music is not available in your region, use a VPN or proxy in a supported region for the first sync.

> [!NOTE]
> If install fails with “package conflicts with an existing package”, uninstall any old **Hush** (`app.hush.music`) or **ArchiveTune** (`moe.rukamori.archivetune`) build first, then install again. Android blocks updates when the signing key does not match.

> [!IMPORTANT]
> **Clean install for this version (13.8.x)** — If an in-place update fails with “package conflicts” or “invalid package”, you need a **clean install**:
>
> 1. **Backup first** — In the app: **Settings → Backup and restore → Backup** (save the `.backup` file somewhere safe).
> 2. **Uninstall** the old Hush or ArchiveTune app completely.
> 3. **Install** the new APK from this repo.
> 4. **Restore** — Open Hush → **Settings → Backup and restore → Restore** and pick your backup file.
>
> Your library, settings, and account data come back from the backup. YouTube login may need a quick re-sign-in after restore depending on what was backed up.

## Build (local)

**One script for every release variant** (FOSS/GMS × mobile/TV × all ABIs). Gradle assembles unsigned APKs; the script re-signs them (same as CI).

```bash
bash scripts/build-release.sh list          # show all 20 variants
bash scripts/build-release.sh foss mobile arm64
bash scripts/build-release.sh gms tv universal
bash scripts/build-release.sh foss mobile all    # all 5 ABIs for FOSS mobile
bash scripts/build-release.sh mobile-all gms       # all GMS mobile ABIs
bash scripts/build-release.sh tv-all gms           # all GMS TV ABIs
bash scripts/build-release.sh all                  # everything (slow)
```

### Variants (distribution × device × ABI)

| | universal | arm64 | armeabi (32-bit ARM) | x86 (32-bit) | x86_64 |
|---|:---:|:---:|:---:|:---:|:---:|
| **foss mobile** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **gms mobile** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **foss tv** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **gms tv** | ✓ | ✓ | ✓ | ✓ | ✓ |

APK names: `hush-{foss|gms}-{mobile|tv}-{abi}-release.apk`  
Example: `hush-gms-tv-universal-release.apk`

### Shortcuts

| Goal | Command |
| --- | --- |
| Phone, FOSS, 64-bit | `bash scripts/build-foss-mobile-release.sh arm64` |
| Phone, FOSS, all CPUs (GitHub CI) | `bash scripts/build-foss-mobile-release.sh universal` |
| Phone, GMS + Cast | `bash scripts/build-gms-mobile-release.sh universal` |
| Android TV (GitHub CI) | `bash scripts/build-tv-release.sh` |
| Emulator / x86 PC | `bash scripts/build-release.sh foss mobile x86_64` |

### Manual Gradle + sign

```bash
./gradlew :app:assembleFossMobileArm64Release --no-daemon --max-workers=2
bash scripts/resign-release-apk.sh app/build/outputs/apk/fossMobileArm64/release/hush-foss-mobile-arm64-release.apk
```

### Release signing (required when using `local.properties`)

When `app/keystore/release.keystore` and passwords are in `local.properties`, Gradle outputs an **unsigned** release APK (same as CI before the sign step). You **must** run the resign script — `./gradlew assemble*Release` alone will produce an uninstallable APK (“package appears to be invalid”).

This is **not** caused by `local.properties` itself; it is the intended two-step flow (assemble → sign), matching GitHub Actions.

You need:

1. `app/keystore/release.keystore`
2. In `local.properties`:

```properties
STORE_PASSWORD=your_store_password
KEY_ALIAS=hush
KEY_PASSWORD=your_key_password
```

`KEYSTORE_PASSWORD` also works instead of `STORE_PASSWORD`.

Validate credentials:

```bash
bash scripts/resign-release-apk.sh --check
```

**Before installing:** uninstall any old Hush (`app.hush.music`) or ArchiveTune (`moe.rukamori.archivetune`) build if you get package conflicts. **Back up first** (Settings → Backup and restore), then restore after a clean install — see the note at the top of this README.

**If install still fails:** copy via USB or `adb install -r <apk>` instead of LocalSend.

## Legal

Hush is an independent third-party client, not affiliated with Google or YouTube. It does not bypass YouTube technical protections. GPL-3.0 license and upstream copyright notices are preserved in the source. Support artists through official channels when you can.
