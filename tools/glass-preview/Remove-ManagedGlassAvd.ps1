function Remove-ManagedGlassAvd {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ProjectRoot,
        [Parameter(Mandatory)][string]$RunId,
        [Parameter(Mandatory)]$State,
        [int[]]$StoppedProcessIds = @(),
        [Parameter(Mandatory)][int]$ServerPort,
        [Parameter(Mandatory)][int]$ConsolePort
    )

    # 没有新版本标记的旧实例、外部实例一律不清理。
    if ($State.PSObject.Properties.Name -notcontains 'CleanupPolicy' -or
        $State.CleanupPolicy -ne 'owned-glass-avd-v1') {
        return [pscustomobject]@{ Removed = $false; Reason = 'LegacyOrUnmanaged' }
    }
    if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid cleanup RunId.' }

    $project = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $base = [IO.Path]::GetFullPath((Join-Path $project 'build/verification/glass-native-preview'))
    $run = [IO.Path]::GetFullPath((Join-Path $base $RunId))
    $target = [IO.Path]::GetFullPath((Join-Path $run 'avd'))
    if (-not $run.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        -not [String]::Equals([IO.Path]::GetDirectoryName($target), $run, [StringComparison]::OrdinalIgnoreCase) -or
        $State.RunId -ne $RunId -or $State.AvdName -ne "mirad_$RunId" -or
        $State.Serial -ne "emulator-$ConsolePort" -or [int]$State.ServerPort -ne $ServerPort -or [int]$State.ConsolePort -ne $ConsolePort -or
        -not [String]::Equals([IO.Path]::GetFullPath([string]$State.RunRoot), $run, [StringComparison]::OrdinalIgnoreCase) -or
        -not [String]::Equals([IO.Path]::GetFullPath([string]$State.ManagedAvdRoot), $target, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Cleanup ownership metadata or path does not match this run.'
    }

    if (-not (Test-Path -LiteralPath $target -PathType Container)) {
        return [pscustomobject]@{ Removed = $false; Reason = 'AlreadyAbsent' }
    }
    $resolved = (Resolve-Path -LiteralPath $target -ErrorAction Stop).ProviderPath
    if (-not [String]::Equals($resolved, $target, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Cleanup target did not resolve to the expected directory.'
    }

    # 防止任一祖先或待删内容通过链接跳出本次运行的目录。
    $cursor = $target
    while ($cursor) {
        $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Cleanup path contains a reparse point: $cursor" }
        $parent = [IO.Path]::GetDirectoryName($cursor.TrimEnd([IO.Path]::DirectorySeparatorChar))
        if (-not $parent -or [String]::Equals($parent, $cursor, [StringComparison]::OrdinalIgnoreCase)) { break }
        $cursor = $parent
    }
    $nestedLinks = @(Get-ChildItem -LiteralPath $target -Force -Recurse -ErrorAction Stop | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
    if ($nestedLinks.Count -gt 0) { throw "Cleanup target contains a reparse point: $($nestedLinks[0].FullName)" }

    foreach ($processId in @($StoppedProcessIds | Where-Object { $_ -gt 0 } | Select-Object -Unique)) {
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) { throw "Cleanup refused: recorded process $processId is still running." }
    }
    $ports = @($ServerPort, $ConsolePort, ($ConsolePort + 1))
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -in $ports })
    if ($listeners.Count -gt 0) { throw "Cleanup refused: a required port is still occupied: $($listeners.LocalPort -join ', ')." }
    $matching = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -match '^(emulator|qemu-system).*\.exe$' -and
        $_.CommandLine -and $_.CommandLine -match [regex]::Escape("mirad_$RunId")
    })
    if ($matching.Count -gt 0) { throw 'Cleanup refused: a process still references this AVD.' }

    Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
    return [pscustomobject]@{ Removed = $true; Reason = 'Removed'; Path = $target }
}
