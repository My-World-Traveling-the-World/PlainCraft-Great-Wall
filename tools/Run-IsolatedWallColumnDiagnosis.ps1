param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$EvidenceId,
    [int]$X = 207,
    [int]$Z = 932,
    [int]$MinY = 69,
    [int]$MaxY = 74
)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ($EvidenceId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid evidence id' }
$evidence = Join-Path $workspace "docs/evidence/s1c/owner-coverage-ac-20261002/$EvidenceId"
if (Test-Path -LiteralPath $evidence) { throw 'Refusing to overwrite prior evidence' }
$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
$javac = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
$mapped = Join-Path $env:USERPROFILE '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/minecraft-common-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar'
$processed = Join-Path $workspace '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-6c431f37c1/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/minecraft-common-6c431f37c1-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar'
$cached = (Get-Content -LiteralPath (Join-Path $workspace '.gradle/loom-cache/remapClasspath.txt') -Raw).Trim().Split(';')
$libraries = @($cached | Where-Object { $_ -notmatch 'minecraft-(common|clientonly)-intermediary' })
$classpath = @($libraries) + @($mapped)
$modules = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$testModules = @(
    'org.junit.jupiter/junit-jupiter-api/5.11.0', 'org.junit.jupiter/junit-jupiter-engine/5.11.0',
    'org.junit.platform/junit-platform-commons/1.11.0', 'org.junit.platform/junit-platform-engine/1.11.0',
    'org.junit.platform/junit-platform-launcher/1.11.0', 'org.opentest4j/opentest4j/1.3.0',
    'org.apiguardian/apiguardian-api/1.1.2'
)
foreach ($module in $testModules) {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $modules $module) -Recurse -Filter '*.jar' -File)
    if ($jars.Count -ne 1) { throw "Ambiguous/unavailable frozen JUnit jar: $module" }
    $classpath += $jars[0].FullName
}
foreach ($path in @($java, $javac, $processed) + $classpath) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Cached file unavailable: $path" }
}
$tool = Join-Path $PSScriptRoot 'ReadIsolatedWallColumn.java'
$helper = Join-Path $workspace 'src/main/java/local/plaincraft/greatwall/diagnostics/WalkwayCollisionCheck.java'
$test = Join-Path $workspace 'src/test/java/local/plaincraft/greatwall/diagnostics/WalkwayCollisionCheckTest.java'
$testRunner = Join-Path $PSScriptRoot 'RunWalkwayCollisionTests.java'
$smokeSource = Join-Path $workspace 'src/serverSmoke/java/local/plaincraft/greatwall/smoke/RealTerrainFixture.java'
$compiledMain = Join-Path $workspace 'build/classes/java/main'
$compiledSmoke = Join-Path $workspace 'build/classes/java/serverSmoke'
$working = Join-Path $workspace "build/diagnostics/$EvidenceId"
$classes = Join-Path $working 'classes'
$compileClasspath = (@($compiledMain, $compiledSmoke, $processed) + $classpath) -join ';'
$runtimeClasspath = (@($classes, $compiledMain) + $classpath) -join ';'
$compileArguments = @('-J-Dfile.encoding=UTF-8', '-J-Dstdout.encoding=UTF-8',
    '-encoding', 'UTF-8', '--release', '21', '-proc:none', '--class-path', $compileClasspath,
    '-d', $classes, $helper, $test, $testRunner, $tool, $smokeSource)
$arguments = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '--class-path', $runtimeClasspath, 'ReadIsolatedWallColumn', $workspace, $RunId,
    [string]$X, [string]$Z, [string]$MinY, [string]$MaxY)
New-Item -ItemType Directory -Path $evidence | Out-Null
$hashes = @((@($processed) + $classpath) | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
})
ConvertTo-Json -InputObject $hashes -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'classpath.sha256.json') -Encoding utf8
$classHashes = @(Get-ChildItem -LiteralPath $compiledMain -Recurse -File | ForEach-Object {
    [ordered]@{ path = $_.FullName; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
ConvertTo-Json -InputObject $classHashes -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'compiled-main.sha256.json') -Encoding utf8
$sourceHashes = @(@($helper, $test, $testRunner, $tool, $PSCommandPath, $smokeSource) | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
})
ConvertTo-Json -InputObject $sourceHashes -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'diagnostic-sources.sha256.json') -Encoding utf8
$execution = [ordered]@{
    schema = 1
    purpose = 'READ_ONLY_OFFLINE_SAVED_NBT_NOT_STRICT_GRADLE_ACCEPTANCE'
    sourceCommit = (& git -C $workspace rev-parse HEAD).Trim()
    sourceStatus = @(& git -C $workspace -c core.longpaths=true status --short)
    toolSha256 = (Get-FileHash -LiteralPath $tool -Algorithm SHA256).Hash.ToLowerInvariant()
    launcherSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    executable = $java
    arguments = $arguments
    compiler = $javac
    compileArguments = $compileArguments
    startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    status = 'RUNNING'
}
New-Item -ItemType Directory -Path $classes | Out-Null
Push-Location -LiteralPath $working
$exitCode = $null
$failure = $null
try {
    & $javac @compileArguments 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'compile.log')
    $execution.compileExitCode = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "Diagnostic compilation failed: $LASTEXITCODE" }
    $testArguments = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
        '--class-path', $runtimeClasspath, 'RunWalkwayCollisionTests')
    $execution.testArguments = $testArguments
    & $java @testArguments 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'diagnostic-junit.log')
    $execution.diagnosticJUnitExitCode = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "Diagnostic JUnit failed: $LASTEXITCODE" }
    & $java @arguments 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'run.log')
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "Diagnostic Java process failed: $exitCode" }
    $report = Get-Content -LiteralPath (Join-Path $evidence 'run.log') -Raw | ConvertFrom-Json
    Copy-Item -LiteralPath $report.region -Destination (Join-Path $evidence ([IO.Path]::GetFileName($report.region)))
    $copy = Get-FileHash -LiteralPath (Join-Path $evidence ([IO.Path]::GetFileName($report.region))) -Algorithm SHA256
    if ($copy.Hash.ToLowerInvariant() -ne $report.regionSha256Before) { throw 'Region archive differs from diagnosed input' }
} catch {
    $failure = $_.Exception.Message
} finally {
    Pop-Location
    $execution.finishedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $execution.javaExitCode = $exitCode
    $execution.exitCode = if ($null -eq $failure) { 0 } else { 1 }
    $execution.status = if ($null -eq $failure) { 'DIAGNOSIS_COMPLETED_NOT_ACCEPTANCE' } else { 'COMMAND_FAILED' }
    if ($null -ne $failure) { $execution.failure = $failure }
    if (Test-Path -LiteralPath (Join-Path $evidence 'run.log')) {
        $execution.logSha256 = (Get-FileHash -LiteralPath (Join-Path $evidence 'run.log') -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    ConvertTo-Json -InputObject $execution -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'execution.json') -Encoding utf8
}
if ($null -ne $failure) { throw $failure }
