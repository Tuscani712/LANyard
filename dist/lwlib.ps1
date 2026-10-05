# Shared helpers for the end-to-end scripts.
#
# Scripts no longer run with a trust-store bypass; they pair the two instances
# first, exactly as a user would, and then exercise the real authorization.

function LW-Post($ui, $path, $obj) {
  Invoke-RestMethod -Method Post -Uri ($ui.Origin + $path) -WebSession $ui.Session `
    -Headers @{ Origin = $ui.Origin; "Content-Type" = "application/json" } -Body ($obj | ConvertTo-Json)
}

function LW-Get($ui, $path) {
  Invoke-RestMethod -Uri ($ui.Origin + $path) -WebSession $ui.Session
}

# Pair $ub (initiator) with $ua (responder) at $addrA. Returns A's fingerprint.
# $ua/$ub are objects with .Session and .Origin, as returned by each script's
# Connect-UI. A grants B browse (and push, harmless for pull tests).
function Pair-Peers($ua, $ub, $addrA) {
  $added = LW-Post $ub "/api/peers/add" @{ address = $addrA }
  $fp = $added.device_id
  $req = LW-Post $ub "/api/sessions/request" @{
    device = $fp; mode = "pair"; permissions = @{ browse = $true; push = $false }; keep_connected = $false
  }
  $aReq = $null
  for ($i = 0; $i -lt 100; $i++) {
    $list = LW-Get $ua "/api/sessions"
    $aReq = $list | Where-Object { $_.incoming -and $_.status -eq "pending" } | Select-Object -First 1
    if ($aReq) { break }
    Start-Sleep -Milliseconds 100
  }
  if (-not $aReq) { throw "pairing request was not seen by the responder" }
  if ($aReq.sas -ne $req.sas) { throw "SAS mismatch: responder=$($aReq.sas) initiator=$($req.sas)" }
  $null = LW-Post $ua "/api/sessions/$($aReq.id)/accept" @{ permissions = @{ browse = $true; push = $true }; keep_connected = $false }
  $ok = $false
  for ($i = 0; $i -lt 100; $i++) {
    $s = LW-Post $ub "/api/sessions/$($req.id)/refresh" @{}
    if ($s.status -eq "accepted" -or $s.status -eq "active") { $ok = $true; break }
    Start-Sleep -Milliseconds 100
  }
  if (-not $ok) { throw "pairing was not accepted by the responder" }
  $null = LW-Post $ub "/api/sessions/$($req.id)/confirm" @{}
  return $fp
}
