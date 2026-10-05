$ErrorActionPreference = "Stop"
# End-to-end share lifetimes: a one-time share is retired by a verified download
# (and a second download is refused with a reason), and a timed share vanishes
# after its time, with the receiver told it expired. The two instances pair
# first (real trust; no bypass).
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-lt-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$srcOne = Join-Path $base "one"; $srcTimed = Join-Path $base "timed"
$dst1 = Join-Path $base "dst1"; $dst2 = Join-Path $base "dst2"; $dst3 = Join-Path $base "dst3"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $srcOne, $srcTimed, $dst1, $dst2, $dst3 | Out-Null
Set-Content -Path (Join-Path $srcOne "once.txt") -Value "only once" -NoNewline
Set-Content -Path (Join-Path $srcTimed "timed.txt") -Value "for a while" -NoNewline

function Start-Node($dir, $peer, $ui, $name) {
  Start-Process -FilePath $exe -WindowStyle Hidden -PassThru -ArgumentList @(
    "--data-dir", $dir, "--no-browser", "--no-tray", "--port", $peer, "--ui-port", $ui, "--name", $name)
}
function Wait-Run($dir) {
  $p = Join-Path $dir "run.json"
  for ($i = 0; $i -lt 50; $i++) { if (Test-Path $p) { return (Get-Content $p -Raw | ConvertFrom-Json) }; Start-Sleep -Milliseconds 200 }
  throw "run.json not created in $dir"
}
function Connect-UI($ri) {
  $u = [Uri]$ri.ui_url
  $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  Invoke-WebRequest -Uri $ri.ui_url -WebSession $s -UseBasicParsing | Out-Null
  return [pscustomobject]@{ Session = $s; Origin = "http://127.0.0.1:$($u.Port)" }
}
function Start-Download($ub, $ohB, $dev, $share, $label, $dest) {
  Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47866/api/transfers" -WebSession $ub.Session -Headers $ohB `
    -Body (@{ device = $dev; share_id = $share.share_id; share_label = $label; peer_name = "Alice"; paths = @(""); dest = $dest } | ConvertTo-Json)
}
function Wait-Job-State($ub, $id) {
  $state = ""
  for ($i = 0; $i -lt 100; $i++) {
    Start-Sleep -Milliseconds 200
    $j = (Invoke-RestMethod -Uri "http://127.0.0.1:47866/api/transfers" -WebSession $ub.Session) | Where-Object { $_.id -eq $id }
    $state = $j.state
    if ($state -eq "Done" -or $state -eq "Failed") { break }
  }
  return $state
}
. "$PSScriptRoot\lwlib.ps1"

$a = Start-Node $dirA 47855 47865 "Alice"
$b = Start-Node $dirB 47856 47866 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)
  $ohA = @{ Origin = $ua.Origin; "Content-Type" = "application/json" }
  $ohB = @{ Origin = $ub.Origin; "Content-Type" = "application/json" }
  $sharesUrl = "http://127.0.0.1:47865/api/shares"

  $once = Invoke-RestMethod -Method Post -Uri $sharesUrl -WebSession $ua.Session -Headers $ohA `
    -Body (@{ path = $srcOne; label = "once"; lifetime = "one_time" } | ConvertTo-Json)
  $timed = Invoke-RestMethod -Method Post -Uri $sharesUrl -WebSession $ua.Session -Headers $ohA `
    -Body (@{ path = $srcTimed; label = "timed"; lifetime = "timed"; seconds = 14 } | ConvertTo-Json)
  $dev = Pair-Peers -UA $ua -UB $ub -AddrA "127.0.0.1:47855"

  # Bob browses the timed share now so the sender knows he used it (-> "expired", not "not found" later).
  Invoke-RestMethod -Uri "http://127.0.0.1:47866/api/remote/tree?device=$dev&share=$($timed.share_id)&path=" -WebSession $ub.Session | Out-Null

  # 1. One-time: first download completes and retires the share.
  $job = Start-Download $ub $ohB $dev $once "once" $dst1
  $st = Wait-Job-State $ub $job.id
  Write-Host "one-time download: $st"
  if ($st -ne "Done") { throw "one-time download failed: $st" }
  if ((Get-Content (Join-Path $dst1 "once.txt") -Raw) -ne "only once") { throw "content mismatch" }
  $gone = $false
  for ($i = 0; $i -lt 40; $i++) {
    $mine = Invoke-RestMethod -Uri $sharesUrl -WebSession $ua.Session
    if (-not ($mine | Where-Object { $_.share_id -eq $once.share_id })) { $gone = $true; break }
    Start-Sleep -Milliseconds 250
  }
  if (-not $gone) { throw "the one-time share was not retired after a verified download" }
  Write-Host "one-time share retired by the sender"

  # 2. A second download of the consumed share is refused with a reason.
  $msg = ""
  try { Start-Download $ub $ohB $dev $once "once" $dst2 | Out-Null } catch { $msg = "$($_.ErrorDetails.Message)" }
  Write-Host "second download: $msg"
  if ($msg -notmatch "already been downloaded") { throw "expected 'already been downloaded', got: $msg" }

  # 3. Timed share disappears after its time and the receiver is told it expired.
  $deadline = (Get-Date).AddSeconds(40)
  $expired = $false
  while ((Get-Date) -lt $deadline) {
    $mine = Invoke-RestMethod -Uri $sharesUrl -WebSession $ua.Session
    if (-not ($mine | Where-Object { $_.share_id -eq $timed.share_id })) { $expired = $true; break }
    Start-Sleep -Milliseconds 500
  }
  if (-not $expired) { throw "timed share never expired" }
  Write-Host "timed share expired and left the sender's list"
  $msg = ""
  try { Start-Download $ub $ohB $dev $timed "timed" $dst3 | Out-Null } catch { $msg = "$($_.ErrorDetails.Message)" }
  Write-Host "download after expiry: $msg"
  if ($msg -notmatch "expired") { throw "expected 'expired', got: $msg" }
  Write-Host "LIFETIME INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
