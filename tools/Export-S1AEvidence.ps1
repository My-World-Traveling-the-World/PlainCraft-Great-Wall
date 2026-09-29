param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $root 'docs/evidence/s1a'
$main = Join-Path $root 'src/main/java/local/plaincraft/greatwall'
$relative = @(
    'geometry/FixedMath.java', 'geometry/DeterministicHash.java', 'geometry/CanonicalCodec.java',
    'geometry/ArcLength.java', 'geometry/Projection.java', 'planning/AnchorField.java',
    'terrain/NaturalTerrainOracle.java', 'planning/RidgePlanner.java', 'terrain/HydrologyClassifier.java',
    'persistence/SegmentCacheStore.java', 'planning/PrefixSummaryIndex.java', 'planning/SiteScheduler.java',
    'planning/HeightContractPlanner.java', 'diagnostics/BudgetCache.java', 'planning/PlannerScheduler.java',
    'model/WallTypes.java', 'model/WallFailure.java', 'model/PlanLease.java', 'decay/PaletteTypes.java'
)
$sources = @($relative | ForEach-Object { Join-Path $main $_ })
$violations = @($sources | Select-String -Pattern 'net\.minecraft|net\.fabricmc|possibleBiomes\s*\(|getChunk\s*\(|\.join\s*\(|future\(\)\.get\s*\(')
if ($violations.Count -ne 0) { throw "Pure planner red-line violation: $violations" }
$proxy = @(Get-Content -LiteralPath (Join-Path $root 'gradle.properties') | Select-String -Pattern 'proxyHost|proxyPort')
if ($proxy.Count -ne 0) { throw 'Project proxy settings remain' }
$lockedFiles = @('gradle.properties','build.gradle','gradle.lockfile','gradle/verification-metadata.xml','gradle/wrapper/gradle-wrapper.properties')
$lockDiff = & git -C $root diff '5ad1a37' -- @lockedFiles
if ($LASTEXITCODE -ne 0 -or $lockDiff) { throw 'Build version or dependency verification inputs changed' }
$junit = Join-Path $out 'junit'
New-Item -ItemType Directory -Path $junit -Force | Out-Null
$suites = @(Get-ChildItem -LiteralPath (Join-Path $root 'build/test-results/test') -Filter 'TEST-*.xml' | ForEach-Object {
    [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
    if ($xml.testsuite.'system-err'.InnerText) { throw "JUnit stderr is nonempty: $($_.Name)" }
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $junit $_.Name) -Force
    [pscustomobject]@{ name=[string]$xml.testsuite.name; tests=[int]$xml.testsuite.tests; failures=[int]$xml.testsuite.failures;
        errors=[int]$xml.testsuite.errors; skipped=[int]$xml.testsuite.skipped; seconds=[double]$xml.testsuite.time }
})
$failures = ($suites | Measure-Object -Property failures -Sum).Sum + ($suites | Measure-Object -Property errors -Sum).Sum
if ($failures -ne 0 -or ($suites | Measure-Object -Property skipped -Sum).Sum -ne 0) { throw 'JUnit did not pass without skips' }
$gamePath = Join-Path $root 'build/reports/gametest/results.xml'
[xml]$gameXml = Get-Content -LiteralPath $gamePath -Raw
Copy-Item -LiteralPath $gamePath -Destination (Join-Path $out 'gametest.xml') -Force
$gameFailures = @($gameXml.SelectNodes('//failure|//error'))
if ($gameFailures.Count -ne 0) { throw 'GameTest failures found' }
$properties = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $root 'gradle.properties') -Raw)
$jar = Get-Item -LiteralPath (Join-Path $root "build/libs/$($properties.archives_base_name)-$($properties.mod_version).jar")
$inventory = @(& git -C $root -c core.quotepath=false diff --name-only '5ad1a37')
$untracked = @(& git -C $root -c core.quotepath=false ls-files --others --exclude-standard)
$files = @(($inventory + $untracked) | Sort-Object -Unique | ForEach-Object { Join-Path $root $_ })
$sourceHashes = @($sources | ForEach-Object {
    [pscustomobject]@{path=$_; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$lockHashes = @($lockedFiles | ForEach-Object {
    $p = Join-Path $root $_
    [pscustomobject]@{path=$p; sha256=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$result = [ordered]@{
    generatedAt=(Get-Date -Format 'o'); stage='S1A'; baseline='5ad1a37'; head=(& git -C $root rev-parse HEAD)
    sourceChecks=@{pureJavaFiles=$sources.Count; forbiddenPatternMatches=$violations.Count; projectProxyLines=$proxy.Count; lockedInputsUnchanged=$true}
    junit=@{tests=($suites | Measure-Object -Property tests -Sum).Sum; failures=$failures; skipped=0; suites=$suites}
    gameTest=@{cases=@($gameXml.SelectNodes('//testcase')).Count; failures=$gameFailures.Count; scope='Existing isolated S0 GameTest regression; no S1B or real terrain integration'}
    artifact=@{path=$jar.FullName; bytes=$jar.Length; sha256=(Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    sourceHashes=$sourceHashes; lockHashes=$lockHashes; fullFileInventory=$files
    notRun=@('S1B block construction','S1C real terrain/C2ME integration','Real-terrain 40ms bound','JFR retained-heap calibration','RoadWeaver/FTB integration','24h/Chunky stress','Tan Huge Trees whole-pack authorization')
}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $out 'final-checks.json') -Encoding utf8
$result | ConvertTo-Json -Depth 4
