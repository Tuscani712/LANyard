$ErrorActionPreference = "Stop"
# Optional, manual: mounts a paired device as a real Windows drive (Z:).
# It needs Windows' WebClient service running (start it once in an administrator
# prompt: sc start WebClient). Without it the script reports SKIP.
if ((Get-Service WebClient -ErrorAction SilentlyContinue).Status -ne "Running") {
  Write-Host "SKIP: the Windows WebClient service is not running, so web folders cannot be mounted."
  exit 0
}
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-mountos-$stamp"
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
$a = Start-Node $dirA 47859 47869 "Alice"
$b = Start-Node $dirB 47860 47870 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)

  # A shares a folder.
  $share = Post $ua.Session $ua.Origin "/api/shares" @{ path = $src; label = "src"; lifetime = "until_stopped" }
  Write-Host "A shared $($share.share_id)"

  # B adds A manually.
  $addedA = Post $ub.Session $ub.Origin "/api/peers/add" @{ address = "127.0.0.1:47859" }
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

  # --- B mounts A as a real Windows drive ---
  $drive = "Z:"
  if (Test-Path "${drive}\") { throw "$drive is already in use; free it or edit this script" }
  $m = Post $ub.Session $ub.Origin "/api/mounts" @{ device = $aFP; drive = $drive }
  Write-Host "mounted=$($m.mounted) drive=$($m.drive) error=$($m.os_error)"
  if (-not $m.mounted) { throw "Windows did not mount the drive: $($m.os_error)" }

  Write-Host ("Z:\ lists: " + ((Get-ChildItem "${drive}\" | ForEach-Object Name) -join ", "))
  $txt = Get-Content "${drive}\src\secret.txt" -Raw
  if ($txt -ne "paired hello") { throw "content through the drive wrong: [$txt]" }
  $deep = Get-Content "${drive}\src\sub\deep.txt" -Raw
  if ($deep -ne "deep file") { throw "nested content wrong: [$deep]" }
  Write-Host "read files through the Windows drive"

  $wrote = $true
  try { Set-Content -Path "${drive}\src
ew.txt" -Value "x" -ErrorAction Stop } catch { $wrote = $false }
  if ($wrote -or (Test-Path (Join-Path $src "new.txt"))) { throw "a write went through the read-only drive" }
  Write-Host "writes through the drive are refused"

  $null = Post $ub.Session $ub.Origin "/api/mounts/$($m.id)/remove" @{}
  Start-Sleep -Milliseconds 800
  if (Test-Path "${drive}\") { throw "the drive is still mapped after unmounting" }
  Write-Host "unmounted: drive removed"
  Write-Host "WINDOWS DRIVE PASS"
}
finally {
  try { & net use Z: /delete /y 2>&1 | Out-Null } catch { }
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
