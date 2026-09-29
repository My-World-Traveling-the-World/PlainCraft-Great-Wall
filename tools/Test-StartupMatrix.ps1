param(
    [string]$SourceInstance = 'D:\Minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING',
    [ValidateSet('vanilla', 'tectonic-c2me')][string[]]$Profiles = @('vanilla', 'tectonic-c2me'),
    [int[]]$Delays = @(1, 100, 2000, 30000),
    [switch]$Faults,
    [switch]$Reload,
    [switch]$Entries,
    [switch]$DisableRewrite,
    [switch]$ReloadWorldgen
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = Split-Path $PSScriptRoot -Parent
$evidence = Join-Path $project 'docs\evidence\s0-02\matrix'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$cases = [Collections.Generic.List[object]]::new()
if ($DisableRewrite -and ($Profiles.Count -ne 1 -or $Profiles[0] -ne 'tectonic-c2me')) {
    throw 'DisableRewrite requires only the tectonic-c2me profile'
}
if ($ReloadWorldgen -and ($Profiles.Count -ne 1 -or $Profiles[0] -ne 'tectonic-c2me' -or $DisableRewrite)) {
    throw 'Post-reload C2ME fault test requires only the enabled tectonic-c2me profile'
}
foreach ($profile in $Profiles) {
    foreach ($delay in $Delays) {
        if ($delay -lt 0 -or $delay -gt 30000) { throw 'S0 delay outside certified range.' }
        $cases.Add(@{profile=$profile; delay=$delay; outcome='SUCCESS'; cancel=$false; reload='none'; entries=$false})
    }
    if ($Faults) {
        foreach ($outcome in @('FAIL', 'CANCEL')) {
            $cases.Add(@{profile=$profile; delay=100; outcome=$outcome; cancel=$false; reload='none'; entries=$false})
        }
        $cases.Add(@{profile=$profile; delay=30000; outcome='SUCCESS'; cancel=$true; reload='none'; entries=$false})
    }
    if ($Reload) { $cases.Add(@{profile=$profile; delay=1; outcome='SUCCESS'; cancel=$false; reload='changed'; entries=$false}) }
    if ($Entries) { $cases.Add(@{profile=$profile; delay=100; outcome='SUCCESS'; cancel=$false; reload='none'; entries=$true}) }
    if ($ReloadWorldgen) { $cases.Add(@{profile=$profile; delay=1; outcome='SUCCESS'; cancel=$false; reload='changed'; entries=$false; postReload=$true}) }
}
$results = [Collections.Generic.List[object]]::new()
foreach ($case in $cases) {
    $postReload = $case.ContainsKey('postReload') -and [bool]$case.postReload
    $off = if ($DisableRewrite) { '-off' } else { '' }
    $id = "s002-$($case.profile)$off-$($case.delay)-$($case.outcome.ToLowerInvariant())-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
    $root = Join-Path $project "build\run\serverSmoke\$($case.profile)\$id"
    $output = Join-Path $evidence $id
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    if ($case.profile -ne 'vanilla' -or $case.entries) {
        & (Join-Path $PSScriptRoot 'Stage-SmokeProfile.ps1') -SourceInstance $SourceInstance -Profile $case.profile -RunId $id -EvidenceStage s0-02 -IncludeChunky:$case.entries
    }
    $expectedFailure = $case.outcome -ne 'SUCCESS' -or $case.cancel -or $postReload
    $arguments = @('--offline', 'runServerSmoke', "-PsmokeProfile=$($case.profile)", "-PsmokeRunId=$id",
        '-Ps0Probe=true', "-Ps0DelayMs=$($case.delay)", "-Ps0Outcome=$($case.outcome)",
        "-Ps0Reload=$($case.reload)", "-Ps0ExpectedFailure=$($expectedFailure.ToString().ToLowerInvariant())",
        "-Ps0Entries=$($case.entries.ToString().ToLowerInvariant())",
        "-Ps0DisableRewrite=$($DisableRewrite.ToString().ToLowerInvariant())",
        "-Ps0ReloadWorldgen=$($postReload.ToString().ToLowerInvariant())",
        '--dependency-verification=strict', '--no-daemon', '--console=plain')
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $env:ComSpec
    $info.Arguments = '/d /c ""{0}" {1}"' -f (Join-Path $project 'gradlew.bat'), ($arguments -join ' ')
    $info.WorkingDirectory = $project
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.Environment['JAVA_HOME'] = 'C:\Program Files\Java\jdk-21'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $cancelSent = $false
    $timedOut = $false
    $dumped = $false
    Write-Output "RUN $id delay=$($case.delay) outcome=$($case.outcome) cancel=$($case.cancel) reload=$($case.reload)"
    while (!$process.WaitForExit(500)) {
        if (!$dumped -and !$expectedFailure -and $case.delay -eq 30000 -and $timer.Elapsed.TotalSeconds -gt 30) {
            $targets = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
                $_.CommandLine -like '*-Dgreatwall.s0.probe=true*' -and $_.CommandLine -like '*plaincraft-great-wall*'
            })
            foreach ($target in $targets) {
                $attachInfo = [Diagnostics.ProcessStartInfo]::new()
                $attachInfo.FileName = 'C:\Program Files\Java\jdk-21\bin\jcmd.exe'
                $attachInfo.Arguments = "$($target.ProcessId) Thread.print"
                $attachInfo.UseShellExecute = $false
                $attachInfo.CreateNoWindow = $true
                $attachInfo.RedirectStandardOutput = $true
                $attachInfo.RedirectStandardError = $true
                $attach = [Diagnostics.Process]::new()
                $attach.StartInfo = $attachInfo
                $null = $attach.Start()
                $attachOut = $attach.StandardOutput.ReadToEndAsync()
                $attachErr = $attach.StandardError.ReadToEndAsync()
                if (!$attach.WaitForExit(5000)) {
                    # The inspector itself can hang while its target exits. Never kill the game here.
                    $attach.Kill()
                    $attach.WaitForExit()
                    "Thread inspection timed out; game was not terminated by this inspector." |
                        Set-Content -LiteralPath (Join-Path $output "attach-timeout-$($target.ProcessId).txt") -Encoding utf8
                }
                ($attachOut.GetAwaiter().GetResult() + "`n" + $attachErr.GetAwaiter().GetResult()) |
                    Set-Content -LiteralPath (Join-Path $output "threads-$($target.ProcessId).txt") -Encoding utf8
                $attach.Dispose()
            }
            $dumped = $targets.Count -gt 0
        }
        if ($case.cancel -and !$cancelSent) {
            $log = Join-Path $root 'logs\latest.log'
            if ((Test-Path -LiteralPath $log) -and (Select-String -LiteralPath $log -SimpleMatch 'WAIT_PLAN_NO_LOCK' -Quiet)) {
                'operator cancellation during unresolved startup planning' | Set-Content -LiteralPath (Join-Path $root 'greatwall-cancel-startup')
                $cancelSent = $true
            }
        }
        if ($timer.Elapsed.TotalSeconds -gt 180) {
            $timedOut = $true
            # This is the process tree created above, never a user game or shared daemon.
            $process.Kill($true)
            $process.WaitForExit()
            break
        }
    }
    $text = $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()
    $text | Set-Content -LiteralPath (Join-Path $output 'gradle.txt') -Encoding utf8
    $exitCode = $process.ExitCode
    $process.Dispose()
    [ordered]@{ id=$id; timedOut=$timedOut; exitCode=$exitCode; expectedFailure=$expectedFailure;
        cancelSent=$cancelSent; seconds=$timer.Elapsed.TotalSeconds; command=('gradlew.bat ' + ($arguments -join ' ')) } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'execution.json') -Encoding utf8
    $sessionPath = Join-Path $root 'world\greatwall-s0\session.json'
    $eventsPath = Join-Path $root 'world\greatwall-s0\events.json'
    if (Test-Path -LiteralPath $sessionPath) { Copy-Item -LiteralPath $sessionPath,$eventsPath -Destination $output }
    if ($timedOut -or !(Test-Path -LiteralPath $sessionPath)) { throw "Missing closed-session proof or timeout: $id" }
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json -AsHashtable
    $events = @(Get-Content -LiteralPath $eventsPath -Raw | ConvertFrom-Json)
    if ($session.state -ne 'CLOSED' -or $session.inFlight -ne 0 -or $session.counts['WAIT_WHILE_LOCKED']) {
        throw "Session or regional lock invariant failed: $id"
    }
    if ($expectedFailure) {
        if (Test-Path -LiteralPath (Join-Path $root 'server-smoke-passed.txt')) { throw "Fault was incorrectly accepted as normal startup: $id" }
        if (!$postReload -and $text.Contains('SERVER_SMOKE_STARTED')) { throw "Fault allowed startup completion: $id" }
        $needle = if ($case.cancel) {'STARTUP_CANCEL'} elseif ($case.outcome -eq 'FAIL') {'Injected planner failure'} else {'Injected planner cancellation'}
        if ($postReload) {
            if (!$text.Contains('RELOAD_WORLDGEN_REQUEST') -or !$session.counts['REQUIRED_PLAN_FAILED'] -or !$session.counts['RECIPE_REJECTED']) {
                throw "Post-reload native request did not fail closed: $id"
            }
        } elseif ($case.cancel) {
            if (!$cancelSent -or !$session.counts[$needle]) { throw "Startup cancellation was not observed: $id" }
        } elseif (!$text.Contains($needle)) { throw "Wrong failure reason in $id" }
    } else {
        if ($exitCode -ne 0 -or !(Test-Path -LiteralPath (Join-Path $root 'server-smoke-passed.txt'))) {
            throw "Normal smoke did not pass: $id"
        }
        Copy-Item -LiteralPath (Join-Path $root 'server-smoke-passed.txt') -Destination $output
        if ($case.profile -eq 'tectonic-c2me' -and !$DisableRewrite) {
            $locks = @($events | Where-Object event -eq 'WRITE_LOCK_ACQUIRED')
            if ($locks.Count -eq 0) { throw "No C2ME lock witness: $id" }
            foreach ($lock in $locks) {
                $ready = @($events | Where-Object { $_.event -eq 'PLAN_READY' -and $_.dimension -eq $lock.dimension -and
                    $_.chunkX -eq $lock.chunkX -and $_.chunkZ -eq $lock.chunkZ -and $_.nanoTime -le $lock.nanoTime })
                if ($ready.Count -eq 0) { throw "Lock preceded PLAN_READY for $($lock.chunkX),$($lock.chunkZ)" }
            }
        }
        if ($DisableRewrite -and (!$text.Contains('REWRITE_DISABLED_LOADER') -or $session.counts['WRITE_LOCK_ACQUIRED'])) {
            throw "Rewrite-disable capability fixture was not verified: $id"
        }
        if ($case.reload -eq 'changed' -and (!$text.Contains('RELOAD_FIXTURE_PASS') -or $session.counts['RECIPE_ACCEPTED'] -ne 2)) {
            throw "Reload invariants not proven: $id"
        }
        if ($case.entries -and !$text.Contains('RUNTIME_ENTRIES_PASS')) { throw "Runtime entry coverage missing: $id" }
    }
    $results.Add([ordered]@{id=$id; profile=$case.profile; delayMs=$case.delay; outcome=$case.outcome; cancel=$case.cancel;
        reload=$case.reload; entries=$case.entries; postReload=$postReload; disableRewrite=[bool]$DisableRewrite; exitCode=$exitCode; seconds=[Math]::Round($timer.Elapsed.TotalSeconds,3); counts=$session.counts; passed=$true})
    $results.ToArray() | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'results.json') -Encoding utf8
    Write-Output "PASS $id exit=$exitCode seconds=$([Math]::Round($timer.Elapsed.TotalSeconds,1))"
}
