# BUILD_LOG — Aniyomi-Local-Extensions-Repo

Generated: **2026-07-18**  
Repo: `D:\aniyomi\Aniyomi-Local-Extensions-Repo`  
Source tree: `D:\aniyomi\anime-extensions`  
Build type: `assembleDebug` with `--no-configuration-cache`  
Signing: Android debug keystore (`local-dev`)

## Coordination

| Phase | Owner | Result |
| --- | --- | --- |
| **P1** | AR + EN modules | Done first (`.p1_done` present); staged **66** APKs (ar 14 + en 52) into `apk/` |
| **P2** | ALL modules (31) | Waited on `D:\aniyomi\.gradle-build.lock`, then built all `:src:all:<name>:assembleDebug` |

Gradle lock file: `D:\aniyomi\.gradle-build.lock` (released after P2 staging).

## Environment

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd D:\aniyomi\anime-extensions
```

## P2 build — `src/all` (31/31 SUCCESS)

All modules built with batch `assembleDebug` (batch size 8). **0 failures.**

| Module | versionName | APK (stable) | Size (bytes) |
| --- | --- | --- | ---: |
| animeonsen | 14.10 | aniyomi-all.animeonsen-v14.10.apk | 202948 |
| animetsu | 14.7 | aniyomi-all.animetsu-v14.7.apk | 319499 |
| animeworldindia | 14.22 | aniyomi-all.animeworldindia-v14.22.apk | 218132 |
| animexin | 14.28 | aniyomi-all.animexin-v14.28.apk | 277153 |
| anizone | 14.11 | aniyomi-all.anizone-v14.11.apk | 208431 |
| chineseanime | 14.32 | aniyomi-all.chineseanime-v14.32.apk | 410863 |
| debridindex | 14.2 | aniyomi-all.debridindex-v14.2.apk | 162360 |
| googledrive | 14.18 | aniyomi-all.googledrive-v14.18.apk | 186106 |
| googledriveindex | 14.7 | aniyomi-all.googledriveindex-v14.7.apk | 188159 |
| hentaitorrent | 14.4 | aniyomi-all.hentaitorrent-v14.4.apk | 142229 |
| jable | 14.2 | aniyomi-all.jable-v14.2.apk | 159776 |
| javgg | 14.19 | aniyomi-all.javgg-v14.19.apk | 385773 |
| javguru | 14.43 | aniyomi-all.javguru-v14.43.apk | 376586 |
| jellyfin | 14.30 | aniyomi-all.jellyfin-v14.30.apk | 717781 |
| lmanime | 14.26 | aniyomi-all.lmanime-v14.26.apk | 376454 |
| missav | 14.25 | aniyomi-all.missav-v14.25.apk | 211513 |
| myreadingmanga | 14.2 | aniyomi-all.myreadingmanga-v14.2.apk | 162151 |
| newgrounds | 14.1 | aniyomi-all.newgrounds-v14.1.apk | 181514 |
| nyaatorrent | 14.4 | aniyomi-all.nyaatorrent-v14.4.apk | 154425 |
| **pornhub** | **14.1** | **aniyomi-all.pornhub-v14.1.apk** | 237275 |
| ptorrent | 14.4 | aniyomi-all.ptorrent-v14.4.apk | 145874 |
| rouvideo | 14.13 | aniyomi-all.rouvideo-v14.13.apk | 231750 |
| shabakatycinemana | 14.3 | aniyomi-all.shabakatycinemana-v14.3.apk | 194271 |
| streamingcommunity | 14.19 | aniyomi-all.streamingcommunity-v14.19.apk | 250992 |
| stremio | 14.9 | aniyomi-all.stremio-v14.9.apk | 714451 |
| subsplease | 14.5 | aniyomi-all.subsplease-v14.5.apk | 162989 |
| supjav | 14.29 | aniyomi-all.supjav-v14.29.apk | 344846 |
| torrentio | 14.8 | aniyomi-all.torrentio-v14.8.apk | 191380 |
| torrentioanime | 14.19 | aniyomi-all.torrentioanime-v14.19.apk | 228820 |
| **xnxx** | **14.5** | **aniyomi-all.xnxx-v14.5.apk** | 226947 |
| **xvideos** | **14.5** | **aniyomi-all.xvideos-v14.5.apk** | 192021 |

Debug outputs originated under:

```text
anime-extensions/src/all/<module>/build/outputs/apk/debug/aniyomi-all.<module>-v14.N-debug.apk
```

Staged to `apk/` with `-debug` stripped from the filename.

Raw Gradle log (P2): `P2-build-raw.log` (same directory).

## Full repo inventory (after index)

| Lang | Count |
| --- | ---: |
| ar | 14 |
| en | 52 |
| all | 31 |
| **Total** | **97** |

| Metric | Value |
| --- | --- |
| NSFW flagged | 34 |
| Total APK bytes | ~25.6 MB |
| Index entries | 97 (one per APK) |

## Index generation

For every file matching `apk/aniyomi-LANG.NAME-vVER.apk`:

1. Parse `LANG`, `NAME`, `VER` from filename  
2. `aapt dump badging` → package, versionCode, versionName, application-label  
3. Manifest / `build.gradle` → nsfw flag  
4. Kotlin sources → `name`, `lang`, `baseUrl`, optional `versionId` / explicit `id`  
5. Source ID = first 64 bits of MD5(`name.lowercase()/lang/versionId`) masked with `Long.MAX_VALUE`

### Adult source ID checks (match prior adult pack)

| APK | source id |
| --- | --- |
| aniyomi-all.pornhub-v14.1.apk | `163747473533778320` |
| aniyomi-all.xnxx-v14.5.apk | `1853973586604481267` |
| aniyomi-all.xvideos-v14.5.apk | `8016727706963226859` |

## repo.json

```json
{
  "meta": {
    "name": "Local AR+EN+ALL (14.x)",
    "website": "file:///local",
    "signingKeyFingerprint": "local-dev"
  }
}
```

## Outputs written

- `apk/*.apk` (97)
- `index.json`
- `index.min.json`
- `repo.json`
- `README.md`
- `BUILD_LOG.md` (this file)

## Policy

- Local only — **no git push**
- Do **not** hardcode this repo into Tadami
- Debug APKs only
