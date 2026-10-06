# Verifies the SSE "notice" event reaches both devices: a downloader sees its
# download finish, a pusher sees its send finish, and the receiver sees the
# files start arriving and then land in the Inbox.
$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-notify-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$src = Join-Path $base "src"; $pushSrc = Join-Path $base "payload"; $dst = Join-Path $base "dst"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $src, $pushSrc, $dst | Out-Null
Set-Content -Path (Join-Path $src "hello.txt") -Value "hello from A" -NoNewline
Set-Content -Path (Join-Path $pushSrc "note.txt") -Value "pushed to inbox" -NoNewline

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
function Start-Collector($ui, $file) {
  $cookie = ($ui.Session.Cookies.GetCookies([Uri]$ui.Origin) | Where-Object { $_.Name -eq "lany" }).Value
  if (-not $cookie) { throw "no lany cookie for $($ui.Origin)" }
  # Start-Process joins array elements unquoted, so build one quoted command line.
  $line = "-s -N --max-time 90 -H `"Cookie: lany=$cookie`" -H `"Origin: $($ui.Origin)`" `"$($ui.Origin)/api/events`""
  return Start-Process -FilePath "curl.exe" -ArgumentList $line -RedirectStandardOutput $file -WindowStyle Hidden -PassThru
}
function Wait-State($ub, $id) {
  for ($i = 0; $i -lt 200; $i++) {
    Start-Sleep -Milliseconds 100
    $t = (LW-Get $ub "/api/transfers") | Where-Object { $_.id -eq $id }
    if ($t.state -eq "Done" -or $t.state -eq "Failed") { return $t.state }
  }
  return "timeout"
}
function Assert-Notice($text, $kind, $who, $where) {
  if ($text -notmatch "`"kind`":`"$kind`"") { throw "missing notice kind=$kind in $where" }
  if ($who -and $text -notmatch "`"peer`":`"$who`"") { throw "notice kind=$kind missing peer=$who in $where" }
}

Add-Type -AssemblyName System.Web
. "$PSScriptRoot\lwlib.ps1"

$fileA = Join-Path $base "A.sse"; $fileB = Join-Path $base "B.sse"
$a = Start-Node $dirA 47872 47882 "Alice"
$b = Start-Node $dirB 47873 47883 "Bob"
$colA = $null; $colB = $null
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)
  $colA = Start-Collector $ua $fileA
  $colB = Start-Collector $ub $fileB
  Start-Sleep -Milliseconds 600

  # Alice shares a folder; Bob pairs and downloads it (Bob pulls).
  $share = LW-Post $ua "/api/shares" @{ path = $src; label = "src"; lifetime = "until_stopped" }
  $fp = Pair-Peers -UA $ua -UB $ub -AddrA "127.0.0.1:47872"
  $job = LW-Post $ub "/api/transfers" @{ device = $fp; share_id = $share.share_id; share_label = "src"; peer_name = "Alice"; paths = @(""); dest = $dst }
  $state = Wait-State $ub $job.id
  if ($state -ne "Done") { throw "download ended: $state" }
  Write-Host "download complete"

  # Bob pushes a folder into Alice's Inbox (Bob sends, Alice receives).
  $push = LW-Post $ub "/api/push" @{ device = $fp; paths = @($pushSrc) }
  $state = Wait-State $ub $push.id
  if ($state -ne "Done") { throw "push ended: $state" }
  Write-Host "push complete"

  Start-Sleep -Milliseconds 1200
  $ta = Get-Content $fileA -Raw
  $tb = Get-Content $fileB -Raw

  Assert-Notice $tb "download" "Alice" "Bob's stream"
  Assert-Notice $tb "send" "Alice" "Bob's stream"
  Assert-Notice $ta "receive-start" "Bob" "Alice's stream"
  Assert-Notice $ta "receive" "Bob" "Alice's stream"
  Write-Host "notices reached both devices"
  Write-Host "NOTIFY INTEGRATION PASS"
}
finally {
  foreach ($p in @($colA, $colB, $a, $b)) { if ($p) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
  Start-Sleep -Milliseconds 300
}
