; Heartbeat Windows installer, compiled by the :platform-main:desktop:packageInnoSetup and packageReleaseInnoSetup
; Gradle tasks (PackageInnoSetup in build-logic) from the createDistributable app image. They pass AppImageDir,
; AppExeName, AppVersion, AppPublisher, AppDescription, AppArchitecture, SetupIconFile and WizardSmallImageFile.
;
; Setup installs for the current user without administrator rights by default; its first page offers an install
; for all users instead. It adds a Start menu shortcut and, optionally, a desktop one, and can start the app
; when it finishes. Installing a newer version over an existing one keeps the user's data; uninstalling removes it.

#if Ver < EncodeVer(6, 6, 0)
  #error Inno Setup 6.6 or newer is required
#endif

#define AppName "Heartbeat"

[Setup]
; Identifies the installation for upgrades and Windows' installed apps list: never change it.
AppId={{3E163BB7-D455-4010-A650-DC89D500569C}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppComments={#AppDescription}
VersionInfoVersion={#AppVersion}
VersionInfoProductName={#AppName}
VersionInfoDescription={#AppName} Setup
; {autopf} is %LOCALAPPDATA%\Programs for a per-user install and Program Files for an all-users one.
DefaultDirName={autopf}\{#AppName}
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed={#AppArchitecture}
ArchitecturesInstallIn64BitMode={#AppArchitecture}
MinVersion=10.0
ShowLanguageDialog=auto
WizardStyle=modern dynamic
SetupIconFile={#SetupIconFile}
WizardSmallImageFile={#WizardSmallImageFile}
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExeName}
; A running Heartbeat (and the Pi engine it started) is closed before its files are replaced.
CloseApplications=yes
RestartApplications=no
Compression=lzma2/max
SolidCompression=yes
; [UninstallDelete] removes the uninstalling user's data on purpose, also for an all-users install.
UsedUserAreasWarning=no
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Jar names change between builds: drop the previous app and runtime so an upgrade leaves no stale files behind.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Comment: "{#AppDescription}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Comment: "{#AppDescription}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; The app's data shares its lifecycle, including the bundled Pi engine's data (<app data>\engines\pi).
; Keep in sync with the data folders of core:datastore (JvmStorageBindings) and core:secrets (JvmProtectedVault).
Type: filesandordirs; Name: "{userappdata}\Aequicor\Heartbeat"
Type: filesandordirs; Name: "{localappdata}\Aequicor\Heartbeat"
Type: dirifempty; Name: "{userappdata}\Aequicor"
Type: dirifempty; Name: "{localappdata}\Aequicor"
