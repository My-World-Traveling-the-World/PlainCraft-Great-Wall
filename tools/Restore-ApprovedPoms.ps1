param(
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [string]$CacheBase = 'C:\Users\18364\.gradle\caches\modules-2\files-2.1'
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
$allowed = [IO.Path]::GetFullPath((Join-Path $workspace 'docs\evidence\build')) + [IO.Path]::DirectorySeparatorChar
if (-not $evidence.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence must stay under this workspace docs/evidence/build'
}
if (Test-Path -LiteralPath $evidence) { throw 'Choose a new evidence directory; prior results are immutable' }
New-Item -ItemType Directory -Path $evidence | Out-Null
[xml]$lock = Get-Content -LiteralPath (Join-Path $workspace 'gradle\verification-metadata.xml') -Raw
$targets = @(
    @{Group='org.ow2.asm'; Name='asm'; Version='9.10.1'; Host='https://repo1.maven.org/maven2'; Sha1='507e7f8821c58fc995a057b63ad4008b96bafa4d'},
    @{Group='org.ow2.asm'; Name='asm-analysis'; Version='9.10.1'; Host='https://repo1.maven.org/maven2'; Sha1='cf2754cfa5e4df15ccf9f8779485f23862c5b488'},
    @{Group='org.ow2.asm'; Name='asm-commons'; Version='9.10.1'; Host='https://repo1.maven.org/maven2'; Sha1='a28039cb619a6c2a5c7ca5b3206381f9032c8368'},
    @{Group='org.ow2.asm'; Name='asm-tree'; Version='9.10.1'; Host='https://repo1.maven.org/maven2'; Sha1='0fe218ecae48cabf4e53295d64ae4e01e5ddfb33'},
    @{Group='org.ow2.asm'; Name='asm-util'; Version='9.10.1'; Host='https://repo1.maven.org/maven2'; Sha1='c6db0976b79a615802b820b08664602fa1f6a5ad'},
    @{Group='net.fabricmc'; Name='sponge-mixin'; Version='0.17.3+mixin.0.8.7'; Host='https://maven.fabricmc.net'; Sha1='e2f41a5b0bead7e1124969f21c0b571fb14fec69'},
    @{Group='io.github.llamalad7'; Name='mixinextras-fabric'; Version='0.5.4'; Host='https://maven.fabricmc.net'; Sha1='f872b6df401e5544b1eaf750723fa24f360ccdbd'},
    @{Group='net.sf.jopt-simple'; Name='jopt-simple'; Version='5.0.4'; Host='https://repo1.maven.org/maven2'; Sha1='c761a09cd9a4695b242c1fc35322d5b1659df7ed'}
)
$results = [Collections.Generic.List[object]]::new()
$started = Get-Date
try {
    foreach ($target in $targets) {
        $file = "$($target.Name)-$($target.Version).pom"
        $component = @($lock.'verification-metadata'.components.component | Where-Object {
            $_.group -eq $target.Group -and $_.name -eq $target.Name -and $_.version -eq $target.Version
        })
        if ($component.Count -ne 1) { throw "No unique locked component for $file" }
        $artifact = @($component[0].artifact | Where-Object name -eq $file)
        $expected = @($artifact.sha256 | ForEach-Object value)
        if ($artifact.Count -ne 1 -or $expected.Count -ne 1) { throw "No unique locked SHA-256 for $file" }
        $url = "$($target.Host)/$($target.Group.Replace('.','/'))/$($target.Name)/$($target.Version)/$file"
        $download = Join-Path $evidence $file
        Invoke-WebRequest -Uri $url -OutFile $download -TimeoutSec 60
        $sha256 = (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant()
        $sha1 = (Get-FileHash -LiteralPath $download -Algorithm SHA1).Hash.ToLowerInvariant()
        $row = [ordered]@{component="$($target.Group):$($target.Name):$($target.Version)"; artifact=$file;
            source=$url; sha256=$sha256; lockedSha256=$expected[0]; sha1=$sha1; auditedSha1=$target.Sha1;
            status='DOWNLOADED_NOT_INSTALLED'; cachePath=$null}
        $results.Add($row)
        if ($sha256 -ne $expected[0]) { throw "Locked SHA-256 mismatch: $file" }
        if ($sha1 -ne $target.Sha1) { throw "Audited SHA-1 directory mismatch: $file" }
        $directory = Join-Path $CacheBase "$($target.Group)\$($target.Name)\$($target.Version)\$sha1"
        $destination = Join-Path $directory $file
        if (Test-Path -LiteralPath $destination) {
            if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
                throw "Existing cache file differs; refusing overwrite: $destination"
            }
        } else {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
            Copy-Item -LiteralPath $download -Destination $destination
        }
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected[0]) {
            throw "Installed cache SHA-256 mismatch: $file"
        }
        $row.cachePath = $destination
        $row.status = 'VERIFIED_INSTALLED'
        Write-Output "$file SHA256=$sha256 SHA1=$sha1 VERIFIED_INSTALLED"
    }
    $status = 'SUCCESS'
} catch {
    $status = 'FAILED'
    $failure = $_.Exception.Message
    throw
} finally {
    [ordered]@{started=$started.ToString('o'); finished=(Get-Date).ToString('o'); status=$status;
        failure=$failure; items=@($results.ToArray()); scope='Eight approved POMs only; no build or index edits'} |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'recovery-result.json') -Encoding UTF8
}
