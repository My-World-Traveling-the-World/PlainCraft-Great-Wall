param(
    [string]$SourceInstance = 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING',
    [ValidateSet('vanilla', 'tectonic', 'tectonic-c2me')]
    [string[]]$Profiles = @('vanilla', 'tectonic', 'tectonic-c2me'),
    [int]$ExpectedMaxY = 0
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$utf8 = [Text.UTF8Encoding]::new($false)
$sourceCommit = (& git -C $project rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit' }
$inputFiles = @(Get-ChildItem -LiteralPath (Join-Path $project 'src'),(Join-Path $project 'gradle'),(Join-Path $project 'tools') -File -Recurse)
$inputFiles += @(Get-Item -LiteralPath (Join-Path $project 'build.gradle'),(Join-Path $project 'settings.gradle'),
    (Join-Path $project 'gradle.properties'),(Join-Path $project 'gradle.lockfile'),(Join-Path $project 'gradlew.bat'))
$sourceInputs = @($inputFiles |
    Sort-Object FullName | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($project,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
$dirty = @(& git -C $project status --porcelain)
$results = [Collections.Generic.List[object]]::new()
foreach ($profile in $Profiles) {
    $id = "s1c-oracle-$profile-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
    $root = Join-Path $project "build\run\serverSmoke\$profile\$id"
    $evidence = Join-Path $project "docs\evidence\s1c\$id"
    if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $id" }
    New-Item -ItemType Directory -Path $evidence | Out-Null
    & (Join-Path $PSScriptRoot 'Stage-SmokeProfile.ps1') -SourceInstance $SourceInstance -Profile $profile -RunId $id -EvidenceStage s1c
    Copy-Item -LiteralPath (Join-Path $project "docs\evidence\s1c\runtime\$id-inputs.json") -Destination (Join-Path $evidence 'staged-inputs.json')
    $arguments = @('--offline', '--dependency-verification=strict', 'runServerSmoke',
        "-PsmokeProfile=$profile", "-PsmokeRunId=$id", '-Ps1cOracleOnly=true',
        '--no-daemon', '--console=plain')
    if ($ExpectedMaxY -ne 0) {
        if ($ExpectedMaxY -lt 1) { throw 'ExpectedMaxY must be positive or omitted' }
        $arguments += "-Ps1cExpectedMaxY=$ExpectedMaxY"
    }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $env:ComSpec
    $info.Arguments = '/d /c ""{0}" {1}"' -f (Join-Path $project 'gradlew.bat'), ($arguments -join ' ')
    $info.WorkingDirectory = $project
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    $info.Environment['JAVA_HOME'] = 'C:\Program Files\Java\jdk-21'
    $started = [DateTime]::UtcNow
    $inputs = [ordered]@{
        sourceCommit=$sourceCommit;workspace=$project;dirty=$dirty;sourceInputs=$sourceInputs
        purpose='native-oracle-only-not-production-wall-acceptance'
        command=('gradlew.bat ' + ($arguments -join ' '));javaHome=$info.Environment['JAVA_HOME']
        profile=$profile;expectedMaxY=$ExpectedMaxY;startedUtc=$started.ToString('o')
        networkUsed=$false;productionWiring='OFF';s0TerrainMatrix='NOT_RUN'
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'inputs.json'), ($inputs | ConvertTo-Json -Depth 10), $utf8)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $timedOut = $false
    Write-Output "RUN $id purpose=native-oracle-only"
    while (!$process.WaitForExit(1000)) {
        if ($timer.Elapsed.TotalMinutes -gt 23) {
            $timedOut = $true
            $process.Kill($true)
            $process.WaitForExit()
            break
        }
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'stdout.log'), $stdout.GetAwaiter().GetResult(), $utf8)
    [IO.File]::WriteAllText((Join-Path $evidence 'stderr.log'), $stderr.GetAwaiter().GetResult(), $utf8)
    $exitCode = $process.ExitCode
    $process.Dispose()
    foreach ($relative in @('logs\latest.log', 'logs\debug.log', 'oracle-profile.json', 'oracle-vm.log', 'server-smoke-passed.txt')) {
        $from = Join-Path $root $relative
        if (Test-Path -LiteralPath $from) { Copy-Item -LiteralPath $from -Destination $evidence }
    }
    $oracle = $null
    $certificatePath = Join-Path $root 'oracle-profile.json'
    if (Test-Path -LiteralPath $certificatePath) {
        if ((Get-Item -LiteralPath $certificatePath).LastWriteTimeUtc -lt $started) { throw 'Stale oracle certificate' }
        $oracle = Get-Content -LiteralPath $certificatePath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    }
    $cleanSmoke = $exitCode -eq 0 -and !$timedOut -and (Test-Path -LiteralPath (Join-Path $root 'server-smoke-passed.txt'))
    $correctness = $null -ne $oracle -and $oracle.correctness -eq 'PASS' -and
        $oracle.columns -eq 512 -and $oracle.states -eq (512L * ($oracle.maxYExclusive - $oracle.minY)) -and
        ($oracle.wet + $oracle.dry) -eq 512 -and ($oracle.seeds -join ',') -eq '0,1,9282026,-9223372036854775808' -and
        $oracle.nativeSha256 -eq $oracle.slicedSha256 -and $oracle.nativeSha256 -match '^[0-9a-f]{64}$'
    $protocol = $false
    if ($null -ne $oracle -and $oracle.ContainsKey('laneSessions') -and $oracle.ContainsKey('drainedLanes')) {
        $identities = @($oracle.laneSessions | ForEach-Object { $_.lane0Oracle; $_.lane1Oracle })
        $badLanes = @($oracle.drainedLanes | Where-Object {
            $_.'factory-calls' -ne 1 -or $_.'oracle-closes' -ne 1 -or $_.drained -ne 1 -or
            ($_.ContainsKey('forbidden-init-thread') -and $_.'forbidden-init-thread' -ne 0) -or
            ($_.ContainsKey('sentinel-WAIT_WHILE_LOCKED') -and $_.'sentinel-WAIT_WHILE_LOCKED' -ne 0)
        })
        $protocol = $oracle.initializationProtocol -eq 'once-per-session-per-lane-lock-external-future-separate-from-40ms-steady' -and
            $oracle.actualNativeInitializations -eq 8 -and $oracle.nativeInitializationSentinelHits -eq 0 -and
            $oracle.steadyPrimitiveLimit -eq 64 -and $oracle.laneSessions.Count -eq 4 -and
            @($identities | Sort-Object -Unique).Count -eq 8 -and
            ($oracle.laneSessions.seed -join ',') -eq '0,1,9282026,-9223372036854775808' -and
            @($oracle.laneSessions | Where-Object { $_.jobsOnLane0 -ne 2 }).Count -eq 0 -and
            $oracle.drainedLanes.Count -eq 8 -and $badLanes.Count -eq 0 -and
            $oracle.timing.maxOracleAdvance64Nanos -le 40000000 -and
            $oracle.timing.oracleOver40msPrimitives -eq 0 -and $oracle.timing.oracleOver40msAdvances -eq 0
    }
    $passed = $cleanSmoke -and $correctness -and $protocol -and $oracle.status -eq 'PASS_NATIVE_ORACLE'
    $execution = [ordered]@{
        sourceCommit=$sourceCommit;workspace=$project;runId=$id;profile=$profile
        startedUtc=$started.ToString('o');finishedUtc=[DateTime]::UtcNow.ToString('o')
        seconds=$timer.Elapsed.TotalSeconds;command=$inputs.command;exitCode=$exitCode;timedOut=$timedOut
        cleanSmoke=$cleanSmoke;passed=$passed;productionWiring='OFF'
        laneProtocolPassed=$protocol
        oracleStatus=$(if ($null -ne $oracle) {$oracle.status} else {'MISSING_CERTIFICATE'})
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'execution.json'), ($execution | ConvertTo-Json -Depth 8), $utf8)
    $hashes = @(Get-ChildItem -LiteralPath $evidence -File | Sort-Object Name | ForEach-Object {
        [ordered]@{file=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    [IO.File]::WriteAllText((Join-Path $evidence 'sha256.json'), ($hashes | ConvertTo-Json -Depth 5), $utf8)
    $results.Add($execution)
    Write-Output "RESULT $id passed=$passed exit=$exitCode status=$($execution.oracleStatus) seconds=$([Math]::Round($timer.Elapsed.TotalSeconds,1)) evidence=$evidence"
    if (!$passed) { throw "Native oracle prerequisite blocked; see $evidence. No production wiring enabled." }
}
$results.ToArray() | ConvertTo-Json -Depth 8
