param(
    [Parameter(Mandatory=$true)][string]$BuildLog,
    [Parameter(Mandatory=$true)][int]$BuildExitCode
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $root 'docs/evidence/s1b/cross-cell/final'
$log = (Resolve-Path -LiteralPath $BuildLog).Path
$text = Get-Content -LiteralPath $log -Raw
if ($BuildExitCode -ne 0 -or $text -notmatch 'BUILD SUCCESSFUL' -or $text -match 'BUILD FAILED') {
    throw 'The complete offline build must succeed before exporting final evidence'
}
$dirty = @(& git -C $root diff HEAD --name-only -- src build.gradle gradle.properties gradle)
if ($LASTEXITCODE -ne 0 -or $dirty.Count) { throw 'Commit the tested source and build inputs first' }
$lockFiles = @('gradle.properties','build.gradle','gradle.lockfile','gradle/verification-metadata.xml','gradle/wrapper/gradle-wrapper.properties')
$lockDiff = & git -C $root diff '5ad1a37' -- @lockFiles
if ($LASTEXITCODE -ne 0 -or $lockDiff) { throw 'Frozen dependency inputs changed' }
if (Get-Content -LiteralPath (Join-Path $root 'gradle.properties') | Select-String -Pattern 'proxyHost|proxyPort') {
    throw 'Project proxy configuration remains'
}
function Hash-Entry([string]$Path) {
    $item = Get-Item -LiteralPath $Path
    [pscustomobject]@{path=$item.FullName; bytes=$item.Length; sha256=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
New-Item -ItemType Directory -Path (Join-Path $out 'junit') -Force | Out-Null
$suites = @(Get-ChildItem -LiteralPath (Join-Path $root 'build/test-results/test') -Filter 'TEST-*.xml' | ForEach-Object {
    [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $out "junit/$($_.Name)") -Force
    [pscustomobject]@{name=[string]$xml.testsuite.name; tests=[int]$xml.testsuite.tests; failures=[int]$xml.testsuite.failures;
        errors=[int]$xml.testsuite.errors; skipped=[int]$xml.testsuite.skipped}
})
$junit = [ordered]@{tests=($suites | Measure-Object tests -Sum).Sum; failures=($suites | Measure-Object failures -Sum).Sum;
    errors=($suites | Measure-Object errors -Sum).Sum; skipped=($suites | Measure-Object skipped -Sum).Sum; suites=$suites.Count}
if ($junit.tests -lt 195 -or $junit.failures -or $junit.errors -or $junit.skipped) { throw 'Full JUnit regression did not pass' }
$gamePath = Join-Path $root 'build/reports/gametest/results.xml'
[xml]$game = Get-Content -LiteralPath $gamePath -Raw
$gameCases = @($game.SelectNodes('//testcase')).Count
$gameFailures = @($game.SelectNodes('//failure|//error|//skipped')).Count
if ($gameCases -lt 22 -or $gameFailures -or $text -notmatch "All $gameCases required tests passed") { throw 'Full GameTest regression did not pass' }
Copy-Item -LiteralPath $gamePath -Destination (Join-Path $out 'gametest-results.xml') -Force
Copy-Item -LiteralPath $log -Destination (Join-Path $out 'strict-offline-build.txt') -Force
$matrixDirectory = Join-Path $root 'docs/evidence/s1b/matrix/cross-cell'
$matrix = @(Get-Content -LiteralPath (Join-Path $matrixDirectory 'results.json') -Raw | ConvertFrom-Json)
if ($matrix.Count -ne 8 -or @($matrix | Where-Object { -not $_.passed -or -not $_.hashesEqual -or $_.rounds -ne 100 -or $_.owners -ne 400 }).Count) {
    throw 'Expected eight successful 100-round S0 regression matrix entries'
}
$matrixFiles = @(Get-ChildItem -LiteralPath $matrixDirectory -Recurse -File | Sort-Object FullName | ForEach-Object { Hash-Entry $_.FullName })
$runtimeFiles = @($matrix | Where-Object profile -ne 'vanilla' | ForEach-Object {
    Hash-Entry (Join-Path $root "docs/evidence/s1b/runtime/$($_.id)-inputs.json")
})
@($matrixFiles + $runtimeFiles) | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'matrix-files.json') -Encoding utf8
$properties = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $root 'gradle.properties') -Raw)
$head = (& git -C $root rev-parse HEAD).Trim()
$checks = [ordered]@{
    generatedAt=(Get-Date -Format o); stage='S1B'; stageStatus='BLOCKED'; bS1B03='PASS'; testedSourceCommit=$head
    command='.\gradlew.bat --offline --dependency-verification=strict build runGameTest --continue --rerun-tasks --no-build-cache --console=plain --no-daemon'
    javaHome='C:\Program Files\Java\jdk-21'; offline=$true; dependencyVerification='strict'; buildExitCode=$BuildExitCode
    junit=$junit; gametest=@{tests=$gameCases; failures=$gameFailures; nativeEntranceCombinations=56}
    matrix=@{sourceCommit='dfa984e82e5c6343d428edb9f3d7bedf33c950a5'; runs=8; owners=3200; passed=$true;
        scope='S0 experimental barrier regression; NOT production wall C2ME certification'}
    productionHooksEnabled=$false; s1cStarted=$false; projectProxyLines=0; lockedInputsUnchanged=$true
    artifact=(Hash-Entry (Join-Path $root "build/libs/$($properties.archives_base_name)-$($properties.mod_version).jar"))
    lockHashes=@($lockFiles | ForEach-Object { Hash-Entry (Join-Path $root $_) })
    notRun=@('Complete pre-allocation resource reservation and peak pinned-memory proof',
        'Complete site/water/cap collision and slope/decoration survival matrix',
        'S1C real CPU/Tectonic/C2ME production-wall lifecycle',
        'S4 visual atlas and S5 24-hour/Chunky stress', 'Tan Huge Trees whole-pack authorization')
}
$checks | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $out 'checks.json') -Encoding utf8
$inventory = @(& git -C $root -c core.quotepath=false diff --name-only '0be2433')
$untracked = @(& git -C $root -c core.quotepath=false ls-files --others --exclude-standard)
$files = @(($inventory + $untracked) | Sort-Object -Unique | Where-Object { $_ -and $_ -notlike 'docs/evidence/*' } | ForEach-Object {
    $entry = Hash-Entry (Join-Path $root $_)
    $blob = (& git -C $root hash-object --path $_ -- $entry.path).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Cannot hash repository content: $_" }
    $entry | Add-Member -NotePropertyName gitBlob -NotePropertyValue $blob
    $entry
})
$manifest = [ordered]@{baseline='0be2433'; testedSourceCommit=$head;
    commits=@(& git -C $root log --reverse --format='%H %s' '0be2433..HEAD'); files=$files;
    evidence=@(Get-ChildItem -LiteralPath $out -Recurse -File | Where-Object Name -ne 'files.json' | Sort-Object FullName | ForEach-Object {Hash-Entry $_.FullName})}
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $out 'files.json') -Encoding utf8
$checks | ConvertTo-Json -Depth 4
