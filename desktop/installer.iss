#define AppName "MH Player"
#ifndef AppVersion
#define AppVersion "1.0.0"
#endif
[Setup]
AppId={{8E1A36F1-8A12-4E5D-9C32-57D7E512F7B4}
AppName={#AppName}
AppVersion={#AppVersion}
DefaultDirName={autopf}\MH Player
DefaultGroupName=MH Player
OutputDir=..\dist
OutputBaseFilename=MH-Player-Windows-x64-Setup
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
WizardStyle=modern
DisableProgramGroupPage=yes
[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
[Icons]
Name: "{autoprograms}\MH Player"; Filename: "{app}\MHPlayer.exe"
Name: "{autodesktop}\MH Player"; Filename: "{app}\MHPlayer.exe"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"
[Run]
Filename: "{app}\MHPlayer.exe"; Description: "Launch MH Player"; Flags: nowait postinstall skipifsilent
