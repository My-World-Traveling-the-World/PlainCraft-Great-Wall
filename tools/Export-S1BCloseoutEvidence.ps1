param(
    [Parameter(Mandatory=$true)][string]$BuildLog,
    [Parameter(Mandatory=$true)][int]$BuildExitCode,
    [Parameter(Mandatory=$true)][datetime]$StartedAt,
    [Parameter(Mandatory=$true)][int]$ExpectedJUnit,
    [Parameter(Mandatory=$true)][int]$ExpectedGameTests
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $root 'docs/evidence/s1b-closeout/final'
$log = (Resolve-Path -LiteralPath $BuildLog).Path
$text = Get-Content -LiteralPath $log -Raw
if ($BuildExitCode -ne 0 -or $text -notmatch 'BUILD SUCCESSFUL' -or $text -match 'BUILD FAILED|DIAGNOSTIC SUBSET ONLY') {
    throw 'Only a successful unfiltered offline build may produce final evidence'
}
$sourcePaths = @('src', 'build.gradle', 'gradle', 'gradle.properties')
$dirty = @(& git -C $root diff HEAD --name-only -- @sourcePaths)
$untracked = @(& git -C $root ls-files --others --exclude-standard -- @sourcePaths)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -or $untracked.Count) { throw 'Commit all tested source/build inputs before export' }
$locked = @('gradle.properties', 'gradle.lockfile', 'gradle/verification-metadata.xml', 'gradle/wrapper/gradle-wrapper.properties')
$changed = @(& git -C $root diff --name-only b46ab38 -- @locked)
if ($LASTEXITCODE -ne 0 -or $changed.Count) { throw 'Locked dependency inputs changed' }
if (Get-Content -LiteralPath (Join-Path $root 'gradle.properties') | Select-String 'proxyHost|proxyPort') {
    throw 'Temporary project proxy settings remain'
}
function Read-FreshXml([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.LastWriteTime -lt $StartedAt) { throw "Stale test evidence: $Path" }
    [xml](Get-Content -LiteralPath $file.FullName -Raw)
}
function Hash-Entry([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    [ordered]@{path=$file.FullName; bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$junit = @(Get-ChildItem -LiteralPath (Join-Path $root 'build/test-results/test') -Filter 'TEST-*.xml' | ForEach-Object {
    $xml = Read-FreshXml $_.FullName
    [pscustomobject]@{file=$_.FullName; tests=[int]$xml.testsuite.tests; failures=[int]$xml.testsuite.failures;
        errors=[int]$xml.testsuite.errors; skipped=[int]$xml.testsuite.skipped}
})
$testCount = ($junit | Measure-Object tests -Sum).Sum
if ($testCount -ne $ExpectedJUnit -or @($junit | Where-Object { $_.failures -or $_.errors -or $_.skipped }).Count) {
    throw 'JUnit counts or failures do not match the complete acceptance run'
}
$gamePath = Join-Path $root 'build/reports/gametest/results.xml'
$game = Read-FreshXml $gamePath
$gameCount = @($game.SelectNodes('//testcase')).Count
if ($gameCount -ne $ExpectedGameTests -or @($game.SelectNodes('//failure|//error|//skipped')).Count -or
        $text -notmatch "All $gameCount required tests passed") { throw 'GameTest suite incomplete or failed' }
$matrices = [ordered]@{BOUNDARY=256; CONFLICT=128; DECISION=32; HYDRO=72; CAP=30; TERMINAL=15; REJECTION=8; GAPS=6; CURVED_SITES=64}
foreach ($tag in $matrices.Keys) {
    $n = $matrices[$tag]
    if ($text -notmatch "S1B_ADJACENCY_$tag attempted=$n passed=$n failed=0") { throw "Missing complete $tag matrix" }
}
if ($text -notmatch 'S1B_SURVIVAL_MATRIX attempted=960 failed=0' -or $text -notmatch 'S1B_CURVE_MATRIX attempted=576 failed=0') {
    throw 'Missing complete isolated survival or curved-wall matrix'
}
if ($text -notmatch 'S1B_RAMP_GROUND_PORTAL attempted=192 passed=192 failed=0') {
    throw 'Missing complete 32-height six-rotation exterior ramp portal matrix'
}
$heapFiles = @('build/reports/s1b-memory/reservation-heap.json',
    'build/run/gameTest/build/reports/s1b-memory/production-pipeline-heap.json')
foreach ($relative in $heapFiles) {
    $file = Get-Item -LiteralPath (Join-Path $root $relative)
    if ($file.LastWriteTime -lt $StartedAt) { throw "Stale heap report: $relative" }
    $heap = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    if ($heap.sampledHeapPeak -lt $heap.baselineHeapUsed -or $heap.jvmMaxHeap -le 0) { throw 'Invalid heap measurement' }
}
New-Item -ItemType Directory -Path (Join-Path $out 'junit') -Force | Out-Null
foreach ($suite in $junit) { Copy-Item -LiteralPath $suite.file -Destination (Join-Path $out 'junit') -Force }
Copy-Item -LiteralPath $gamePath -Destination (Join-Path $out 'gametest.xml') -Force
foreach ($relative in $heapFiles) { Copy-Item -LiteralPath (Join-Path $root $relative) -Destination $out -Force }
$source = [System.IO.File]::OpenRead($log)
$target = [System.IO.File]::Create((Join-Path $out 'strict-offline-build.log.gz'))
try {
    $gzip = [System.IO.Compression.GZipStream]::new($target, [System.IO.Compression.CompressionLevel]::Optimal, $true)
    try { $source.CopyTo($gzip) } finally { $gzip.Dispose() }
} finally { $target.Dispose(); $source.Dispose() }
$checks = [ordered]@{
    generatedAt=(Get-Date -Format o); testedSourceCommit=(& git -C $root rev-parse HEAD).Trim(); baseline='b46ab38'
    buildExitCode=$BuildExitCode; javaHome='C:\Program Files\Java\jdk-21'; offline=$true; dependencyVerification='strict'
    command='.\gradlew.bat --offline --dependency-verification=strict build runGameTest --continue --rerun-tasks --no-build-cache -PgreatwallTestHeap=1024m'
    startedAt=$StartedAt.ToString('o'); junitTests=$testCount; gameTests=$gameCount
    adjacencyFiniteDomain=$matrices; isolatedSurvivalCases=960; curvedWallCases=576; rampGroundPortalCases=192
    limits=@{terrainBytes=67108864; compiledBytes=33554432; gameTestHeapBytes=1073741824}
    heapScope='Whole isolated JVM sampled maximum, not the sum of logical reservation limits; pool peaks are not simultaneous'
    sourceLocks=@($locked | ForEach-Object { Hash-Entry (Join-Path $root $_) })
    originalLog=(Hash-Entry $log)
    notCertified=@('S1C production world generation and lifecycle', 'All continuous geometry inputs beyond enumerated domains',
        'S4 visual atlas', 'S5 24-hour/Chunky stress', 'Tan Huge Trees whole-tree authorization')
}
$checks | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $out 'checks.json') -Encoding utf8
Get-ChildItem -LiteralPath $out -Recurse -File | Sort-Object FullName | ForEach-Object { Hash-Entry $_.FullName } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'files.json') -Encoding utf8
$checks | ConvertTo-Json -Depth 5
