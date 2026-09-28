param(
    [Parameter(Mandatory)][string]$SourceAssets,
    [string]$TargetAssets = "$env:USERPROFILE\.gradle\caches\fabric-loom\assets"
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = Split-Path $PSScriptRoot -Parent
$source = (Resolve-Path -LiteralPath $SourceAssets).Path
$target = [IO.Path]::GetFullPath($TargetAssets)
$allowed = [IO.Path]::GetFullPath("$env:USERPROFILE\.gradle\caches\fabric-loom\assets")
if ($target -ne $allowed -or $source -eq $target) {
    throw 'Only the isolated Loom asset cache is an authorized destination.'
}
$index = Join-Path $source 'indexes\17.json'
$expectedIndexHash = 'b39bbb1f62212e6725227460974d158b2f7e685c'
if ((Get-FileHash -LiteralPath $index -Algorithm SHA1).Hash.ToLowerInvariant() -ne $expectedIndexHash) {
    throw 'Local asset index does not match the locked Minecraft 1.21.1 index.'
}
$manifest = Get-Content -LiteralPath $index -Raw | ConvertFrom-Json
$objects = @($manifest.objects.PSObject.Properties | ForEach-Object {
    [pscustomobject]@{ Name = $_.Name; Hash = $_.Value.hash; Size = [long]$_.Value.size }
})
# Verify the complete source before changing the isolated destination.
foreach ($object in $objects) {
    if ($object.Hash -notmatch '^[a-f0-9]{40}$') { throw 'Invalid asset hash.' }
    $path = Join-Path $source "objects\$($object.Hash.Substring(0, 2))\$($object.Hash)"
    if ((Get-Item -LiteralPath $path).Length -ne $object.Size -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA1).Hash.ToLowerInvariant() -ne $object.Hash) {
        throw "Local asset integrity failure: $($object.Name)"
    }
}
$copied = 0
$reused = 0
foreach ($object in ($objects | Sort-Object Hash -Unique)) {
    $relative = "objects\$($object.Hash.Substring(0, 2))\$($object.Hash)"
    $destination = Join-Path $target $relative
    if ((Test-Path -LiteralPath $destination) -and
        (Get-FileHash -LiteralPath $destination -Algorithm SHA1).Hash.ToLowerInvariant() -eq $object.Hash) {
        $reused++
        continue
    }
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $source $relative) -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA1).Hash.ToLowerInvariant() -ne $object.Hash) {
        throw "Destination integrity failure: $($object.Name)"
    }
    $copied++
}
New-Item -ItemType Directory -Path (Join-Path $target 'indexes') -Force | Out-Null
$targetIndex = Join-Path $target 'indexes\1.21.1-17.json'
Copy-Item -LiteralPath $index -Destination $targetIndex
$evidence = Join-Path $project 'docs\evidence\s0-01\runtime'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$report = [ordered]@{
    collectedAt = (Get-Date).ToString('o')
    source = $source
    destination = $target
    indexSha1 = (Get-FileHash -LiteralPath $targetIndex -Algorithm SHA1).Hash.ToLowerInvariant()
    indexedEntriesVerified = $objects.Count
    uniqueObjectsCopied = $copied
    uniqueObjectsReused = $reused
    bytesIndexed = ($objects | Measure-Object Size -Sum).Sum
    networkUsed = $false
    sourceModified = $false
}
$report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'local-assets.json') -Encoding utf8
$report | ConvertTo-Json
