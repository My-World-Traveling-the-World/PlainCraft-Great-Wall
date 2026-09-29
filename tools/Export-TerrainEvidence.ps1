param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=Split-Path $PSScriptRoot -Parent
$root=Join-Path $project 'docs\evidence\s0-03'
$matrix=@(Get-Content -LiteralPath (Join-Path $root 'final-matrix-results.json') -Raw | ConvertFrom-Json)
$recovery=@(Get-Content -LiteralPath (Join-Path $root 'final-recovery-results.json') -Raw | ConvertFrom-Json)
$skip=@(Get-Content -LiteralPath (Join-Path $root 'final-skip-results.json') -Raw | ConvertFrom-Json)
$native=@(Get-Content -LiteralPath (Join-Path $root 'final-native-qos-results.json') -Raw | ConvertFrom-Json)
if ($matrix.Count -ne 8 -or $recovery.Count -ne 8 -or $skip.Count -ne 1 -or
        @(@($matrix)+@($recovery)+@($skip) | Where-Object { !$_.passed }).Count -ne 0) { throw 'Incomplete final S0-03 suite' }
if ($native.Count -ne 1 -or !$native[0].passed -or $native[0].workerPolicy -ne 'original-qos' -or
        $native[0].rounds -ne 100 -or $native[0].owners -ne 400) { throw 'Missing original QoS 100-round control' }
$baselines=@{}
foreach ($profile in @('vanilla','tectonic-c2me')) {
    foreach ($workers in @(1,2,8,16)) {
        if (@($matrix | Where-Object { $_.profile -eq $profile -and $_.workers -eq $workers }).Count -ne 1) {
            throw "Missing or duplicate concurrency case: $profile/$workers"
        }
    }
    foreach ($point in @('durable','saved')) {
        $faults=if ($point -eq 'saved') { @('none','missing','corrupt') } else { @('none') }
        foreach ($fault in $faults) {
            if (@($recovery | Where-Object { $_.profile -eq $profile -and $_.crashPoint -eq $point -and $_.fault -eq $fault }).Count -ne 1) {
                throw "Missing or duplicate recovery case: $profile/$point/$fault"
            }
        }
    }
}
$output=Join-Path $root 'adapter'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$exports=[Collections.Generic.List[object]]::new()
$durations=[Collections.Generic.List[object]]::new()
foreach ($case in $matrix) {
    if ($case.rounds -ne 100 -or $case.owners -ne 400 -or !$case.hashesEqual) { throw 'Matrix does not meet 100-round gate' }
    $caseRoot=Join-Path $root "matrix\$($case.id)"
    $execution=Get-Content -LiteralPath (Join-Path $caseRoot 'execution.json') -Raw | ConvertFrom-Json
    $log=Get-Content -LiteralPath (Join-Path $caseRoot 'gradle.txt') -Raw
    $session=Get-Content -LiteralPath (Join-Path $caseRoot 'terrain\barrier-session.json') -Raw | ConvertFrom-Json -AsHashtable
    $owners=@(Get-Content -LiteralPath (Join-Path $caseRoot 'terrain\barrier-results.json') -Raw | ConvertFrom-Json)
    if ($execution.exitCode -ne 0 -or $execution.arguments -notcontains '--offline' -or
            $execution.arguments -notcontains '--dependency-verification=strict' -or
            $log -notmatch "FIXED_ENGINE_WORKERS actual=$($case.workers) requested=$($case.workers)" -or
            $log -notmatch 'FIXTURE_PASS' -or !(Test-Path -LiteralPath (Join-Path $caseRoot 'server-smoke-passed.txt'))) {
        throw "Incomplete isolated runtime proof: $($case.id)"
    }
    if (!$session.closed -or $session.leases -ne 0 -or $session.delays -ne 0 -or
            [long]$session.counts['WAIT_WHILE_LOCKED'] -ne 0 -or [long]$session.counts['TRACE_TRUNCATED'] -ne 0 -or
            $session.counts['T0_CAPTURED'] -ne 400 -or $session.counts['T0_REUSED_AFTER_APPLY'] -ne 400 -or
            $owners.Count -ne 400 -or @($owners | Group-Object x,z).Count -ne 400) {
        throw "Incomplete lifecycle or owner coverage: $($case.id)"
    }
    $fingerprint=($owners | ForEach-Object { "$($_.round):$($_.x):$($_.z):$($_.t0Hash):$($_.realizationHash):$($_.finalHash)" }) -join "`n"
    if (!$baselines.ContainsKey($case.profile)) { $baselines[$case.profile]=$fingerprint }
    elseif ($baselines[$case.profile] -cne $fingerprint) { throw "Cross-concurrency output mismatch: $($case.id)" }
    $events=@(Get-Content -LiteralPath (Join-Path $caseRoot 'terrain\barrier-events.json') -Raw | ConvertFrom-Json)
    if (@($events | Where-Object { $_.event -eq 'PREPARE_FEATURES' -and $_.stage -ne 'features' }).Count) {
        throw 'PREPARE requested a status beyond FEATURES'
    }
    if (@($events | Where-Object event -eq 'OUTPUT_STABLE_AT_FULL').Count -ne 400) { throw 'Missing post-FULL block verification' }
    foreach ($stage in @('CAPTURE','APPLY')) {
        $pairs=[Collections.Generic.List[double]]::new()
        $starts=@{}
        foreach ($event in $events) {
            $key="$($event.chunkX):$($event.chunkZ)"
            if ($event.event -eq "${stage}_ENTER") { $starts[$key]=$event.nanoTime }
            if ($event.event -eq "${stage}_EXIT") {
                if (!$starts.ContainsKey($key)) { throw 'Unpaired stage timing' }
                $pairs.Add(($event.nanoTime-$starts[$key])/1000000.0)
            }
        }
        $sorted=@($pairs | Sort-Object)
        if ($sorted.Count -ne 400) { throw 'Unexpected timed owner count' }
        $durations.Add([ordered]@{id=$case.id;profile=$case.profile;workers=$case.workers;stage=$stage;
            p50Ms=$sorted[199];p95Ms=$sorted[379];maxMs=$sorted[-1]})
    }
    if ($case.workers -ne 1) { continue }
    $run=Join-Path $project "build\run\serverSmoke\$($case.profile)\$($case.id)"
    $classes=@('net\minecraft\server\level\ChunkMap.class','net\minecraft\server\level\ServerChunkCache.class')
    if ($case.profile -eq 'tectonic-c2me') {
        $classes+='com\ishland\c2me\rewrites\chunksystem\common\statuses\VanillaWorldGenerationDelegate.class'
    }
    foreach ($class in $classes) {
        $file=Join-Path $run ".mixin.out\class\$class"
        $name=[IO.Path]::GetFileNameWithoutExtension($class)
        & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c $file |
            Set-Content -LiteralPath (Join-Path $output "$($case.profile)-$name.txt") -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw "Cannot export actual class: $file" }
        $exports.Add([ordered]@{id=$case.id;class=$class;sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash})
    }
    if ($case.profile -eq 'tectonic-c2me') {
        $file=Join-Path $run 'greatwall-calibrated-runtime-limiter.class'
        & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c $file |
            Set-Content -LiteralPath (Join-Path $output 'test-only-calibrated-runtime-limiter.txt') -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw 'Missing actual calibrated class' }
        $exports.Add([ordered]@{id=$case.id;class='test-only RuntimeLimiter';sha256=(Get-FileHash -LiteralPath $file).Hash})
    }
}
$recoveryChecks=[Collections.Generic.List[object]]::new()
foreach ($case in $recovery) {
    $caseRoot=Join-Path $root "recovery\$($case.id)"
    $crash=Get-Content -LiteralPath (Join-Path $caseRoot 'crash-execution.json') -Raw | ConvertFrom-Json
    $resume=Get-Content -LiteralPath (Join-Path $caseRoot 'resume-execution.json') -Raw | ConvertFrom-Json
    if (!$crash.forcedTestJvmKill -or $resume.forcedTestJvmKill -or
            $crash.arguments -notcontains '--offline' -or $resume.arguments -notcontains '--offline' -or
            $crash.arguments -notcontains '--dependency-verification=strict' -or
            $resume.arguments -notcontains '--dependency-verification=strict') { throw 'Invalid recovery process evidence' }
    $text=Get-Content -LiteralPath (Join-Path $caseRoot 'resume-gradle.txt') -Raw
    $session=Get-Content -LiteralPath (Join-Path $caseRoot 'resume-terrain\barrier-session.json') -Raw | ConvertFrom-Json -AsHashtable
    if (!$session.closed -or $session.leases -ne 0 -or $session.delays -ne 0) { throw 'Recovery did not close all terrain work' }
    $hashesEqual=$null
    if ($case.fault -eq 'none') {
        if ($resume.exitCode -ne 0 -or $text -notmatch 'FIXTURE_PASS' -or
                ([long]$session.counts['T0_DISK_REUSED']+[long]$session.counts['LOADED_EVIDENCE_VERIFIED']) -lt 1) {
            throw 'Recovery did not reuse durable T0 and complete normally'
        }
        foreach ($file in Get-ChildItem -LiteralPath (Join-Path $caseRoot 'crash-terrain\evidence') -Filter '*.t0') {
            $after=Join-Path $caseRoot "resume-terrain\evidence\$($file.Name)"
            if ((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $after).Hash) {
                throw 'Recovery changed durable input evidence'
            }
        }
        if ($case.crashPoint -eq 'saved') {
            $before=Get-Content -LiteralPath (Join-Path $caseRoot 'crash-terrain\barrier-results.json') -Raw | ConvertFrom-Json
            $after=Get-Content -LiteralPath (Join-Path $caseRoot 'resume-terrain\barrier-results.json') -Raw | ConvertFrom-Json
            $first=($before | ForEach-Object { "$($_.x):$($_.z):$($_.t0Hash):$($_.realizationHash):$($_.finalHash)" }) -join "`n"
            $second=($after | ForEach-Object { "$($_.x):$($_.z):$($_.t0Hash):$($_.realizationHash):$($_.finalHash)" }) -join "`n"
            $hashesEqual=@($before).Count -eq 4 -and $first -ceq $second
            if (!$hashesEqual) { throw "Saved crash/restart hashes differ: $($case.id)" }
        }
    } else {
        $expected=if ($case.fault -eq 'missing') { 'T0_EVIDENCE_MISSING' } else { 'T0_CORRUPT' }
        if ($text -notmatch $expected -or $text -match 'FIXTURE_PASS') {
            throw 'Damaged authoritative evidence was not rejected before recapture/write'
        }
        $null=& (Join-Path $PSScriptRoot 'Test-TerrainFaultEvidence.ps1') -CaseDirectory $caseRoot
    }
    $recoveryChecks.Add([ordered]@{id=$case.id;actualProcessKill=$true;closed=$true;savedOutputHashesEqual=$hashesEqual;passed=$true})
}
$nativeRoot=Join-Path $root "matrix\$($native[0].id)"
$nativeOwners=Get-Content -LiteralPath (Join-Path $nativeRoot 'terrain\barrier-results.json') -Raw | ConvertFrom-Json
$nativeFingerprint=($nativeOwners | ForEach-Object { "$($_.round):$($_.x):$($_.z):$($_.t0Hash):$($_.realizationHash):$($_.finalHash)" }) -join "`n"
$nativeLog=Get-Content -LiteralPath (Join-Path $nativeRoot 'gradle.txt') -Raw
$nativeExecution=Get-Content -LiteralPath (Join-Path $nativeRoot 'execution.json') -Raw | ConvertFrom-Json
$nativeSession=Get-Content -LiteralPath (Join-Path $nativeRoot 'terrain\barrier-session.json') -Raw | ConvertFrom-Json -AsHashtable
if ($nativeFingerprint -cne $baselines['tectonic-c2me'] -or $nativeLog -notmatch 'FIXTURE_PASS' -or
        $nativeLog -match 'TEST_ONLY_WORKER_CALIBRATION|FIXED_ENGINE_WORKERS' -or
        $nativeExecution.exitCode -ne 0 -or $nativeExecution.arguments -notcontains '--offline' -or
        $nativeExecution.arguments -notcontains '--dependency-verification=strict' -or
        !$nativeSession.closed -or $nativeSession.leases -ne 0 -or $nativeSession.delays -ne 0 -or
        $nativeSession.counts['T0_REUSED_AFTER_APPLY'] -ne 400 -or
        [long]$nativeSession.counts['WAIT_WHILE_LOCKED'] -ne 0) { throw 'Original QoS control differs or lacks proof' }
$skipCaseRoot=Join-Path $root "matrix\$($skip[0].id)"
$skipLog=Get-Content -LiteralPath (Join-Path $skipCaseRoot 'gradle.txt') -Raw
$skipSession=Get-Content -LiteralPath (Join-Path $skipCaseRoot 'terrain\barrier-session.json') -Raw | ConvertFrom-Json -AsHashtable
if ($skipLog -notmatch 'T0_COMPAT_FEATURE_SKIPPED' -or $skipLog -match 'FIXTURE_PASS|TEST_ONLY_WORKER_CALIBRATION' -or
        !$skipSession.closed -or $skipSession.leases -ne 0 -or $skipSession.delays -ne 0 -or
        [long]$skipSession.counts['APPLY_EXIT'] -ne 0) { throw 'QoS skip did not visibly reject before writes and close' }
$skipRoot=Join-Path $project "build\run\serverSmoke\tectonic-c2me\$($skip[0].id)"
$skipClass=Join-Path $skipRoot '.mixin.out\class\local\plaincraftc2meqos\FeatureOrderCycleFallback.class'
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c $skipClass |
    Set-Content -LiteralPath (Join-Path $output 'actual-qos-skip-guard.txt') -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'Missing actual QoS fault adapter class' }
$forbidden=@(Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' |
    Select-String -Pattern '\.join\s*\(|\.getChunk\s*\(|possibleBiomes\s*\(|Thread\.sleep\s*\(|ForkJoinPool\.commonPool')
if ($forbidden.Count) { throw ($forbidden -join "`n") }
$proxy=@(Get-Content -LiteralPath (Join-Path $project 'gradle.properties') | Select-String 'proxyHost|proxyPort')
if ($proxy.Count) { throw 'Proxy settings remain' }
$lock=(Get-FileHash -LiteralPath (Join-Path $project 'gradle.lockfile')).Hash.ToLowerInvariant()
$verification=(Get-FileHash -LiteralPath (Join-Path $project 'gradle\verification-metadata.xml')).Hash.ToLowerInvariant()
if ($lock -ne '367ba3559edbec89d075630c6ce9a2b881620de0028ef2b43366fb58640651f4' -or
        $verification -ne 'c4e281095c96f26a421fc7d676a6d218f20f9739b40978b061863707f7edcee5') { throw 'Dependency verification/lock changed' }
$exports.ToArray() | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'exports.json') -Encoding utf8
$durations.ToArray() | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'stage-timings.json') -Encoding utf8
$recoveryChecks.ToArray() | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'recovery-checks.json') -Encoding utf8
[ordered]@{matrixCases=$matrix.Count;rounds=800;owners=3200;recoveryCases=$recovery.Count;skipCases=$skip.Count;
    originalQosRounds=100;originalQosOwners=400;originalQosMatchesCalibrated=$true;
    redlineViolations=$forbidden.Count;proxySettings=$proxy.Count;lockSha256=$lock;verificationSha256=$verification;
    calibration='Fixed-worker cases use test-only premain calibration of two QoS capacity methods; native region locks unchanged. Recovery and skip fault use the original QoS policy.'} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'checks.json') -Encoding utf8
Write-Output 'S0-03 final evidence checks passed.'
