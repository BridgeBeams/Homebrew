@echo off
REM ============================================================
REM  wAide server - one-time setup for a new Windows PC
REM
REM  Put this file in the same folder as waide_server.py, then
REM  right-click it and choose "Run as administrator" (admin is
REM  only needed for the firewall step).
REM
REM  After this has run once, you can start the server any time
REM  with:   python waide_server.py
REM  The server also re-installs any missing library on its own
REM  at startup, so step 1 here is just a head start.
REM ============================================================

setlocal
cd /d "%~dp0"

echo.
echo === 1. Python libraries ===
python -m pip install --disable-pip-version-check pypdf python-docx openpyxl python-pptx reportlab ebooklib
if errorlevel 1 (
  echo.
  echo    "python" was not found. Trying the "py" launcher instead...
  py -m pip install --disable-pip-version-check pypdf python-docx openpyxl python-pptx reportlab ebooklib
)

echo.
echo === 2. Windows Firewall ===
REM The phone reaches the server over Tailscale on TCP port 11435.
REM Allow it inbound so the connection is not blocked.
netsh advfirewall firewall add rule name="wAide server 11435" dir=in action=allow protocol=TCP localport=11435 >nul 2>&1
if errorlevel 1 (
  echo    Could not add the firewall rule. Re-run this file as administrator
  echo    if the phone cannot reach the server.
) else (
  echo    Firewall rule added ^(TCP 11435 inbound^).
)

echo.
echo === 3. Starting the server ===
echo    Leave this window open while you use wAide. Close it to stop the server.
echo.
python waide_server.py
if errorlevel 1 py waide_server.py

pause
endlocal
