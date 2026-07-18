# extentions-repo

Professional **Aniyomi / Tadami / Anikku** anime extension repository (lib **14.x**).

| | |
|--|--|
| Languages | **AR** · **EN** · **ALL** |
| Extensions | **97** |
| Icons | `icon/{package}.png` |
| APKs | `apk/*.apk` |

## Install (store URL)

Add this under **Settings → Browse → Anime extension repos** (Aniyomi)  
or **More → Settings → Browse → Extension Stores (Anime)** (Tadami):

```text
https://raw.githubusercontent.com/omarallsharkawy/extentions-repo/main/index.min.json
```

### One-tap (Aniyomi)

[Click here to install repo](aniyomi://add-repo?url=https%3A%2F%2Fraw.githubusercontent.com%2Fomarallsharkawy%2Fextentions-repo%2Fmain%2Findex.min.json)

### Install page

Open [`index.html`](./index.html) after hosting, or browse the repo on GitHub.

## Important: first-time setup on device

If you previously **sideloaded** extensions (shown as **Local** / “Not in any store”):

1. Add the store URL above.
2. **Uninstall** those Local extensions (old signature or no store link).
3. Install again **from the store** (Browse → Anime Extensions).

All APKs in this repo are signed with the **Android Debug** key used for local builds:

```text
SHA-256: 84300648046b4e4d24e940d892207fc94d6c723c120fddb5450b222c4e8d3a4d
```

You cannot “update” over an extension signed with a different key — uninstall first.

## Layout (Yuzono-compatible)

```text
index.min.json   # apk field = filename only (app prepends /apk/)
index.json
repo.json        # name + signingKeyFingerprint
apk/*.apk
icon/*.png
index.html       # optional install UI
```

## Usage

1. Add the repo URL in the app.  
2. Browse → **Anime Extensions** → install / update.  
3. Open **Sources** and use Popular / Search / Play.

## Disclaimer

Not affiliated with Aniyomi, Tadami, or any content host. You are responsible for lawful use of third-party sources.
