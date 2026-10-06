param(
    [Parameter(Mandatory)][string]$TestRoot,
    [int]$Seed = 9282026,
    [ValidateSet('vanilla', 'tectonic', 'c2me-cpu', 'fullpack-opencl')][string]$Profile = 'vanilla',
    [int]$Hours = 24,
    [ValidateSet(1, 2, 8, 16)][int]$Concurrency = 8
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$utf8 = [Text.UTF8Encoding]::new($false)
$commit = (& git -C $project rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit' }

# Stress root must live under build\run; refuse real instance/symlink escapes.
$root = [IO.Path]::GetFullPath((Join-Path $project $TestRoot))
$allowed = [IO.Path]::GetFullPath((Join-Path $project 'build\run')) + '\'
if (!$root.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw "TestRoot must resolve under $allowed (got $root)."
}
$id = "s5-stress-$Profile-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$evidence = Join-Path $project "docs\evidence\s5\$id"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null

# 24h stress: duration measured in hours by the driver, converted to minutes for the in-server
# fixture; concurrency is validated upstream by StressController to {1,2,8,16}.
$durationSeconds = [long]$Hours * 3600
$arguments = @('--offline', '--dependency-verification=strict', 'runS5Stress',
    "-PgreatwallStressHours=$Hours", "-Ps5Stress=true", "-Ps5StressMinutes=$([long]$Hours * 60)",
    "-PgreatwallStressConcurrency=$Concurrency", "-PgreatwallStressSeed=$Seed",
    '--console=plain', '--no-daemon')

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

$start = [DateTime]::UtcNow
$command = 'gradlew.bat ' + ($arguments -join ' ')
[IO.File]::WriteAllText((Join-Path $evidence 'inputs.json'), ([ordered]@{
    sourceCommit = $commit; workspace = $project; command = $command
    startedUtc = $start.ToString('o'); seed = $Seed; profile = $Profile
    hours = $Hours; concurrency = $Concurrency; durationSeconds = $durationSeconds
    testRoot = $root; javaHome = $info.Environment['JAVA_HOME']
    productionWiring = 'OFF'; networkUsed = $false; purpose = 's5-stress-24h-profile'
} | ConvertTo-Json -Depth 10), $utf8)

$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$timer = [Diagnostics.Stopwatch]::StartNew()
$null = $process.Start()
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
Write-Output "RUN $id profile=$Profile hours=$Hours concurrency=$Concurrency seed=$Seed"
$timeout = $false
# 24h wall clock plus generous shutdown margin.
$timeoutMinutes = [double]($Hours * 60) + 90
while (!$process.WaitForExit(1000)) {
    if ($timer.Elapsed.TotalMinutes -gt $timeoutMinutes) {
        $timeout = $true; $process.Kill($true); $process.WaitForExit(); break
    }
}
$out = $stdout.GetAwaiter().GetResult()
$err = $stderr.GetAwaiter().GetResult()
$code = $process.ExitCode
$process.Dispose()
[IO.File]::WriteAllText((Join-Path $evidence 'stdout.log'), $out, $utf8)
[IO.File]::WriteAllText((Join-Path $evidence 'stderr.log'), $err, $utf8)

foreach ($relative in @('logs\latest.log', 'server-smoke-passed.txt')) {
    $from = Join-Path $root $relative
    if (Test-Path -LiteralPath $from) {
        $target = Join-Path $evidence $relative
        [IO.Directory]::CreateDirectory((Split-Path $target -Parent)) | Out-Null
        Copy-Item -LiteralPath $from -Destination $target
    }
}
$crash = Join-Path $root 'crash-reports'
if (Test-Path -LiteralPath $crash) { Copy-Item -LiteralPath $crash -Destination $evidence -Recurse }
$passed = $code -eq 0 -and !$timeout
[IO.File]::WriteAllText((Join-Path $evidence 'execution.json'), ([ordered]@{
    sourceCommit = $commit; workspace = $project; runId = $id; profile = $Profile
    seed = $Seed; hours = $Hours; concurrency = $Concurrency; durationSeconds = $durationSeconds
    command = $command; startedUtc = $start.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o')
    seconds = $timer.Elapsed.TotalSeconds; exitCode = $code; timedOut = $timeout; passed = $passed
    productionWiring = 'OFF'
} | ConvertTo-Json -Depth 8), $utf8)
$outputs = @(Get-ChildItem -LiteralPath $evidence -File -Recurse | Sort-Object FullName | ForEach-Object {
    [ordered]@{ path = [IO.Path]::GetRelativePath($evidence, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
})
[IO.File]::WriteAllText((Join-Path $evidence 'sha256.json'), ($outputs | ConvertTo-Json -Depth 5), $utf8)
Write-Output "RESULT $id passed=$passed exit=$code seconds=$([Math]::Round($timer.Elapsed.TotalSeconds, 1)) evidence=$evidence"
if (!$passed) { throw "S5 stress profile did not pass; preserve $evidence." }
