# ============================================================
#  wAide Server - full setup for Windows 11
#  Installs Ollama + Tailscale + Python, binds everything to
#  your private Tailscale tunnel, installs the conversations
#  service and starts it on boot, and sets up a models drop
#  folder. Safe to re-run; it only does what's missing.
#  Run by the installer, or from the Start Menu shortcut.
# ============================================================

$ErrorActionPreference = "Continue"
function Say($m, $c = "White") { Write-Host $m -ForegroundColor $c }

Say "============================================" Yellow
Say "   wAide Server setup" Yellow
Say "============================================" Yellow
Say ""

# --- winget present? ---
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Say "winget (App Installer) isn't available on this PC." Red
    Say "Open the Microsoft Store, install 'App Installer', then run this again." Red
    Read-Host "Press Enter to close"; exit 1
}

# --- Ollama ---
$ollamaExe = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"
$ollamaApp = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama app.exe"
if ((Test-Path $ollamaExe) -or (Get-Command ollama -ErrorAction SilentlyContinue)) {
    Say "Ollama already installed." Green
} else {
    Say "Installing Ollama..." Cyan
    try { winget install --id Ollama.Ollama -e --silent --accept-source-agreements --accept-package-agreements } catch {}
}
if (-not (Test-Path $ollamaExe)) { $ollamaExe = "ollama" }

# --- Tailscale ---
$tsExe = "C:\Program Files\Tailscale\tailscale.exe"
if (Test-Path $tsExe) {
    Say "Tailscale already installed." Green
} else {
    Say "Installing Tailscale..." Cyan
    try { winget install --id Tailscale.Tailscale -e --silent --accept-source-agreements --accept-package-agreements } catch {}
    Start-Sleep -Seconds 4
}
if (-not (Test-Path $tsExe)) { $tsExe = "tailscale" }

# --- connect Tailscale (one-time browser sign-in if needed) ---
Say ""
Say "Connecting Tailscale. If a browser opens, sign in with the SAME account you" Cyan
Say "use on your phone, then come back here." Cyan
try { & $tsExe up } catch {}
Start-Sleep -Seconds 2

# --- read the private Tailscale address ---
$tsip = (& $tsExe ip -4 2>$null | Select-Object -First 1)
if ($tsip) { $tsip = $tsip.Trim() }
if ([string]::IsNullOrWhiteSpace($tsip)) {
    Say "" ; Say "Could not read a Tailscale address yet." Red
    Say "Open Tailscale, make sure it says Connected, then run this setup again." Red
    Read-Host "Press Enter to close"; exit 1
}
Say "Private address: $tsip" Green

# --- bind Ollama to the tunnel only, now and permanently ---
[Environment]::SetEnvironmentVariable("OLLAMA_HOST", "$($tsip):11434", "Machine")
[Environment]::SetEnvironmentVariable("OLLAMA_KEEP_ALIVE", "30m", "Machine")
$env:OLLAMA_HOST = "$($tsip):11434"
$env:OLLAMA_KEEP_ALIVE = "30m"
Say "Ollama bound to $($tsip):11434" Green

# --- never sleep on AC, open the firewall for both ports ---
powercfg /change standby-timeout-ac 0 2>$null
powercfg /change hibernate-timeout-ac 0 2>$null
if (-not (Get-NetFirewallRule -DisplayName "wAide Ollama" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "wAide Ollama" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 11434 -ErrorAction SilentlyContinue | Out-Null
}
if (-not (Get-NetFirewallRule -DisplayName "wAide Conversations" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "wAide Conversations" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 11435 -ErrorAction SilentlyContinue | Out-Null
}
Say "Sleep disabled; firewall opened for 11434 and 11435." Green

# --- restart Ollama so it serves on the tunnel now ---
Say "Restarting Ollama..." Cyan
Get-Process "ollama app" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process "ollama"     -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
if (Test-Path $ollamaApp) { Start-Process $ollamaApp } else { try { Start-Process $ollamaExe -ArgumentList "serve" -WindowStyle Hidden } catch {} }
Start-Sleep -Seconds 4

# --- Python (for the conversations service) ---
# Searches the normal install spot AND the new Windows "Python install manager"
# location (pythoncore-*), which is where recent Python builds land.
function Find-Py([string]$exe) {
    $c = Get-Command $exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($pat in @(
        "$env:LOCALAPPDATA\Programs\Python\Python3*\$exe",
        "$env:LOCALAPPDATA\Python\pythoncore-*\$exe",
        "C:\Python3*\$exe"
    )) {
        $hit = Get-ChildItem $pat -ErrorAction SilentlyContinue | Select-Object -Last 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

$python = Find-Py "python.exe"
if (-not $python) {
    Say "Installing Python..." Cyan
    try { winget install --id Python.Python.3.12 -e --silent --accept-source-agreements --accept-package-agreements } catch {}
    Start-Sleep -Seconds 4
    $python = Find-Py "python.exe"
}
if (-not $python) {
    Say "Python did not install cleanly. Close this window, reopen it, and run" Red
    Say "the 'Set up / Update wAide Server' shortcut again." Red
    Read-Host "Press Enter to close"; exit 1
}
Say "Python: $python" Green
Say "No extra libraries needed; the service uses only Python's standard library." Green

# --- configure + register the conversations service ---
$svc = Join-Path $PSScriptRoot "waide_server.py"
# Shared data location so the service uses the SAME conversations whether it runs
# now (as admin) or later from the logon task (as you). Public is writable by all.
$dataDir = Join-Path $env:PUBLIC "wAide\data"
if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory -Path $dataDir -Force | Out-Null }
[Environment]::SetEnvironmentVariable("WAIDE_HOST", $tsip, "Machine")
[Environment]::SetEnvironmentVariable("WAIDE_OLLAMA", "http://127.0.0.1:11434", "Machine")
[Environment]::SetEnvironmentVariable("WAIDE_PORT", "11435", "Machine")
[Environment]::SetEnvironmentVariable("WAIDE_DATA", $dataDir, "Machine")

$pythonw = Find-Py "pythonw.exe"
if (-not $pythonw) { $pythonw = $python }
$watchdog = Join-Path $PSScriptRoot "waide_watchdog.py"

# Start the watchdog on every logon. It runs hidden (pythonw) and relaunches the
# service within seconds if it ever exits, so there's no window and no babysitting.
try {
    $action  = New-ScheduledTaskAction -Execute $pythonw -Argument "`"$watchdog`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    Register-ScheduledTask -TaskName "wAide Conversations" -Action $action -Trigger $trigger `
        -Force -RunLevel Limited -Description "wAide private conversations service (watchdog)" | Out-Null
    Say "Service set to start (hidden, self-healing) at logon." Green
} catch { Say "Could not register the startup task: $_" Red }

# Start it right now, hidden, in this session. Ollama is found automatically by the
# service (OLLAMA_HOST is already set above), so no WAIDE_OLLAMA is needed.
$env:WAIDE_HOST = $tsip
$env:WAIDE_PORT = "11435"
try {
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*waide_server.py*" -or $_.CommandLine -like "*waide_watchdog.py*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Process $pythonw -ArgumentList "`"$watchdog`"" -WindowStyle Hidden
    Say "Conversations service started (hidden) on $($tsip):11435" Green
} catch { Say "Could not start the service now: $_" Red }

# --- models drop folder ---
$modelsDir = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "wAide Models"
if (-not (Test-Path $modelsDir)) { New-Item -ItemType Directory -Path $modelsDir -Force | Out-Null }
Say "Drop .gguf files into: $modelsDir" Green

# --- optional model downloads ---
# --- default models (from default-models.txt kept in the repo) ---
$listPath = Join-Path $PSScriptRoot "default-models.txt"
if (Test-Path $listPath) {
    $models = Get-Content $listPath | ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith("#") }
    if ($models.Count -gt 0) {
        Say ""
        Say "Default models for this server (from default-models.txt):" Cyan
        $models | ForEach-Object { Say "   $_" Cyan }
        $ans = Read-Host "Download these now? (large, one-time)  [Y/n]"
        if ($ans -eq "" -or $ans -match "^[Yy]") {
            foreach ($m in $models) {
                Say "Pulling $m ..." Cyan
                try { & $ollamaExe pull $m } catch { Say "  failed: $m  (you can retry from the app)" Red }
            }
        }
    }
} else {
    Say "No default-models.txt found next to this script; skipping default pulls." Yellow
}

Say ""
Say "============================================" Green
Say "   Done. Your wAide server is ready." Green
Say "============================================" Green
Say ""
Say "On your phone, in the wAide app:" White
Say "   Settings > Machines > Add a machine" White
Say "      Model engine (amnesia Server tab): http://$($tsip):11434" White
Say "      Conversations service:             http://$($tsip):11435" White
Say ""
Say "Drop .gguf models into: $modelsDir" White
Say "then run the 'Import dropped models' shortcut to register them." White
Say ""
Say "Keep this PC on and awake when you want to reach it from your phone." White
Read-Host "Press Enter to close"
