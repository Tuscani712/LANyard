$ErrorActionPreference = "Stop"
# Paired-device permission editing: changing "browse" for a paired peer takes
# effect on the next request, without re-pairing.
$exe = Join-Path $PSScriptRoot "lanyard.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-perm-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$src = Join-Path $base "src"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $src | Out-Null
Set-Content -Path (Join-Path $src "a.txt") -Value "hi" -NoNewline

function Start-Node($dir, $peer, $ui, $name) {
  Start-Process -FilePath $exe -WindowStyle Hidden -PassThru -ArgumentList @(
    "--data-dir", $dir, "--no-browser", "--no-tray", "--port", $peer, "--ui-port", $ui, "--name", $name)
}
function Wait-Run($dir) {
  $p = Join-Path $dir "run.json"
  for ($i = 0; $i -lt 50; $i++) { if (Test-Path $p) { return (Get-Content $p -Raw | ConvertFrom-Json) }; Start-Sleep -Milliseconds 200 }
  throw "no run.json"
}
function Connect-UI($ri) {
  $u = [Uri]$ri.ui_url
  $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  Invoke-WebRequest -Uri $ri.ui_url -WebSession $s -UseBasicParsing | Out-Null
  return [pscustomobject]@{ Session = $s; Origin = "http://127.0.0.1:$($u.Port)" }
}
Add-Type -AssemblyName System.Web
. "$PSScriptRoot\lwlib.ps1"
$a = Start-Node $dirA 47872 47882 "Alice"
$b = Start-Node $dirB 47873 47883 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)
  $null = LW-Post $ua "/api/shares" @{ path = $src; label = "src"; lifetime = "until_stopped" }
  $fpA = Pair-Peers -UA $ua -UB $ub -AddrA "127.0.0.1:47872"
  Write-Host "paired (A=$fpA)"

  $remote = LW-Get $ub "/api/remote/shares?device=$fpA"
  if (-not ($remote | Where-Object { $_.label -eq "src" })) { throw "paired peer cannot browse" }
  Write-Host "browse allowed before edit"

  # Find B's fingerprint in A's trust store and revoke browse.
  $trust = LW-Get $ua "/api/trust"
  $bFP = $trust[0].cert_fingerprint
  $null = LW-Post $ua "/api/trust/$bFP/permissions" @{ browse = $false; push = $false; push_max_bytes = 0; ask_over = 0 }
  $e = (LW-Get $ua "/api/trust")[0]
  if ($e.permissions.browse) { throw "permissions did not update" }
  Write-Host "browse revoked via permissions edit"

  $blocked = $false
  try { LW-Get $ub "/api/remote/shares?device=$fpA" | Out-Null } catch { $blocked = $true }
  if (-not $blocked) { throw "revoking browse did not take effect" }
  Write-Host "browse refused after edit"

  # Grant it back.
  $null = LW-Post $ua "/api/trust/$bFP/permissions" @{ browse = $true; push = $false; push_max_bytes = 0; ask_over = 0 }
  $remote = LW-Get $ub "/api/remote/shares?device=$fpA"
  if (-not ($remote | Where-Object { $_.label -eq "src" })) { throw "browse not restored" }
  Write-Host "browse granted again"
  Write-Host "PERMISSIONS INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
