param(
    [string]$SourceInstance = 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING',
    [ValidateSet('vanilla','tectonic-c2me')][string[]]$Profiles = @('vanilla','tectonic-c2me'),
    [ValidateSet(1,2,8,16)][int[]]$Concurrency = @(1,2,8,16),
    [ValidateRange(1,100)][int]$Rounds = 100,
    [switch]$SkipFault,
    [switch]$OriginalQos
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = Split-Path $PSScriptRoot -Parent
$evidence = Join-Path $project 'docs\evidence\s0-03\matrix'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$results = [Collections.Generic.List[object]]::new()
if (($SkipFault -or $OriginalQos) -and ($Profiles.Count -ne 1 -or $Profiles[0] -ne 'tectonic-c2me')) {
    throw 'Original QoS and skip-fault tests require the C2ME profile'
}
$fixedWorkers = !$SkipFault -and !$OriginalQos
foreach ($profile in $Profiles) {
    $baseline = $null
    foreach ($workers in $Concurrency) {
        $id = "s003-$profile-c$workers-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
        $root = Join-Path $project "build\run\serverSmoke\$profile\$id"
        $output = Join-Path $evidence $id
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        if ($profile -ne 'vanilla') {
            & (Join-Path $PSScriptRoot 'Stage-SmokeProfile.ps1') -SourceInstance $SourceInstance -Profile $profile -RunId $id -EvidenceStage s0-03
            if ($fixedWorkers) {
                $config = Join-Path $root 'config\c2me.toml'
                $text = Get-Content -LiteralPath $config -Raw
                $pattern = '(?m)^globalExecutorParallelism\s*=\s*\d+\s*$'
                if ([regex]::Matches($text, $pattern).Count -ne 1) { throw 'Cannot uniquely set isolated C2ME worker count' }
                [regex]::Replace($text, $pattern, "globalExecutorParallelism = $workers") |
                    Set-Content -LiteralPath $config -Encoding utf8NoBOM
            }
        }
        $arguments = @('--offline','runServerSmoke',"-PsmokeProfile=$profile","-PsmokeRunId=$id",
            '-Ps0Probe=true','-Ps0Terrain=true',"-Ps0TerrainRounds=$Rounds", "-Ps0TerrainConcurrency=$workers",
            "-Ps0TerrainOrder=$($workers * 101 + 7)","-Ps0TerrainSkip=$($SkipFault.ToString().ToLowerInvariant())",
            "-Ps0TerrainFixedWorkers=$($fixedWorkers.ToString().ToLowerInvariant())",
            "-Ps0ExpectedFailure=$($SkipFault.ToString().ToLowerInvariant())",
            '--dependency-verification=strict','--no-daemon','--console=plain')
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $env:ComSpec
        $info.Arguments = '/d /c ""{0}" {1}"' -f (Join-Path $project 'gradlew.bat'), ($arguments -join ' ')
        $info.WorkingDirectory = $project
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.Environment['JAVA_HOME'] = 'C:\Program Files\Java\jdk-21'
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $info
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        Write-Output "RUN $id rounds=$Rounds workers=$workers skip=$SkipFault"
        try {
            while (!$process.WaitForExit(1000)) {
                if ($timer.Elapsed.TotalSeconds -gt 600) {
                    $process.Kill($true)
                    $process.WaitForExit()
                    throw "Terrain fixture timed out: $id"
                }
            }
        } finally {
            if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
            $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
            $text | Set-Content -LiteralPath (Join-Path $output 'gradle.txt') -Encoding utf8
            $terrain = Join-Path $root 'world\greatwall-s0\terrain'
            if (Test-Path -LiteralPath $terrain) { Copy-Item -LiteralPath $terrain -Destination (Join-Path $output 'terrain') -Recurse }
            [ordered]@{id=$id;profile=$profile;workers=$workers;rounds=$Rounds;skip=[bool]$SkipFault;
                exitCode=$process.ExitCode;seconds=$timer.Elapsed.TotalSeconds;arguments=$arguments} |
                ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'execution.json') -Encoding utf8
        }
        if ($SkipFault) {
            if ($text -notmatch 'T0_COMPAT_FEATURE_SKIPPED' -or $text -match 'FIXTURE_PASS' -or
                    (Test-Path -LiteralPath (Join-Path $root 'server-smoke-passed.txt'))) { throw "Skip guard not verified: $id" }
            $results.Add([ordered]@{id=$id;profile=$profile;workers=$workers;skip=$true;passed=$true})
            continue
        }
        if ($process.ExitCode -ne 0 -or $text -notmatch 'FIXTURE_PASS' -or
                !(Test-Path -LiteralPath (Join-Path $root 'server-smoke-passed.txt'))) { throw "Terrain smoke failed: $id" }
        if ($fixedWorkers -and $text -notmatch "FIXED_ENGINE_WORKERS actual=$workers requested=$workers") {
            throw "Unverified native worker count: $id"
        }
        if (!$fixedWorkers -and $text -match 'TEST_ONLY_WORKER_CALIBRATION|FIXED_ENGINE_WORKERS') {
            throw "Worker calibration unexpectedly active in original QoS test: $id"
        }
        $session = Get-Content -LiteralPath (Join-Path $terrain 'barrier-session.json') -Raw | ConvertFrom-Json
        $events = @(Get-Content -LiteralPath (Join-Path $terrain 'barrier-events.json') -Raw | ConvertFrom-Json)
        $owners = @(Get-Content -LiteralPath (Join-Path $terrain 'barrier-results.json') -Raw | ConvertFrom-Json)
        if (!$session.closed -or $session.leases -ne 0 -or $session.delays -ne 0 -or $owners.Count -ne $Rounds * 4) {
            throw "Incomplete lifecycle evidence: $id"
        }
        if ($session.counts.T0_REUSED_AFTER_APPLY -ne $Rounds * 4) { throw "No late-neighbor reuse proof: $id" }
        if (@($events | Where-Object { $_.event -eq 'PREPARE_FEATURES' -and $_.stage -ne 'features' }).Count -gt 0) {
            throw "PREPARE requested a later status: $id"
        }
        foreach ($owner in $owners) {
            $own = @($events | Where-Object { $_.chunkX -eq $owner.x -and $_.chunkZ -eq $owner.z })
            $capture = @($own | Where-Object event -eq 'CAPTURE_EXIT')
            $durable = @($own | Where-Object event -eq 'T0_DURABLE')
            $apply = @($own | Where-Object event -eq 'APPLY_ENTER')
            if ($capture.Count -ne 1 -or $durable.Count -ne 1 -or $apply.Count -ne 1 -or
                    $capture[0].nanoTime -ge $durable[0].nanoTime -or $durable[0].nanoTime -ge $apply[0].nanoTime) {
                throw "Capture/persist/apply ordering violation: $id $($owner.x),$($owner.z)"
            }
            $expectedThread = if ($profile -eq 'vanilla') { '^Worker-Main-' } else { '^c2me-worker-' }
            if ($capture[0].threadName -notmatch $expectedThread -or $apply[0].threadName -notmatch $expectedThread) {
                throw "Wrong native execution context: $id"
            }
        }
        $fingerprint = ($owners | ForEach-Object { "$($_.round):$($_.x):$($_.z):$($_.t0Hash):$($_.realizationHash):$($_.finalHash)" }) -join "`n"
        if ($null -eq $baseline) { $baseline = $fingerprint }
        elseif ($baseline -cne $fingerprint) { throw "Same-input hash differs by concurrency: $id" }
        Copy-Item -LiteralPath (Join-Path $root 'server-smoke-passed.txt') -Destination $output
        $results.Add([ordered]@{id=$id;profile=$profile;workers=$(if ($fixedWorkers) { $workers } else { $null });
            fixtureConcurrency=$workers;workerPolicy=$(if ($fixedWorkers) { 'fixed-test-only' } else { 'original-qos' });rounds=$Rounds;owners=$owners.Count;
            recipe=$session.recipe;hashesEqual=$true;seconds=$timer.Elapsed.TotalSeconds;passed=$true})
        $results.ToArray() | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'results.json') -Encoding utf8
        Write-Output "PASS $id owners=$($owners.Count) seconds=$([math]::Round($timer.Elapsed.TotalSeconds,1))"
    }
}
$results.ToArray() | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'results.json') -Encoding utf8
