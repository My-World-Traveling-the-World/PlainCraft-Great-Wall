$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) -Parent
Set-Location -LiteralPath $root
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$sha=[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($root))
$key=[Convert]::ToHexString($sha).ToLowerInvariant()
$bridge=Join-Path $env:USERPROFILE ".gradle/caches/plaincraft-great-wall/test-classpath/$key/classpath.jar"
if(!(Test-Path -LiteralPath $bridge)){throw 'Compile the project testClasspathBridge offline before this analysis.'}
$out=Join-Path $root 'build/analysis/fort0-policy'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$source=Join-Path $PSScriptRoot 'PolicyPreflight.java'
$fixture=Join-Path $root 'build/run/serverSmoke/vanilla/fort0-revised-foundation-17-20261002-002523'
$receipt=[ordered]@{
    sourceCommit=(git rev-parse HEAD)
    startedUtc=(Get-Date).ToUniversalTime().ToString('o')
    diagnosticSourceSHA256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    command='Run-Preflight.ps1: javac with existing strict-offline Gradle classpath bridge; two java analysis processes in series'
    scope='counterfactual T0/grade analysis only, no production changes or real world launch'
    production='OFF'
    Tans='BLOCKED'
}
& "$env:JAVA_HOME/bin/javac.exe" '-J-Dfile.encoding=UTF-8' '-J-Dstderr.encoding=UTF-8' -proc:none `
    -encoding UTF-8 -cp $bridge -d $out $source 2>&1 |
    Tee-Object -FilePath (Join-Path $PSScriptRoot 'compile.log')
$receipt.compileExitCode=$LASTEXITCODE
if($receipt.compileExitCode -ne 0){throw 'Analysis compile failed.'}
$runs=@()
foreach($mode in 'support','replace-only'){
    $extra=@()
    if($mode -eq 'replace-only'){$extra=@('replace-only')}
    & "$env:JAVA_HOME/bin/java.exe" '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
        -cp "$out;$bridge" PolicyPreflight $fixture (Join-Path $PSScriptRoot "$mode-preflight.json") @extra 2>&1 |
        Tee-Object -FilePath (Join-Path $PSScriptRoot "$mode-run.log")
    $code=$LASTEXITCODE
    $runs+=@{mode=$mode;exitCode=$code}
    if($code -ne 0){throw "Analysis mode $mode failed."}
}
$receipt.runs=$runs
$receipt.finishedUtc=(Get-Date).ToUniversalTime().ToString('o')
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'final-execution.json') -Encoding utf8
