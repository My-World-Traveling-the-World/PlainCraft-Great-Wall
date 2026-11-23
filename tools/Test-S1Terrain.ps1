param(
    [string]$SourceInstance = 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING',
    [ValidateSet('vanilla','tectonic','tectonic-c2me')]
    [string[]]$Profiles = @('vanilla','tectonic','tectonic-c2me'),
    [long[]]$Seeds = @(9282026),
    [switch]$Reopen
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$utf8 = [Text.UTF8Encoding]::new($false)
$commit = (& git -C $project rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit' }
$files = @(Get-ChildItem -LiteralPath (Join-Path $project 'src'),(Join-Path $project 'gradle'),(Join-Path $project 'tools') -Recurse -File)
$files += @(Get-Item -LiteralPath (Join-Path $project 'build.gradle'),(Join-Path $project 'settings.gradle'),
    (Join-Path $project 'gradle.properties'),(Join-Path $project 'gradle.lockfile'),(Join-Path $project 'gradlew.bat'))
$hashes = @($files | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($project,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
foreach ($profile in $Profiles) {
    foreach ($seed in $Seeds) {
        $id = "s1c-wall-$profile-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
        $root = Join-Path $project "build\run\serverSmoke\$profile\$id"
        $evidence = Join-Path $project "test-output\evidence\s1c\$id"
        if (Test-Path -LiteralPath $evidence) { throw "Evidence directory collision: $id" }
        New-Item -ItemType Directory -Path $evidence | Out-Null
        & (Join-Path $PSScriptRoot 'Stage-SmokeProfile.ps1') -SourceInstance $SourceInstance -Profile $profile -RunId $id -EvidenceStage s1c
        Copy-Item -LiteralPath (Join-Path $project "test-output\evidence\s1c\runtime\$id-inputs.json") -Destination (Join-Path $evidence 'staged-inputs.json')
        $phases = @('generated')
        if ($Reopen) { $phases += 'reopened' }
        foreach ($phase in $phases) {
            $destination = Join-Path $evidence $phase
            New-Item -ItemType Directory -Path $destination | Out-Null
            $arguments = @('--offline','--dependency-verification=strict','runServerSmoke',
                "-PsmokeProfile=$profile","-PsmokeRunId=$id",'-Ps1cRealWall=true',"-Ps1cSeed=$seed",'--console=plain','--no-daemon')
            if ($phase -eq 'reopened') { $arguments += '-Ps1cReopen=true' }
            $info = [Diagnostics.ProcessStartInfo]::new()
            $info.FileName = $env:ComSpec
            $info.Arguments = '/d /c ""{0}" {1}"' -f (Join-Path $project 'gradlew.bat'),($arguments -join ' ')
            $info.WorkingDirectory = $project
            $info.UseShellExecute = $false
            $info.CreateNoWindow = $true
            $info.RedirectStandardOutput = $true
            $info.RedirectStandardError = $true
            $info.StandardOutputEncoding = $utf8
            $info.StandardErrorEncoding = $utf8
            $info.Environment['JAVA_HOME'] = 'C:\Program Files\Java\jdk-21'
            $start = [DateTime]::UtcNow
            $command = 'gradlew.bat ' + ($arguments -join ' ')
            [IO.File]::WriteAllText((Join-Path $destination 'inputs.json'),([ordered]@{
                sourceCommit=$commit;workspace=$project;command=$command;sourceInputs=$hashes;startedUtc=$start.ToString('o')
                seed=$seed;profile=$profile;phase=$phase;javaHome=$info.Environment['JAVA_HOME']
                productionWiring='OFF';networkUsed=$false;purpose='ordinary-dedicated-real-wall-not-human-walking'
            } | ConvertTo-Json -Depth 10),$utf8)
            $process = [Diagnostics.Process]::new()
            $process.StartInfo = $info
            $timer = [Diagnostics.Stopwatch]::StartNew()
            $null = $process.Start()
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            Write-Output "RUN $id phase=$phase seed=$seed production=OFF"
            $timeout = $false
            while (!$process.WaitForExit(1000)) {
                if ($timer.Elapsed.TotalMinutes -gt 43) {
                    $timeout = $true; $process.Kill($true); $process.WaitForExit(); break
                }
            }
            [IO.File]::WriteAllText((Join-Path $destination 'stdout.log'),$stdout.GetAwaiter().GetResult(),$utf8)
            [IO.File]::WriteAllText((Join-Path $destination 'stderr.log'),$stderr.GetAwaiter().GetResult(),$utf8)
            $code = $process.ExitCode
            $process.Dispose()
            foreach ($relative in @('logs\latest.log','logs\debug.log','server-smoke-passed.txt',
                'real-wall-progress.json','real-wall-reopen-progress.json',"real-wall-$phase.json",
                'world\greatwall\manifest\creation.json','world\greatwall\manifest\world.json',
                'world\greatwall\diagnostics\session-STOPPING.json','world\greatwall\diagnostics\session-STOPPED.json',
                'world\greatwall\diagnostics\events-STOPPING.json','world\greatwall\diagnostics\events-STOPPED.json')) {
                $from = Join-Path $root $relative
                if (Test-Path -LiteralPath $from) {
                    $target = Join-Path $destination $relative
                    [IO.Directory]::CreateDirectory((Split-Path $target -Parent)) | Out-Null
                    Copy-Item -LiteralPath $from -Destination $target
                }
            }
            $crash = Join-Path $root 'crash-reports'
            if (Test-Path -LiteralPath $crash) { Copy-Item -LiteralPath $crash -Destination $destination -Recurse }
            $sameInputs = @($hashes | Where-Object {
                (Get-FileHash -LiteralPath (Join-Path $project $_.path)).Hash.ToLowerInvariant() -ne $_.sha256
            }).Count -eq 0
            $cert = Join-Path $root "real-wall-$phase.json"
            $valid = $false
            if ((Test-Path -LiteralPath $cert) -and (Get-Item -LiteralPath $cert).LastWriteTimeUtc -ge $start) {
                $value = Get-Content -LiteralPath $cert -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
                $valid = $value.status -eq 'PASS_OBSERVED_REAL_WALL' -and $value.productionWiring -eq 'OFF' -and
                    $value.seed -eq $seed -and $value.ownerCount -gt 0 -and $value.owners.Count -eq $value.ownerCount -and
                    $value.voxels -gt 0 -and $value.walkwayFeet -gt 0 -and $value.reopen -eq ($phase -eq 'reopened')
            }
            $smoke = Join-Path $root 'server-smoke-passed.txt'
            $clean = (Test-Path -LiteralPath $smoke) -and (Get-Item -LiteralPath $smoke).LastWriteTimeUtc -ge $start
            $passed = $code -eq 0 -and !$timeout -and $valid -and $clean -and $sameInputs
            [IO.File]::WriteAllText((Join-Path $destination 'execution.json'),([ordered]@{
                sourceCommit=$commit;workspace=$project;runId=$id;profile=$profile;seed=$seed;phase=$phase
                command=$command;startedUtc=$start.ToString('o');finishedUtc=[DateTime]::UtcNow.ToString('o')
                seconds=$timer.Elapsed.TotalSeconds;exitCode=$code;timedOut=$timeout;passed=$passed
                freshCertificate=$valid;cleanShutdown=$clean;sourceInputsUnchanged=$sameInputs;productionWiring='OFF'
                NOT_RUN=@('S0 hundred-round terrain matrix','human walking','Golden','height6000','S5 24h')
                BLOCKED=@("Tan's Huge Trees",'TYG write-set')
            } | ConvertTo-Json -Depth 8),$utf8)
            $outputs = @(Get-ChildItem -LiteralPath $destination -File -Recurse | Sort-Object FullName | ForEach-Object {
                [ordered]@{path=[IO.Path]::GetRelativePath($destination,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
            })
            [IO.File]::WriteAllText((Join-Path $destination 'sha256.json'),($outputs | ConvertTo-Json -Depth 5),$utf8)
            Write-Output "RESULT $id phase=$phase passed=$passed exit=$code seconds=$([Math]::Round($timer.Elapsed.TotalSeconds,1)) evidence=$destination"
            if (!$passed) { throw "Real wall fixture did not pass; preserve $destination. Production stays OFF." }
        }
    }
}
