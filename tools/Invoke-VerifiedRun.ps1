param(
    [Parameter(Mandatory)][string]$Evidence,
    [Parameter(Mandatory)][string[]]$Tasks
)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$directory=[IO.Path]::GetFullPath((Join-Path $workspace $Evidence))
if (!$directory.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence directory must remain inside this project'
}
if (Test-Path -LiteralPath $directory) { throw 'Evidence directory already exists; refusing to overwrite' }
New-Item -ItemType Directory -Path $directory | Out-Null
Set-Location -LiteralPath $workspace
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$sourceCommit=(& git rev-parse HEAD).Trim()
$status=@(& git -c core.longpaths=true status --short)
$hashes=[ordered]@{}
foreach ($file in @(& rg --files src gradle tools libs)) {
    $hashes[$file]=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
}
foreach ($file in @('build.gradle','gradle.properties','settings.gradle','gradle.lockfile',
        'gradle/verification-metadata.xml')) {
    if (Test-Path -LiteralPath $file) {
        $hashes[$file]=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$hashes | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $directory 'source-files.sha256.json') -Encoding utf8
$arguments=@('--offline','--dependency-verification=strict')+$Tasks+@('--no-daemon','--console=plain')
$start=[DateTimeOffset]::UtcNow
$record=[ordered]@{
    schema=1; sourceCommit=$sourceCommit; workspace=$workspace; sourceStatus=$status
    sourceHashes='source-files.sha256.json'; command=@('.\gradlew.bat')+$arguments
    startedUtc=$start.ToString('o'); status='RUNNING'; exitCode=$null
}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $directory 'execution.json') -Encoding utf8
$code=1
try {
    & .\gradlew.bat @arguments *> (Join-Path $directory 'run.log')
    $code=$LASTEXITCODE
} catch {
    $_ | Out-String | Add-Content -LiteralPath (Join-Path $directory 'run.log') -Encoding utf8
    $record['wrapperFailure']=$_.ToString()
} finally {
    $finish=[DateTimeOffset]::UtcNow
    $record['finishedUtc']=$finish.ToString('o')
    $record['elapsedSeconds']=($finish-$start).TotalSeconds
    $record['status']=if ($code -eq 0) {'COMMAND_COMPLETED_EXIT_0'} else {'COMMAND_FAILED'}
    $record['exitCode']=$code
    $record['logSha256']=(Get-FileHash -LiteralPath (Join-Path $directory 'run.log') -Algorithm SHA256).Hash.ToLowerInvariant()
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $directory 'execution.json') -Encoding utf8
}
Get-Content -LiteralPath (Join-Path $directory 'execution.json') -Encoding utf8
exit $code
