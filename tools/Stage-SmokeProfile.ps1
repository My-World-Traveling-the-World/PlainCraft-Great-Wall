param(
    [Parameter(Mandatory)][string]$SourceInstance,
    [Parameter(Mandatory)][ValidateSet('vanilla', 'tectonic', 'tectonic-c2me', 'pack-opencl')][string]$Profile,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]{1,80}$')][string]$RunId,
    [ValidateSet('s0-01', 's0-02', 's0-03', 's1b', 's1c')][string]$EvidenceStage = 's0-01',
    [switch]$IncludeChunky
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$project = Split-Path $PSScriptRoot -Parent
$source = (Resolve-Path -LiteralPath $SourceInstance).Path
$root = [IO.Path]::GetFullPath((Join-Path $project "build\run\serverSmoke\$Profile\$RunId"))
$allowed = [IO.Path]::GetFullPath((Join-Path $project 'build\run\serverSmoke')) + '\'
if (!$root.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $root)) {
    throw 'Smoke staging requires a new directory inside this project build/run/serverSmoke.'
}
$selected = [Collections.Generic.List[object]]::new()
$wanted = if ($Profile -eq 'vanilla') { @() } else { @('tectonic', 'lithostitched') }
if ($Profile -eq 'tectonic-c2me') { $wanted += 'c2me' }
if ($IncludeChunky) { $wanted += 'chunky' }
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $source 'mods') -Filter '*.jar' | Sort-Object Name) {
    $zip = [IO.Compression.ZipFile]::OpenRead($file.FullName)
    try {
        $entry = $zip.GetEntry('fabric.mod.json')
        $meta = $null
        if ($entry) {
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $meta = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable } finally { $reader.Dispose() }
        }
        if ($Profile -ne 'pack-opencl' -and (!$meta -or $meta.id -notin $wanted)) { continue }
        # Loom supplies this exact Fabric API version on its runtime classpath.
        if ($meta -and $meta.id -eq 'fabric-api') { continue }
        $selected.Add([pscustomobject]@{
            source = $file.FullName
            name = $file.Name
            id = $(if ($meta) { $meta.id } else { $null })
            version = $(if ($meta) { $meta.version } else { $null })
            environment = $(if ($meta) { $meta['environment'] } else { $null })
            java = $(if ($meta -and $meta['depends']) { $meta['depends']['java'] } else { $null })
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        })
    } finally { $zip.Dispose() }
}
foreach ($id in $wanted) {
    if ($id -notin $selected.id) { throw "Missing required local mod: $id" }
}
New-Item -ItemType Directory -Path (Join-Path $root 'mods') -Force | Out-Null
foreach ($item in $selected) {
    $destination = Join-Path $root "mods\$($item.name)"
    Copy-Item -LiteralPath $item.source -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256) {
        throw "Staged mod hash mismatch: $($item.id)"
    }
}
$configs = [Collections.Generic.List[object]]::new()
if ($Profile -eq 'pack-opencl') {
    # No saves, user accounts, options, or real instance caches are copied.
    foreach ($name in @('config', 'defaultconfigs', 'datapacks', 'kubejs', 'moonlight-global-datapacks')) {
        $from = Join-Path $source $name
        if (!(Test-Path -LiteralPath $from)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $from -Recurse -File) {
            if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked input: $($file.FullName)" }
            $relative = [IO.Path]::GetRelativePath($source, $file.FullName)
            $destination = Join-Path $root $relative
            New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination
            $configs.Add([pscustomobject]@{path=$relative; sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()})
        }
    }
} else {
    # Tectonic-default is deliberately separate from the full pack's modified terrain.
    # For CPU C2ME use the real config verbatim; the OpenCL module is absent.
    if ($Profile -eq 'tectonic-c2me') {
        New-Item -ItemType Directory -Path (Join-Path $root 'config') -Force | Out-Null
        $from = Join-Path $source 'config\c2me.toml'
        Copy-Item -LiteralPath $from -Destination (Join-Path $root 'config\c2me.toml')
        $configs.Add([pscustomobject]@{path='config\c2me.toml'; sha256=(Get-FileHash -LiteralPath $from -Algorithm SHA256).Hash.ToLowerInvariant()})
    }
}
$evidence = Join-Path $project "docs\evidence\$EvidenceStage\runtime"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$report = [ordered]@{
    collectedAt=(Get-Date).ToString('o'); source=$source; destination=$root
    profile=$Profile; runId=$RunId; mods=$selected.ToArray(); configs=$configs.ToArray()
    fabricApiProvidedByLoom='0.116.14+1.21.1'; networkUsed=$false; sourceModified=$false
}
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $evidence "$RunId-inputs.json") -Encoding utf8
[pscustomobject]@{ Profile=$Profile; RunId=$RunId; Mods=$selected.Count; ConfigFiles=$configs.Count; Destination=$root } | Format-List
