<div align="center">

# 🎬 Aniyomi & Anikku Anime Extensions Repository

[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen?style=for-the-badge&logo=github-actions)](https://github.com/omarallsharkawy/extentions-repo)
[![Aniyomi Compatible](https://img.shields.io/badge/Aniyomi-Compatible-blueviolet?style=for-the-badge&logo=android)](https://github.com/aniyomiorg/aniyomi)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple?style=for-the-badge&logo=kotlin)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=for-the-badge)](LICENSE)

An official, highly optimized extension repository for **[Aniyomi](https://github.com/aniyomiorg/aniyomi)** and **[Anikku](https://github.com/komikku-app/anikku)** Android apps. Providing lightning-fast anime, cartoon, movie, and Asian drama streaming sources.

---

### 🚀 Quick One-Click Installation

| Add to Aniyomi | Add to Anikku | Direct Repo URL |
| :---: | :---: | :---: |
| [![Install Aniyomi](https://img.shields.io/badge/Add_Repo-Aniyomi-238636?style=flat-square&logo=android&logoColor=white)](https://intradeus.github.io/http-protocol-redirector/?r=aniyomi://add-repo?url=https://raw.githubusercontent.com/omarallsharkawy/extentions-repo/repo/index.min.json) | [![Install Anikku](https://img.shields.io/badge/Add_Repo-Anikku-1F6FEB?style=flat-square&logo=android&logoColor=white)](https://intradeus.github.io/http-protocol-redirector/?r=anikku://add-repo?url=https://raw.githubusercontent.com/omarallsharkawy/extentions-repo/repo/index.min.json) | `https://raw.githubusercontent.com/omarallsharkawy/extentions-repo/repo/index.min.json` |

</div>

---

## 🌟 Key Features & Architectural Enhancements

- ⚡ **Zero-Latency Offline Filters**: All filter sheets (`getFilterList()`) initialize instantly offline without blocking the UI thread or sending unnecessary network requests.
- 🔄 **Robust Infinite Scroll & Pagination**: Custom CSS selectors and dynamic pagination logic prevent premature scrolling truncation during both catalog browsing and keyword searches.
- 🔒 **Cloudflare & Network Resiliency**: Built-in HTTP client interceptors, cookie managers, and user-agent rotation to seamlessly bypass anti-bot challenges.
- 📱 **Multi-Language Source Coverage**: Extensive collection of curated anime and media providers across **Arabic (`ar`)**, **English (`en`)**, **Multi-language (`all`)**, **Spanish (`es`)**, **French (`fr`)**, **Italian (`it`)**, **Portuguese (`pt`)**, **Indonesian (`id`)**, and more.

---

## 📦 Extension Catalog Breakdown

<details open>
<summary><b>🌍 Arabic Extensions (<code>src/ar</code>)</b></summary>

- **Anime4Up** - High-speed Arabic anime streaming with category & tag pagination.
- **ArabSeed** - Movies, series, and anime with full search filter support.
- **Animerco**, **Cimaleek**, **EgyDead**, **FASELHD**, **Okanime**, **Aflamk1**, **CimaLight**, **ArabsHentai**, **ArabX**, **ArabXN**, **SexAlArab**, **SexMahali**, **NxxHentai**.
</details>

<details open>
<summary><b>🇬🇧 English Extensions (<code>src/en</code>)</b></summary>

- **UniqueStream**, **Hanime**, **Anikage**, **AnimeParadise**, **AnimeTake**, **HexaWatch**, **Kayoanime**, **KimoiTV**, **Mapple**, **Myanime**, **OneThreeTwoAnime**, **PinoyMoviePedia**.
</details>

<details open>
<summary><b>🌐 Multi-Language / Adult Extensions (<code>src/all</code>)</b></summary>

- **MissAV**, **NyaaTorrent**, **PTorrent**, **StreamingCommunity**, **SupJav**, **XNXX**, **XVideos**, **PornHub**.
</details>

---

## 🛠️ Developer & Building Guide

### Environment Setup
Ensure your local development environment has Java 17+ (or Android Studio JBR / Adoptium JDK 25) configured:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```

### Compiling Extensions
To compile a specific extension module:

```bash
# Example: Compiling ArabSeed extension
./gradlew :src:ar:arabseed:compileReleaseKotlin

# Example: Compiling UniqueStream extension
./gradlew :src:en:uniquestream:compileReleaseKotlin
```

### Code Formatting & Linting
We enforce strict Kotlin formatting rules via Spotless:

```bash
# Check code formatting across all modules
./gradlew spotlessKotlinCheck

# Automatically apply formatting fixes
./gradlew spotlessKotlinApply
```

---

<div align="center">

Made with ❤️ for the Aniyomi & Anikku Community.

</div>
