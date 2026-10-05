$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-cli-$stamp"
$dirA = Join-Path $base "A"; $src = Join-Path $base "src"
New-Item -ItemType Directory -Force -Path $dirA, $src | Out-Null
Set-Content -Path (Join-Path $src "a.txt") -Value "hello" -NoNewline

function Start-Node($dir, $peer, $ui, $name) {
  Start-Process -FilePath $exe -WindowStyle Hidden -PassThru -ArgumentList @(
    "--data-dir", $dir, "--no-browser", "--no-tray", "--port", $peer, "--ui-port", $ui, "--name", $name)
}
function Wait-Run($dir) {
  $p = Join-Path $dir "run.json"
  for ($i = 0; $i -lt 50; $i++) { if (Test-Path $p) { return (Get-Content $p -Raw | ConvertFrom-Json) }; Start-Sleep -Milliseconds 200 }
  throw "no run.json"
}

$a = Start-Node $dirA 47871 47881 "Alice"
try {
  $ri = Wait-Run $dirA

  $out = & $exe settings get --data-dir $dirA
  if (-not ($out -match "device_name")) { throw "settings get output: $out" }
  Write-Host "settings get OK"

  $null = & $exe settings set theme dark --data-dir $dirA
  $out = & $exe settings get --data-dir $dirA
  if (-not ($out -match "dark")) { throw "theme did not stick: $out" }
  Write-Host "settings set OK"

  $null = & $exe share add $src --for 30m --label cli --data-dir $dirA
  # Reading /api/shares needs the UI token cookie; use the launch URL to get it.
  $ri = Wait-Run $dirA
  $u = [Uri]$ri.ui_url
  $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  Invoke-WebRequest -Uri $ri.ui_url -WebSession $s -UseBasicParsing | Out-Null
  $shares = Invoke-RestMethod -Uri "$($ri.base)/api/shares" -WebSession $s
  if ($shares.Count -ne 1 -or $shares[0].label -ne "cli") { throw "share add did not create the share" }
  Write-Host "share add OK (label=$($shares[0].label) lifetime=$($shares[0].lifetime))"

  $null = & $exe shares cancel-all --data-dir $dirA
  $shares = Invoke-RestMethod -Uri "$($ri.base)/api/shares" -WebSession $s
  if ($shares.Count -ne 0) { throw "cancel-all left shares" }
  Write-Host "shares cancel-all OK"

  $peers = & $exe peers --data-dir $dirA
  Write-Host "peers: $($peers -join '; ')"
  Write-Host "CLI INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
