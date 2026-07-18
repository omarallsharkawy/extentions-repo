import json
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

AAPT = Path(r"C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt.exe")
ROOT = Path(__file__).resolve().parent
APK_DIR = ROOT / "apk"
ICON_DIR = ROOT / "icon"
ICON_DIR.mkdir(exist_ok=True)

prev_data = json.loads((ROOT / "index.min.json").read_text(encoding="utf-8-sig"))
by_pkg = {e["pkg"]: e for e in prev_data}


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
(ROOT / "index.min.json").write_bytes(
    json.dumps(entries, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
)
(ROOT / "index.json").write_bytes(
    (json.dumps(entries, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
)
(ROOT / "repo.json").write_text(
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
    + "\n",
    encoding="utf-8",
)

print("entries", len(entries), "icons", len(list(ICON_DIR.glob("*.png"))))
for e in entries:
    if "pornhub" in e["pkg"] or "streaming" in e["pkg"]:
        print(e["version"], e["apk"], [s["name"] for s in e["sources"][:4]])
