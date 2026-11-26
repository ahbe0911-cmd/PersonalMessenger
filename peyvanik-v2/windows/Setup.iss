[Setup]
AppId={{34B6E28A-985B-4B7D-ADE9-28D95D39F411}
AppName=Peyvanik
AppVersion=2.6.0
AppPublisher=Peyvanik
DefaultDirName={localappdata}\Programs\Peyvanik
DefaultGroupName=Peyvanik
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19041
SetupIconFile=Assets\Messenger.ico
OutputDir=..\..\dist-peyvanik-win
OutputBaseFilename=Peyvanik-v2.6-x64-Setup
UninstallDisplayIcon={app}\Peyvanik.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: checkedonce
[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "MicrosoftEdgeWebview2Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall
[Icons]
Name: "{autoprograms}\Peyvanik"; Filename: "{app}\Peyvanik.exe"
Name: "{autodesktop}\Peyvanik"; Filename: "{app}\Peyvanik.exe"; Tasks: desktopicon
[Run]
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Installing Microsoft WebView2 Runtime..."; Flags: waituntilterminated; Check: NeedsWebView2
Filename: "{app}\Peyvanik.exe"; Description: "Launch Peyvanik"; Flags: nowait postinstall skipifsilent

[Code]
function NeedsWebView2(): Boolean;
var Version: String;
begin
  Result := True;
  if RegQueryStringValue(HKLM32, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) then
    if (Version <> '') and (Version <> '0.0.0.0') then begin Result := False; exit; end;
  if RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) then
    if (Version <> '') and (Version <> '0.0.0.0') then Result := False;
end;
