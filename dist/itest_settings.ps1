$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "lanyard-console.exe"
$stamp = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$base = Join-Path $env:TEMP "lanyard-set-$stamp"
$dirA = Join-Path $base "A"; $src = Join-Path $base "src"
New-Item -ItemType Directory -Force -Path $dirA, $src | Out-Null
Set-Content -Path (Join-Path $src "a.txt") -Value "hello" -NoNewline

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
$a = Start-Node $dirA 47870 47880 "Alice"
try {
  $ua = Connect-UI (Wait-Run $dirA)
  $h = @{ Origin = $ua.Origin; "Content-Type" = "application/json" }

  $s0 = Invoke-RestMethod -Uri "$($ua.Origin)/api/settings" -WebSession $ua.Session
  if ($s0.theme -ne "system" -or $s0.speed_unit -ne "mbs") { throw "unexpected defaults: theme=$($s0.theme) speed=$($s0.speed_unit)" }
  if (-not $s0.fingerprint) { throw "no fingerprint in settings" }
  Write-Host "defaults OK (theme=$($s0.theme) speed=$($s0.speed_unit))"

  # Invalid label is rejected.
  $bad = $false
  try { Invoke-RestMethod -Method Put -Uri "$($ua.Origin)/api/settings" -WebSession $ua.Session -Headers $h -Body (@{ device_id_label = "bad label" } | ConvertTo-Json) } catch { $bad = $true }
  if (-not $bad) { throw "invalid label was accepted" }

  $s1 = Invoke-RestMethod -Method Put -Uri "$($ua.Origin)/api/settings" -WebSession $ua.Session -Headers $h -Body (@{
    device_id_label = "alice-pc"; theme = "dark"; speed_unit = "mbps"; sound_on_complete = $true;
    default_download_folder = $src; bandwidth_limit_mbps = 5
  } | ConvertTo-Json)
  if ($s1.device_id_label -ne "alice-pc" -or $s1.theme -ne "dark" -or $s1.speed_unit -ne "mbps" -or -not $s1.sound_on_complete -or $s1.bandwidth_limit_mbps -ne 5) {
    throw "settings did not stick: $($s1 | ConvertTo-Json -Compress)"
  }
  Write-Host "update OK (theme=dark, speed=mbps, bw=5)"

  $t = Invoke-RestMethod -Method Put -Uri "$($ua.Origin)/api/settings" -WebSession $ua.Session -Headers $h -Body (@{ minimize_to_tray = $true } | ConvertTo-Json)
  if (-not $t.minimize_to_tray -or -not $t.tray_supported) { throw "minimize_to_tray did not stick: $($t | ConvertTo-Json -Compress)" }
  Write-Host "minimize to tray OK"

  $self = Invoke-RestMethod -Uri "$($ua.Origin)/api/self" -WebSession $ua.Session
  if ($self.device_label -ne "alice-pc") { throw "self label = $($self.device_label)" }
  Write-Host "self label OK"

  # Cancel all shares.
  $null = Invoke-RestMethod -Method Post -Uri "$($ua.Origin)/api/shares" -WebSession $ua.Session -Headers $h -Body (@{ path = $src; label = "src"; lifetime = "until_stopped" } | ConvertTo-Json)
  $before = Invoke-RestMethod -Uri "$($ua.Origin)/api/shares" -WebSession $ua.Session
  if ($before.Count -lt 1) { throw "share was not added" }
  $res = Invoke-RestMethod -Method Post -Uri "$($ua.Origin)/api/cancel-all" -WebSession $ua.Session -Headers $h -Body "{}"
  $after = Invoke-RestMethod -Uri "$($ua.Origin)/api/shares" -WebSession $ua.Session
  if ($after.Count -ne 0) { throw "cancel-all left shares: $($after.Count)" }
  Write-Host "cancel-all OK (stopped $($res.shares_stopped))"
  Write-Host "SETTINGS INTEGRATION PASS"
}
finally {
  Stop-Process -Id $a.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 300
}
