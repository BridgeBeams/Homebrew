@echo off
REM ===========================================================
REM  wAide auto-start installer  (run this ONCE)
REM  Double-click it. It will ask for administrator access,
REM  then set everything up so the server launches at login.
REM ===========================================================

REM --- self-elevate to Administrator (UAC prompt) ---
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator access...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

REM --- run the real installer next to this file ---
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0waide-install.ps1"
