; Inno Setup script for the wAide Server installer.
; GitHub Actions compiles this into wAide-Server-Setup.exe.
; It lays down the service files, then (optionally) runs the setup script that
; installs Ollama, Tailscale and Python, binds everything to Tailscale, starts
; the conversations service, and pulls the default models.

#define AppName "wAide Server"
#define AppVersion "1.0.0"

[Setup]
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=wAide
DefaultDirName={autopf}\wAide Server
DefaultGroupName=wAide Server
DisableProgramGroupPage=yes
PrivilegesRequired=admin
OutputDir=Output
OutputBaseFilename=wAide-Server-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible

[Files]
Source: "waide_server.py";          DestDir: "{app}"; Flags: ignoreversion
Source: "waide_watchdog.py";        DestDir: "{app}"; Flags: ignoreversion
Source: "Install-WaideServer.ps1";  DestDir: "{app}"; Flags: ignoreversion
Source: "import-models.ps1";        DestDir: "{app}"; Flags: ignoreversion
Source: "default-models.txt";       DestDir: "{app}"; Flags: ignoreversion
Source: "README.txt";               DestDir: "{app}"; Flags: ignoreversion isreadme

[Dirs]
; Shared models drop folder (writable by any user).
Name: "{commondocs}\wAide Models"

[Icons]
Name: "{group}\Set up or Update wAide Server"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\Install-WaideServer.ps1"""; WorkingDir: "{app}"
Name: "{group}\Import dropped .gguf models";   Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\import-models.ps1"""; WorkingDir: "{app}"
Name: "{group}\Open models folder";            Filename: "{commondocs}\wAide Models"
Name: "{group}\Edit default models list";      Filename: "notepad.exe"; Parameters: """{app}\default-models.txt"""
Name: "{group}\Uninstall wAide Server";        Filename: "{uninstallexe}"

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\Install-WaideServer.ps1"""; WorkingDir: "{app}"; Flags: postinstall; Description: "Set up the server now (Ollama, Tailscale, Python, service, models)"
