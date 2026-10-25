# Heartbeat update helper (Windows), started by the app right before it quits (DesktopUpdateInstaller).
# Waits up to ten minutes for the app to quit, runs the release's Inno Setup installer silently over the existing
# installation (same AppId: the user's data stays) and starts the app again, also when the installer failed.
# Without -Installer (a bundle train the launcher applies at its next start) it only starts the app again.
# Output goes to helper.log; Inno Setup writes its own log to -InstallLog.
param(
    [Parameter(Mandatory = $true)][int] $ProcessId,
    [string] $Installer = '',
    [Parameter(Mandatory = $true)][string] $App,
    [string] $InstallLog = ''
)

function Write-Step([string] $Message) {
    Write-Output ('{0} {1}' -f (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'), $Message)
}

$running = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
if ($null -ne $running -and -not $running.WaitForExit(600000)) {
    Write-Step 'the app did not quit; the update is not installed'
    exit 1
}

if ($Installer -ne '') {
    try {
        Write-Step 'installing the update'
        $arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', ('/LOG="{0}"' -f $InstallLog))
        $setup = Start-Process -FilePath $Installer -ArgumentList $arguments -Wait -PassThru -ErrorAction Stop
        Write-Step ('installer exit code {0}' -f $setup.ExitCode)
    } catch {
        Write-Step ('the installer could not run: {0}' -f $_.Exception.GetType().Name)
    }
} else {
    Write-Step 'relaunching the app'
}

Start-Process -FilePath $App
