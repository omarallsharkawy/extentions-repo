# Regenerates config.js REPO_BASE_URL and normalizes index APK paths.
# Usage:
#   .\generate-install-page.ps1
#   .\generate-install-page.ps1 -BaseUrl "https://youruser.github.io/aniyomi-local-extensions"

param(
    [string]$BaseUrl = ""
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

# Normalize apk paths
python -c @"
import json, pathlib, sys
root = pathlib.Path(r'$($Root -replace '\\','/')')
for fname in ['index.json', 'index.min.json']:
    f = root / fname
    if not f.exists():
        print('skip missing', fname)
        continue
    data = json.loads(f.read_text(encoding='utf-8'))
    for e in data:
        apk = e.get('apk', '')
        base = apk.replace('\\\\', '/').split('/')[-1]
        e['apk'] = 'apk/' + base if base else apk
    if fname.endswith('.min.json'):
        f.write_text(json.dumps(data, separators=(',', ':'), ensure_ascii=False), encoding='utf-8')
    else:
        f.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(fname, len(data), 'ok')
"@

$base = $BaseUrl.TrimEnd('/')
$config = @"
// Public base URL for this extension repo (no trailing slash).
// Replace after you host on GitHub Pages / any static HTTPS host.
// Example: https://youruser.github.io/aniyomi-local-extensions
window.REPO_BASE_URL = "$base";
"@
Set-Content -Path (Join-Path $Root "config.js") -Value $config -Encoding utf8

$repo = @{
    meta = @{
        name = "Local AR+EN+ALL (14.x)"
        website = $(if ($base) { $base } else { "https://YOUR_USER.github.io/aniyomi-local-extensions" })
        signingKeyFingerprint = "local-dev"
    }
} | ConvertTo-Json -Depth 5
Set-Content -Path (Join-Path $Root "repo.json") -Value $repo -Encoding utf8

Write-Host "config.js REPO_BASE_URL = '$base'"
Write-Host "Open index.html via a local server for preview:"
Write-Host "  cd `"$Root`""
Write-Host "  python -m http.server 8080"
Write-Host "  http://127.0.0.1:8080/"
if ($base) {
    $idx = "$base/index.min.json"
    Write-Host "Manual install URL: $idx"
    Write-Host "Aniyomi deep link: aniyomi://add-repo?url=$([uri]::EscapeDataString($idx))"
}
