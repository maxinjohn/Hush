<div align="center">

  <img src="https://github.com/ArchiveTuneApp/ArchiveTune/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="160" height="160" alt="ArchiveTune Logo" style="border-radius: 22%">

  <h1>Hush</h1>

  <p align="center">
    <strong>A personal fork of ArchiveTune for Android.</strong>
    <br />
    <em>Installs separately as <code>app.hush.music</code>. Upstream credits and GPL license notices are preserved.</em>
  </p>

  <p align="center">
    <a href="https://github.com/maxinjohn/Hush"><b>GitHub</b></a> •
    <a href="#features"><b>Features</b></a> •
    <a href="PRIVACY.md"><b>Privacy</b></a> •
    <a href="#build"><b>Build</b></a> •
    <a href="https://github.com/ArchiveTuneApp/ArchiveTune"><b>Upstream</b></a>
  </p>

  <div align="center">
    <img src="https://img.shields.io/github/v/release/ArchiveTuneApp/ArchiveTune?style=for-the-badge&color=6366f1&labelColor=1e1e2e&logo=github" alt="Latest Version" />
    <img src="https://img.shields.io/github/downloads/ArchiveTuneApp/ArchiveTune/total?style=for-the-badge&color=6366f1&labelColor=1e1e2e&logo=github" alt="Downloads" />
    <img src="https://img.shields.io/github/stars/ArchiveTuneApp/ArchiveTune?style=for-the-badge&color=6366f1&labelColor=1e1e2e&logo=github" alt="Stars" />
    <img src="https://img.shields.io/github/license/ArchiveTuneApp/ArchiveTune?style=for-the-badge&color=6366f1&labelColor=1e1e2e" alt="License" />
    <img src="https://img.shields.io/badge/Architecture-MVVM-6366f1?style=for-the-badge&labelColor=1e1e2e&logo=kotlin" alt="MVVM Architecture" />
    <img src="https://img.shields.io/badge/Language-Kotlin-7f52ff?style=for-the-badge&logo=kotlin&color=6366f1&labelColor=1e1e2e" alt="Kotlin Language" />
    <img src="https://img.shields.io/badge/Toolkit-Jetpack_Compose-4285f4?style=for-the-badge&logo=jetpack-compose&color=6366f1&labelColor=1e1e2e" alt="Jetpack Compose Toolkit" />
    <img src="https://img.shields.io/badge/Design-Material_3-000000?style=for-the-badge&logo=material-design&color=6366f1&labelColor=1e1e2e" alt="Material Design 3" />
    <a href="https://www.virustotal.com/gui/file/176bea37aff02a606d04ff0a61478fabdb0bd079f9e97319645452af420e5d84/detection/f-176bea37aff02a606d04ff0a61478fabdb0bd079f9e97319645452af420e5d84-1778840479" target="_blank"><img src="https://img.shields.io/badge/VirusTotal-SAFE-green?style=for-the-badge&logo=virustotal&logoColor=white&labelColor=1e1e2e&color=5865F2" alt="VirusTotal" /></a>
  </div>
  
  <br />
</div>

<hr />

**Hush** is a personal fork of [ArchiveTune](https://github.com/ArchiveTuneApp/ArchiveTune). Most documentation below still describes upstream features. See [PRIVACY.md](PRIVACY.md) for this fork.

## Build

```bash
./gradlew :app:assembleFossMobileArm64Debug --no-daemon --max-workers=2
```

APK output:

```text
app/build/outputs/apk/fossMobileArm64/debug/hush-foss-mobile-arm64-debug.apk
```

Copy `local.properties.example` to `local.properties` for optional API keys. Release builds require `app/keystore/release.keystore` and signing env vars (`STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

---

> [!IMPORTANT]  
> **Geographic Availability:** If YouTube Music is not supported in your region, a VPN or proxy set to a supported region is required for initial data fetching.

---

## ✨ Features

<div align="center">

<table>
  <tr>
    <td width="50%" valign="top">
      <div align="left">
        <h3>Playback</h3>
        <ul>
          <li>Multiple account support with quick switching</li>
          <li>Ad-free playback with background listening</li>
          <li>Your playlists, liked songs, and subscriptions appear after sign-in</li>
          <li>Support local file and local song playback</li>
          <li>Fast startup and lightweight performance</li>
          <li>Built for a private, uninterrupted listening experience</li>
        </ul>
      </div>
    </td>
    <td width="50%" valign="top">
      <div align="left">
        <h3>Audio</h3>
        <ul>
          <li>EBU R128 loudness normalization</li>
          <li>Tempo, pitch, and playback speed controls</li>
          <li>Crossfade between tracks</li>
          <li>System equalizer and spatial audio integration</li>
        </ul>
      </div>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <div align="left">
        <h3>Lyrics &amp; Discovery</h3>
        <ul>
          <li>Live synced lyrics</li>
          <li>Lyrics translation, AI Lyrics translation and romanization</li>
          <li>Music recognition for songs around you</li>
          <li>Listening statistics whenever you want them</li>
        </ul>
      </div>
    </td>
    <td width="50%" valign="top">
      <div align="left">
        <h3>Sync &amp; Social</h3>
        <ul>
          <li>Import playlist from spotify</li>
          <li>YouTube Music account integration</li>
          <li>Last.fm scrobbling</li>
          <li>ListenBrainz history sync</li>
        </ul>
      </div>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <div align="left">
        <h3>Interface</h3>
        <ul>
          <li>Material 3 design language</li>
          <li>Album-art powered dynamic colors</li>
          <li>Up to 9 different player styles</li>
          <li>Up to 8 different player background styles</li>
          <li>Responsive layouts for different screen sizes</li>
          <li>Clean browsing, player, artist, album, and lyrics views</li>
        </ul>
      </div>
    </td>
    <td width="50%" valign="top">
      <div align="left">
        <h3>Customization</h3>
        <ul>
          <li>Deep playback and interface settings</li>
          <li>Dynamic color theming options</li>
          <li>Gesture customization</li>
          <li>Animation and layout tuning</li>
          <li>Flexible controls to shape the app around your workflow</li>
        </ul>
      </div>
    </td>
  </tr>
</table>

</div>

---

## 📥 Download

<div align="center">

Installs separately as **`app.hush.music`** (debug: `app.hush.music.debug`).

| Source | Link |
| --- | --- |
| GitHub Releases | [maxinjohn/Hush releases](https://github.com/maxinjohn/Hush/releases/latest) |
| Obtainium | [Add Hush to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maxinjohn/Hush/) |

Recommended APK for most phones: `hush-foss-mobile-arm64-release.apk` (or `hush-gms-mobile-arm64-release.apk` for direct in-app updates).

</div>

> [!WARNING]  
> Only install builds from this repository unless you trust another source. Upstream ArchiveTune store listings use a different package ID.

---

### ✨ Project Contributors

<a href="https://github.com/maxinjohn/Hush/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=maxinjohn/Hush&columns=6" />
</a>

### 🛠️ Development & Engineering
Interested in building the project? Hush is built on a high-performance Kotlin stack.
<a href="CONTRIBUTING.md"><b>Read the Build & Contribution Guide →</b></a>

---

### Open-Source Acknowledgments

ArchiveTune is made possible by the work of many open-source projects and communities:

- **Metrolist** by [Mostafa Alagamy](https://github.com/mostafaalagamy/Metrolist) for the base framework.
- **SimpMusic** by [maxrave-dev](https://github.com/maxrave-dev/SimpMusic) for the lyrics API provider.
- [BetterLyrics](https://better-lyrics.boidu.dev/) for word-by-word lyrics, unison and artwork provider support.
- [Material Color Utilities](https://github.com/material-foundation/material-color-utilities)
- [Read You](https://github.com/Ashinch/ReadYou) and [Seal](https://github.com/JunkFood02/Seal) for UI component inspiration.
- Beta testers, contributors, and community members who continue to support the project.

---

## ⚖️ Legal Disclaimer

ArchiveTune is an independent third-party client.
- Not affiliated with Google LLC or YouTube.
- Does not bypass YouTube's technical protections.
- Users are encouraged to support artists by purchasing music via official channels.

