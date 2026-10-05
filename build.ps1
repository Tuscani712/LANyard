# Builds LANyard File Transfer.
#
#   .\build.ps1            development build: dist\lanyard.exe (windowless app)
#                          and dist\lanyard-console.exe (console; used by dist\itest*.ps1)
#   .\build.ps1 -Release   also builds the distributable binaries into dist\release\
#
# Every binary is a single static executable (CGO off) with the web UI embedded.
# The app build uses -H=windowsgui, so double-clicking it opens no console
# window; it still prints to the terminal it was started from when you use the
# command line (lanyard peers, lanyard settings get, ...). PowerShell pipes and
# redirects work; cmd.exe does not wait for a windowless program, so for batch
# files use the console build (dist\lanyard-console.exe, or the release
# lanyard-win-x64-console.exe), which behaves like any CLI.
#
# The Windows binaries carry the app icon from cmd\lanyard\rsrc_windows_amd64.syso
# (a Go linker resource object). To regenerate after changing icons\icon.png:
#   go run ./tools/mkicon
#   rsrc -arch amd64 -ico icons\icon.ico -o cmd\lanyard\rsrc_windows_amd64.syso
# (rsrc: go install github.com/akavel/rsrc@latest) then rebuild.
param([switch]$Release)

$ErrorActionPreference = "Stop"
$goCmd = Get-Command go -ErrorAction SilentlyContinue
if ($goCmd) { $go = $goCmd.Source }
elseif ($env:GOROOT -and (Test-Path "$env:GOROOT\bin\go.exe")) { $go = "$env:GOROOT\bin\go.exe" }
elseif (Test-Path "$env:ProgramFiles\Go\bin\go.exe") { $go = "$env:ProgramFiles\Go\bin\go.exe" }
else { throw "Go was not found: install it from https://go.dev/dl/ and put it on PATH" }
Set-Location $PSScriptRoot
$env:CGO_ENABLED = "0"

function Build($os, $arch, $out, $extra) {
    $env:GOOS = $os; $env:GOARCH = $arch
    & $go build -trimpath "-ldflags=-s -w $extra" -o $out ./cmd/lanyard
    if ($LASTEXITCODE -ne 0) { throw "build failed for $os/$arch" }
    "{0,-34} {1,6:N1} MB" -f $out, ((Get-Item $out).Length / 1MB)
}

& $go vet ./...
if ($LASTEXITCODE -ne 0) { throw "go vet failed" }

New-Item -ItemType Directory -Force dist | Out-Null
Build windows amd64 "dist\lanyard.exe" "-H=windowsgui"
Build windows amd64 "dist\lanyard-console.exe" ""

if ($Release) {
    New-Item -ItemType Directory -Force dist\release | Out-Null
    Build windows amd64 "dist\release\lanyard-win-x64.exe" "-H=windowsgui"
    Build windows amd64 "dist\release\lanyard-win-x64-console.exe" ""
    Build darwin  arm64 "dist\release\lanyard-mac-arm64" ""
    Build darwin  amd64 "dist\release\lanyard-mac-x64" ""
    Build linux   amd64 "dist\release\lanyard-linux-x64" ""
    Build linux   arm64 "dist\release\lanyard-linux-arm64" ""
    "Release binaries are unsigned. Sign before distributing (Windows Authenticode, macOS codesign + notarization)."
}
