param(
    [Parameter(Mandatory)][string]$OutputJsonPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$orchestrator = Join-Path $PSScriptRoot 'Run-FixVerification.ps1'

# Ensure ports 5049, 5580, 5581 are currently free before starting self-contained S5 tests
$existing = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(5049, 5580, 5581) })
if ($existing.Count -gt 0) {
    throw "Cannot run S5 self-test while ports 5049/5580/5581 are already in use: $($existing.LocalPort -join ', ')"
}

$results = [Collections.Generic.List[object]]::new()
$tempRunA = 's5-selftest-run-a'
$tempRunB = 's5-selftest-run-b'
$runRootA = Join-Path $projectRoot "build/verification/glass-native-preview/$tempRunA"
$runRootB = Join-Path $projectRoot "build/verification/glass-native-preview/$tempRunB"
$tempVerDir = Join-Path $projectRoot "build/verification/glass-native-preview/s5-temp-ver"
$cmdLog = Join-Path $projectRoot "build/verification/glass-native-preview/s5-cmd-audit.jsonl"

New-Item -ItemType Directory -Force -Path $runRootA | Out-Null
New-Item -ItemType Directory -Force -Path $tempVerDir | Out-Null
if (Test-Path -LiteralPath $cmdLog) { Remove-Item -LiteralPath $cmdLog -Force }

$listener = $null
try {
    # Start a self-owned isolated TCP listener on 127.0.0.1:5049 to simulate another instance/service
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 5049)
    $listener.Start()

    # 1. Unowned port occupied -> Start must fail with non-zero exit code, 0 commands issued, listener still alive
    $statePathA = Join-Path $runRootA 'instance.json'
    [ordered]@{
        RunId                = $tempRunA
        RunRoot              = $runRootA
        AvdName              = "mirad_$tempRunA"
        Serial               = 'emulator-5580'
        ServerPort           = 5049
        ConsolePort          = 5580
        EmulatorPid          = 0
        AdbServerPid         = 0
        AdbServerCommandLine = ''
        CreatedAt            = [DateTimeOffset]::Now.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $statePathA -Encoding utf8

    $out1 = & pwsh -NoProfile -ExecutionPolicy Bypass -File $driver -Action Start -RunId $tempRunA -CommandLogFile $cmdLog 2>&1 | Out-String
    $exit1 = $LASTEXITCODE
    $cmds1 = if (Test-Path -LiteralPath $cmdLog) { @(Get-Content -LiteralPath $cmdLog).Count } else { 0 }
    $listenerAlive1 = $listener.Server.IsBound -and @(Get-NetTCPConnection -State Listen -LocalPort 5049 -ErrorAction SilentlyContinue).Count -gt 0
    if ($exit1 -eq 0 -or $cmds1 -ne 0 -or -not $listenerAlive1 -or $out1 -notmatch 'Ownership check failed on Start') {
        throw "S5 Case 1 failed: exit=$exit1 cmds=$cmds1 alive=$listenerAlive1 out=$out1"
    }
    $results.Add([pscustomobject]@{
        Scenario           = 'OtherInstancePortOccupied_StartRejected'
        ExitCode           = $exit1
        CommandsIssued     = $cmds1
        ListenerStillAlive = $listenerAlive1
        Message            = ($out1.Trim() -split "`r?`n")[0]
    })

    # 2. Unowned port occupied -> Stop must fail with non-zero exit code, 0 commands issued, listener still alive
    [ordered]@{
        RunId                = $tempRunA
        RunRoot              = $runRootA
        AvdName              = "mirad_$tempRunA"
        Serial               = 'emulator-5580'
        ServerPort           = 5049
        ConsolePort          = 5580
        EmulatorPid          = 999991
        AdbServerPid         = 999992
        AdbServerCommandLine = 'adb.exe -L tcp:5049 fork-server server'
        CreatedAt            = [DateTimeOffset]::Now.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $statePathA -Encoding utf8

    $out2 = & pwsh -NoProfile -ExecutionPolicy Bypass -File $driver -Action Stop -RunId $tempRunA -CommandLogFile $cmdLog 2>&1 | Out-String
    $exit2 = $LASTEXITCODE
    $cmds2 = if (Test-Path -LiteralPath $cmdLog) { @(Get-Content -LiteralPath $cmdLog).Count } else { 0 }
    $listenerAlive2 = $listener.Server.IsBound -and @(Get-NetTCPConnection -State Listen -LocalPort 5049 -ErrorAction SilentlyContinue).Count -gt 0
    if ($exit2 -eq 0 -or $cmds2 -ne 0 -or -not $listenerAlive2 -or $out2 -notmatch 'Ownership check failed') {
        throw "S5 Case 2 failed: exit=$exit2 cmds=$cmds2 alive=$listenerAlive2 out=$out2"
    }
    $results.Add([pscustomobject]@{
        Scenario           = 'OtherInstancePortOccupied_StopRejected'
        ExitCode           = $exit2
        CommandsIssued     = $cmds2
        ListenerStillAlive = $listenerAlive2
        Message            = ($out2.Trim() -split "`r?`n")[0]
    })

    # 3. Run-FixVerification.ps1 abnormal exit on occupied port -> finally block must NOT stop listener
    $env:MIRAD_GLASS_CMD_LOG = $cmdLog
    $out3 = & pwsh -NoProfile -ExecutionPolicy Bypass -File $orchestrator -RunId $tempRunB -VerificationDir $tempVerDir 2>&1 | Out-String
    $exit3 = $LASTEXITCODE
    Remove-Item Env:\MIRAD_GLASS_CMD_LOG -ErrorAction SilentlyContinue
    $cmds3 = if (Test-Path -LiteralPath $cmdLog) { @(Get-Content -LiteralPath $cmdLog).Count } else { 0 }
    $listenerAlive3 = $listener.Server.IsBound -and @(Get-NetTCPConnection -State Listen -LocalPort 5049 -ErrorAction SilentlyContinue).Count -gt 0
    if ($exit3 -eq 0 -or $cmds3 -ne 0 -or -not $listenerAlive3 -or $out3 -notmatch 'S5 GUARD: Skipping Stop/cleanup') {
        throw "S5 Case 3 failed: exit=$exit3 cmds=$cmds3 alive=$listenerAlive3 out=$out3"
    }
    $results.Add([pscustomobject]@{
        Scenario           = 'OrchestratorPrecheckFailure_FinallyGuardPreservesListener'
        ExitCode           = $exit3
        CommandsIssued     = $cmds3
        ListenerStillAlive = $listenerAlive3
        FinallyGuardFired  = ($out3 -match 'S5 GUARD: Skipping Stop/cleanup')
    })

    # Stop our isolated listener so ports are completely free for stale metadata tests
    $listener.Stop()
    $listener = $null
    Start-Sleep -Milliseconds 300

    # 4. Stale PID metadata on free ports -> both Start and Stop must fail without starting or killing anything
    $out4a = & pwsh -NoProfile -ExecutionPolicy Bypass -File $driver -Action Start -RunId $tempRunA -CommandLogFile $cmdLog 2>&1 | Out-String
    $exit4a = $LASTEXITCODE
    $out4b = & pwsh -NoProfile -ExecutionPolicy Bypass -File $driver -Action Stop -RunId $tempRunA -CommandLogFile $cmdLog 2>&1 | Out-String
    $exit4b = $LASTEXITCODE
    $cmds4 = if (Test-Path -LiteralPath $cmdLog) { @(Get-Content -LiteralPath $cmdLog).Count } else { 0 }
    if ($exit4a -eq 0 -or $exit4b -eq 0 -or $cmds4 -ne 0 -or $out4a -notmatch 'Stale PID metadata') {
        throw "S5 Case 4 failed: exitStart=$exit4a exitStop=$exit4b cmds=$cmds4 outStart=$out4a outStop=$out4b"
    }
    $results.Add([pscustomobject]@{
        Scenario           = 'StalePidMetadataOnFreePorts_StartAndStopRejected'
        StartExitCode      = $exit4a
        StopExitCode       = $exit4b
        CommandsIssued     = $cmds4
        Message            = ($out4a.Trim() -split "`r?`n")[0]
    })

    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $OutputJsonPath -Encoding utf8
    Write-Host "PASS S5 ownership negative & stale metadata checks ($($results.Count) cases verified, 0 unowned commands issued)."
} finally {
    Remove-Item Env:\MIRAD_GLASS_CMD_LOG -ErrorAction SilentlyContinue
    if ($null -ne $listener) {
        try { $listener.Stop() } catch {}
    }
    Remove-Item -LiteralPath $runRootA -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $runRootB -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempVerDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $cmdLog -Force -ErrorAction SilentlyContinue
}
