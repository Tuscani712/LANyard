$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-pair-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$src = Join-Path $base "src"; $dst = Join-Path $base "dst"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $src, $dst | Out-Null
Set-Content -Path (Join-Path $src "secret.txt") -Value "paired hello" -NoNewline

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
function Post($sess, $origin, $path, $obj) {
  Invoke-RestMethod -Method Post -Uri $origin$path -WebSession $sess -Headers @{ Origin = $origin; "Content-Type" = "application/json" } -Body ($obj | ConvertTo-Json)
}
Add-Type -AssemblyName System.Web
$a = Start-Node $dirA 47854 47864 "Alice"
$b = Start-Node $dirB 47855 47865 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)

  # A shares a folder.
  $share = Post $ua.Session $ua.Origin "/api/shares" @{ path = $src; label = "src"; lifetime = "until_stopped" }
  Write-Host "A shared $($share.share_id)"

  # B adds A manually.
  $addedA = Post $ub.Session $ub.Origin "/api/peers/add" @{ address = "127.0.0.1:47854" }
  $aFP = $addedA.device_id
  Write-Host "A fingerprint $($aFP.Substring(0,16))..."

  # Before pairing, B cannot browse A.
  $blocked = $false
  try { Invoke-RestMethod -Uri "$($ub.Origin)/api/remote/shares?device=$aFP" -WebSession $ub.Session | Out-Null }
  catch { $blocked = $true }
  if (-not $blocked) { throw "unpaired peer was allowed to list shares" }
  Write-Host "unpaired browse correctly refused"

  # B initiates pairing (B grants A browse).
  $bView = Post $ub.Session $ub.Origin "/api/sessions/request" @{ device = $aFP; mode = "pair"; permissions = @{ browse = $true; push = $false }; keep_connected = $false }
  Write-Host "B session $($bView.id) sas=$($bView.sas) status=$($bView.status)"
  if ($bView.sas.Length -ne 6) { throw "no SAS on initiator" }

  # A sees an incoming pending request; its SAS must match B's.
  $aReq = $null
  for ($i = 0; $i -lt 50; $i++) {
    $list = Invoke-RestMethod -Uri "$($ua.Origin)/api/sessions" -WebSession $ua.Session
    $aReq = $list | Where-Object { $_.incoming -and $_.status -eq "pending" } | Select-Object -First 1
    if ($aReq) { break }
    Start-Sleep -Milliseconds 100
  }
  if (-not $aReq) { throw "A never saw the request" }
  Write-Host "A sees sas=$($aReq.sas)"
  if ($aReq.sas -ne $bView.sas) { throw "SAS mismatch: A=$($aReq.sas) B=$($bView.sas)" }

  # A accepts, granting B browse.
  $null = Post $ua.Session $ua.Origin "/api/sessions/$($aReq.id)/accept" @{ permissions = @{ browse = $true; push = $false }; keep_connected = $false }

  # B polls the peer (refresh) until accepted, then confirms the code.
  $accepted = $false
  for ($i = 0; $i -lt 50; $i++) {
    $s = Post $ub.Session $ub.Origin "/api/sessions/$($bView.id)/refresh" @{}
    if ($s.status -eq "accepted" -or $s.status -eq "active") { $accepted = $true; break }
    Start-Sleep -Milliseconds 100
  }
  if (-not $accepted) { throw "B never saw acceptance" }
  $conf = Post $ub.Session $ub.Origin "/api/sessions/$($bView.id)/confirm" @{}
  Write-Host "B confirmed: $($conf.status)"

  # Both sides now have a trust entry.
  $bTrust = Invoke-RestMethod -Uri "$($ub.Origin)/api/trust" -WebSession $ub.Session
  if (-not $bTrust -or $bTrust.Count -lt 1) { throw "B has no trust entry" }
  $aTrust = Invoke-RestMethod -Uri "$($ua.Origin)/api/trust" -WebSession $ua.Session
  if (-not $aTrust -or $aTrust.Count -lt 1) { throw "A has no trust entry" }
  Write-Host "paired both sides"

  # B can now browse and pull.
  $remote = Invoke-RestMethod -Uri "$($ub.Origin)/api/remote/shares?device=$aFP" -WebSession $ub.Session
  if (-not ($remote | Where-Object { $_.share_id -eq $share.share_id })) { throw "paired peer cannot see the share" }
  $job = Post $ub.Session $ub.Origin "/api/transfers" @{ device = $aFP; share_id = $share.share_id; share_label = "src"; peer_name = "Alice"; paths = @(""); dest = $dst }
  $state = ""
  for ($i = 0; $i -lt 200; $i++) {
    Start-Sleep -Milliseconds 100
    $t = (Invoke-RestMethod -Uri "$($ub.Origin)/api/transfers" -WebSession $ub.Session) | Where-Object { $_.id -eq $job.id }
    $state = $t.state
    if ($state -eq "Done" -or $state -eq "Failed") { break }
  }
  if ($state -ne "Done") { throw "transfer after pairing: $state" }
  $content = Get-Content (Join-Path $dst "secret.txt") -Raw
  if ($content -ne "paired hello") { throw "content mismatch: $content" }
  Write-Host "paired pull OK"

  # Unpair on A; B must be refused again.
  $bFP = $aTrust[0].cert_fingerprint
  $null = Post $ua.Session $ua.Origin "/api/trust/$bFP/unpair" @{}
  Start-Sleep -Milliseconds 300
  $blocked2 = $false
  try { Invoke-RestMethod -Uri "$($ub.Origin)/api/remote/shares?device=$aFP" -WebSession $ub.Session | Out-Null } catch { $blocked2 = $true }
  if (-not $blocked2) { throw "revoked peer was still allowed" }
  Write-Host "unpair revoked access"
  Write-Host "PAIR INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
