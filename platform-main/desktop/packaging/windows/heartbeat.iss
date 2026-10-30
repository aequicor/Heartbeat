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
; Launcher files of installations made before the app was named Heartbeat (package name io.aequicor).
#define LegacyExeName "io.aequicor.exe"
#define LegacyIconName "io.aequicor.ico"

#ifndef AppIconFile
  #define AppIconFile AppExeName
#endif
#ifndef MinWindowsVersion
  #define MinWindowsVersion "10.0"
#endif

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
MinVersion={#MinWindowsVersion}
ShowLanguageDialog=auto
WizardStyle=modern dynamic
SetupIconFile={#SetupIconFile}
WizardSmallImageFile={#WizardSmallImageFile}
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppIconFile}
; A running Heartbeat is closed before its files are replaced. The Restart Manager first asks it to close, but neither
; the app's JVM nor its windowless helpers started from the same launcher (diagram, compiler and build workers, the
; Chromium native-messaging host) answer that request: without force they kept Heartbeat.exe locked and the upgrade
; failed with "DeleteFile: code 5". Force terminates what is still running after the request times out; the user
; agrees to it on the Preparing to Install page (or by running the update silently), and can choose not to close.
CloseApplications=force
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
; Installations made before the app was named Heartbeat had a launcher named after the package.
Type: files; Name: "{app}\{#LegacyExeName}"; Check: IsInstalledHere
Type: files; Name: "{app}\{#LegacyIconName}"; Check: IsInstalledHere

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Comment: "{#AppDescription}"; IconFilename: "{app}\{#AppIconFile}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Comment: "{#AppDescription}"; IconFilename: "{app}\{#AppIconFile}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; The app's data shares its lifecycle, including the bundled Pi engine's data (<app data>\engines\pi).
; Keep in sync with the data folders of core:datastore (JvmStorageRoot) and core:secrets (JvmProtectedVault and
; JvmDevelopmentVault, which development builds use), and DesktopManagedInstallStore's machine-local copies.
Type: filesandordirs; Name: "{userappdata}\Aequicor\Heartbeat"
; The platform launcher keeps its bundle trains beside the data (platform-main/launcher, LauncherMain.launcherRootBeside).
Type: filesandordirs; Name: "{userappdata}\Aequicor\Heartbeat-launcher"
Type: filesandordirs; Name: "{localappdata}\Aequicor\Heartbeat"
Type: dirifempty; Name: "{userappdata}\Aequicor"
Type: dirifempty; Name: "{localappdata}\Aequicor"

[Registry]
; Native-messaging host registrations of the Chromium bridge (features:chromium-bridge, BrowserLocations): the app
; writes them on request from its settings; uninstalling removes them, their manifests go with the app data above.
Root: HKCU; Subkey: "Software\Google\Chrome\NativeMessagingHosts\io.aequicor.heartbeat"; Flags: uninsdeletekey dontcreatekey
Root: HKCU; Subkey: "Software\Microsoft\Edge\NativeMessagingHosts\io.aequicor.heartbeat"; Flags: uninsdeletekey dontcreatekey
Root: HKCU; Subkey: "Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\io.aequicor.heartbeat"; Flags: uninsdeletekey dontcreatekey
Root: HKCU; Subkey: "Software\Chromium\NativeMessagingHosts\io.aequicor.heartbeat"; Flags: uninsdeletekey dontcreatekey
Root: HKCU; Subkey: "Software\Vivaldi\NativeMessagingHosts\io.aequicor.heartbeat"; Flags: uninsdeletekey dontcreatekey

[Code]
const
  RetiredMsiKey = 'Software\Aequicor\Heartbeat';

// [InstallDelete] runs only over an existing installation, also one made before the app was named Heartbeat: a first
// install may go into a folder the user typed in, and its own app or runtime subfolders must survive.
function IsInstalledHere: Boolean;
begin
  Result := FileExists(ExpandConstant('{app}\{#AppExeName}')) or FileExists(ExpandConstant('{app}\{#LegacyExeName}'));
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
