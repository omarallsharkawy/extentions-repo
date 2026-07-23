# Omar Extensions

A maintained **Aniyomi-compatible anime extension repository** for AR, EN, and language-independent sources. Extension API: **14.x**.

## Add the repository

### One tap on Android

[Open the repository in Aniyomi / Tadami](aniyomi://add-repo?url=https%3A%2F%2Fraw.githubusercontent.com%2Fomarallsharkawy%2Fextentions-repo%2Fmain%2Findex.min.json)

The link opens the app's own confirmation screen. It does not silently install extensions, grant trust, or bypass Android permission prompts.

### Manual URL

Copy this URL into the app's **Anime extension repositories** setting:

```text
https://raw.githubusercontent.com/omarallsharkawy/extentions-repo/main/index.min.json
```

- Aniyomi: **Settings → Browse → Anime extension repos**
- Tadami: **More → Settings → Browse → Extension Stores (Anime)**

Then open **Browse → Anime Extensions**, install the source you want, and approve the Android installation prompt.

## Browse before installing

The repository includes a searchable, mobile-first install page in [`index.html`](./index.html). When GitHub Pages or another static HTTPS host is enabled, it provides the same app link, a copyable fallback URL, filters, extension metadata, and direct APK downloads.

## Signing and updates

Published APKs use this signing-key fingerprint:

```text
SHA-256: 84300648046b4e4d24e940d892207fc94d6c723c120fddb5450b222c4e8d3a4d
```

Android cannot update an extension signed by a different key. If an existing extension appears as **Local** or reports a signature conflict, uninstall that APK once and reinstall it from this repository.

## Repository layout

```text
index.json       Human-readable extension index
index.min.json   Index URL added to the app
repo.json        Repository identity and signing fingerprint
apk/*.apk        The only published APK location
icon/*.png       Package icons used by the app and catalog
src/             Extension source code maintained in this repository
index.html       Static install and download page
styles.css       Install-page styles
```

Root-level APK copies and nested `apk/apk` mirrors are intentionally excluded. The app reads the APK basename from the index and resolves it under `/apk/`.

## Maintainer workflow

Build a source with the Gradle wrapper:

```powershell
.\gradlew.bat :src:all:sextb:assembleRelease
```

After placing signed builds in `apk/`, regenerate and validate the catalog:

```powershell
python .\rebuild_index.py
```

Preview the install page locally:

```powershell
python -m http.server 8080
```

Then open `http://127.0.0.1:8080/`.

## Disclaimer

This is an independent repository and is not affiliated with Aniyomi, Tadami, or any content host. Users are responsible for lawful use of third-party sources.
