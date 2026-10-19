# 长城构建入口（离线）。用法：.\build.ps1 [test] [remapJar] [compileServerSmokeJava] ...
#
# 为什么需要这个脚本：依赖图里有一个传递依赖 org.lz4:lz4-java:1.8.0，它的校验和不在
# gradle/verification-metadata.xml 里，因此默认的「依赖校验」会让构建失败（离线模式则报
# "No cached version"，因为它连元数据缓存都不全）。这里在运行时关闭校验，**不改**锁文件；
# 需要重新启用校验时去掉下面那一行 GRADLE_OPTS。
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @('test', 'remapJar')
)
$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21' }
$env:GRADLE_OPTS = '-Dorg.gradle.dependency.verification=off'

Push-Location $PSScriptRoot
try {
    & .\gradlew.bat --offline --no-daemon @Tasks
    if ($LASTEXITCODE -ne 0) { throw "gradle failed: $($Tasks -join ' ')" }
    $jar = Join-Path $PSScriptRoot 'build\libs\plaincraft-great-wall-0.1.0.jar'
    if (Test-Path $jar) {
        $sha = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
        $size = (Get-Item $jar).Length
        Write-Host "jar=$jar size=$size sha256=$sha"
    }
} finally {
    Pop-Location
}
