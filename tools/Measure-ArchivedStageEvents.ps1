param(
    [Parameter(Mandatory)][string]$Evidence
)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $workspace 'docs/evidence/s1c'))
$directory=[IO.Path]::GetFullPath((Join-Path $workspace $Evidence))
if (!$directory.StartsWith($allowed+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Read-only analysis requires an archived S1C evidence directory'
}
for ($item=Get-Item -LiteralPath $directory; $null -ne $item; $item=$item.Parent) {
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Evidence ancestry must not contain links or reparse points'
    }
}
$sessionFile=Join-Path $directory 'session-STOPPING.json'
$archiveFile=Join-Path $directory 'events-STOPPING.zip'
foreach ($file in @($sessionFile,$archiveFile)) {
    $item=Get-Item -LiteralPath $file
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Analysis inputs must be ordinary archived files'
    }
}
$session=Get-Content -LiteralPath $sessionFile -Raw -Encoding utf8 | ConvertFrom-Json
$archive=[IO.Compression.ZipFile]::OpenRead($archiveFile)
try {
    if ($archive.Entries.Count -ne 1 -or $archive.Entries[0].FullName -ne 'events-STOPPING.json' -or
            $archive.Entries[0].Length -gt 64MB) {
        throw 'Expected one bounded events-STOPPING.json entry'
    }
    $reader=[IO.StreamReader]::new($archive.Entries[0].Open())
    try { $events=@($reader.ReadToEnd() | ConvertFrom-Json) }
    finally { $reader.Dispose() }
} finally { $archive.Dispose() }
if ($events.Count -eq 0 -or $events.Count -gt 50000) { throw 'Unexpected archived event count' }
$featureStages=@('vanilla:features','c2me:features')
$features=@($events | Where-Object { $_.event -eq 'STAGE_ENTER' -and $_.stage -in $featureStages })
$groups=@($features | Group-Object -Property dimension,chunkX,chunkZ)
$sorted=@($groups | Sort-Object @{Expression='Count';Descending=$true},Name)
$times=@($events | ForEach-Object { [long]$_.nanoTime } | Measure-Object -Minimum -Maximum)
[ordered]@{
    schema=1
    status='READ_ONLY_DIAGNOSTIC_NOT_ACCEPTANCE'
    source=[ordered]@{
        sessionSha256=(Get-FileHash -LiteralPath $sessionFile -Algorithm SHA256).Hash.ToLowerInvariant()
        archiveSha256=(Get-FileHash -LiteralPath $archiveFile -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    fullSessionCounts=[ordered]@{
        prepareFeatures=$session.barrier.PRODUCTION_PREPARE_FEATURES
        prepareReferences=$session.barrier.PRODUCTION_PREPARE_REFERENCES
        sessionTraceTruncated=$session.counts.TRACE_TRUNCATED
        barrierTraceTruncated=$session.barrier.TRACE_TRUNCATED
    }
    retainedSample=[ordered]@{
        events=$events.Count
        windowSeconds=([double]($times[0].Maximum-$times[0].Minimum))/1e9
        countedFeaturesStages=$featureStages
        observedEntryStages=@($events | Where-Object { $_.event -eq 'STAGE_ENTER' } |
                Group-Object stage | Sort-Object Name | ForEach-Object {
                    [ordered]@{stage=$_.Name;entries=$_.Count}
                })
        featuresGateEntries=$features.Count
        distinctFeaturesCoordinates=$groups.Count
        repeatedCoordinateEntries=$features.Count-$groups.Count
        repeatedCoordinatePercent=if ($features.Count) {100.0*($features.Count-$groups.Count)/$features.Count} else {0.0}
        topCoordinates=@($sorted | Select-Object -First 10 | ForEach-Object {
            $first=$_.Group[0]
            [ordered]@{dimension=$first.dimension;chunkX=$first.chunkX;chunkZ=$first.chunkZ;entries=$_.Count}
        })
    }
    limits=@(
        'The retained sample is truncated; it does not describe the whole session.'
        'Coordinates do not identify cells or capture owners.'
        'Repeated entries are not a measurement of complete capture cost.'
        'No world, permanent T0, or manifest is read or modified.'
    )
} | ConvertTo-Json -Depth 8
