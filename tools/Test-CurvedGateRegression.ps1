param(
    [switch]$Worker,
    [string]$Workspace,
    [string]$Evidence,
    [string]$Classes,
    [string]$Snapshot,
    [string]$RunId = ('regression-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) { throw 'Pinned JDK21 is missing' }

if ($Worker) {
    $started = Get-Date
    $exitCode = -1
    $failure = $null
    $arguments = @('--offline', '--dependency-verification=strict', 'runGameTest',
        "-PgreatwallGameTestClasses=$Classes", '-PgreatwallTestHeap=1536m',
        '--rerun-tasks', '--no-build-cache', '--console=plain', '--no-daemon')
    try {
        Set-Location -LiteralPath $Workspace
        & .\gradlew.bat @arguments *> (Join-Path $Evidence 'gradle.log')
        $exitCode = $LASTEXITCODE
    } catch { $failure = $_.ToString() }
    finally {
        foreach ($name in @('latest.log','debug.log')) {
            $source = Join-Path $Workspace "build\run\gameTest\logs\$name"
            if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $Evidence $name) }
        }
        $xml = Join-Path $Workspace 'build\reports\gametest\results.xml'
        if (Test-Path -LiteralPath $xml) { Copy-Item -LiteralPath $xml -Destination (Join-Path $Evidence 'results.xml') }
        [ordered]@{
            snapshot=$Snapshot; classes=$Classes; started=$started.ToString('o'); finished=(Get-Date).ToString('o')
            workspace=$Workspace; javaHome=$env:JAVA_HOME; arguments=$arguments; exitCode=$exitCode; wrapperFailure=$failure
            files=@(Get-ChildItem -LiteralPath $Evidence -File | Where-Object { $_.Name -in @('gradle.log','latest.log','debug.log','results.xml') } |
                ForEach-Object { @{name=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash; bytes=$_.Length} })
        } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Evidence 'execution.json') -Encoding utf8
    }
    exit $exitCode
}

if ($RunId -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid run ID' }
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
if ((git status --porcelain).Length -ne 0) { throw 'Freeze and commit the source before starting regression' }
$Snapshot = (git rev-parse HEAD).Trim()
$scratch = Join-Path $root "build\curved-gate-shards\$RunId"
$archive = Join-Path $root "test-output\evidence\s1b-curved-gate\$RunId"
if ((Test-Path -LiteralPath $scratch) -or (Test-Path -LiteralPath $archive)) { throw 'Never overwrite an existing run' }
New-Item -ItemType Directory -Path $scratch,$archive | Out-Null
$zip = Join-Path $scratch 'inputs.zip'
git archive --format=zip "--output=$zip" $Snapshot src gradle tools build.gradle settings.gradle gradle.properties gradle.lockfile gradlew gradlew.bat
if ($LASTEXITCODE -ne 0) { throw 'Unable to snapshot tracked inputs' }
$specs = @(
    @{name='curved-sites'; classes='CurvedSiteMatrixTests'; combinations=64},
    @{name='adjacent'; classes='AdjacentComponentMatrixTests'; combinations=547}
)
$processes = @()
foreach ($spec in $specs) {
    $workspace = Join-Path $scratch $spec.name
    $evidence = Join-Path $archive $spec.name
    New-Item -ItemType Directory -Path $workspace,$evidence | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $workspace
    # Locked verification covers the existing remapped bytes, not a fresh remap of the same inputs.
    $cacheSource = Join-Path $root '.gradle\loom-cache\remapped_mods'
    $cacheParent = Join-Path $workspace '.gradle\loom-cache'
    if (-not (Test-Path -LiteralPath $cacheSource)) { throw 'Verified local remapped cache is missing' }
    New-Item -ItemType Directory -Path $cacheParent -Force | Out-Null
    Copy-Item -LiteralPath $cacheSource -Destination $cacheParent -Recurse
    $copied = foreach ($file in Get-ChildItem -LiteralPath $cacheSource -Recurse -File) {
        $relative = [IO.Path]::GetRelativePath($cacheSource,$file.FullName)
        $target = Join-Path (Join-Path $cacheParent 'remapped_mods') $relative
        $sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        if ($sourceHash -ne (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash) { throw "Cache copy differs: $relative" }
        @{path=$relative; sha256=$sourceHash}
    }
    $copied | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $evidence 'copied-cache.json') -Encoding utf8
    $args = @('-NoProfile','-File',"`"$PSCommandPath`"",'-Worker',
        '-Workspace',"`"$workspace`"",'-Evidence',"`"$evidence`"",'-Classes',$spec.classes,'-Snapshot',$Snapshot)
    $process = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -ArgumentList $args -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $evidence 'wrapper.stdout.log') -RedirectStandardError (Join-Path $evidence 'wrapper.stderr.log')
    $processes += @{process=$process; evidence=$evidence; name=$spec.name; reported=$false}
}
[ordered]@{snapshot=$Snapshot; runId=$RunId; started=(Get-Date).ToString('o'); archiveSha256=(Get-FileHash -LiteralPath $zip).Hash;
    shards=@($processes | ForEach-Object { @{name=$_.name; pid=$_.process.Id; evidence=$_.evidence} })
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $archive 'run.json') -Encoding utf8
Write-Output "REGRESSION_STARTED snapshot=$Snapshot evidence=$archive"
while ($processes | Where-Object { -not $_.reported }) {
    foreach ($item in $processes | Where-Object { -not $_.reported }) {
        $item.process.Refresh()
        if ($item.process.HasExited) {
            $item.process.WaitForExit()
            $item.reported = $true
            Write-Output "SHARD_FINISHED name=$($item.name) exit=$($item.process.ExitCode) evidence=$($item.evidence)"
        }
    }
    if ($processes | Where-Object { -not $_.reported }) { Start-Sleep -Seconds 10 }
}
$failed = @($processes | Where-Object { $_.process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $_.evidence 'execution.json')) })
Write-Output "REGRESSION_FINISHED failedShards=$($failed.Count) evidence=$archive"
if ($failed.Count -gt 0) { exit 1 }
