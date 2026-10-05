param(
    [Parameter(Mandatory)][string]$TestRoot,
    [int]$Seed = 9282026,
    [ValidateSet('vanilla', 'tectonic', 'c2me-cpu', 'fullpack-opencl')][string]$Profile = 'vanilla'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$utf8 = [Text.UTF8Encoding]::new($false)
$commit = (& git -C $project rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit' }

# Release verification runs verifyReleaseArtifacts twice from two clean builds and
# confirms the remapJar SHA matches. No run directory is required here, but keep
# the same TestRoot/Profile signature for protocol consistency.
$root = [IO.Path]::GetFullPath((Join-Path $project $TestRoot))
$allowedRun = [IO.Path]::GetFullPath((Join-Path $project 'build\run')) + '\'
$allowedFault = [IO.Path]::GetFullPath((Join-Path $project 'build\fault-runs')) + '\'
if (!$root.StartsWith($allowedRun, [StringComparison]::OrdinalIgnoreCase) -and
    !$root.StartsWith($allowedFault, [StringComparison]::OrdinalIgnoreCase)) {
    throw "TestRoot must resolve under build\run\ or build\fault-runs\ (got $root)."
}
$id = "s5-release-$Profile-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$evidence = Join-Path $project "docs\evidence\s5\$id"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null

function Invoke-GradleBuild([string]$tag, [string[]]$extraArgs) {
    $destination = Join-Path $evidence $tag
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $arguments = @('--offline', '--dependency-verification=strict', 'clean', 'build',
        'verifyReleaseArtifacts') + $extraArgs + @('--console=plain', '--no-daemon')
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
    [IO.File]::WriteAllText((Join-Path $destination 'inputs.json'), ([ordered]@{
        sourceCommit = $commit; workspace = $project; tag = $tag
        command = 'gradlew.bat ' + ($arguments -join ' '); startedUtc = $start.ToString('o')
        seed = $Seed; profile = $Profile; javaHome = $info.Environment['JAVA_HOME']
        networkUsed = $false; purpose = 's5-release-two-clean-builds'
    } | ConvertTo-Json -Depth 8), $utf8)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $timeout = $false
    while (!$process.WaitForExit(1000)) {
        if ($timer.Elapsed.TotalMinutes -gt 60) { $timeout = $true; $process.Kill($true); $process.WaitForExit(); break }
    }
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    $code = $process.ExitCode
    $process.Dispose()
    [IO.File]::WriteAllText((Join-Path $destination 'stdout.log'), $out, $utf8)
    [IO.File]::WriteAllText((Join-Path $destination 'stderr.log'), $err, $utf8)
    $verify = Join-Path $project 'build\release\verifyReleaseArtifacts.json'
    $verifyCopy = $null
    if (Test-Path -LiteralPath $verify) {
        Copy-Item -LiteralPath $verify -Destination (Join-Path $destination 'verifyReleaseArtifacts.json')
        $verifyCopy = Get-Content -LiteralPath $verify -Raw | ConvertFrom-Json
    }
    $jar = Get-ChildItem -LiteralPath (Join-Path $project 'build\libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Sort-Object Name
    $jarHashes = @($jar | ForEach-Object {
        [ordered]@{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
    })
    [IO.File]::WriteAllText((Join-Path $destination 'execution.json'), ([ordered]@{
        sourceCommit = $commit; workspace = $project; tag = $tag; startedUtc = $start.ToString('o')
        finishedUtc = [DateTime]::UtcNow.ToString('o'); seconds = $timer.Elapsed.TotalSeconds
        exitCode = $code; timedOut = $timeout; passed = $code -eq 0 -and !$timeout
        verify = $verifyCopy; jars = $jarHashes
    } | ConvertTo-Json -Depth 10), $utf8)
    return [ordered]@{ tag = $tag; exitCode = $code; timedOut = $timeout; jars = $jarHashes; verify = $verifyCopy }
}

$buildA = Invoke-GradleBuild 'build-a' @()
$buildB = Invoke-GradleBuild 'build-b' @()

# Compare the remapJar (plaincraft-great-wall-0.1.0.jar) SHA across the two builds.
$remapA = @($buildA.jars | Where-Object { $_.name -like 'plaincraft-great-wall-*.jar' } | Sort-Object name)
$remapB = @($buildB.jars | Where-Object { $_.name -like 'plaincraft-great-wall-*.jar' } | Sort-Object name)
$shaMatch = $false
if ($remapA.Count -gt 0 -and $remapB.Count -gt 0) {
    $shaMatch = $remapA[0].sha256 -ceq $remapB[0].sha256
}
$passed = !$buildA.timedOut -and !$buildB.timedOut -and
    $buildA.exitCode -eq 0 -and $buildB.exitCode -eq 0 -and $shaMatch

[IO.File]::WriteAllText((Join-Path $evidence 'execution.json'), ([ordered]@{
    sourceCommit = $commit; workspace = $project; runId = $id; profile = $Profile; seed = $Seed
    buildA = $buildA; buildB = $buildB; remapJarShaMatch = $shaMatch
    remapJarA = $(if ($remapA.Count) { $remapA[0].sha256 } else { $null })
    remapJarB = $(if ($remapB.Count) { $remapB[0].sha256 } else { $null })
    passed = $passed; productionWiring = 'OFF'
} | ConvertTo-Json -Depth 10), $utf8)
Write-Output "RESULT $id passed=$passed shaMatch=$shaMatch evidence=$evidence"
if (!$passed) { throw "S5 release verification did not pass; preserve $evidence." }
