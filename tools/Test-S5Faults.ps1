param(
    [Parameter(Mandatory)][string]$TestRoot,
    [int]$Seed = 9282026,
    [ValidateSet('vanilla', 'tectonic', 'c2me-cpu', 'fullpack-opencl')][string]$Profile = 'vanilla',
    [ValidateSet('exception', 'halt')][string]$FaultMode = 'exception',
    [int]$Repeats = 10
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$utf8 = [Text.UTF8Encoding]::new($false)
$commit = (& git -C $project rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit' }

# Fault runs land under build\fault-runs; refuse real instance/symlink escapes.
$root = [IO.Path]::GetFullPath((Join-Path $project $TestRoot))
$allowed = [IO.Path]::GetFullPath((Join-Path $project 'build\fault-runs')) + '\'
if (!$root.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw "TestRoot must resolve under $allowed (got $root)."
}
$id = "s5-faults-$FaultMode-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$evidence = Join-Path $project "docs\evidence\s5\$id"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
New-Item -ItemType Directory -Path $root -Force | Out-Null

# 18 fault points, each x {exception,halt} x Repeats rounds.
$points = @('CACHE_HALF_WRITE', 'MANIFEST_BEFORE_MOVE', 'MANIFEST_AFTER_MOVE',
    'T0_BEFORE_FORCE', 'T0_AFTER_FORCE', 'HISTORY_BEFORE_APPEND', 'HISTORY_AFTER_APPEND',
    'HISTORY_BEFORE_FORCE', 'HISTORY_AFTER_FORCE', 'HEAD_BEFORE_MOVE', 'HEAD_AFTER_MOVE',
    'GATE_BEFORE_FIRST_WRITE', 'GATE_MID_CHUNK', 'GATE_BEFORE_ACK', 'GATE_AFTER_ACK',
    'WORLD_AFTER_CLOSE', 'DISK_FULL', 'QOS_FEATURE_SKIP')

$results = [Collections.Generic.List[object]]::new()
$overall = $true
foreach ($point in $points) {
    $pointOk = $true
    for ($occurrence = 1; $occurrence -le $Repeats; $occurrence++) {
        # FaultProcessMain is run as an isolated subprocess. halt mode is expected
        # to exit 137; exception mode is expected to exit non-zero after writing
        # fault-hit.json.
        $faultArgs = @("--root", $root, "--point", $point, "--occurrence", "$occurrence",
            "--mode", $FaultMode)
        $runDir = Join-Path $root "$point-$occurrence-$FaultMode"
        New-Item -ItemType Directory -Path $runDir -Force | Out-Null
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $env:ComSpec
        $info.Arguments = '/d /c ""{0}" {1}"' -f (Join-Path $project 'gradlew.bat'),
            ((@('--offline', '--dependency-verification=strict', 'runS5Faults',
                "-PgreatwallFaultRoot=$root",
                "-PgreatwallFaultPoint=$point", "-PgreatwallFaultOccurrence=$occurrence",
                "-PgreatwallFaultMode=$FaultMode", '--console=plain', '--no-daemon')) -join ' ')
        $info.WorkingDirectory = $project
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.StandardOutputEncoding = $utf8
        $info.StandardErrorEncoding = $utf8
        $info.Environment['JAVA_HOME'] = 'C:\Program Files\Java\jdk-21'
        $proc = [Diagnostics.Process]::new()
        $proc.StartInfo = $info
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $null = $proc.Start()
        $stdout = $proc.StandardOutput.ReadToEndAsync()
        $stderr = $proc.StandardError.ReadToEndAsync()
        $timedOut = $false
        while (!$proc.WaitForExit(1000)) {
            if ($timer.Elapsed.TotalMinutes -gt 43) { $timedOut = $true; $proc.Kill($true); $proc.WaitForExit(); break }
        }
        $outText = $stdout.GetAwaiter().GetResult()
        $errText = $stderr.GetAwaiter().GetResult()
        $code = $proc.ExitCode
        $proc.Dispose()
        [IO.File]::WriteAllText((Join-Path $runDir 'stdout.log'), $outText, $utf8)
        [IO.File]::WriteAllText((Join-Path $runDir 'stderr.log'), $errText, $utf8)

        # Expected exit: exception → non-zero; halt → 137 (Runtime.halt).
        $expectedExit = if ($FaultMode -eq 'halt') { 137 } else { -1 }
        $hitFile = Join-Path $runDir 'fault-hit.json'
        $hitWritten = (Test-Path -LiteralPath $hitFile)
        # The Gradle wrapper always exits 0 because the task ignores the child's status on purpose, so
        # the real code is read from the witness the task writes after the child exits. Without this the
        # driver's non-zero expectation could never be satisfied and every point would report failure.
        $codeFile = Join-Path $runDir 'exit-code.txt'
        $childCode = if (Test-Path -LiteralPath $codeFile) {
            [int](Get-Content -LiteralPath $codeFile -Raw).Trim()
        } else { $null }
        if ($null -ne $childCode) { $code = $childCode }
        $ok = !$timedOut -and $hitWritten -and $null -ne $childCode
        if ($FaultMode -eq 'halt') { $ok = $ok -and $code -eq 137 }
        else { $ok = $ok -and $code -ne 0 }
        if (!$ok) { $pointOk = $false; $overall = $false }
        $results.Add([ordered]@{
            point = $point; occurrence = $occurrence; mode = $FaultMode
            exitCode = $code; expectedExit = $expectedExit; faultHitWritten = $hitWritten
            timedOut = $timedOut; seconds = $timer.Elapsed.TotalSeconds; passed = $ok
        })
        Write-Output "FAULT $point #$occurrence mode=$FaultMode exit=$code passed=$ok"
    }
}

$results.ToArray() | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'results.json') -Encoding utf8
[IO.File]::WriteAllText((Join-Path $evidence 'execution.json'), ([ordered]@{
    sourceCommit = $commit; workspace = $project; runId = $id; faultMode = $FaultMode
    repeats = $Repeats; points = $points; testRoot = $root; passed = $overall
    productionWiring = 'OFF'
} | ConvertTo-Json -Depth 6), $utf8)
Write-Output "RESULT $id passed=$overall mode=$FaultMode repeats=$Repeats evidence=$evidence"
if (!$overall) { throw "S5 fault matrix did not pass; preserve $evidence." }
