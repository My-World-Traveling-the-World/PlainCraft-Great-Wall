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

# TestRoot must live under this project's build/run tree; refuse real instances
# and symlink escapes. The gallery produces a fresh run directory per seed.
$root = [IO.Path]::GetFullPath((Join-Path $project $TestRoot))
$allowedRun = [IO.Path]::GetFullPath((Join-Path $project 'build\run')) + '\'
if (!$root.StartsWith($allowedRun, [StringComparison]::OrdinalIgnoreCase)) {
    throw "TestRoot must resolve under $allowedRun (got $root)."
}
$id = "s4-gallery-$Profile-$((Get-Date).ToString('yyyyMMdd'))-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$evidence = Join-Path $project "test-output\evidence\s4\$id"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null

$files = @(Get-ChildItem -LiteralPath (Join-Path $project 'src'), (Join-Path $project 'gradle'), (Join-Path $project 'tools') -Recurse -File)
$files += @(Get-Item -LiteralPath (Join-Path $project 'build.gradle'), (Join-Path $project 'settings.gradle'),
    (Join-Path $project 'gradle.properties'), (Join-Path $project 'gradle.lockfile'), (Join-Path $project 'gradlew.bat'))
$hashes = @($files | Sort-Object FullName | ForEach-Object {
    [ordered]@{ path = [IO.Path]::GetRelativePath($project, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
})

# S4 gallery is a GameTest subset: only the visual gallery + palette GameTests run.
$arguments = @('--offline', '--dependency-verification=strict', 'runS4Gallery',
    '-PgreatwallGameTestClasses=VisualGalleryFixture,PaletteGameTests',
    "-PgreatwallTestSeed=$Seed", '--console=plain', '--no-daemon')

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
    sourceCommit = $commit; workspace = $project; command = $command; sourceInputs = $hashes
    startedUtc = $start.ToString('o'); seed = $Seed; profile = $Profile; testRoot = $root
    javaHome = $info.Environment['JAVA_HOME']; productionWiring = 'OFF'; networkUsed = $false
    purpose = 's4-visual-gallery-gametest'
} | ConvertTo-Json -Depth 10), $utf8)

$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$timer = [Diagnostics.Stopwatch]::StartNew()
$null = $process.Start()
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
Write-Output "RUN $id profile=$Profile seed=$Seed"
$timeout = $false
while (!$process.WaitForExit(1000)) {
    if ($timer.Elapsed.TotalMinutes -gt 43) {
        $timeout = $true; $process.Kill($true); $process.WaitForExit(); break
    }
}
$out = $stdout.GetAwaiter().GetResult()
$err = $stderr.GetAwaiter().GetResult()
$code = $process.ExitCode
$process.Dispose()
[IO.File]::WriteAllText((Join-Path $evidence 'stdout.log'), $out, $utf8)
[IO.File]::WriteAllText((Join-Path $evidence 'stderr.log'), $err, $utf8)

# Collect the gametest report and any gallery JSON from the run tree.
foreach ($relative in @('reports\gametest', 'logs\latest.log')) {
    $from = Join-Path $project $relative
    if (Test-Path -LiteralPath $from) {
        $target = Join-Path $evidence $relative
        [IO.Directory]::CreateDirectory((Split-Path $target -Parent)) | Out-Null
        Copy-Item -LiteralPath $from -Destination $target -Recurse -Force
    }
}
$galleryJsons = @(Get-ChildItem -LiteralPath $root -Filter '*gallery*.json' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName | Select-Object -First 8)
foreach ($g in $galleryJsons) {
    Copy-Item -LiteralPath $g.FullName -Destination (Join-Path $evidence $g.Name)
}

$sameInputs = @($hashes | Where-Object {
    (Get-FileHash -LiteralPath (Join-Path $project $_.path)).Hash.ToLowerInvariant() -ne $_.sha256
}).Count -eq 0
$passed = $code -eq 0 -and !$timeout -and $sameInputs
[IO.File]::WriteAllText((Join-Path $evidence 'execution.json'), ([ordered]@{
    sourceCommit = $commit; workspace = $project; runId = $id; profile = $Profile; seed = $Seed
    command = $command; startedUtc = $start.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o')
    seconds = $timer.Elapsed.TotalSeconds; exitCode = $code; timedOut = $timeout; passed = $passed
    sourceInputsUnchanged = $sameInputs; productionWiring = 'OFF'
} | ConvertTo-Json -Depth 8), $utf8)
$outputs = @(Get-ChildItem -LiteralPath $evidence -File -Recurse | Sort-Object FullName | ForEach-Object {
    [ordered]@{ path = [IO.Path]::GetRelativePath($evidence, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
})
[IO.File]::WriteAllText((Join-Path $evidence 'sha256.json'), ($outputs | ConvertTo-Json -Depth 5), $utf8)
Write-Output "RESULT $id passed=$passed exit=$code seconds=$([Math]::Round($timer.Elapsed.TotalSeconds, 1)) evidence=$evidence"
if (!$passed) { throw "S4 gallery did not pass; preserve $evidence." }
