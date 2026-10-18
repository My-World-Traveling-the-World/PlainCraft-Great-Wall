# Worldgen startup verifier.
#
# Reads a Fabric client log, extracts the startup markers the wall and the road mod emit, and prints one
# verdict per checkpoint. The point is that a session is diagnosed from its log, not from a screenshot:
# every line below exists for exactly one question, and the table says which one failed first.
#
#   pwsh -File tools/verify-startup.ps1
#   pwsh -File tools/verify-startup.ps1 -Log "D:\path\to\latest.log" -Since 13:00
#
# Exit code 0 when every required checkpoint passed, 1 otherwise.

[CmdletBinding()]
param(
    [string]$Log = "D:\minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING\logs\latest.log",
    [string]$Since = "",
    [switch]$Full
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Log)) {
    Write-Host "LOG MISSING: $Log" -ForegroundColor Red
    exit 1
}

# The game holds the file open; a plain read is refused, so open it shared.
$stream = [IO.File]::Open($Log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
try {
    $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Dispose()
} finally {
    $stream.Dispose()
}

$lines = $text -split "`r?`n"
if ($Since) {
    $lines = $lines | Where-Object { $_ -match '^\[(\d\d:\d\d:\d\d)\]' -and $Matches[1] -ge $Since }
}

function First-Line([string]$pattern) {
    foreach ($line in $lines) { if ($line -match $pattern) { return $line.Trim() } }
    return $null
}
function Last-Line([string]$pattern) {
    $hit = $null
    foreach ($line in $lines) { if ($line -match $pattern) { $hit = $line.Trim() } }
    return $hit
}
function Count-Line([string]$pattern) {
    $n = 0
    foreach ($line in $lines) { if ($line -match $pattern) { $n++ } }
    return $n
}
function Field([string]$line, [string]$name) {
    if (-not $line) { return $null }
    $m = [regex]::Match($line, [regex]::Escape($name) + '=([^\s]+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}
function Stamp([string]$line) {
    if (-not $line) { return '--:--:--' }
    $m = [regex]::Match($line, '^\[(\d\d:\d\d:\d\d)\]')
    if ($m.Success) { return $m.Groups[1].Value }
    return '--:--:--'
}

$rows = New-Object System.Collections.Generic.List[object]
function Add-Row([string]$check, [string]$verdict, [string]$when, [string]$detail) {
    $rows.Add([pscustomobject]@{ Check = $check; Verdict = $verdict; At = $when; Detail = $detail })
}

# --- 1. Streams prewarm: another mod, but it gates the wall, so a session where it never finishes has no
#        wall either and the rest of the table would be misleading.
$streams = Last-Line 'Streams startup prewarm complete'
if ($streams) { Add-Row 'streams-prewarm' 'ok' (Stamp $streams) ('elapsedMs=' + (Field $streams 'elapsedMs')) }
else { Add-Row 'streams-prewarm' 'WAIT' '--:--:--' 'not finished (or Streams absent)' }

# --- 2. Root search: the axis selection. cacheHit tells a first run from a repeat.
$rootDone = Last-Line 'ROOT_SEARCH_DONE'
if ($rootDone) {
    $cols = Field $rootDone 'columns'
    Add-Row 'root-search' 'ok' (Stamp $rootDone) ("columns=$cols elapsedMs=" + (Field $rootDone 'elapsedMs') +
        " over40msBatches=" + (Field $rootDone 'over40msBatches'))
} elseif (Last-Line 'ROOT_CACHE_HIT') {
    Add-Row 'root-search' 'ok' (Stamp (Last-Line 'ROOT_CACHE_HIT')) 'cached axis reused'
} else {
    Add-Row 'root-search' 'WAIT' '--:--:--' 'no ROOT_SEARCH_DONE yet'
}

# --- 3. Structure scan: how much of it was wasted, and what a dimension-level biome filter would save.
$scan = Last-Line 'structure scan'
if ($scan) {
    Add-Row 'structure-scan' 'ok' (Stamp $scan) ("candidates=" + (Field $scan 'candidates') +
        " starts=" + (Field $scan 'starts') + " empty=" + (Field $scan 'emptyCandidates') +
        " filterWouldSkip=" + (Field $scan 'filterWouldSkip') + " elapsedMs=" + (Field $scan 'elapsedMs'))
} else {
    Add-Row 'structure-scan' 'WAIT' '--:--:--' 'no scan line'
}

# --- 4. The corridor water map: the measurement behind "cross under 50 blocks, terminate above".
$water = Last-Line 'CORRIDOR_WATER'
if ($water) {
    Add-Row 'corridor-water' 'info' (Stamp $water) ("runs=" + (Field $water 'runs') +
        " widest=" + (Field $water 'widest') + " crossable=" + (Field $water 'crossable'))
} else {
    Add-Row 'corridor-water' 'MISSING' '--:--:--' 'no CORRIDOR_WATER line'
}

# --- 5. Fine sample: must run exactly once, must not repeat, and must actually finish.
$call = Last-Line 'FINE_SAMPLE_CALL'
if ($call) {
    $callCount = Field $call 'call'
    $verdict = if ($callCount -eq '1') { 'ok' } else { 'FAIL' }
    Add-Row 'fine-sample-call' $verdict (Stamp $call) ("call=$callCount segments=" + (Field $call 'segments') +
        " slices=" + (Field $call 'slicesPerSegment'))
} else {
    Add-Row 'fine-sample-call' 'WAIT' '--:--:--' 'stage never started'
}

$runaway = Count-Line 'FINE_SAMPLE_RUNAWAY'
if ($runaway -gt 0) { Add-Row 'fine-sample-runaway' 'FAIL' (Stamp (Last-Line 'FINE_SAMPLE_RUNAWAY')) "$runaway occurrence(s)" }
else { Add-Row 'fine-sample-runaway' 'ok' '' 'none' }

$sampleDone = Last-Line 'FINE_SAMPLE_DONE'
if ($sampleDone) {
    $repeated = Field $sampleDone 'repeatedPct'
    $verdict = if ($repeated -eq '0') { 'ok' } else { 'FAIL' }
    Add-Row 'fine-sample-done' $verdict (Stamp $sampleDone) ("columns=" + (Field $sampleDone 'columns') +
        " repeatedPct=$repeated sections=" + (Field $sampleDone 'sections') +
        " blocksPerColumn=" + (Field $sampleDone 'blocksPerColumn'))
} else {
    Add-Row 'fine-sample-done' 'WAIT' '--:--:--' 'sample not finished'
}

# --- 6. Prewarm: four segments, and how long they took.
$prewarmDone = Last-Line 'PREWARM_COMPLETE'
if ($prewarmDone) {
    Add-Row 'prewarm' 'ok' (Stamp $prewarmDone) ("segments=" + (Field $prewarmDone 'segments') +
        " totalMs=" + (Field $prewarmDone 'totalMs'))
} else {
    Add-Row 'prewarm' 'WAIT' '--:--:--' 'PREWARM_COMPLETE absent (a failure would say PREWARM_ABORTED)'
}

# --- 7. Compile: the wall only exists if this ran and published. failed must be zero and plans positive.
$compileDone = Last-Line 'PLANNING_COMPILE_DONE'
$ownerFailed = Count-Line 'PLANNING_COMPILE_OWNER_FAILED'
if ($compileDone) {
    $failed = Field $compileDone 'failed'
    $plans = Field $compileDone 'plans'
    $verdict = if ($failed -eq '0' -and [int]$plans -gt 0) { 'ok' } else { 'FAIL' }
    Add-Row 'compile' $verdict (Stamp $compileDone) ("owners=" + (Field $compileDone 'owners') +
        " plans=$plans failed=$failed elapsedMs=" + (Field $compileDone 'elapsedMs'))
} elseif (Last-Line 'PLANNING_COMPILE_WAIT_FOR_SAMPLE') {
    Add-Row 'compile' 'WAIT' (Stamp (Last-Line 'PLANNING_COMPILE_WAIT_FOR_SAMPLE')) 'deferred until the sample publishes'
} else {
    Add-Row 'compile' 'MISSING' '--:--:--' 'compile never ran'
}
if ($ownerFailed -gt 0) {
    Add-Row 'compile-owner-failed' 'FAIL' (Stamp (First-Line 'PLANNING_COMPILE_OWNER_FAILED')) "$ownerFailed occurrence(s)"
}

# --- 8. The road gate: the one ordering guarantee. It must open on success, and before any RoadWeaver work.
$gate = Last-Line 'ROAD_GATE_OPENED'
$road = First-Line 'RoadWeaver '
if ($gate) {
    $success = Field $gate 'success'
    if ($success -eq $null) {
        Add-Row 'road-gate' 'info' (Stamp $gate) 'released (old build: no success field)'
    } elseif ($success -eq 'true') {
        if ($road -and (Stamp $road) -lt (Stamp $gate)) {
            Add-Row 'road-gate' 'FAIL' (Stamp $gate) "released at $($gate.Substring(1,8)) but RoadWeaver logged at $(Stamp $road) - ordering violated"
        } else {
            Add-Row 'road-gate' 'ok' (Stamp $gate) 'success=true, before any RoadWeaver line'
        }
    } else {
        Add-Row 'road-gate' 'FAIL' (Stamp $gate) 'released with success=false (the wall gave up)'
    }
} else {
    Add-Row 'road-gate' 'WAIT' '--:--:--' 'not released yet'
}

$kept = Count-Line 'PUMP_TASK_FAILED_GATE_KEPT'
$released = Count-Line 'PUMP_TASK_FAILED the wall releases'
if ($kept -gt 0) { Add-Row 'pump-failures' 'info' '' "$kept task failure(s), gates kept" }
if ($released -gt 0) { Add-Row 'pump-failures' 'FAIL' (Stamp (Last-Line 'PUMP_TASK_FAILED the wall releases')) "$released fatal release(s)" }

# --- 9. Road side: did it start, and did it hit the same compile class of problem?
$roadTiming = Last-Line 'RoadWeaver (planning|initial) .*(ms|timing)'
if ($roadTiming) { Add-Row 'road-start' 'ok' (Stamp $roadTiming) ($roadTiming.Substring(0, [Math]::Min(110, $roadTiming.Length))) }
elseif ($road) { Add-Row 'road-start' 'ok' (Stamp $road) ($road.Substring(0, [Math]::Min(110, $road.Length))) }
else { Add-Row 'road-start' 'WAIT' '--:--:--' 'roads have not started' }

# --- Report
$width = 26
Write-Host ''
Write-Host ('{0,-22} {1,-8} {2,-10} {3}' -f 'CHECK', 'VERDICT', 'AT', 'DETAIL') -ForegroundColor Cyan
Write-Host ('-' * 110) -ForegroundColor DarkGray
foreach ($row in $rows) {
    $colour = switch ($row.Verdict) {
        'ok' { 'Green' }
        'info' { 'Gray' }
        'WAIT' { 'Yellow' }
        default { 'Red' }
    }
    Write-Host ('{0,-22} {1,-8} {2,-10} {3}' -f $row.Check, $row.Verdict, $row.At, $row.Detail) -ForegroundColor $colour
}
Write-Host ('-' * 110) -ForegroundColor DarkGray

$failures = @($rows | Where-Object { $_.Verdict -eq 'FAIL' })
$waiting = @($rows | Where-Object { $_.Verdict -eq 'WAIT' -or $_.Verdict -eq 'MISSING' })
if ($failures.Count -gt 0) {
    Write-Host "VERDICT: FAIL - $($failures.Count) checkpoint(s) failed: $($failures[0].Check)" -ForegroundColor Red
    exit 1
}
if ($waiting.Count -gt 0) {
    Write-Host "VERDICT: IN PROGRESS - still waiting on: $(($waiting | ForEach-Object { $_.Check }) -join ', ')" -ForegroundColor Yellow
    exit 1
}
Write-Host 'VERDICT: PASS - every checkpoint met' -ForegroundColor Green
exit 0
