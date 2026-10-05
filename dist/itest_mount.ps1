$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-mount-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$src = Join-Path $base "src"; $dst = Join-Path $base "dst"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $src, $dst | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $src "sub") | Out-Null
Set-Content -Path (Join-Path $src "secret.txt") -Value "paired hello" -NoNewline
Set-Content -Path (Join-Path $src "sub\deep.txt") -Value "deep file" -NoNewline

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
Add-Type -AssemblyName System.Net.Http
# Windows PowerShell 5.1 cannot send PROPFIND with Invoke-WebRequest, so use HttpClient.
function Dav($method, $url, $headers, $body) {
  $c = New-Object System.Net.Http.HttpClient
  $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($method)), $url
  foreach ($k in $headers.Keys) { $null = $req.Headers.TryAddWithoutValidation($k, [string]$headers[$k]) }
  if ($body) { $req.Content = New-Object System.Net.Http.StringContent $body }
  $resp = $c.SendAsync($req).Result
  [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
}
$a = Start-Node $dirA 47857 47867 "Alice"
$b = Start-Node $dirB 47858 47868 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)

  # A shares a folder.
  $share = Post $ua.Session $ua.Origin "/api/shares" @{ path = $src; label = "src"; lifetime = "until_stopped" }
  Write-Host "A shared $($share.share_id)"

  # B adds A manually.
  $addedA = Post $ub.Session $ub.Origin "/api/peers/add" @{ address = "127.0.0.1:47857" }
  $aFP = $addedA.device_id
  Write-Host "A fingerprint $($aFP.Substring(0,16))..."

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

  # --- B mounts A read-only ---
  $m = Post $ub.Session $ub.Origin "/api/mounts" @{ device = $aFP }
  Write-Host "mount: $($m.url)"
  if ($m.url -notmatch '^http://127\.0\.0\.1:\d+/[0-9a-f]{48}/$') { throw "unexpected mount URL: $($m.url)" }

  $root = Dav "PROPFIND" $m.url @{ Depth = "1" }
  if ($root.Status -ne 207 -or $root.Body -notmatch "src") { throw "root listing wrong: $($root.Status)" }
  $sub = Dav "PROPFIND" ($m.url + "src/sub") @{ Depth = "1" }
  if ($sub.Body -notmatch "deep.txt") { throw "sub-folder listing wrong" }
  $f = Dav "GET" ($m.url + "src/sub/deep.txt") @{}
  if ($f.Body -ne "deep file") { throw "file content wrong: $($f.Body)" }
  Write-Host "listed and read files through the mount"

  $put = Dav "PUT" ($m.url + "src/new.txt") @{} "x"
  if ($put.Status -ne 405) { throw "the mount accepted a write: $($put.Status)" }
  if (Test-Path (Join-Path $src "new.txt")) { throw "a write reached the sharer" }
  Write-Host "writes refused (read-only)"

  # A revokes B: the drive must stop working at once.
  $bFP = (Invoke-RestMethod -Uri "$($ua.Origin)/api/trust" -WebSession $ua.Session)[0].cert_fingerprint
  $null = Post $ua.Session $ua.Origin "/api/trust/$bFP/unpair" @{}
  Start-Sleep -Milliseconds 300
  $r1 = Dav "GET" ($m.url + "src/secret.txt") @{}
  if ($r1.Status -eq 200) { throw "the mount still works after the sharer unpaired us" }
  Write-Host "sharer unpaired us: mount read now fails ($($r1.Status))"

  # B unpairs A: the mount disappears.
  $null = Post $ub.Session $ub.Origin "/api/trust/$aFP/unpair" @{}
  Start-Sleep -Milliseconds 500
  $left = Invoke-RestMethod -Uri "$($ub.Origin)/api/mounts" -WebSession $ub.Session
  if (@($left).Count -ne 0) { throw "mount still listed after unpairing" }
  $r2 = Dav "PROPFIND" $m.url @{ Depth = "1" }
  if ($r2.Status -eq 200 -or $r2.Status -eq 207) { throw "the mount URL still answers after unpairing" }
  Write-Host "unpairing removed the mount"
  Write-Host "MOUNT INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
