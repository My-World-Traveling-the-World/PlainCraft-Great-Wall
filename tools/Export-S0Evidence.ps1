param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = Split-Path $PSScriptRoot -Parent
$output = Join-Path $project 'docs\evidence\s0-02\adapter'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$results = @(Get-Content -LiteralPath (Join-Path $project 'docs\evidence\s0-02\matrix\final-default-results.json') -Raw | ConvertFrom-Json)
$offResults = @(Get-Content -LiteralPath (Join-Path $project 'docs\evidence\s0-02\matrix\final-rewrite-off-results.json') -Raw | ConvertFrom-Json)
$postReloadResults = @(Get-Content -LiteralPath (Join-Path $project 'docs\evidence\s0-02\matrix\final-post-reload-results.json') -Raw | ConvertFrom-Json)
$allResults = @($results) + @($offResults) + @($postReloadResults)
if ($results.Count -ne 18 -or $offResults.Count -ne 9 -or $postReloadResults.Count -ne 1 -or
        @($allResults | Where-Object { !$_.passed }).Count -gt 0) {
    throw 'Expected 18 default, 9 rewrite-off and 1 post-reload passing cases'
}
$exports = [Collections.Generic.List[object]]::new()
foreach ($profile in @('vanilla', 'tectonic-c2me')) {
    $case = $results | Where-Object { $_.profile -eq $profile -and $_.outcome -eq 'SUCCESS' -and !$_.cancel -and $_.reload -eq 'none' } | Select-Object -First 1
    if (!$case) { throw "Missing passing runtime case: $profile" }
    $run = Join-Path $project "build\run\serverSmoke\$profile\$($case.id)"
    $classes = @('net\minecraft\server\level\ChunkMap.class')
    if ($profile -eq 'tectonic-c2me') {
        $classes += 'com\ishland\c2me\rewrites\chunksystem\common\statuses\VanillaWorldGenerationDelegate.class'
    }
    foreach ($relative in $classes) {
        $source = Join-Path $run ".mixin.out\class\$relative"
        if (!(Test-Path -LiteralPath $source)) { throw "No actual transformed class: $source" }
        $name = [IO.Path]::GetFileNameWithoutExtension($relative)
        $disassembly = & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c $source 2>&1
        if ($LASTEXITCODE -ne 0) { throw "javap failed: $source" }
        $text = $disassembly -join "`n"
        $expected = if ($name -eq 'ChunkMap') { 'greatwall\$gate' } else { 'greatwall\$beforeLock' }
        if ($text -notmatch $expected) { throw "Required injection absent: $source" }
        $text | Set-Content -LiteralPath (Join-Path $output "$profile-$name.txt") -Encoding utf8
        $exports.Add([ordered]@{profile=$profile; runId=$case.id; class=$relative;
            sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant(); injection=$expected})
    }
}
$offCase = $offResults | Where-Object { $_.outcome -eq 'SUCCESS' -and !$_.cancel -and $_.reload -eq 'none' } | Select-Object -First 1
$offRoot = Join-Path $project "build\run\serverSmoke\tectonic-c2me\$($offCase.id)\.mixin.out\class"
$offClass = Join-Path $offRoot 'net\minecraft\server\level\ChunkMap.class'
$offText = & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c $offClass 2>&1
if ($LASTEXITCODE -ne 0 -or ($offText -join "`n") -notmatch 'greatwall\$gate') { throw 'Rewrite-off gate not exported' }
if (Test-Path -LiteralPath (Join-Path $offRoot 'com\ishland\c2me\rewrites\chunksystem\common\statuses\VanillaWorldGenerationDelegate.class')) {
    throw 'Rewrite target unexpectedly loaded in the disabled-module fixture'
}
$offText | Set-Content -LiteralPath (Join-Path $output 'tectonic-c2me-rewrite-off-ChunkMap.txt') -Encoding utf8
$exports.Add([ordered]@{profile='tectonic-c2me-rewrite-off'; runId=$offCase.id; class='net\minecraft\server\level\ChunkMap.class';
    sha256=(Get-FileHash -LiteralPath $offClass -Algorithm SHA256).Hash.ToLowerInvariant(); injection='greatwall\$gate'})
$jar = Join-Path $project 'build\inspection\c2me\c2me-fabric-rewrites-chunk-system-mc1.21.1-0.4.0-alpha.0.27.jar'
$config = & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -classpath $jar -p -c -constants `
    com.ishland.c2me.rewrites.chunksystem.ModuleEntryPoint com.ishland.c2me.rewrites.chunksystem.common.Config 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect pinned rewrite configuration' }
$config | Set-Content -LiteralPath (Join-Path $output 'pinned-rewrite-configuration.txt') -Encoding utf8
$baseJar = Join-Path $project 'build\inspection\c2me\c2me-fabric-base-mc1.21.1-0.4.0-alpha.0.27-all.jar'
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -classpath "$jar;$baseJar" -p -c `
    com.ishland.c2me.rewrites.chunksystem.MixinPlugin com.ishland.c2me.base.common.ModuleMixinPlugin |
    Set-Content -LiteralPath (Join-Path $output 'pinned-module-plugins.txt') -Encoding utf8
$minecraft = Get-ChildItem -LiteralPath (Join-Path $project '.gradle\loom-cache\minecraftMaven\net\minecraft') -Recurse -Filter '*common*.jar' | Select-Object -First 1
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -classpath $minecraft.FullName -p -c `
    net.minecraft.server.level.ServerChunkCache 'net.minecraft.server.level.ServerChunkCache$MainThreadExecutor' |
    Set-Content -LiteralPath (Join-Path $output 'minecraft-future-and-fatal-channel.txt') -Encoding utf8
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -classpath $jar -p -c com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem |
    Set-Content -LiteralPath (Join-Path $output 'c2me-error-transaction.txt') -Encoding utf8
$loaderJar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\net.fabricmc\fabric-loader\0.19.3') -Recurse -Filter '*.jar' | Select-Object -First 1
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -classpath $loaderJar.FullName -p -c net.fabricmc.loader.impl.discovery.ModDiscoverer |
    Set-Content -LiteralPath (Join-Path $output 'loader-disable-modules.txt') -Encoding utf8
$entryCase = $results | Where-Object entries | Select-Object -First 1
$stagedInputs = Get-Content -LiteralPath (Join-Path $project "docs\evidence\s0-02\runtime\$($entryCase.id)-inputs.json") -Raw | ConvertFrom-Json
$chunky = $stagedInputs.mods | Where-Object id -eq 'chunky'
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -classpath $chunky.source -p -c org.popcraft.chunky.GenerationTask org.popcraft.chunky.platform.FabricWorld |
    Set-Content -LiteralPath (Join-Path $output 'chunky-actual-completion-contract.txt') -Encoding utf8
$sampled = [Collections.Generic.List[object]]::new()
foreach ($case in $allResults) {
    $caseDirectory = Join-Path $project "docs\evidence\s0-02\matrix\$($case.id)"
    $events = @(Get-Content -LiteralPath (Join-Path $caseDirectory 'events.json') -Raw | ConvertFrom-Json)
    $badContinuations = @($events | Where-Object { $_.event -eq 'STAGE_ENTER' -and $_.threadName -notmatch '^(Worker-Main-|c2me-worker-)' })
    if ($badContinuations.Count -gt 0) { throw "Generation resumed on an unexpected executor: $($case.id)" }
    foreach ($dump in Get-ChildItem -LiteralPath $caseDirectory -Filter 'threads-*.txt') {
        $text = Get-Content -LiteralPath $dump.FullName -Raw
        $workerStacks = @($text -split '(?m)(?=^"[^"\r\n]+" #)' | Where-Object { $_ -match '^"(?:c2me-worker-|Worker-Main-)' })
        $bad = @($workerStacks | Where-Object { $_ -match 'local\.plaincraft\.greatwall' -and
            $_ -match 'CompletableFuture\.(?:join|get|waitingGet)\(|ServerChunkCache\.getChunk\(' })
        if ($bad.Count -gt 0) { throw "Sampled mod worldgen stack contains a blocking wait: $($dump.FullName)" }
        $sampled.Add([ordered]@{runId=$case.id; file=$dump.Name; workerStacks=$workerStacks.Count; violations=$bad.Count})
    }
}
if ($sampled.Count -eq 0 -or ($sampled | ForEach-Object { $_['workerStacks'] } | Measure-Object -Sum).Sum -eq 0) {
    throw 'No actual worker-thread samples were captured'
}
$sampled.ToArray() | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'sampled-worker-stacks.json') -Encoding utf8
$findings = @(Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' |
    Select-String -Pattern '\.join\s*\(|\.getChunk\s*\(|possibleBiomes\s*\(|Thread\.sleep\s*\(|ForkJoinPool\.commonPool')
if ($findings.Count -gt 0) { throw ('Forbidden production code: ' + ($findings -join "`n")) }
[xml]$verification = Get-Content -LiteralPath (Join-Path $project 'gradle\verification-metadata.xml') -Raw
$proxies = @(Get-Content -LiteralPath (Join-Path $project 'gradle.properties') | Select-String 'proxyHost|proxyPort')
if ($proxies.Count -ne 0) { throw 'Temporary proxy configuration remains' }
$properties = Get-Content -LiteralPath (Join-Path $project 'gradle.properties') -Raw | ConvertFrom-StringData
$locked = @{minecraft_version='1.21.1'; loader_version='0.19.3'; fabric_version='0.116.14+1.21.1'; junit_version='5.11.0'; junit_platform_version='1.11.0'}
foreach ($key in $locked.Keys) {
    if ($properties[$key] -ne $locked[$key]) { throw "Locked version changed: $key" }
}
if ((Get-Content -LiteralPath (Join-Path $project 'build.gradle') -Raw) -notmatch "id 'fabric-loom' version '1.16.3'") {
    throw 'Loom lock changed'
}
$exports.ToArray() | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'exports.json') -Encoding utf8
[ordered]@{sourceRedlineFindings=$findings.Count; projectProxySettings=$proxies.Count;
    finalPassingCases=$allResults.Count;
    verificationComponents=@($verification.'verification-metadata'.components.component).Count;
    verificationSha256=(Get-FileHash -LiteralPath (Join-Path $project 'gradle\verification-metadata.xml') -Algorithm SHA256).Hash.ToLowerInvariant();
    checkedLockedVersions=$locked; loom='1.16.3';
    rewriteOff='Pinned enabled=true has no config toggle. Tested unchanged C2ME JAR with Fabric Loader disableModIds for rewrite and its dependent notickvd module; separately tested C2ME absent.'} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'checks.json') -Encoding utf8
Write-Output "Exported $($exports.Count) actual transformed classes; production redline scan clear."
