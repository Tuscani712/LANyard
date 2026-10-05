$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-it-$stamp"
$dirA = Join-Path $base "A"
$dirB = Join-Path $base "B"
$src = Join-Path $base "src"
$dst = Join-Path $base "dst"
New-Item -ItemType Directory -Force -Path $dirA, $dirB, (Join-Path $src "sub"), $dst | Out-Null
Set-Content -Path (Join-Path $src "hello.txt") -Value "hello from A" -NoNewline
Set-Content -Path (Join-Path $src "sub\nested.bin") -Value ("x" * 5000) -NoNewline

function Start-Node($dir, $peer, $ui, $name) {
  Start-Process -FilePath $exe -WindowStyle Hidden -PassThru -ArgumentList @(
    "--data-dir", $dir, "--no-browser", "--no-tray", "--port", $peer, "--ui-port", $ui, "--name", $name
  )
}

function Wait-Run($dir) {
  $p = Join-Path $dir "run.json"
  for ($i = 0; $i -lt 50; $i++) {
    if (Test-Path $p) { return (Get-Content $p -Raw | ConvertFrom-Json) }
    Start-Sleep -Milliseconds 200
  }
  throw "run.json not created in $dir"
}

function Connect-UI($ri) {
  $u = [Uri]$ri.ui_url
  $token = [System.Web.HttpUtility]::ParseQueryString($u.Query).Get("t")
  $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  Invoke-WebRequest -Uri $ri.ui_url -WebSession $s -UseBasicParsing | Out-Null
  return [pscustomobject]@{ Session = $s; Origin = "http://127.0.0.1:$($u.Port)" }
}

Add-Type -AssemblyName System.Web
. "$PSScriptRoot\lwlib.ps1"

$a = Start-Node $dirA 47850 47860 "Alice"
$b = Start-Node $dirB 47851 47861 "Bob"
try {
  $riA = Wait-Run $dirA
  $riB = Wait-Run $dirB
  $ua = Connect-UI $riA
  $ub = Connect-UI $riB
  $oh = @{ Origin = $ua.Origin; "Content-Type" = "application/json" }

  # A shares the source folder.
  $share = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47860/api/shares" -WebSession $ua.Session -Headers $oh `
    -Body (@{ path = $src; label = "src"; lifetime = "until_stopped" } | ConvertTo-Json)
  Write-Host "share: $($share.share_id) kind=$($share.kind)"

  # B adds A and pairs (real trust, no bypass).
  $deviceId = Pair-Peers -UA $ua -UB $ub -AddrA "127.0.0.1:47850"
  Write-Host "paired with: $deviceId"

  # B lists A's shares.
  $remote = Invoke-RestMethod -Uri "http://127.0.0.1:47861/api/remote/shares?device=$deviceId" -WebSession $ub.Session
  Write-Host "remote shares: $($remote.Count) label=$($remote[0].label)"

  # B downloads the whole folder.
  $ohB = @{ Origin = $ub.Origin; "Content-Type" = "application/json" }
  $job = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47861/api/transfers" -WebSession $ub.Session -Headers $ohB `
    -Body (@{ device = $deviceId; share_id = $share.share_id; share_label = "src"; peer_name = "Alice"; paths = @(""); dest = $dst } | ConvertTo-Json)
  Write-Host "job: $($job.id) files=$($job.files.Count)"

  $state = ""
  for ($i = 0; $i -lt 100; $i++) {
    Start-Sleep -Milliseconds 200
    $list = Invoke-RestMethod -Uri "http://127.0.0.1:47861/api/transfers" -WebSession $ub.Session
    $state = $list[0].state
    if ($state -eq "Done" -or $state -eq "Failed") { break }
  }
  Write-Host "final state: $state"
  if ($state -ne "Done") { throw "transfer did not complete: $state" }

  $h = Get-Content (Join-Path $dst "hello.txt") -Raw
  if ($h -ne "hello from A") { throw "hello.txt mismatch: $h" }
  $n = (Get-Item (Join-Path $dst "sub\nested.bin")).Length
  if ($n -ne 5000) { throw "nested.bin size $n" }
  Write-Host "CONTENT OK"
  Write-Host "INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Stop-Process -Id $b.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
