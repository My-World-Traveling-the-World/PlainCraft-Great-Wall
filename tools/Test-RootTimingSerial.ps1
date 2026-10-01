param(
    [string]$SourceInstance = 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$utf8=[Text.UTF8Encoding]::new($false)
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$seeds=@(4,8,9,21)
$commit=(& git -C $project rev-parse HEAD).Trim()
& git -C $project diff --quiet b440e33 -- src/main src/client
if ($LASTEXITCODE -ne 0) { throw 'Production code differs from frozen baseline' }
function Java-Environment {
    @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe') } | ForEach-Object {
        $line=[string]$_.CommandLine
        $role=if ($line -match 'KnotClient|net\.minecraft\.client\.main\.Main') {'CLIENT'}
              elseif ($line -match 'KnotServer|net\.minecraft\.server\.Main') {'SERVER'}
              elseif ($line -match 'GradleDaemon') {'GRADLE_DAEMON'} else {'OTHER_JAVA'}
        [ordered]@{pid=$_.ProcessId;role=$role;cpu100ns=([long]$_.KernelModeTime+[long]$_.UserModeTime)
            workingSetBytes=[long]$_.WorkingSetSize}
    })
}
function Assert-EnvironmentFree {
    $now=@(Java-Environment)
    if (@($now | Where-Object { $_.role -in @('CLIENT','SERVER','OTHER_JAVA') }).Count -ne 0) {
        throw 'Other active game/Java process present; do not operate or terminate it. Close it before testing.'
    }
}
Assert-EnvironmentFree
$id="root-timing-serial-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$evidence=Join-Path $project "docs\evidence\s1c\$id"
New-Item -ItemType Directory -Path $evidence | Out-Null
$runtimeId="$id-runtime"
$runtimeRoot=Join-Path $project "build\run\serverSmoke\vanilla\$runtimeId"
$inputFiles=@(Get-ChildItem -LiteralPath (Join-Path $project 'src'),(Join-Path $project 'gradle'),(Join-Path $project 'tools') -Recurse -File)
$inputFiles+=@(Get-Item -LiteralPath (Join-Path $project 'build.gradle'),(Join-Path $project 'settings.gradle'),
    (Join-Path $project 'gradle.properties'),(Join-Path $project 'gradle.lockfile'),(Join-Path $project 'gradlew.bat'))
$sourceInputs=@($inputFiles | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($project,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $evidence 'inputs.json'),([ordered]@{
    sourceCommit=$commit;productionBaseline='b440e33';seeds=$seeds;maxParallel=1;heap='8G'
    sourceInputs=$sourceInputs;startedUtc=[DateTime]::UtcNow.ToString('o');initialEnvironment=@(Java-Environment)
    purpose='root steady latency attribution only';productionWiring='OFF';networkUsed=$false
} | ConvertTo-Json -Depth 9),$utf8)
& (Join-Path $PSScriptRoot 'Stage-SmokeProfile.ps1') -SourceInstance $SourceInstance -Profile vanilla -RunId $runtimeId -EvidenceStage s1c
Copy-Item -LiteralPath (Join-Path $project "docs\evidence\s1c\runtime\$runtimeId-inputs.json") -Destination (Join-Path $evidence 'runtime-staging.json')
Push-Location -LiteralPath $project
try {
    & .\gradlew.bat --offline --dependency-verification=strict prepareSeedSurveyRuntime -PsmokeProfile=vanilla "-PsmokeRunId=$runtimeId" -Ps1cSeedSurvey=true -Ps1cSeed=4 --console=plain --no-daemon 2>&1 |
        Tee-Object -FilePath (Join-Path $evidence 'runtime-prepare.log')
    $prep=$LASTEXITCODE
} finally { Pop-Location }
[IO.File]::WriteAllText((Join-Path $evidence 'runtime-execution.json'),([ordered]@{
    sourceCommit=$commit;exitCode=$prep;finishedUtc=[DateTime]::UtcNow.ToString('o')
    command="gradlew.bat --offline --dependency-verification=strict prepareSeedSurveyRuntime -PsmokeProfile=vanilla -PsmokeRunId=$runtimeId -Ps1cSeedSurvey=true -Ps1cSeed=4 --console=plain --no-daemon"
} | ConvertTo-Json),$utf8)
if ($prep -ne 0) { throw 'Strict offline runtime preparation failed' }
$runtime=Get-Content -LiteralPath (Join-Path $runtimeRoot 'seed-survey-runtime.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
Copy-Item -LiteralPath (Join-Path $runtimeRoot 'seed-survey-runtime.json') -Destination (Join-Path $evidence 'runtime.json')
$java=Join-Path $runtime.javaHome 'bin\java.exe'
if ($runtime.javaHome -ne $env:JAVA_HOME) { throw 'JDK21 mismatch' }
$jarHashes=@($runtime.mods | ForEach-Object { [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()} })
[IO.File]::WriteAllText((Join-Path $evidence 'runtime-mod-sha256.json'),($jarHashes | ConvertTo-Json),$utf8)

# A copy of the installed JDK profile; the JDK configuration itself is never edited.
[xml]$jfc=Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'lib\jfr\profile.jfc') -Raw
foreach ($name in @('greatwall.RootClockAnchor','jdk.SafepointBegin','jdk.SafepointEnd','jdk.SafepointStateSynchronization','jdk.GCPhasePause')) {
    $event=$jfc.SelectSingleNode("/configuration/event[@name='$name']")
    if ($null -eq $event) { $event=$jfc.CreateElement('event');$event.SetAttribute('name',$name);$null=$jfc.DocumentElement.AppendChild($event) }
    foreach ($pair in @(@('enabled','true'),@('threshold','0 ms'))) {
        $node=$event.SelectSingleNode("setting[@name='$($pair[0])']")
        if ($null -eq $node) { $node=$jfc.CreateElement('setting');$node.SetAttribute('name',$pair[0]);$null=$event.AppendChild($node) }
        $node.InnerText=$pair[1]
    }
}
$jfcFile=Join-Path $runtimeRoot 'root-timing.jfc'
$writer=[Xml.XmlWriter]::Create($jfcFile,[Xml.XmlWriterSettings]@{Encoding=$utf8;Indent=$true})
try { $jfc.Save($writer) } finally { $writer.Dispose() }
Copy-Item -LiteralPath $jfcFile -Destination (Join-Path $evidence 'root-timing.jfc')
$all=[Collections.Generic.List[object]]::new()
foreach ($seed in $seeds) {
    Assert-EnvironmentFree
    $runId="$id-seed$seed"
    $root=[IO.Path]::GetFullPath((Join-Path $project "build\run\serverSmoke\vanilla\$runId"))
    $allowed=[IO.Path]::GetFullPath((Join-Path $project 'build\run\serverSmoke\vanilla'))+'\'
    if (!$root.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $root)) { throw 'New isolated directory required' }
    $out=Join-Path $evidence "seed-$seed"
    New-Item -ItemType Directory -Path (Join-Path $root 'mods'),$out | Out-Null
    foreach ($jar in $jarHashes) {
        $dest=Join-Path $root ('mods\'+[IO.Path]::GetFileName($jar.path));Copy-Item -LiteralPath $jar.path -Destination $dest
        if ((Get-FileHash -LiteralPath $dest).Hash.ToLowerInvariant() -ne $jar.sha256) { throw 'Staged JAR mismatch' }
    }
    [IO.File]::WriteAllText((Join-Path $root 'greatwall_test_world'),"read-only root timing $runId`n",$utf8)
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
    $arguments=@('-Xmx8G','-Dfile.encoding=UTF-8','-Dfabric.development=false','-Dfabric.noGui=true',
        '-Dgreatwall.smoke.profile=vanilla','-Dgreatwall.s1c.seedSurvey=true',"-Dgreatwall.s1c.surveySeed=$seed",
        '-Dgreatwall.s1c.rootTiming=true','-Dgreatwall.production=false','-Dgreatwall.s1c.productionFixture=false',
        '-Dgreatwall.s0.probe=false','-Dgreatwall.s1c.oracle=false',
        '-Xlog:gc,safepoint:file=root-vm.log:time,uptimenanos,level,tags',
        "-XX:StartFlightRecording=filename=root.jfr,settings=$jfcFile,dumponexit=true,disk=true,maxsize=256m",
        '-cp',($runtime.classpath -join ';'),'net.fabricmc.loader.impl.launch.knot.KnotServer','--nogui','--port','0','--world','world')
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$java;$info.WorkingDirectory=$root
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $info.StandardOutputEncoding=$utf8;$info.StandardErrorEncoding=$utf8
    foreach ($arg in $arguments) {$info.ArgumentList.Add([string]$arg)}
    $time=[DateTime]::UtcNow
    [IO.File]::WriteAllText((Join-Path $out 'inputs.json'),([ordered]@{
        sourceCommit=$commit;seed=$seed;runDir=$root;executable=$java;arguments=$arguments;startedUtc=$time.ToString('o')
        environmentBefore=@(Java-Environment);productionWiring='OFF';maxParallel=1;heap='8G'
    } | ConvertTo-Json -Depth 7),$utf8)
    $p=[Diagnostics.Process]::new();$p.StartInfo=$info;$null=$p.Start()
    $stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
    Write-Output "ROOT_TIMING_START seed=$seed pid=$($p.Id) serial=true heap=8G"
    $environment=Join-Path $out 'environment.jsonl'
    $envWriter=[IO.StreamWriter]::new($environment,$false,$utf8)
    $contaminated=$false;$timeout=$false
    try {
        while (!$p.WaitForExit(1000)) {
            $roles=@(Java-Environment)
            $foreign=@($roles | Where-Object { $_.pid -ne $p.Id -and $_.role -ne 'GRADLE_DAEMON' })
            if ($foreign.Count -gt 0) {$contaminated=$true}
            $envWriter.WriteLine(([ordered]@{utc=[DateTime]::UtcNow.ToString('o');testPid=$p.Id;javaProcesses=$roles} | ConvertTo-Json -Compress -Depth 6))
            if (([DateTime]::UtcNow-$time).TotalMinutes -gt 22) { $timeout=$true;$p.Kill($true);$p.WaitForExit();break }
        }
    } finally {$envWriter.Dispose()}
    $p.WaitForExit();$code=$p.ExitCode
    [IO.File]::WriteAllText((Join-Path $out 'stdout.log'),$stdout.GetAwaiter().GetResult(),$utf8)
    [IO.File]::WriteAllText((Join-Path $out 'stderr.log'),$stderr.GetAwaiter().GetResult(),$utf8)
    $p.Dispose()
    foreach ($relative in @('logs\latest.log','logs\debug.log','seed-survey-result.json','server-smoke-passed.txt',
        'root-timing.json','root-batches.bin.gz','root-vm.log','root.jfr')) {
        $from=Join-Path $root $relative
        if (Test-Path -LiteralPath $from) { $dest=Join-Path $out $relative;[IO.Directory]::CreateDirectory((Split-Path $dest -Parent)) | Out-Null;Copy-Item -LiteralPath $from -Destination $dest }
    }
    $analyzeCode=$null;$analysis=$null
    if ((Test-Path -LiteralPath (Join-Path $out 'root-timing.json')) -and (Test-Path -LiteralPath (Join-Path $out 'root.jfr'))) {
        & $java -Xmx1G -cp (($runtime.classpath+$runtime.mods) -join ';') local.plaincraft.greatwall.smoke.RootTimingAnalyzer $out 2>&1 |
            Tee-Object -FilePath (Join-Path $out 'analysis-stdout.log')
        $analyzeCode=$LASTEXITCODE
        if (Test-Path -LiteralPath (Join-Path $out 'root-timing-analysis.json')) {
            $analysis=Get-Content -LiteralPath (Join-Path $out 'root-timing-analysis.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
        }
    }
    $same=@($sourceInputs | Where-Object { (Get-FileHash -LiteralPath (Join-Path $project $_.path)).Hash.ToLowerInvariant() -ne $_.sha256 }).Count -eq 0
    $execution=[ordered]@{
        sourceCommit=$commit;seed=$seed;startedUtc=$time.ToString('o');finishedUtc=[DateTime]::UtcNow.ToString('o')
        exitCode=$code;analysisExitCode=$analyzeCode;timedOut=$timeout;otherActiveJvmObserved=$contaminated
        sourceInputsUnchanged=$same;verdict=$(if ($null -ne $analysis) {$analysis.verdict} else {'MISSING_ANALYSIS'})
        productionWiring='OFF';fullWallAcceptance='NOT_RUN'
    }
    [IO.File]::WriteAllText((Join-Path $out 'execution.json'),($execution | ConvertTo-Json),$utf8)
    $all.Add($execution)
    $hashes=@(Get-ChildItem -LiteralPath $out -File -Recurse | Sort-Object FullName | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($out,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
    })
    [IO.File]::WriteAllText((Join-Path $out 'sha256.json'),($hashes | ConvertTo-Json -Depth 5),$utf8)
    Write-Output "ROOT_TIMING_RESULT seed=$seed exit=$code analysis=$analyzeCode verdict=$($execution.verdict) contaminated=$contaminated"
    if (!$same) {throw 'Frozen test sources changed during root timing run'}
}
[IO.File]::WriteAllText((Join-Path $evidence 'summary.json'),([ordered]@{
    sourceCommit=$commit;seeds=$seeds;maxParallel=1;heap='8G';results=$all.ToArray()
    productionWiring='OFF';finishedUtc=[DateTime]::UtcNow.ToString('o');scope='A/B/C attribution only, no production changes'
} | ConvertTo-Json -Depth 8),$utf8)
Write-Output "ROOT_TIMING_COMPLETE evidence=$evidence"
