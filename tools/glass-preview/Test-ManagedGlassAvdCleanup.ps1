param([Parameter(Mandatory)][string]$FixtureParent)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'Remove-ManagedGlassAvd.ps1')

$parent = (Resolve-Path -LiteralPath $FixtureParent -ErrorAction Stop).ProviderPath
$fixture = Join-Path $parent ('glass-cleanup-test-' + [Guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $fixture) { throw 'Fixture already exists.' }
New-Item -ItemType Directory -Path $fixture | Out-Null
$project = Join-Path $fixture 'project'
$root = Join-Path $project 'build/verification/glass-native-preview'
$serverPort = 59101
$consolePort = 59110
$listener = $null
$cases = 0

function New-Fixture([string]$RunId) {
    $run = Join-Path $root $RunId
    foreach ($name in @('avd', 'captures', 'logs')) { New-Item -ItemType Directory -Path (Join-Path $run $name) -Force | Out-Null }
    Set-Content -LiteralPath (Join-Path $run 'avd/userdata.img') -Value 'temporary' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $run 'captures/screen.png') -Value 'screenshot' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $run 'logs/test.log') -Value 'log' -Encoding utf8
    return [pscustomobject]@{
        RunId = $RunId; RunRoot = $run; AvdName = "mirad_$RunId"; Serial = "emulator-$consolePort"
        ServerPort = $serverPort; ConsolePort = $consolePort; CleanupPolicy = 'owned-glass-avd-v1'
        ManagedAvdRoot = (Join-Path $run 'avd')
    }
}
function Run-Cleanup($State, [int[]]$Pids = @()) {
    Remove-ManagedGlassAvd -ProjectRoot $project -RunId $State.RunId -State $State -StoppedProcessIds $Pids -ServerPort $serverPort -ConsolePort $consolePort
}
function Assert-Throws([scriptblock]$Action) {
    $threw = $false
    try { & $Action | Out-Null } catch { $threw = $true }
    if (-not $threw) { throw 'Expected cleanup to refuse an unsafe case.' }
}

try {
    $other = New-Fixture 'other-device'
    $ok = New-Fixture 'owned'
    $result = Run-Cleanup $ok
    if (-not $result.Removed -or (Test-Path -LiteralPath $ok.ManagedAvdRoot) -or
        -not (Test-Path -LiteralPath (Join-Path $ok.RunRoot 'captures/screen.png')) -or
        -not (Test-Path -LiteralPath (Join-Path $ok.RunRoot 'logs/test.log')) -or
        -not (Test-Path -LiteralPath (Join-Path $other.ManagedAvdRoot 'userdata.img'))) { throw 'Owned cleanup or preservation failed.' }
    $cases++

    $legacy = New-Fixture 'legacy'
    $legacy.PSObject.Properties.Remove('CleanupPolicy')
    $result = Run-Cleanup $legacy
    if ($result.Removed -or -not (Test-Path -LiteralPath $legacy.ManagedAvdRoot)) { throw 'Legacy AVD was affected.' }
    $cases++

    $wrong = New-Fixture 'wrong-path'
    $wrong.ManagedAvdRoot = $other.ManagedAvdRoot
    Assert-Throws { Run-Cleanup $wrong }
    if (-not (Test-Path -LiteralPath $other.ManagedAvdRoot)) { throw 'Foreign AVD was affected.' }
    $cases++

    $live = New-Fixture 'live-process'
    Assert-Throws { Run-Cleanup $live @($PID) }
    if (-not (Test-Path -LiteralPath $live.ManagedAvdRoot)) { throw 'Live-process AVD was affected.' }
    $cases++

    $busy = New-Fixture 'busy-port'
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $serverPort)
    $listener.Start()
    Assert-Throws { Run-Cleanup $busy }
    if (-not $listener.Server.IsBound -or -not (Test-Path -LiteralPath $busy.ManagedAvdRoot)) { throw 'Busy listener or AVD was affected.' }
    $listener.Stop()
    $listener = $null
    $cases++

    $linked = New-Fixture 'linked'
    $outside = Join-Path $fixture 'outside'
    New-Item -ItemType Directory -Path $outside | Out-Null
    Set-Content -LiteralPath (Join-Path $outside 'sentinel.txt') -Value 'untouched'
    $junction = Join-Path $linked.ManagedAvdRoot 'outside-link'
    New-Item -ItemType Junction -Path $junction -Target $outside | Out-Null
    Assert-Throws { Run-Cleanup $linked }
    if (-not (Test-Path -LiteralPath (Join-Path $outside 'sentinel.txt'))) { throw 'Junction target was affected.' }
    Remove-Item -LiteralPath $junction -Force
    $cases++

    Write-Output "PASS: $cases managed AVD cleanup and isolation cases."
} finally {
    if ($null -ne $listener) { $listener.Stop() }
    # 仅清理本测试新建的、已校验的专用临时目录。
    if (Test-Path -LiteralPath $fixture) {
        $resolved = (Resolve-Path -LiteralPath $fixture).ProviderPath
        if (-not [String]::Equals($resolved, [IO.Path]::GetFullPath($fixture), [StringComparison]::OrdinalIgnoreCase) -or
            -not $resolved.StartsWith($parent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Unsafe fixture cleanup path.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Stop
    }
}
