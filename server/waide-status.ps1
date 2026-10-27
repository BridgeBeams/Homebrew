# wAide status: the desktop 'wAide Status' shortcut runs this.
# Shows whether the server is up, and lets you restart it (e.g. after pasting an
# updated server file) with no admin prompt.

$up = $false
$c = New-Object System.Net.Sockets.TcpClient
try { $c.Connect('127.0.0.1', 11435); $up = $c.Connected } catch { } finally { $c.Close() }

Add-Type -AssemblyName System.Windows.Forms | Out-Null

if ($up) {
    $r = [System.Windows.Forms.MessageBox]::Show(
        "wAide server is RUNNING.`n`nRestart it now? (do this after pasting an updated server file)",
        "wAide", 'YesNo', 'Information')
    if ($r -eq 'Yes') {
        # The watchdog (running as admin) watches for this flag and restarts the server.
        New-Item -Path 'C:\waide\restart.flag' -ItemType File -Force | Out-Null
        [System.Windows.Forms.MessageBox]::Show("Restarting. Give it about 5 seconds.", "wAide") | Out-Null
    }
} else {
    $r = [System.Windows.Forms.MessageBox]::Show(
        "wAide server is NOT running.`n`nStart it now?",
        "wAide", 'YesNo', 'Warning')
    if ($r -eq 'Yes') {
        Start-Process schtasks -ArgumentList '/Run', '/TN', 'wAide Server' -WindowStyle Hidden
        [System.Windows.Forms.MessageBox]::Show("Starting in the background.", "wAide") | Out-Null
    }
}
