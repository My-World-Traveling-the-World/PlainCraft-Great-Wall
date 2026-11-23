param(
    [Parameter(Mandatory = $true)][string]$InstanceDirectory,
    [string]$GradleUserHome = (Join-Path $env:USERPROFILE '.gradle')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out = Join-Path $project 'gradle'
$mods = Join-Path $InstanceDirectory 'mods'
if (!(Test-Path -LiteralPath $mods -PathType Container)) { throw "Missing mods directory: $mods" }
[IO.Directory]::CreateDirectory($out) | Out-Null

function Read-ArchiveJson([string]$Archive, [string]$EntryName) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) { return $null }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { return ($reader.ReadToEnd() | ConvertFrom-Json) }
        finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Archive-Record([string]$Path, [string]$Id, [string]$Version, [string]$Environment) {
    [ordered]@{
        id = $Id
        version = $Version
        environment = $Environment
        path = [IO.Path]::GetFullPath($Path)
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        versionSource = 'archive_metadata'
    }
}

$records = [Collections.Generic.List[object]]::new()
$wanted = @('fabric-api', 'c2me', 'c2me-opts-accel-opencl', 'tectonic', 'lithostitched',
    'ftbquests', 'ftbteams', 'ftblibrary', 'bettersmoke', 'roadweaver',
    'plaincraft_height_6000_patch', 'plaincraft_erdtree_sanctuary')
$found = [Collections.Generic.HashSet[string]]::new()
foreach ($file in (Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' | Sort-Object Name)) {
    $meta = Read-ArchiveJson $file.FullName 'fabric.mod.json'
    if ($null -eq $meta -or $meta.id -notin $wanted) { continue }
    $environment = if ($meta.PSObject.Properties.Name -contains 'environment') { $meta.environment } else { '*' }
    if (!$found.Add($meta.id)) { throw "Duplicate active mod id: $($meta.id)" }
    $records.Add((Archive-Record $file.FullName $meta.id $meta.version $environment))
}

$instanceName = Split-Path $InstanceDirectory -Leaf
$versionPath = Join-Path $InstanceDirectory "$instanceName.json"
$versionJson = Get-Content -LiteralPath $versionPath -Raw | ConvertFrom-Json
$loader = @($versionJson.libraries | Where-Object { $_.name -like 'net.fabricmc:fabric-loader:*' })
if ($loader.Count -ne 1) { throw 'Expected one loader coordinate in instance metadata' }
$loaderVersion = $loader[0].name.Split(':')[2]
$gameRoot = Split-Path (Split-Path $InstanceDirectory -Parent) -Parent
$loaderJar = Join-Path $gameRoot "libraries\net\fabricmc\fabric-loader\$loaderVersion\fabric-loader-$loaderVersion.jar"
if (!(Test-Path -LiteralPath $loaderJar -PathType Leaf)) { throw "Missing actual loader jar: $loaderJar" }
$loaderMeta = Read-ArchiveJson $loaderJar 'fabric.mod.json'
if ($loaderMeta.version -ne $loaderVersion) { throw 'Loader coordinate and archive metadata disagree' }
$records.Add((Archive-Record $loaderJar 'fabricloader' $loaderMeta.version '*'))

$minecraftJar = Join-Path $InstanceDirectory "$instanceName.jar"
$minecraftMeta = Read-ArchiveJson $minecraftJar 'version.json'
if ($null -eq $minecraftMeta) { throw 'Actual Minecraft jar has no version.json' }
$records.Add((Archive-Record $minecraftJar 'minecraft' $minecraftMeta.id '*'))

$wrappers = @('gradlew', 'gradlew.bat', 'gradle\wrapper\gradle-wrapper.jar',
    'gradle\wrapper\gradle-wrapper.properties') | ForEach-Object {
    $path = Join-Path $project $_
    [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$loomJars = @(Get-ChildItem -LiteralPath (Join-Path $GradleUserHome 'caches\modules-2\files-2.1\net.fabricmc\fabric-loom\1.16.3') -Recurse -File -Filter 'fabric-loom-1.16.3.jar')
if ($loomJars.Count -ne 1) { throw 'Expected exactly one cached Loom 1.16.3 artifact' }
$report = [ordered]@{
    schemaVersion = 1
    collectedAt = [DateTimeOffset]::Now.ToString('o')
    sourceInstance = [IO.Path]::GetFullPath($InstanceDirectory)
    buildsAreOffline = $true
    runtimeCompatibilityVerified = $false
    minecraftPackVersions = $minecraftMeta.pack_version
    minecraftJavaVersion = $minecraftMeta.java_version
    artifacts = @($records | Sort-Object { $_.id })
    missingRequestedModIds = @($wanted | Where-Object { !$found.Contains($_) })
    wrapperFiles = @($wrappers)
    loomArtifact = Archive-Record $loomJars[0].FullName 'fabric-loom' '1.16.3' 'build'
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out 'local-inputs.json') -Encoding utf8
$records | ForEach-Object { "{0} {1} {2}" -f $_.id, $_.version, $_.sha256 }
if ($report.missingRequestedModIds.Count -gt 0) {
    Write-Warning "Missing requested ids: $($report.missingRequestedModIds -join ', ')"
}

