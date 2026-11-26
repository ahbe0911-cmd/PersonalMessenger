[Setup]
AppId={{34B6E28A-985B-4B7D-ADE9-28D95D39F411}
AppName=Peyvanik Preview
AppVersion=2.0.0
AppPublisher=Peyvanik
DefaultDirName={localappdata}\Programs\PeyvanikPreview
DefaultGroupName=Peyvanik Preview
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\..\dist-peyvanik-win
OutputBaseFilename=Peyvanik-v2.0-preview-x64-Setup
UninstallDisplayIcon={app}\PeyvanikPreview.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: checkedonce
[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{autoprograms}\Peyvanik Preview"; Filename: "{app}\PeyvanikPreview.exe"
Name: "{autodesktop}\Peyvanik Preview"; Filename: "{app}\PeyvanikPreview.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\PeyvanikPreview.exe"; Description: "Launch Peyvanik Preview"; Flags: nowait postinstall skipifsilent
