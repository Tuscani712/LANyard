$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-push-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$pushSrc = Join-Path $base "payload"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $pushSrc | Out-Null
Set-Content -Path (Join-Path $pushSrc "note.txt") -Value "pushed to inbox" -NoNewline

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
$a = Start-Node $dirA 47858 47868 "Alice"
$b = Start-Node $dirB 47859 47869 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)
  $added = Post $ub.Session $ub.Origin "/api/peers/add" @{ address = "127.0.0.1:47858" }
  $aFP = $added.device_id

  # Pair, with Alice granting Bob push.
  $bView = Post $ub.Session $ub.Origin "/api/sessions/request" @{ device = $aFP; mode = "pair"; permissions = @{ browse = $true; push = $false } }
  $aReq = $null
  for ($i = 0; $i -lt 50; $i++) {
    $list = Invoke-RestMethod -Uri "$($ua.Origin)/api/sessions" -WebSession $ua.Session
    $aReq = $list | Where-Object { $_.incoming -and $_.status -eq "pending" } | Select-Object -First 1
    if ($aReq) { break }; Start-Sleep -Milliseconds 100
  }
  if ($aReq.sas -ne $bView.sas) { throw "SAS mismatch" }
  $null = Post $ua.Session $ua.Origin "/api/sessions/$($aReq.id)/accept" @{ permissions = @{ browse = $true; push = $true }; keep_connected = $false }
  $ok = $false
  for ($i = 0; $i -lt 50; $i++) {
    $s = Post $ub.Session $ub.Origin "/api/sessions/$($bView.id)/refresh" @{}
    if ($s.status -eq "accepted" -or $s.status -eq "active") { $ok = $true; break }; Start-Sleep -Milliseconds 100
  }
  if (-not $ok) { throw "pair not accepted" }
  $null = Post $ub.Session $ub.Origin "/api/sessions/$($bView.id)/confirm" @{}
  Write-Host "paired"

  # Bob pushes a folder to Alice's Inbox.
  $job = Post $ub.Session $ub.Origin "/api/push" @{ device = $aFP; paths = @($pushSrc) }
  $state = ""
  for ($i = 0; $i -lt 200; $i++) {
    Start-Sleep -Milliseconds 100
    $t = (Invoke-RestMethod -Uri "$($ub.Origin)/api/transfers" -WebSession $ub.Session) | Where-Object { $_.id -eq $job.id }
    $state = $t.state
    if ($state -eq "Done" -or $state -eq "Failed") { break }
  }
  if ($state -ne "Done") { throw "push ended: $state" }
  $landed = Join-Path $dirA "Inbox\payload\note.txt"
  if (-not (Test-Path $landed)) { throw "file did not land in the Inbox: $landed" }
  if ((Get-Content $landed -Raw) -ne "pushed to inbox") { throw "content mismatch" }
  Write-Host "push landed in Inbox"

  # A second push must not overwrite.
  $job2 = Post $ub.Session $ub.Origin "/api/push" @{ device = $aFP; paths = @($pushSrc) }
  for ($i = 0; $i -lt 200; $i++) {
    Start-Sleep -Milliseconds 100
    $t = (Invoke-RestMethod -Uri "$($ub.Origin)/api/transfers" -WebSession $ub.Session) | Where-Object { $_.id -eq $job2.id }
    if ($t.state -eq "Done" -or $t.state -eq "Failed") { break }
  }
  if (-not (Test-Path (Join-Path $dirA "Inbox\payload\note (1).txt"))) { throw "collision file missing" }
  Write-Host "no overwrite (note (1).txt created)"
  Write-Host "PUSH INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
