; Heartbeat Windows installer, compiled by the :platform-main:desktop:packageInnoSetup and packageReleaseInnoSetup
; Gradle tasks (PackageInnoSetup in build-logic) from the createDistributable app image. They pass AppImageDir,
; AppExeName, AppVersion, AppPublisher, AppDescription, AppArchitecture, SetupIconFile and WizardSmallImageFile.
;
; Setup installs for the current user without administrator rights by default; its first page offers an install
; for all users instead. It adds a Start menu shortcut and, optionally, a desktop one, and can start the app
; when it finishes. Installing a newer version over an existing one keeps the user's data; uninstalling removes it.
; Removing a leftover MSI of an earlier Heartbeat keeps the data too, see DisarmRetiredMsiCleanup.

#if Ver < EncodeVer(6, 6, 0)
  #error Inno Setup 6.6 or newer is required: update it or set heartbeat.innoSetupDir to a newer ISCC.exe folder
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
; [UninstallDelete] removes the data of the account the uninstaller runs as on purpose, also for an all-users install:
; the uninstalling user, or the administrator whose credentials confirmed the UAC prompt.
UsedUserAreasWarning=no
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Jar names change between builds: drop the previous app and runtime so an upgrade leaves no stale files behind.
Type: filesandordirs; Name: "{app}\app"; Check: IsInstalledHere
Type: filesandordirs; Name: "{app}\runtime"; Check: IsInstalledHere

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Comment: "{#AppDescription}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Comment: "{#AppDescription}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; The app's data shares its lifecycle, including the bundled Pi engine's data (<app data>\engines\pi).
; Keep in sync with the data folders of core:datastore (JvmStorageRoot) and core:secrets (JvmProtectedVault and
; JvmDevelopmentVault, which development builds use).
Type: filesandordirs; Name: "{userappdata}\Aequicor\Heartbeat"
Type: filesandordirs; Name: "{localappdata}\Aequicor\Heartbeat"
Type: dirifempty; Name: "{userappdata}\Aequicor"
Type: dirifempty; Name: "{localappdata}\Aequicor"

[Code]
const
  RetiredMsiKey = 'Software\Aequicor\Heartbeat';

// [InstallDelete] runs only over an existing installation: a first install may go into a folder the user typed in,
// and its own app or runtime subfolders must survive.
function IsInstalledHere: Boolean;
begin
  Result := FileExists(ExpandConstant('{app}\{#AppExeName}'));
end;

// Earlier Heartbeat versions were MSI packages that remembered the data folders under HKCU\Software\Aequicor\Heartbeat
// and removed them with WiX RemoveFolderEx when the MSI was uninstalled, which would also wipe this installation's
// data. Point the MSI at a folder that never exists: RemoveFolderEx skips a missing folder, while an empty value would
// fail the MSI uninstall. Only the account Setup runs as is covered: an all-users install confirmed with another
// administrator's credentials leaves the logged-on user's MSI values as they were.
procedure DisarmRetiredMsiCleanup(const ValueName: String);
begin
  if RegValueExists(HKEY_CURRENT_USER, RetiredMsiKey, ValueName) then
  begin
    Log('Disarming the data cleanup of the earlier MSI installation: ' + ValueName);
    if not RegWriteStringValue(HKEY_CURRENT_USER, RetiredMsiKey, ValueName,
      ExpandConstant('{localappdata}\Aequicor\retired-msi-cleanup')) then
      Log('Failed to disarm the data cleanup of the earlier MSI installation: ' + ValueName);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    DisarmRetiredMsiCleanup('DataDir');
    DisarmRetiredMsiCleanup('SecretsDir');
  end;
end;
