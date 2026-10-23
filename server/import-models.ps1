# Registers any .gguf files you dropped into the models folder with Ollama, so
# they show up in the app. Safe to re-run; it skips ones already imported.
param(
    [string]$ModelsDir = (Join-Path ([Environment]::GetFolderPath("CommonDocuments")) "wAide Models")
)

$ErrorActionPreference = "Continue"

$ollama = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"
if (-not (Test-Path $ollama)) { $ollama = "ollama" }

if (-not (Test-Path $ModelsDir)) {
    New-Item -ItemType Directory -Path $ModelsDir -Force | Out-Null
}

Write-Host "Looking for .gguf files in:" -ForegroundColor Cyan
Write-Host "  $ModelsDir"
$ggufs = Get-ChildItem -Path $ModelsDir -Filter *.gguf -File -ErrorAction SilentlyContinue
if (-not $ggufs) {
    Write-Host "Nothing to import. Drop .gguf files in that folder and run this again." -ForegroundColor Yellow
    Read-Host "Press Enter to close"; exit 0
}

# Models already known to Ollama, so we don't re-create them.
$existing = @()
try { $existing = (& $ollama list) 2>$null | ForEach-Object { ($_ -split '\s+')[0] } } catch {}

foreach ($f in $ggufs) {
    $name = ($f.BaseName.ToLower() -replace '[^a-z0-9._-]', '-').Trim('-')
    if ($existing -contains $name -or $existing -contains "$name`:latest") {
        Write-Host "Already imported: $name" -ForegroundColor DarkGray
        continue
    }
    Write-Host "Importing $($f.Name) as '$name'..." -ForegroundColor Cyan
    $mf = Join-Path $env:TEMP "waide_modelfile.txt"
    "FROM `"$($f.FullName)`"" | Set-Content -Path $mf -Encoding ASCII
    try { & $ollama create $name -f $mf } catch { Write-Host "  failed: $_" -ForegroundColor Red }
}

Write-Host ""
Write-Host "Done. New models now appear in the app's model dropdown." -ForegroundColor Green
Read-Host "Press Enter to close"
