param(
    [string]$SourceInstance = 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING',
    [ValidateSet('vanilla','tectonic-c2me')][string]$Profile = 'vanilla',
    [ValidateSet('durable','saved')][string]$CrashPoint = 'durable',
    [ValidateSet('none','missing','corrupt')][string]$Fault = 'none'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Fault -ne 'none' -and $CrashPoint -ne 'saved') { throw 'Evidence-loss tests require saved native markers' }
$project = Split-Path $PSScriptRoot -Parent
$id = "s003-recovery-$Profile-$CrashPoint-$Fault-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$root = [IO.Path]::GetFullPath((Join-Path $project "build\run\serverSmoke\$Profile\$id"))
$output = Join-Path $project "docs\evidence\s0-03\recovery\$id"
New-Item -ItemType Directory -Path $output -Force | Out-Null
if ($Profile -ne 'vanilla') {
    & (Join-Path $PSScriptRoot 'Stage-SmokeProfile.ps1') -SourceInstance $SourceInstance -Profile $Profile -RunId $id -EvidenceStage s0-03
}
function Invoke-Phase([bool]$Resume) {
    $phase = if ($Resume) { 'resume' } else { 'crash' }
    $arguments = @('--offline','runServerSmoke',"-PsmokeProfile=$Profile","-PsmokeRunId=$id",'-Ps0Probe=true',
        '-Ps0Terrain=true','-Ps0TerrainRounds=1','-Ps0TerrainConcurrency=2',
        "-Ps0TerrainResume=$($Resume.ToString().ToLowerInvariant())",
        "-Ps0ExpectedFailure=$((!$Resume -or $Fault -ne 'none').ToString().ToLowerInvariant())",
        '--dependency-verification=strict','--no-daemon','--console=plain')
    if (!$Resume) {
        $arguments += if ($CrashPoint -eq 'durable') { '-Ps0TerrainPauseBeforeApply=true' } else { '-Ps0TerrainCrashAfterSave=true' }
    }
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
    $killed = $false
    $checkpoint = if ($CrashPoint -eq 'durable') { 'CRASH_READY_AFTER_DURABLE' } else { 'CRASH_READY_AFTER_SAVE' }
    Write-Host "RUN $id phase=$phase"
    try {
        while (!$process.WaitForExit(500)) {
            if ($timer.Elapsed.TotalSeconds -gt 180) { throw "Recovery phase timed out: $id $phase" }
            $log = Join-Path $root 'logs\latest.log'
            if (!$Resume -and !$killed -and (Test-Path -LiteralPath $log) -and
                    (Get-Content -LiteralPath $log -Raw) -match $checkpoint) {
                $games = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and
                    $_.CommandLine -match [regex]::Escape("-Dgreatwall.s0.runId=$id") })
                if ($games.Count -ne 1) { throw "Cannot identify exactly this isolated test JVM: $id" }
                $games | Select-Object ProcessId,ParentProcessId,ExecutablePath,CommandLine |
                    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'killed-test-jvm.json') -Encoding utf8
                Stop-Process -Id $games[0].ProcessId -Force
                $killed = $true
            }
        }
    } finally {
        if (!$process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        $text | Set-Content -LiteralPath (Join-Path $output "$phase-gradle.txt") -Encoding utf8
        $terrain = Join-Path $root 'world\greatwall-s0\terrain'
        if (Test-Path -LiteralPath $terrain) { Copy-Item -LiteralPath $terrain -Destination (Join-Path $output "$phase-terrain") -Recurse }
        [ordered]@{phase=$phase;exitCode=$process.ExitCode;seconds=$timer.Elapsed.TotalSeconds;forcedTestJvmKill=$killed;arguments=$arguments} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "$phase-execution.json") -Encoding utf8
    }
    if (!$Resume) {
        if (!$killed -or $text -notmatch $checkpoint -or $text -match 'FIXTURE_PASS') { throw "Crash point was not reached: $id" }
    } elseif ($Fault -eq 'none') {
        if ($process.ExitCode -ne 0 -or $text -notmatch 'FIXTURE_PASS' -or !(Test-Path -LiteralPath (Join-Path $root 'server-smoke-passed.txt'))) {
            throw "Recovery did not pass: $id"
        }
    } else {
        $expected = if ($Fault -eq 'missing') { 'T0_EVIDENCE_MISSING' } else { 'T0_CORRUPT' }
        if ($text -notmatch $expected -or $text -match 'FIXTURE_PASS' -or (Test-Path -LiteralPath (Join-Path $root 'server-smoke-passed.txt'))) {
            throw "Authoritative evidence failure was not rejected: $id"
        }
    }
}
Invoke-Phase $false
$evidence = [IO.Path]::GetFullPath((Join-Path $root 'world\greatwall-s0\terrain\evidence'))
$files = @(Get-ChildItem -LiteralPath $evidence -Filter '*.t0')
if ($files.Count -lt 1) { throw 'No durable T0 survived the actual process kill' }
if ($Fault -ne 'none') {
    $target = $files[0].FullName
    if (![IO.Path]::GetFullPath($target).StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Fault target escaped this test world' }
    [ordered]@{fault=$Fault;path=$target;sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'fault.json') -Encoding utf8
    if ($Fault -eq 'missing') { Remove-Item -LiteralPath $target }
    else {
        $bytes = [IO.File]::ReadAllBytes($target)
        $bytes[12] = $bytes[12] -bxor 1
        [IO.File]::WriteAllBytes($target, $bytes)
    }
}
Invoke-Phase $true
$session = Get-Content -LiteralPath (Join-Path $root 'world\greatwall-s0\terrain\barrier-session.json') -Raw | ConvertFrom-Json -AsHashtable
if (!$session.closed -or $session.leases -ne 0 -or $session.delays -ne 0) { throw 'Recovery left active terrain work' }
if ($Fault -eq 'none') {
    $reused = [long]$session.counts['T0_DISK_REUSED'] + [long]$session.counts['LOADED_EVIDENCE_VERIFIED']
    if ($reused -lt 1) { throw 'Recovery did not read durable T0' }
    foreach ($before in Get-ChildItem -LiteralPath (Join-Path $output 'crash-terrain\evidence') -Filter '*.t0') {
        $after = Join-Path $evidence $before.Name
        if ((Get-FileHash -LiteralPath $before.FullName).Hash -ne (Get-FileHash -LiteralPath $after).Hash) {
            throw 'Recovery changed authoritative pre-apply T0 evidence'
        }
    }
} else {
    $faultCheck=& (Join-Path $PSScriptRoot 'Test-TerrainFaultEvidence.ps1') -CaseDirectory $output
    $faultCheck | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'fault-checks.json') -Encoding utf8
}
[ordered]@{id=$id;profile=$Profile;crashPoint=$CrashPoint;fault=$Fault;survivingEvidence=$files.Count;passed=$true;counts=$session.counts} |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
Write-Output "PASS $id"
