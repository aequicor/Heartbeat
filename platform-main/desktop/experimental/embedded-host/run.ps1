param(
    [Parameter(Mandatory = $true)][string]$RuntimeHome,
    [Parameter(Mandatory = $true)][string]$AppDirectory,
    [ValidateSet('dark', 'light')][string]$Theme = 'dark',
    [switch]$Welcome
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$output = Join-Path $repo 'build/embedded-host'
$runtime = (Resolve-Path -LiteralPath $RuntimeHome).Path
$app = (Resolve-Path -LiteralPath $AppDirectory).Path
$profile = Join-Path $output ('sessions/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $profile | Out-Null
$options = Join-Path $profile 'studio.options'
$classpath = (Get-Content -LiteralPath (Join-Path $output 'classpath.txt') -Raw).Trim()
$arguments = @(
    '-Djava.class.path=' + $classpath
    '-Djava.awt.headless=false'
    '-Dfile.encoding=UTF-8'
    '-Dheartbeat.embedded.content=studio'
    '-Dheartbeat.embedded.dark=' + ($Theme -eq 'dark').ToString().ToLowerInvariant()
    '-Dheartbeat.data.name=HeartbeatEmbeddedProbe'
    '-Dheartbeat.app.version=1.0.0'
    '-Dheartbeat.channel=release'
    '-Dskiko.library.path=' + $app
    '-Dcompose.application.resources.dir=' + (Join-Path $app 'resources')
)
[System.IO.File]::WriteAllLines($options, $arguments, [System.Text.UTF8Encoding]::new($false))
$previousRoaming = $env:APPDATA
$previousLocal = $env:LOCALAPPDATA
$previousIntro = $env:HEARTBEAT_EMBEDDED_INTRO_PREVIEW
try {
    $env:APPDATA = Join-Path $profile 'roaming'
    $env:LOCALAPPDATA = Join-Path $profile 'local'
    $env:HEARTBEAT_EMBEDDED_INTRO_PREVIEW = if ($Welcome) { '1' } else { '0' }
    $renderer = Join-Path $output 'heartbeat_embedded_renderer.dll'
    Start-Process -FilePath (Join-Path $output 'heartbeat-embedded.exe') -ArgumentList @(
        ('"' + $runtime + '"'), ('"' + $options + '"'), ('"' + $renderer + '"')
    ) -PassThru
} finally {
    $env:APPDATA = $previousRoaming
    $env:LOCALAPPDATA = $previousLocal
    $env:HEARTBEAT_EMBEDDED_INTRO_PREVIEW = $previousIntro
}
