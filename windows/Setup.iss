[Setup]
AppId={{D9CB9A92-E238-4316-A38B-FAF130E28A99}
AppName=Personal Messenger
AppVersion=1.0.0
AppPublisher=Personal Messenger
DefaultDirName={localappdata}\Programs\PersonalMessenger
DefaultGroupName=Personal Messenger
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19041
OutputDir=..\dist-windows
OutputBaseFilename=PersonalMessenger-Windows-x64-Setup
SetupIconFile=Assets\Messenger.ico
UninstallDisplayIcon={app}\PersonalMessenger.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: checkedonce
[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "MicrosoftEdgeWebview2Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall
[Icons]
Name: "{autoprograms}\Personal Messenger"; Filename: "{app}\PersonalMessenger.exe"
Name: "{autodesktop}\Personal Messenger"; Filename: "{app}\PersonalMessenger.exe"; Tasks: desktopicon
[Run]
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Installing Microsoft WebView2 Runtime if needed..."; Flags: waituntilterminated; Check: NeedsWebView2
Filename: "{app}\PersonalMessenger.exe"; Description: "Launch Personal Messenger"; Flags: nowait postinstall skipifsilent
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
