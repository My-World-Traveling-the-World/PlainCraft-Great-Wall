$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) -Parent
Set-Location -LiteralPath $root
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$runId='entrance-ab17-final-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
$dir=Join-Path $PSScriptRoot $runId
if(Test-Path -LiteralPath $dir){throw 'Refuse reusing an evidence run'}
New-Item -ItemType Directory -Path $dir | Out-Null
$started=(Get-Date).ToUniversalTime().ToString('o')
$head=git rev-parse HEAD
$command="gradlew.bat --offline --dependency-verification=strict runServerSmoke -PsmokeProfile=vanilla -PsmokeRunId=$runId -Ps1cRealWall=true -Ps1cSeed=17 -Ps1cRepresentativeBlocks=100 -Ps1cProtectionTrace=true --no-daemon --console=plain"
[ordered]@{sourceCommit="$head";runId=$runId;seed=17;startedUtc=$started;command=$command;production='OFF';Tans='BLOCKED'} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dir 'inputs.json') -Encoding utf8
Get-ChildItem -LiteralPath src -Recurse -File | ForEach-Object {
    [ordered]@{path=$_.FullName.Substring($root.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $dir 'source-inputs.json') -Encoding utf8
.\gradlew.bat --offline --dependency-verification=strict runServerSmoke '-PsmokeProfile=vanilla' `
    "-PsmokeRunId=$runId" '-Ps1cRealWall=true' '-Ps1cSeed=17' '-Ps1cRepresentativeBlocks=100' `
    '-Ps1cProtectionTrace=true' --no-daemon --console=plain 2>&1 |
    Tee-Object -FilePath (Join-Path $dir 'run.log')
$code=$LASTEXITCODE
$run=Join-Path $root "build/run/serverSmoke/vanilla/$runId"
foreach($name in 'real-wall-progress.json','real-wall-generated.json','real-wall-reopened.json',
        'greatwall_test_world','greatwall_s1c_fixture'){
    $file=Join-Path $run $name
    if(Test-Path -LiteralPath $file){Copy-Item -LiteralPath $file -Destination $dir}
}
foreach($name in 'logs','crash-reports','world/greatwall'){
    $from=Join-Path $run $name
    if(Test-Path -LiteralPath $from){
        $to=Join-Path $dir $name
        New-Item -ItemType Directory -Force -Path $to | Out-Null
        Get-ChildItem -LiteralPath $from -Force | Copy-Item -Destination $to -Recurse -Force
    }
}
$voxels=$null
$progress=Join-Path $run 'real-wall-progress.json'
if(Test-Path -LiteralPath $progress){
    $value=Get-Content -LiteralPath $progress -Raw -Encoding UTF8 | ConvertFrom-Json -DateKind String
    $voxels=$value.voxels
}
[ordered]@{
    sourceCommit="$head";runId=$runId;seed=17;startedUtc=$started
    finishedUtc=(Get-Date).ToUniversalTime().ToString('o');exitCode=$code;voxels=$voxels
    command=$command;sourceCommitUnchanged=((git rev-parse HEAD) -eq $head)
    production='OFF';Tans='BLOCKED';scope='fixed seed17 isolated fixture, diagnostic 100 threshold; not full-stage acceptance'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dir 'execution.json') -Encoding utf8
Write-Output "REAL_RUN runId=$runId exit=$code voxels=$voxels evidence=$dir"
exit $code
