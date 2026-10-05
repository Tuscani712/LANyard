$ErrorActionPreference = "Stop"
# Pushes a folder of 3,000 small files plus one 2 MB file from Bob to Alice's
# Inbox through the real programs, then checks every file arrived intact
# (count, sizes, and SHA-256 of a sample and of the big file) and how long it took.
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-pushmany-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$payload = Join-Path $base "payload"
New-Item -ItemType Directory -Force -Path $dirA, $dirB | Out-Null

$rng = New-Object System.Random 4242
$count = 3000
for ($d = 0; $d -lt 30; $d++) {
  $sub = Join-Path $payload ("d{0:D2}" -f $d)
  New-Item -ItemType Directory -Force -Path $sub | Out-Null
  for ($i = 0; $i -lt ($count / 30); $i++) {
    $b = New-Object byte[] (100 + $rng.Next(900)); $rng.NextBytes($b)
    [IO.File]::WriteAllBytes((Join-Path $sub ("f{0:D3}.dat" -f $i)), $b)
  }
}
$big = New-Object byte[] (2MB); $rng.NextBytes($big)
[IO.File]::WriteAllBytes((Join-Path $payload "big.bin"), $big)

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
$a = Start-Node $dirA 47874 47884 "Alice"
$b = Start-Node $dirB 47875 47885 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)
  $aFP = Pair-Peers $ua $ub "127.0.0.1:47874"
  Write-Host "paired"

  $sw = [Diagnostics.Stopwatch]::StartNew()
  $job = LW-Post $ub "/api/push" @{ device = $aFP; paths = @($payload) }
  $state = ""
  for ($i = 0; $i -lt 1200; $i++) {
    Start-Sleep -Milliseconds 100
    $t = (LW-Get $ub "/api/transfers") | Where-Object { $_.id -eq $job.id }
    $state = $t.state
    if ($state -eq "Done" -or $state -eq "Failed") { break }
  }
  $sw.Stop()
  if ($state -ne "Done") { throw "push ended as '$state': $($t.error)" }
  Write-Host ("pushed {0} files in {1:N1} s ({2:N0} files/s)" -f ($count + 1), $sw.Elapsed.TotalSeconds, (($count + 1) / $sw.Elapsed.TotalSeconds))

  $inbox = Join-Path $dirA "Inbox\payload"
  $got = @(Get-ChildItem $inbox -Recurse -File | Where-Object { $_.Extension -ne ".lanpart" -and $_.Extension -ne ".lanstate" })
  if ($got.Count -ne ($count + 1)) { throw "Inbox has $($got.Count) files, expected $($count + 1)" }
  $left = @(Get-ChildItem $inbox -Recurse -File -Include *.lanpart, *.lanstate)
  if ($left.Count -ne 0) { throw "$($left.Count) leftover .lanpart/.lanstate files" }

  $srcFiles = Get-ChildItem $payload -Recurse -File
  $bad = 0
  foreach ($f in ($srcFiles | Get-Random -Count 150 -SetSeed 7)) {
    $rel = $f.FullName.Substring($payload.Length)
    $dst = Join-Path $inbox $rel
    if (-not (Test-Path $dst) -or (Get-FileHash $dst).Hash -ne (Get-FileHash $f.FullName).Hash) { $bad++ }
  }
  if ($bad -ne 0) { throw "$bad sampled files differ" }
  if ((Get-FileHash (Join-Path $inbox "big.bin")).Hash -ne (Get-FileHash (Join-Path $payload "big.bin")).Hash) { throw "big.bin differs" }
  # sizes of everything
  $sizeBad = 0
  foreach ($f in $srcFiles) { if ((Get-Item (Join-Path $inbox $f.FullName.Substring($payload.Length))).Length -ne $f.Length) { $sizeBad++ } }
  if ($sizeBad -ne 0) { throw "$sizeBad files have the wrong size" }
  Write-Host "all $($count + 1) files present with the right size; 150 sampled files and big.bin hash-identical"
  Write-Host "PUSH MANY INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
