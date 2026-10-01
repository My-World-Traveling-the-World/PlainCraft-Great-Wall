param(
    [string]$SourceInstance = 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING',
    [ValidateRange(1,2)][int]$MaxParallel = 2
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$utf8 = [Text.UTF8Encoding]::new($false)
$baseline = 'b440e33'
$seeds = @(0..39)
$sourceCommit = (& git -C $project rev-parse HEAD).Trim()
& git -C $project diff --quiet $baseline -- src/main src/client
if ($LASTEXITCODE -ne 0) { throw 'Production source changed from the frozen baseline' }
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$id = "root-hydro-40-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$evidence = Join-Path $project "docs\evidence\s1c\$id"
$runtimeId = "$id-runtime"
$runtimeRoot = Join-Path $project "build\run\serverSmoke\vanilla\$runtimeId"
if (Test-Path -LiteralPath $evidence) { throw 'Survey evidence directory must be new' }
New-Item -ItemType Directory -Path $evidence | Out-Null
$files = @(Get-ChildItem -LiteralPath (Join-Path $project 'src'),(Join-Path $project 'gradle'),(Join-Path $project 'tools') -File -Recurse)
$files += @(Get-Item -LiteralPath (Join-Path $project 'build.gradle'),(Join-Path $project 'settings.gradle'),
    (Join-Path $project 'gradle.properties'),(Join-Path $project 'gradle.lockfile'),(Join-Path $project 'gradlew.bat'))
$inputs = @($files | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($project,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
$started = [DateTime]::UtcNow
[IO.File]::WriteAllText((Join-Path $evidence 'inputs.json'),([ordered]@{
    sourceCommit=$sourceCommit;productionBaseline=$baseline;sourceInputs=$inputs;workspace=$project
    startedUtc=$started.ToString('o');seeds=$seeds;profile='vanilla';maxParallel=$MaxParallel
    purpose='root-and-segment0-hydrology-only-not-wall-acceptance';productionWiring='OFF'
    networkUsed=$false;javaHome=$env:JAVA_HOME
} | ConvertTo-Json -Depth 10),$utf8)
& (Join-Path $PSScriptRoot 'Stage-SmokeProfile.ps1') -SourceInstance $SourceInstance -Profile vanilla -RunId $runtimeId -EvidenceStage s1c
Copy-Item -LiteralPath (Join-Path $project "docs\evidence\s1c\runtime\$runtimeId-inputs.json") -Destination (Join-Path $evidence 'runtime-staging.json')
$prepCommand = @('--offline','--dependency-verification=strict','prepareSeedSurveyRuntime',
    '-PsmokeProfile=vanilla',"-PsmokeRunId=$runtimeId",'-Ps1cSeedSurvey=true','-Ps1cSeed=0','--console=plain','--no-daemon')
Push-Location -LiteralPath $project
try {
    & .\gradlew.bat @prepCommand 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'runtime-prepare.log')
    $prepCode = $LASTEXITCODE
} finally { Pop-Location }
[IO.File]::WriteAllText((Join-Path $evidence 'runtime-execution.json'),([ordered]@{
    command='gradlew.bat '+($prepCommand -join ' ');exitCode=$prepCode;sourceCommit=$sourceCommit
    finishedUtc=[DateTime]::UtcNow.ToString('o');productionWiring='OFF'
} | ConvertTo-Json),$utf8)
if ($prepCode -ne 0) { throw 'Strict offline runtime preparation failed; no seeds launched' }
$runtimeFile = Join-Path $runtimeRoot 'seed-survey-runtime.json'
$runtime = Get-Content -LiteralPath $runtimeFile -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
Copy-Item -LiteralPath $runtimeFile -Destination (Join-Path $evidence 'runtime.json')
$java = Join-Path $runtime.javaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java) -or $runtime.javaHome -ne $env:JAVA_HOME) { throw 'JDK21 runtime mismatch' }
$jars = @($runtime.mods | ForEach-Object {
    [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $evidence 'runtime-mod-sha256.json'),($jars | ConvertTo-Json),$utf8)
$results = [Collections.Generic.List[object]]::new()
$active = [Collections.Generic.List[object]]::new()
$next = 0

function Update-Progress {
    $counts = [ordered]@{rootNotFound=0;hydrologyRejected=0;hydrologyFinished=0;other=0}
    foreach ($item in $results) {
        switch ($item.category) {
            '1_ROOT_NOT_FOUND' { $counts.rootNotFound++ }
            '2_SEGMENT0_HYDROLOGY_REJECTED' { $counts.hydrologyRejected++ }
            '3_SEGMENT0_HYDROLOGY_FINISHED' { $counts.hydrologyFinished++ }
            default { $counts.other++ }
        }
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'progress.json'),([ordered]@{
        updatedUtc=[DateTime]::UtcNow.ToString('o');completed=$results.Count;total=40
        active=@($active | ForEach-Object { [ordered]@{seed=$_.seed;pid=$_.process.Id;startedUtc=$_.started.ToString('o')} })
        counts=$counts;productionWiring='OFF';fullWallAcceptance='NOT_RUN'
    } | ConvertTo-Json -Depth 7),$utf8)
}

function Start-Seed([int]$seed) {
    $runId = "$id-seed$seed"
    $root = [IO.Path]::GetFullPath((Join-Path $project "build\run\serverSmoke\vanilla\$runId"))
    $allowed = [IO.Path]::GetFullPath((Join-Path $project 'build\run\serverSmoke\vanilla')) + '\'
    if (!$root.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $root)) {
        throw 'Seed world must be a new isolated directory'
    }
    $out = Join-Path $evidence "seed-$seed"
    New-Item -ItemType Directory -Path (Join-Path $root 'mods'),$out | Out-Null
    foreach ($jar in $jars) {
        $dest = Join-Path $root ('mods\'+[IO.Path]::GetFileName($jar.path))
        Copy-Item -LiteralPath $jar.path -Destination $dest
        if ((Get-FileHash -LiteralPath $dest).Hash.ToLowerInvariant() -ne $jar.sha256) { throw 'Runtime mod hash mismatch' }
    }
    [IO.File]::WriteAllText((Join-Path $root 'greatwall_test_world'),"read-only 40-seed survey $runId`n",$utf8)
    [IO.File]::WriteAllText((Join-Path $root 'eula.txt'),'eula=true',$utf8)
    [IO.File]::WriteAllText((Join-Path $root 'server.properties'),@"
server-ip=127.0.0.1
server-port=0
online-mode=false
enable-query=false
enable-rcon=false
level-seed=$seed
level-type=minecraft:normal
view-distance=2
simulation-distance=2
max-tick-time=60000
sync-chunk-writes=true
spawn-protection=0
"@,$utf8)
    $args = @('-Xmx4G','-Dfile.encoding=UTF-8','-Dfabric.development=false','-Dfabric.noGui=true',
        '-Dmixin.debug.export=true','-Dmixin.debug.export.decompile=false','-Dgreatwall.smoke.profile=vanilla',
        '-Dgreatwall.s1c.seedSurvey=true',"-Dgreatwall.s1c.surveySeed=$seed",'-Dgreatwall.production=false',
        '-Dgreatwall.s1c.productionFixture=false','-Dgreatwall.s0.probe=false','-Dgreatwall.s1c.oracle=false',
        '-Xlog:gc,safepoint:file=survey-vm.log:time,uptime,level,tags','-cp',($runtime.classpath -join ';'),
        'net.fabricmc.loader.impl.launch.knot.KnotServer','--nogui','--port','0','--world','world')
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$java; $info.WorkingDirectory=$root; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.StandardOutputEncoding=$utf8; $info.StandardErrorEncoding=$utf8
    foreach ($arg in $args) { $info.ArgumentList.Add([string]$arg) }
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    $time=[DateTime]::UtcNow
    [IO.File]::WriteAllText((Join-Path $out 'inputs.json'),([ordered]@{
        sourceCommit=$sourceCommit;seed=$seed;profile='vanilla';runId=$runId;runDir=$root
        executable=$java;arguments=$args;startedUtc=$time.ToString('o');productionWiring='OFF'
        purpose='root-segment0-hydrology-only';networkUsed=$false
    } | ConvertTo-Json -Depth 7),$utf8)
    $null=$process.Start()
    Write-Host "SURVEY_START seed=$seed pid=$($process.Id)"
    return [pscustomobject]@{seed=$seed;root=$root;out=$out;process=$process;started=$time
        stdout=$process.StandardOutput.ReadToEndAsync();stderr=$process.StandardError.ReadToEndAsync();timedOut=$false}
}

while ($next -lt $seeds.Count -or $active.Count -gt 0) {
    while ($next -lt $seeds.Count -and $active.Count -lt $MaxParallel) {
        $job = Start-Seed $seeds[$next]
        $active.Add($job);$next++;Update-Progress
    }
    foreach ($job in @($active.ToArray())) {
        if (!$job.process.HasExited -and ([DateTime]::UtcNow-$job.started).TotalMinutes -gt 22) {
            $job.timedOut=$true;$job.process.Kill($true);$job.process.WaitForExit()
        }
        if (!$job.process.HasExited) { continue }
        $job.process.WaitForExit()
        $code=$job.process.ExitCode
        [IO.File]::WriteAllText((Join-Path $job.out 'stdout.log'),$job.stdout.GetAwaiter().GetResult(),$utf8)
        [IO.File]::WriteAllText((Join-Path $job.out 'stderr.log'),$job.stderr.GetAwaiter().GetResult(),$utf8)
        foreach ($relative in @('logs\latest.log','logs\debug.log','survey-vm.log','seed-survey-result.json',
            'survey-route0.bin','survey-route1.bin','server-smoke-passed.txt')) {
            $from=Join-Path $job.root $relative
            if (Test-Path -LiteralPath $from) {
                $dest=Join-Path $job.out $relative
                [IO.Directory]::CreateDirectory((Split-Path $dest -Parent)) | Out-Null
                Copy-Item -LiteralPath $from -Destination $dest
            }
        }
        $crash=Join-Path $job.root 'crash-reports'
        if (Test-Path -LiteralPath $crash) { Copy-Item -LiteralPath $crash -Destination $job.out -Recurse }
        $file=Join-Path $job.out 'seed-survey-result.json'
        $row=$null
        if (Test-Path -LiteralPath $file) { $row=Get-Content -LiteralPath $file -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
        $fresh=$null -ne $row -and (Get-Item -LiteralPath $file).LastWriteTimeUtc -ge $job.started
        $valid=$fresh -and $row.seed -eq $job.seed -and $row.productionWiring -eq 'OFF' -and
            $row.noWallWrites -eq $true -and $row.observationStatus -eq 'OBSERVATION_COMPLETE'
        $smoke=Join-Path $job.out 'server-smoke-passed.txt'
        $clean=$code -eq 0 -and (Test-Path -LiteralPath $smoke) -and (Get-Item -LiteralPath $smoke).LastWriteTimeUtc -ge $job.started
        $same=@($inputs | Where-Object {
            (Get-FileHash -LiteralPath (Join-Path $project $_.path)).Hash.ToLowerInvariant() -ne $_.sha256
        }).Count -eq 0
        $category=if ($valid -and $clean -and !$job.timedOut -and $same) { $row.category } else { 'OTHER' }
        $execution=[ordered]@{
            sourceCommit=$sourceCommit;seed=$job.seed;startedUtc=$job.started.ToString('o')
            finishedUtc=[DateTime]::UtcNow.ToString('o');seconds=([DateTime]::UtcNow-$job.started).TotalSeconds
            exitCode=$code;timedOut=$job.timedOut;fresh=$fresh;observationValid=$valid;cleanShutdown=$clean
            sourceInputsUnchanged=$same;category=$category;productionWiring='OFF';fullWallAcceptance='NOT_RUN'
        }
        [IO.File]::WriteAllText((Join-Path $job.out 'execution.json'),($execution | ConvertTo-Json),$utf8)
        $sha=@(Get-ChildItem -LiteralPath $job.out -File -Recurse | Sort-Object FullName | ForEach-Object {
            [ordered]@{path=[IO.Path]::GetRelativePath($job.out,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
        })
        [IO.File]::WriteAllText((Join-Path $job.out 'sha256.json'),($sha | ConvertTo-Json -Depth 5),$utf8)
        $results.Add([pscustomobject]@{seed=$job.seed;category=$category;execution=$execution;row=$row})
        $null=$active.Remove($job);$job.process.Dispose()
        Write-Output "SURVEY_RESULT seed=$($job.seed) category=$category exit=$code completed=$($results.Count)/40"
        Update-Progress
        if (!$same) { throw 'Frozen source changed during survey; stop new admissions' }
    }
    if ($active.Count -gt 0) { Start-Sleep -Milliseconds 1000 }
}
$ordered=@($results | Sort-Object seed)
$counts=[ordered]@{rootNotFound=0;hydrologyRejected=0;hydrologyFinished=0;other=0}
$arc=@{};$axis=@{};$unlocated=0;$endBand=0;$outsideAcross=0;$earlyOcean=0;$rootSuccess=0
foreach ($item in $ordered) {
    switch ($item.category) {
        '1_ROOT_NOT_FOUND' { $counts.rootNotFound++ }
        '2_SEGMENT0_HYDROLOGY_REJECTED' { $counts.hydrologyRejected++ }
        '3_SEGMENT0_HYDROLOGY_FINISHED' { $counts.hydrologyFinished++; if ($item.row.oceanBefore1200) { $earlyOcean++ } }
        default { $counts.other++ }
    }
    if ($null -ne $item.row -and $item.row.ContainsKey('rootSucceeded') -and $item.row.rootSucceeded) { $rootSuccess++ }
    if ($item.category -eq '2_SEGMENT0_HYDROLOGY_REJECTED') {
        $failure=$item.row.phaseSnapshots.hydrologyFailure
        if ($failure.ContainsKey('location')) {
            $location=$failure.location
            $arc[$location.arcBucket]=1+[int]$arc[$location.arcBucket]
            $axis[$location.axialBucket]=1+[int]$axis[$location.axialBucket]
            if ($location.endAlong384Band) { $endBand++ }
            if ($location.outsideProbeAcross48Band) { $outsideAcross++ }
        } else { $unlocated++ }
    }
}
$lower=$counts.hydrologyFinished/40.0
$upper=($counts.hydrologyFinished+$counts.other)/40.0
$summary=[ordered]@{
    sourceCommit=$sourceCommit;productionBaseline=$baseline;seeds=$seeds;total=$results.Count;counts=$counts
    finishedUtc=[DateTime]::UtcNow.ToString('o');rootSucceeded=$rootSuccess
    segment0SuccessRate=$lower;successUpperBoundIfAllOtherSucceed=$upper
    conditionalSuccessGivenRoot=$(if ($rootSuccess -gt 0) { $counts.hydrologyFinished/[double]$rootSuccess } else { $null })
    category2ArcBuckets=$arc;category2AxialBuckets=$axis;category2Unlocated=$unlocated
    endAlong384BandOverlap=$endBand;outsideSparseProbeAcross48Overlap=$outsideAcross
    category3OceanBefore1200=$earlyOcean;productionWiring='OFF';fullWallAcceptance='NOT_RUN'
    purpose='fixed-seed-functional-survey-not-performance-or-wall-acceptance';results=$ordered
}
[IO.File]::WriteAllText((Join-Path $evidence 'summary.json'),($summary | ConvertTo-Json -Depth 40),$utf8)
Write-Output "SURVEY_COMPLETE evidence=$evidence counts=$($counts | ConvertTo-Json -Compress)"
