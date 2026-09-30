param([Parameter(Mandatory)][string]$WorkspaceRoot)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath($WorkspaceRoot)
$frozen = '6026605204cfc73f5219c13918e6402b34cd5b76'
$utf8 = [Text.UTF8Encoding]::new($false)

function Require([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}
function ReadJson([string]$Path) {
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
}
function Sha([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Instant($Value) {
    # New PowerShell versions decode ISO JSON timestamps to DateTime automatically.
    # Re-parsing that value as a string loses UTC kind and fractional precision.
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) { return [DateTimeOffset]$Value }
    return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture)
}
function CheckInputs($Rows) {
    foreach ($row in $Rows) {
        Require ((Sha (Join-Path $project $row.path)) -eq $row.sha256) "Source input changed: $($row.path)"
    }
}

# This rechecks archived evidence only; it does not start Minecraft or Gradle.
$paths = @(
    'src', 'tools', 'gradle', 'build.gradle', 'settings.gradle',
    'gradle.properties', 'gradle.lockfile', 'gradlew', 'gradlew.bat'
)
$changes = @(& git -C $project diff --name-only $frozen -- @paths)
Require ($LASTEXITCODE -eq 0 -and $changes.Count -eq 0) 'Frozen source/build inputs changed'
$regressionDelta = @(& git -C $project diff --name-only 71fbc57 $frozen -- src/main src/client src/test src/gametest build.gradle settings.gradle gradle.properties gradle gradle.lockfile)
Require ($LASTEXITCODE -eq 0 -and $regressionDelta.Count -eq 0) 'Regression source isolation no longer holds'

$runs = @(
    's1c-oracle-vanilla-20260930-b797f27f',
    's1c-oracle-tectonic-20260930-93cb5fa6',
    's1c-oracle-tectonic-c2me-20260930-7b2bdc7d'
)
$profiles = [Collections.Generic.List[object]]::new()
$manifestPaths = [Collections.Generic.List[string]]::new()
foreach ($run in $runs) {
    $directory = Join-Path $project "docs/evidence/s1c/$run"
    $execution = ReadJson (Join-Path $directory 'execution.json')
    $inputs = ReadJson (Join-Path $directory 'inputs.json')
    $certificate = ReadJson (Join-Path $directory 'oracle-profile.json')
    $staged = ReadJson (Join-Path $directory 'staged-inputs.json')
    $hashRows = @(ReadJson (Join-Path $directory 'sha256.json'))
    foreach ($row in $hashRows) {
        Require ((Sha (Join-Path $directory $row.file)) -eq $row.sha256) "Archive hash mismatch: $run/$($row.file)"
    }
    Require ($hashRows.Count -eq 10) "Unexpected artifact count: $run"
    Require ($execution.sourceCommit -eq $frozen -and $inputs.sourceCommit -eq $frozen) "Wrong source commit: $run"
    Require ($inputs.workspace -eq $project -and $execution.workspace -eq $project) "Wrong workspace: $run"
    Require ($inputs.dirty.Count -eq 0 -and !$inputs.networkUsed) "Run inputs not isolated/offline: $run"
    Require ($inputs.javaHome -eq 'C:\Program Files\Java\jdk-21') "Wrong JDK: $run"
    Require ($execution.command -match '--offline --dependency-verification=strict') "Missing build guards: $run"
    Require ($execution.exitCode -eq 0 -and !$execution.timedOut -and $execution.cleanSmoke -and $execution.passed -and $execution.laneProtocolPassed) "Execution failed: $run"
    Require ($execution.productionWiring -eq 'OFF' -and $inputs.productionWiring -eq 'OFF') "Production scope changed: $run"
    CheckInputs $inputs.sourceInputs
    foreach ($mod in $staged.mods) {
        Require ((Sha (Join-Path $staged.destination "mods/$($mod.name)")) -eq $mod.sha256) "Staged JAR changed: $($mod.name)"
        Require ((Sha $mod.source) -eq $mod.sha256) "Reference JAR changed: $($mod.name)"
    }
    $configObservations = [Collections.Generic.List[object]]::new()
    foreach ($config in $staged.configs) {
        $sourceHash = Sha (Join-Path $staged.source $config.path)
        $postRunHash = Sha (Join-Path $staged.destination $config.path)
        Require ($sourceHash -eq $config.sha256) "Read-only input config changed: $($config.path)"
        if ($postRunHash -ne $config.sha256) {
            # C2ME flushConfig purges unused modules and saves the isolated copy.
            # Keep input and post-run bytes distinct; never claim they are identical.
            Require ($run -eq 's1c-oracle-tectonic-c2me-20260930-7b2bdc7d' -and $config.path -eq 'config\c2me.toml') "Unexpected config rewrite: $run/$($config.path)"
            Require ($postRunHash -eq '3d2611f14764da2667ca39c2f6c0a63ae33ba351756f4061ac998b72f94e6114') 'Post-run C2ME config changed since audit'
            Require ((Sha (Join-Path $PSScriptRoot 'C2ME-config-input-6026605.toml')) -eq $config.sha256) 'Archived input config differs'
            Require ((Sha (Join-Path $PSScriptRoot 'C2ME-config-after-run-6026605.toml')) -eq $postRunHash) 'Archived post-run config differs'
        }
        $configObservations.Add([ordered]@{
            path=$config.path; inputSha256=$config.sha256; readOnlySourceSha256=$sourceHash
            postRunSha256=$postRunHash
            scope='input identity and runtime bytes separately archived; not a claim of all-settings equivalence'
        })
    }
    $c2meBaseHash = $null
    if ($execution.profile -eq 'tectonic-c2me') {
        $c2meMod = @($staged.mods | Where-Object { $_.id -eq 'c2me' })
        Require ($c2meMod.Count -eq 1) 'Missing locked C2ME JAR'
        $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $staged.destination "mods/$($c2meMod[0].name)"))
        try {
            $entry = @($zip.Entries | Where-Object { $_.FullName -match 'c2me-fabric-base.*\.jar$' })
            Require ($entry.Count -eq 1) 'Missing exact runtime base module'
            $stream = $entry[0].Open()
            $hash = [Security.Cryptography.SHA256]::Create()
            try { $c2meBaseHash = [Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant() }
            finally { $hash.Dispose(); $stream.Dispose() }
        } finally { $zip.Dispose() }
        Require ($c2meBaseHash -eq 'e04c6cbc4f7bed2b514715f48bb725b894f69e1f890866b980aabc9dff0c6efd') 'Config bytecode belongs to a different base module'
    }
    $runtime = Join-Path $project "docs/evidence/s1c/runtime/$run-inputs.json"
    Require ((Sha $runtime) -eq (Sha (Join-Path $directory 'staged-inputs.json'))) "Runtime input copy differs: $run"
    Require ($certificate.status -eq 'PASS_NATIVE_ORACLE' -and $certificate.correctness -eq 'PASS') "Certificate failed: $run"
    Require ($certificate.minY -eq -64 -and $certificate.maxYExclusive -eq 320 -and $certificate.columns -eq 512 -and $certificate.states -eq 196608) "Input domain changed: $run"
    Require (($certificate.wet + $certificate.dry) -eq 512 -and $certificate.nativeSha256 -eq $certificate.slicedSha256) "Parity mismatch: $run"
    Require ($certificate.nativeSha256 -match '^[0-9a-f]{64}$') "Invalid parity hash: $run"
    Require (($certificate.seeds -join ',') -eq '0,1,9282026,-9223372036854775808') "Seed domain changed: $run"
    Require ($certificate.actualNativeInitializations -eq 8 -and $certificate.nativeInitializationSentinelHits -eq 0 -and $certificate.steadyPrimitiveLimit -eq 64) "Initialization protocol failed: $run"
    Require ($certificate.laneSessions.Count -eq 4 -and $certificate.drainedLanes.Count -eq 8) "Lane count changed: $run"
    $identities = @($certificate.laneSessions | ForEach-Object { $_.lane0Oracle; $_.lane1Oracle })
    Require (@($identities | Sort-Object -Unique).Count -eq 8) "Oracle identity reused: $run"
    Require (@($certificate.laneSessions | Where-Object { $_.jobsOnLane0 -ne 2 }).Count -eq 0) "Cross-job reuse not tested: $run"
    $initTimes = [Collections.Generic.List[long]]::new()
    foreach ($lane in $certificate.drainedLanes) {
        Require ($lane.'factory-calls' -eq 1 -and $lane.'native-constructions' -eq 1 -and $lane.'oracle-closes' -eq 1 -and $lane.drained -eq 1 -and $lane.queued -eq 0) "Lane lifecycle failed: $run"
        Require (!$lane.ContainsKey('forbidden-init-thread') -or $lane.'forbidden-init-thread' -eq 0) "Wrong initialization thread: $run"
        Require (!$lane.ContainsKey('sentinel-WAIT_WHILE_LOCKED') -or $lane.'sentinel-WAIT_WHILE_LOCKED' -eq 0) "Initialization lock hit: $run"
        $initTimes.Add($lane.'initialization-nanos')
    }
    $scheduler = $certificate.actualScheduler
    $d = $scheduler.diagnostics
    Require ($scheduler.status -eq 'PASS_OBSERVED_BATCHES' -and $scheduler.routes -eq 2 -and $d.'batch-count' -gt 0) "Scheduler domain missing: $run"
    foreach ($lane in 0..1) {
        $p = "lane-$lane-"
        foreach ($key in @('factory-calls', 'native-constructions', 'oracle-closes', 'drained')) {
            Require ($d["$p$key"] -eq 1) "Scheduler lane lifecycle failed: $run/$p$key"
        }
        Require ($d["${p}queued"] -eq 0) "Scheduler lane still queued: $run"
        Require (!$d.ContainsKey("${p}forbidden-init-thread") -or $d["${p}forbidden-init-thread"] -eq 0) "Scheduler init thread hit: $run"
        Require (!$d.ContainsKey("${p}sentinel-WAIT_WHILE_LOCKED") -or $d["${p}sentinel-WAIT_WHILE_LOCKED"] -eq 0) "Scheduler lock hit: $run"
        $initTimes.Add($d["${p}initialization-nanos"])
    }
    Require ($d.'max-batch-nanos' -le 40000000 -and (!$d.ContainsKey('over-40ms-batches') -or $d.'over-40ms-batches' -eq 0)) "Scheduler steady limit failed: $run"
    Require ($certificate.timing.oracleOver40msPrimitives -eq 0 -and $certificate.timing.oracleOver40msAdvances -eq 0 -and $certificate.timing.maxOracleAdvance64Nanos -le 40000000) "Oracle steady limit failed: $run"
    Require ($d.'startup-failed' -eq 1) "Diagnostic cancellation scope changed: $run"
    $profiles.Add([ordered]@{
        runId=$run; sourceCommit=$execution.sourceCommit; exitCode=$execution.exitCode
        archiveHashes=$hashRows.Count; sourceInputHashes=$inputs.sourceInputs.Count
        minY=$certificate.minY; maxYExclusive=$certificate.maxYExclusive
        columns=$certificate.columns; states=$certificate.states; nativeSha256=$certificate.nativeSha256
        parityLanes=8; schedulerLanes=2; nativeInitializationMaxNanos=($initTimes | Measure-Object -Maximum).Maximum
        advance64MaxNanos=$certificate.timing.maxOracleAdvance64Nanos
        schedulerBatchMaxNanos=$d.'max-batch-nanos'; schedulerBatchCount=$d.'batch-count'
        startupPumpCallNanos=$scheduler.startupPumpCallNanos
        configurations=$configObservations.ToArray(); actualC2meBaseSha256=$c2meBaseHash
        scope='observed native inputs; route 0/1 only; intentional close, not full prewarm or production worldgen'
    })
    foreach ($file in Get-ChildItem -LiteralPath $directory -File -Recurse) { $manifestPaths.Add($file.FullName) }
    $manifestPaths.Add($runtime)
}

$regression = Join-Path $project 'docs/evidence/s1c/lane-native-20260930/ready-scope-after'
$runInfo = ReadJson (Join-Path $regression 'execution.json')
Require ($runInfo.exitCode -eq 0) 'Regression command failed'
$start = Instant $runInfo.startedUtc
$finish = Instant $runInfo.finishedUtc
$junit = [ordered]@{suites=0; tests=0; actualNodes=0; failures=0; errors=0; skipped=0}
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $regression 'junit') -Filter 'TEST-*.xml' -File) {
    [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    $suite = $xml.testsuite
    $stamp = Instant $suite.timestamp
    Require ($stamp -ge $start -and $stamp -le $finish) "Stale JUnit: $($file.Name)"
    $nodes = $xml.SelectNodes('//testcase').Count
    Require ($nodes -eq [int]$suite.tests) "JUnit count mismatch: $($file.Name)"
    $junit.suites++
    $junit.actualNodes += $nodes
    foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $junit[$key] += [int]$suite.$key }
}
Require ($junit.suites -eq 57 -and $junit.tests -eq 309 -and $junit.actualNodes -eq 309 -and $junit.failures -eq 0 -and $junit.errors -eq 0 -and $junit.skipped -eq 0) 'Full JUnit result mismatch'
[xml]$gameXml = Get-Content -LiteralPath (Join-Path $regression 'gametest/results.xml') -Raw -Encoding UTF8
$gameNames = @($gameXml.SelectNodes('//testcase') | ForEach-Object { $_.name })
Require ($gameNames.Count -eq 6 -and @($gameNames | Sort-Object -Unique).Count -eq 6 -and $gameXml.SelectNodes('//failure|//error|//skipped').Count -eq 0) 'Directed GameTest result mismatch'
foreach ($suite in $gameXml.SelectNodes('//testsuite[@timestamp]')) {
    $stamp = Instant $suite.timestamp
    Require ($stamp -ge $start -and $stamp -le $finish) 'Stale GameTest results'
}
$sourceRows = @(ReadJson (Join-Path $regression 'source-inputs.json'))
$diagnosticChanges = [Collections.Generic.List[string]]::new()
foreach ($row in $sourceRows) {
    if ((Sha (Join-Path $project $row.path)) -ne $row.sha256) { $diagnosticChanges.Add($row.path) }
}
Require ($sourceRows.Count -eq 6 -and $diagnosticChanges.Count -eq 0) 'Archived regression input changed'
$bytecode = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'main-bytecode-6026605.txt') -Raw -Encoding UTF8
$forbidden = [regex]::Matches($bytecode, 'java/util/concurrent/(?:CompletableFuture|Future)\.(?:get|join):|\.getChunk:|\.possibleBiomes:')
Require ($forbidden.Count -eq 0) 'Forbidden core bytecode method references'
$properties = Get-Content -LiteralPath (Join-Path $project 'gradle.properties') -Raw -Encoding UTF8
Require ($properties -notmatch '(?m)^\s*systemProp\.(https?|socks)\.proxy') 'Proxy settings still present'

$audit = [ordered]@{
    auditedUtc=[DateTime]::UtcNow.ToString('o'); frozenSourceCommit=$frozen
    verification='archive recheck, not new execution of Gradle/GameTest/profiles'
    productionWiring='OFF'; profiles=$profiles.ToArray(); junit=$junit
    gameTests=[ordered]@{count=$gameNames.Count; names=$gameNames; scope='six directed tests, not all GameTest matrices'}
    regressionSource=[ordered]@{
        executionParent=$runInfo.sourceCommit; producerFixCommit='71fbc57'
        archivedInputs='ready-scope-after/source-inputs.json'
        diagnosticOnlyChangesAfterRegression=$diagnosticChanges.ToArray()
        mainClientTestGameTestBuildChangesSinceProducerFix=$regressionDelta
        scope='all six archived input hashes match; production/JUnit/GameTest unchanged since producer fix; full profile input manifest rerun on frozen source'
    }
    coreForbiddenBytecodeReferences=$forbidden.Count
    productionWorldgenSentinel='NOT_RUN'; realWallGeneration='NOT_RUN'; fullPrewarm='NOT_RUN'
    tectonicInitializationInternalBreakdown='UNRESOLVED'; tansHugeTrees='BLOCKED'
}
$auditPath = Join-Path $PSScriptRoot 'final-audit-6026605.json'
[IO.File]::WriteAllText($auditPath, ($audit | ConvertTo-Json -Depth 12), $utf8)
foreach ($file in Get-ChildItem -LiteralPath $regression -File -Recurse) { $manifestPaths.Add($file.FullName) }
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -File) {
    if ($file.Name -ne 'final-sha256-6026605.json') { $manifestPaths.Add($file.FullName) }
}
$hashes = @($manifestPaths | Sort-Object -Unique | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($project, $_).Replace('\', '/'); sha256=(Sha $_)}
})
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'final-sha256-6026605.json'), ($hashes | ConvertTo-Json -Depth 5), $utf8)
$audit | ConvertTo-Json -Depth 12
