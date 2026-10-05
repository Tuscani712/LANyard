$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-rs-$stamp"
$dirA = Join-Path $base "A"; $dirB = Join-Path $base "B"
$src = Join-Path $base "src"; $dst = Join-Path $base "dst"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, $src, $dst | Out-Null
$big = Join-Path $src "big.bin"
& fsutil file createnew $big 314572800 | Out-Null   # 300 MB of zeros

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
$a = Start-Node $dirA 47852 47862 "Alice"
$b = Start-Node $dirB 47853 47863 "Bob"
try {
  $ua = Connect-UI (Wait-Run $dirA); $ub = Connect-UI (Wait-Run $dirB)
  $ohA = @{ Origin = $ua.Origin; "Content-Type" = "application/json" }
  $ohB = @{ Origin = $ub.Origin; "Content-Type" = "application/json" }
  $share = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47862/api/shares" -WebSession $ua.Session -Headers $ohA `
    -Body (@{ path = $big; label = "big"; lifetime = "until_stopped" } | ConvertTo-Json)
  $dev = Pair-Peers -UA $ua -UB $ub -AddrA "127.0.0.1:47852"
  $job = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47863/api/transfers" -WebSession $ub.Session -Headers $ohB `
    -Body (@{ device = $dev; share_id = $share.share_id; share_label = "big"; peer_name = "Alice"; paths = @(""); dest = $dst } | ConvertTo-Json)
  $id = $job.id

  $paused = $false
  for ($i = 0; $i -lt 2000; $i++) {
    Start-Sleep -Milliseconds 10
    $t = (Invoke-RestMethod -Uri "http://127.0.0.1:47863/api/transfers" -WebSession $ub.Session)[0]
    if ($t.state -eq "Transferring") {
      Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47863/api/transfers/$id/pause" -WebSession $ub.Session -Headers $ohB | Out-Null
      $paused = $true; break
    }
    if ($t.state -eq "Done") { break }
  }
  Start-Sleep -Milliseconds 300
  $part = Join-Path $dst "big.bin.lanpart"
  $side = Join-Path $dst "big.bin.lanstate"
  Write-Host "paused=$paused partExists=$(Test-Path $part) sideExists=$(Test-Path $side) partSize=$((Get-Item $part -ErrorAction SilentlyContinue).Length)"

  Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47863/api/transfers/$id/resume" -WebSession $ub.Session -Headers $ohB | Out-Null
  $state = ""
  for ($i = 0; $i -lt 300; $i++) {
    Start-Sleep -Milliseconds 100
    $state = (Invoke-RestMethod -Uri "http://127.0.0.1:47863/api/transfers" -WebSession $ub.Session)[0].state
    if ($state -eq "Done" -or $state -eq "Failed") { break }
  }
  Write-Host "final=$state"
  $hs = (Get-FileHash $big -Algorithm SHA256).Hash
  $hd = (Get-FileHash (Join-Path $dst "big.bin") -Algorithm SHA256).Hash
  Write-Host "src=$hs"
  Write-Host "dst=$hd"
  if ($state -ne "Done" -or $hs -ne $hd) { throw "resume failed" }
  Write-Host "RESUME PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
