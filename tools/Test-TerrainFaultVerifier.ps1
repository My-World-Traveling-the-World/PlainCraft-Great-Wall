param([Parameter(Mandatory)][string]$CaseDirectory)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=Split-Path $PSScriptRoot -Parent
$null=. (Join-Path $PSScriptRoot 'Test-TerrainFaultEvidence.ps1') -CaseDirectory $CaseDirectory
if ($after.Count -lt 1) { throw 'Choose recorded evidence containing an independent healthy replay' }
$checks=[Collections.Generic.List[object]]::new()
$checks.Add([ordered]@{case='independent healthy replay';passed=$true})
function Test-Rejected([string]$label,[scriptblock]$test,[string]$message) {
    try { $null=& $test }
    catch {
        if ($_.Exception.Message -notlike $message) { throw }
        $checks.Add([ordered]@{case=$label;passed=$true;reason=$_.Exception.Message})
        return
    }
    throw "Verifier unexpectedly accepted $label"
}
$badEvents=@($events + [pscustomobject]@{event='APPLY_ENTER';chunkX=2000;chunkZ=2000})
Test-Rejected 'affected owner writes' { Assert-TerrainFaultScope $before $after $badEvents $session $name } '*depending on damaged evidence*'
$badEvents=@($events + [pscustomobject]@{event='T0_CAPTURED';chunkX=2000;chunkZ=2000})
Test-Rejected 'terrain recaptured' { Assert-TerrainFaultScope $before $after $badEvents $session $name } '*recaptured terrain*'
$badOutput=@($after | ConvertTo-Json -Depth 5 | ConvertFrom-Json)
$badOutput[0].finalHash='0' * 64
Test-Rejected 'healthy result altered' { Assert-TerrainFaultScope $before $badOutput $events $session $name } '*healthy recovery changed*'
$checks.ToArray() | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $project 'docs\evidence\s0-03\fault-verifier-tests.json') -Encoding utf8
$checks.ToArray()
