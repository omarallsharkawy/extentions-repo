# Regenerates config.js with the public repository base URL.
# Usage:
#   .\generate-install-page.ps1
#   .\generate-install-page.ps1 -BaseUrl "https://raw.githubusercontent.com/user/repo/main"

param(
    [string]$BaseUrl = "https://raw.githubusercontent.com/omarallsharkawy/extentions-repo/main"
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

$base = $BaseUrl.TrimEnd('/')
$config = @"
// Public base URL for this extension repository (no trailing slash).
window.REPO_BASE_URL = "$base";
"@
Set-Content -Path (Join-Path $Root "config.js") -Value $config -Encoding utf8

Write-Host "config.js REPO_BASE_URL = '$base'"
Write-Host "Preview the install page with:"
Write-Host "  cd `"$Root`""
Write-Host "  python -m http.server 8080"
Write-Host "  http://127.0.0.1:8080/"
$idx = "$base/index.min.json"
Write-Host "Repository URL: $idx"
Write-Host "Deep link: aniyomi://add-repo?url=$([uri]::EscapeDataString($idx))"
