# wAide auto-start installer (invoked elevated by install-waide.bat).
$ErrorActionPreference = 'Continue'
$waide = 'C:\waide'
Write-Host "================================================"
Write-Host "   wAide auto-start setup"
Write-Host "================================================"
Write-Host ""

# 1. Make Ollama listen on all interfaces, so Server Chat is reachable over Tailscale.
#    (Takes effect after the next reboot or Ollama restart.)
try {
    [Environment]::SetEnvironmentVariable('OLLAMA_HOST', '0.0.0.0', 'Machine')
    Write-Host "[1/5] Ollama set to listen on all network interfaces."
} catch { Write-Host "[1/5] Could not set OLLAMA_HOST: $_" }

# 2. Firewall: allow Ollama (11434) and the wAide server (11435). Idempotent.
foreach ($port in 11434, 11435) {
    $name = "wAide $port"
    if (-not (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port | Out-Null
    }
}
Write-Host "[2/5] Firewall openings confirmed."

# 3. Find the windowless Python launcher.
$pyw = (Get-Command pythonw.exe -ErrorAction SilentlyContinue).Source
if (-not $pyw) { $pyw = 'pythonw.exe' }

# 4. Scheduled task: run the watchdog at login, hidden, with admin rights.
try {
    $action    = New-ScheduledTaskAction -Execute $pyw -Argument "`"$waide\watchdog.py`"" -WorkingDirectory $waide
    $trigger   = New-ScheduledTaskTrigger -AtLogOn
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -RunLevel Highest -LogonType Interactive
    $settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName 'wAide Server' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    Write-Host "[3/5] Startup task created (runs at login, no window, admin)."
} catch { Write-Host "[3/5] Could not create the startup task: $_" }

# 5. Desktop shortcut that checks status and can restart.
try {
    $lnk = [Environment]::GetFolderPath('Desktop') + '\wAide Status.lnk'
    $sc  = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk)
    $sc.TargetPath       = 'powershell.exe'
    $sc.Arguments        = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$waide\waide-status.ps1`""
    $sc.WorkingDirectory = $waide
    $sc.IconLocation     = 'shell32.dll,18'
    $sc.Save()
    Write-Host "[4/5] 'wAide Status' shortcut placed on your desktop."
} catch { Write-Host "[4/5] Could not create the desktop shortcut: $_" }

# Start it right now so you don't have to reboot.
schtasks /Run /TN "wAide Server" | Out-Null
Write-Host "[5/5] Server starting now in the background."

Write-Host ""
Write-Host "All set. wAide now starts automatically every time you log in."
Write-Host "For Server Chat (phone to Ollama directly) to work, reboot once so"
Write-Host "the Ollama setting takes effect. You can close this window."
Start-Sleep -Seconds 10
