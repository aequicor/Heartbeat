param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$LlvmHome,
    [Parameter(Mandatory = $true)][string]$MingwHome
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$output = Join-Path $repo 'build/embedded-host'
New-Item -ItemType Directory -Force -Path $output | Out-Null
Push-Location $repo
try {
    & ./gradlew.bat :platform-main:desktop:writeEmbeddedClasspath :platform-main:native-launcher:linkEmbeddedRendererReleaseSharedMingwX64 --init-script platform-main/desktop/experimental/embedded-host/probe.gradle --no-configuration-cache
    if ($LASTEXITCODE) { throw 'Kotlin build failed' }
    $icon = (Join-Path $repo 'platform-main/desktop/icons/heartbeat.ico').Replace('\', '/')
    Set-Content -LiteralPath (Join-Path $output 'host.rc') -Value ('1 ICON "' + $icon + '"') -Encoding ascii
    & (Join-Path $MingwHome 'bin/windres.exe') ('--preprocessor=' + (Join-Path $LlvmHome 'bin/clang.exe')) --preprocessor-arg=-E --preprocessor-arg=-xc -i "$output/host.rc" -o "$output/host-icon.o" -O coff
    if ($LASTEXITCODE) { throw 'Icon compilation failed' }
    & (Join-Path $LlvmHome 'bin/clang++.exe') --target=x86_64-w64-windows-gnu "--sysroot=$MingwHome" -std=c++17 -O2 -static -municode -mwindows -I "$JdkHome/include" -I "$JdkHome/include/win32" "$PSScriptRoot/host.cpp" "$output/host-icon.o" -o "$output/heartbeat-embedded.exe" -fuse-ld=lld -luser32 -lgdi32 -lshell32 -lwinpthread -ldwmapi -lcomctl32
    if ($LASTEXITCODE) { throw 'Native host compilation failed' }
    Copy-Item -LiteralPath "$repo/platform-main/native-launcher/build/bin/mingwX64/embeddedRendererReleaseShared/heartbeat_embedded_renderer.dll" -Destination "$output/heartbeat_embedded_renderer.dll"
} finally {
    Pop-Location
}
