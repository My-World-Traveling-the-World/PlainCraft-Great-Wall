param([Parameter(Mandatory)][string]$CaseDirectory)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$fault=Get-Content -LiteralPath (Join-Path $CaseDirectory 'fault.json') -Raw | ConvertFrom-Json
if ($fault.fault -notin @('missing','corrupt')) { throw 'Expected a damaged-evidence fixture' }
$before=@(Get-Content -LiteralPath (Join-Path $CaseDirectory 'crash-terrain\barrier-results.json') -Raw | ConvertFrom-Json)
$after=@(Get-Content -LiteralPath (Join-Path $CaseDirectory 'resume-terrain\barrier-results.json') -Raw | ConvertFrom-Json)
$events=@(Get-Content -LiteralPath (Join-Path $CaseDirectory 'resume-terrain\barrier-events.json') -Raw | ConvertFrom-Json)
$session=Get-Content -LiteralPath (Join-Path $CaseDirectory 'resume-terrain\barrier-session.json') -Raw | ConvertFrom-Json -AsHashtable
$name=[IO.Path]::GetFileName($fault.path)
function Assert-TerrainFaultScope($before, $after, $events, $session, [string]$name) {
$target=@($before | Where-Object {
    $identity="minecraft:overworld:$($_.x):$($_.z):80:118"
    $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($identity))).ToLowerInvariant()
    "$digest.t0" -ceq $name
})
if ($before.Count -ne 4 -or $target.Count -ne 1) { throw 'Cannot identify the damaged T0 owner from pre-crash evidence' }
# The one-round laboratory cell membership must match BarrierFixtureLayout.cell.
$cells=@{
    '2000:2000'=@('2000:2000','2001:2000')
    '2001:2000'=@('2001:2000','2001:2001')
    '2000:2001'=@('2000:2000','2000:2001','2001:2001')
    '2001:2001'=@('2000:2000','2001:2000','2000:2001','2001:2001')
}
foreach ($owner in $before) {
    if ($owner.round -ne 0 -or !$cells.ContainsKey("$($owner.x):$($owner.z)")) { throw 'Unsupported fault-fixture layout' }
}
$targetKey="$($target[0].x):$($target[0].z)"
$affected=@($cells.Keys | Where-Object { $cells[$_] -contains $targetKey } | Sort-Object)
if (!$session.closed -or $session.leases -ne 0 -or $session.delays -ne 0 -or
        @($events | Where-Object event -eq 'T0_CAPTURED').Count -ne 0) {
    throw 'Damaged-evidence recovery recaptured terrain or did not close'
}
if (@($events | Where-Object {
    ($_.event -in @('APPLY_ENTER','APPLY_EXIT','APPLY_REPLAY_NOOP')) -and
        $affected -contains "$($_.chunkX):$($_.chunkZ)"
}).Count -ne 0) { throw 'An owner depending on damaged evidence entered APPLY' }
foreach ($owner in $after) {
    if ($affected -contains "$($owner.x):$($owner.z)") { throw 'Damaged cell published an output' }
    $expected=@($before | Where-Object { $_.x -eq $owner.x -and $_.z -eq $owner.z })
    if ($expected.Count -ne 1 -or $expected[0].t0Hash -cne $owner.t0Hash -or
            $expected[0].realizationHash -cne $owner.realizationHash -or $expected[0].finalHash -cne $owner.finalHash) {
        throw 'Independent healthy recovery changed the pre-crash result'
    }
}
[ordered]@{damagedOwner=$targetKey;affectedOwners=$affected;affectedApplyCount=0;
    recaptureCount=0;healthyOutputCount=$after.Count;healthyHashesUnchanged=$true}
}
$scope=Assert-TerrainFaultScope $before $after $events $session $name
$saved=Join-Path $CaseDirectory "crash-terrain\evidence\$name"
$resumed=Join-Path $CaseDirectory "resume-terrain\evidence\$name"
if ($fault.fault -eq 'missing') {
    if (Test-Path -LiteralPath $resumed) { throw 'Missing authoritative evidence was silently recreated' }
} else {
    $bytes=[IO.File]::ReadAllBytes($saved)
    $bytes[12]=$bytes[12] -bxor 1
    $expected=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
    if ((Get-FileHash -LiteralPath $resumed -Algorithm SHA256).Hash -cne $expected) {
        throw 'Corrupt authoritative evidence was silently replaced'
    }
}
$scope['evidenceNotRecreated']=$true
$scope
