# Verifies a runGameTest results.xml against the 129-case expectation.
# Written because a pass message reports a total, and a total cannot show that one class
# was skipped while another gained cases. Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\Test-GameTestCompleteness.ps1 -ResultsXml <path>
param(
    [Parameter(Mandatory = $true)][string]$ResultsXml,
    [string]$SourceRoot
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ResultsXml)) { throw "results xml not found: $ResultsXml" }
if (-not $SourceRoot) { $SourceRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'src\gametest' }

# Expected names, from the source of truth rather than a stored list.
$expected = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $SourceRoot -Recurse -Filter *.java | ForEach-Object {
    $cls = $_.BaseName.ToLowerInvariant()
    $text = [IO.File]::ReadAllText($_.FullName, [Text.Encoding]::UTF8)
    foreach ($m in [regex]::Matches($text, '@GameTest[^)]*\)\s*(?:public\s+)?void\s+(\w+)')) {
        $expected.Add("$cls.$($m.Groups[1].Value.ToLowerInvariant())")
    }
}

$xml = [IO.File]::ReadAllText($ResultsXml, [Text.Encoding]::UTF8)
$actual = New-Object System.Collections.Generic.HashSet[string]
foreach ($m in [regex]::Matches($xml, '<testcase[^>]*name="([^"]+)"')) {
    $n = $m.Groups[1].Value.ToLowerInvariant()
    if ($n -match '\.') { $null = $actual.Add($n) }
}

$missing = @($expected | Where-Object { -not $actual.Contains($_) })
$unexpected = @($actual | Where-Object { $expected -notcontains $_ })
$failures = ([regex]::Matches($xml, '<failure')).Count
$errors = ([regex]::Matches($xml, '<error')).Count

Write-Output "expected=$($expected.Count) reported=$($actual.Count) missing=$($missing.Count) unexpected=$($unexpected.Count) failures=$failures errors=$errors"
$missing | Sort-Object | ForEach-Object { Write-Output "MISSING $_" }
$unexpected | Sort-Object | ForEach-Object { Write-Output "UNEXPECTED $_" }

$ok = $missing.Count -eq 0 -and $expected.Count -eq $actual.Count -and $failures -eq 0 -and $errors -eq 0
Write-Output "completeness=$ok"
if (-not $ok) { exit 1 }