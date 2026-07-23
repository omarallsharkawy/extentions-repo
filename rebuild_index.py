import json
import re
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile

AAPT = Path(r"C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt.exe")
ROOT = Path(__file__).resolve().parent
APK_DIR = ROOT / "apk"
APK_APK_DIR = APK_DIR / "apk"
ICON_DIR = ROOT / "icon"


def sync_apks_triple_path(root: Path):
    apk_dir = root / "apk"
    apk_apk_dir = apk_dir / "apk"
    apk_dir.mkdir(exist_ok=True)
    apk_apk_dir.mkdir(exist_ok=True)

    pkgs = {}
    for apk in apk_dir.glob("*.apk"):
        m = re.match(r"^(.*?)-v(\d+)\.apk$", apk.name)
        if m:
            pkg_base = m.group(1)
            ver_code = int(m.group(2))
            if pkg_base not in pkgs or ver_code > pkgs[pkg_base][0]:
                pkgs[pkg_base] = (ver_code, apk)
        else:
            pkg_base = apk.stem
            if pkg_base not in pkgs:
                pkgs[pkg_base] = (0, apk)

    valid_apk_names = {apk.name for _, apk in pkgs.values()}

    for p in [root, apk_dir, apk_apk_dir]:
        for apk in p.glob("*.apk"):
            if apk.name not in valid_apk_names:
                print(f"Removing orphaned/old APK: {apk}")
                apk.unlink()

    for _, apk in pkgs.values():
        for dest in [root / apk.name, apk_apk_dir / apk.name]:
            if not dest.exists() or dest.stat().st_size != apk.stat().st_size:
                shutil.copy2(apk, dest)
                print(f"Synced {apk.name} -> {dest}")


def badging(apk: Path):
    out = subprocess.check_output(
        [str(AAPT), "dump", "--include-meta-data", "badging", str(apk)],
        text=True,
        errors="replace",
    )
    pkg = re.search(r"package: name='([^']+)'", out).group(1)
    code = int(re.search(r"versionCode='([^']+)'", out).group(1))
    ver = re.search(r"versionName='([^']+)'", out).group(1)
    label = re.search(r"^application-label:'([^']+)'", out, re.M).group(1)
    nsfw_m = re.search(r"'tachiyomi\.animeextension\.nsfw' value='([^']+)'", out)
    nsfw = int(nsfw_m.group(1)) if nsfw_m else 0
    icon_m = re.search(r"application-icon-320:'([^']+)'", out)
    icon = icon_m.group(1) if icon_m else None
    return pkg, code, ver, label, nsfw, icon


def main():
    APK_DIR.mkdir(exist_ok=True)
    APK_APK_DIR.mkdir(exist_ok=True)
    ICON_DIR.mkdir(exist_ok=True)

    sync_apks_triple_path(ROOT)

    prev_data = []
    index_min_file = ROOT / "index.min.json"
    if index_min_file.exists():
        prev_data = json.loads(index_min_file.read_text(encoding="utf-8-sig"))
    by_pkg = {e["pkg"]: e for e in prev_data}

    entries = []
    for apk in sorted(APK_DIR.glob("*.apk")):
        pkg, code, ver, label, nsfw, icon_path = badging(apk)
        if icon_path:
            with ZipFile(apk) as z:
                (ICON_DIR / f"{pkg}.png").write_bytes(z.read(icon_path))

        prev = by_pkg.get(pkg, {})
        sources = list(prev.get("sources") or [])
        sources = [
            s
            for s in sources
            if "replaceFirstChar" not in s.get("name", "") and "${" not in s.get("name", "")
        ]

        if pkg.endswith(".streamingcommunity"):
            good = [s for s in sources if "StreamingUnity" in s.get("name", "")]
            if len(good) >= 4:
                sources = good
            else:
                sources = [
                    {
                        "name": "StreamingUnity (Movie)",
                        "lang": "en",
                        "id": "4960926380444655625",
                        "baseUrl": "https://streamingunity.dog",
                    },
                    {
                        "name": "StreamingUnity (Movie)",
                        "lang": "it",
                        "id": "7219684421086407321",
                        "baseUrl": "https://streamingunity.dog",
                    },
                    {
                        "name": "StreamingUnity (Tv)",
                        "lang": "en",
                        "id": "3141592653589793238",
                        "baseUrl": "https://streamingunity.dog",
                    },
                    {
                        "name": "StreamingUnity (Tv)",
                        "lang": "it",
                        "id": "2718281828459045235",
                        "baseUrl": "https://streamingunity.dog",
                    },
                ]

        m = re.match(r"aniyomi-([^.]+)\.", apk.name)
        lang = m.group(1) if m else "all"
        name = label if label.startswith("Aniyomi") else f"Aniyomi: {label}"
        if not sources:
            sources = [
                {
                    "name": name.replace("Aniyomi: ", ""),
                    "lang": lang,
                    "id": "0",
                    "baseUrl": "",
                }
            ]

        entries.append(
            {
                "name": name,
                "pkg": pkg,
                "apk": apk.name,  # basename only
                "lang": lang,
                "code": code,
                "version": ver,
                "nsfw": nsfw,
                "sources": sources,
            }
        )

    assert all("/" not in e["apk"] for e in entries), "apk fields must be basenames"

    min_json_bytes = json.dumps(entries, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    full_json_bytes = (json.dumps(entries, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    repo_json_str = (
        json.dumps(
            {
                "meta": {
                    "name": "Omar AR+EN+ALL (14.x)",
                    "website": "https://github.com/omarallsharkawy/extentions-repo",
                    "signingKeyFingerprint": "84300648046b4e4d24e940d892207fc94d6c723c120fddb5450b222c4e8d3a4d",
                }
            },
            indent=2,
        )
        + "\n"
    )

    for p in [ROOT, APK_DIR, APK_APK_DIR]:
        (p / "index.min.json").write_bytes(min_json_bytes)
        (p / "index.json").write_bytes(full_json_bytes)
        (p / "repo.json").write_text(repo_json_str, encoding="utf-8")

    print("entries", len(entries), "icons", len(list(ICON_DIR.glob("*.png"))))
    for e in entries:
        if "pornhub" in e["pkg"] or "streaming" in e["pkg"]:
            print(e["version"], e["apk"], [s["name"] for s in e["sources"][:4]])


if __name__ == "__main__":
    main()

