$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-conn-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$offered = Join-Path $base "offered"; $hidden = Join-Path $base "hidden"; $dst = Join-Path $base "dst"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $offered, $hidden, $dst | Out-Null
Set-Content -Path (Join-Path $offered "yes.txt") -Value "offered content" -NoNewline
Set-Content -Path (Join-Path $hidden "no.txt") -Value "must not leak" -NoNewline

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
$a = Start-Node $dirA 47856 47866 "Alice"
$b = Start-Node $dirB 47857 47867 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)
  $s1 = Post $ua.Session $ua.Origin "/api/shares" @{ path = $offered; label = "offered"; lifetime = "until_stopped" }
  $s2 = Post $ua.Session $ua.Origin "/api/shares" @{ path = $hidden; label = "hidden"; lifetime = "until_stopped" }
  $added = Post $ub.Session $ub.Origin "/api/peers/add" @{ address = "127.0.0.1:47856" }
  $aFP = $added.device_id

  $bView = Post $ub.Session $ub.Origin "/api/sessions/request" @{ device = $aFP; mode = "connect"; permissions = @{}; keep_connected = $true }
  Write-Host "connect sas=$($bView.sas)"

  $aReq = $null
  for ($i = 0; $i -lt 50; $i++) {
    $list = Invoke-RestMethod -Uri "$($ua.Origin)/api/sessions" -WebSession $ua.Session
    $aReq = $list | Where-Object { $_.incoming -and $_.status -eq "pending" } | Select-Object -First 1
    if ($aReq) { break }; Start-Sleep -Milliseconds 100
  }
  if (-not $aReq) { throw "A saw no request" }
  if ($aReq.sas -ne $bView.sas) { throw "SAS mismatch" }
  $null = Post $ua.Session $ua.Origin "/api/sessions/$($aReq.id)/accept" @{ permissions = @{}; keep_connected = $false }
  $null = Post $ua.Session $ua.Origin "/api/sessions/$($aReq.id)/offers" @{ share_ids = @($s1.share_id) }

  $accepted = $false
  for ($i = 0; $i -lt 50; $i++) {
    $s = Post $ub.Session $ub.Origin "/api/sessions/$($bView.id)/refresh" @{}
    if ($s.status -eq "accepted" -or $s.status -eq "active") { $accepted = $true; break }; Start-Sleep -Milliseconds 100
  }
  if (-not $accepted) { throw "not accepted" }
  $c = Post $ub.Session $ub.Origin "/api/sessions/$($bView.id)/confirm" @{}
  Write-Host "connect active: $($c.status)"

  $remote = Invoke-RestMethod -Uri "$($ub.Origin)/api/remote/shares?device=$aFP" -WebSession $ub.Session
  $ids = @($remote | ForEach-Object { $_.share_id })
  Write-Host "visible shares: $($ids -join ',')"
  if ($ids -contains $s2.share_id) { throw "session exposed a share that was not offered" }
  if (-not ($ids -contains $s1.share_id)) { throw "offered share is missing" }

  $job = Post $ub.Session $ub.Origin "/api/transfers" @{ device = $aFP; share_id = $s1.share_id; share_label = "offered"; peer_name = "Alice"; paths = @(""); dest = $dst }
  $state = ""
  for ($i = 0; $i -lt 200; $i++) {
    Start-Sleep -Milliseconds 100
    $t = (Invoke-RestMethod -Uri "$($ub.Origin)/api/transfers" -WebSession $ub.Session) | Where-Object { $_.id -eq $job.id }
    $state = $t.state
    if ($state -eq "Done" -or $state -eq "Failed") { break }
  }
  if ($state -ne "Done") { throw "connect pull: $state" }
  if ((Get-Content (Join-Path $dst "yes.txt") -Raw) -ne "offered content") { throw "content mismatch" }
  Write-Host "connect pull OK"
  Write-Host "CONNECT INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
