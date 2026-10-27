wAide SERVER
============

This folder turns a Windows PC into your private wAide server. It runs the model
engine (Ollama) and the PIN-locked conversations service, reachable from the
phone app over Tailscale. Once set up, the server starts automatically at login
and keeps itself running.

FILES
  waide_server.py    The server (conversations, agents, premium AI, search, images).
  watchdog.py        Keeps the server running; restarts it if it ever stops.
  install-waide.bat  One-time setup launcher (double-click this).
  waide-install.ps1  The actual installer (install-waide.bat calls it).
  waide-status.ps1   Powers the "wAide Status" desktop icon.

NEED THESE INSTALLED FIRST (once, if not already present)
  - Python 3  (the 'py' / 'pythonw' launcher available on PATH)
  - Ollama    (set it to start at login in its tray settings)
  - Tailscale (signed in to the SAME account your phone uses)

FIRST-TIME SETUP
  1. Put all five files above into  C:\waide
  2. Double-click  install-waide.bat
  3. Click Yes on the administrator (UAC) prompt
  4. Reboot once, so Ollama's network setting takes effect
  Done. The server now launches automatically every login, hidden and with admin
  rights, and a "wAide Status" shortcut is added to your desktop.

CHECK OR RESTART IT
  Double-click "wAide Status" on the desktop. It tells you whether the server is
  running and offers to restart it. No admin prompt.

UPDATE THE SERVER
  1. Replace  C:\waide\waide_server.py  with the new version.
  2. Double-click "wAide Status"  ->  Yes to restart.
  The watchdog reloads the new code within a few seconds.

NOTES
  - Models are managed from the app (Settings > Manage server models), so no
    separate model-import scripts live here anymore.
  - watchdog.log and server.log are written here while running. Safe to delete;
    they come back on their own.
