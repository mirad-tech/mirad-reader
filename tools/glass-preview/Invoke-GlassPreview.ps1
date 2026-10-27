param(
    [Parameter(Mandatory)][ValidateSet('Create', 'Start', 'Status', 'Install', 'Capture', 'Adb', 'Stop')][string]$Action,
    [string]$RunId = 'glass-20261022',
    [string]$SdkRoot = 'S:\Android\Sdk',
    [string]$StudioRoot = 'S:\Android\Android Studio',
    [int]$ServerPort = 5049,
    [int]$ConsolePort = 5580,
    [string]$Apk,
    [string]$ExpectedPackage = 'com.mirad.reader.preview',
    [string]$Name = 'preview',
    [string[]]$AdbArgs = @(),
    [switch]$KeepAvdOnStop,
    [string]$CommandLogFile = $env:MIRAD_GLASS_CMD_LOG
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'RunId must be a simple directory name.' }
if ($ServerPort -eq 5037 -or $ConsolePort % 2 -ne 0 -or $ConsolePort -lt 5554 -or $ConsolePort -gt 5584) {
    throw 'Use a dedicated ADB port and an even emulator port between 5554 and 5584.'
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runRoot = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId"
$avdName = "mirad_$RunId"
$serial = "emulator-$ConsolePort"
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$emulator = Join-Path $SdkRoot 'emulator/emulator.exe'
$statePath = Join-Path $runRoot 'instance.json'
$taskEnvironment = @{
    ANDROID_USER_HOME = (Join-Path $runRoot 'android-home')
    ANDROID_EMULATOR_HOME = (Join-Path $runRoot 'android-home')
    ANDROID_AVD_HOME = (Join-Path $runRoot 'avd')
    ANDROID_SDK_ROOT = $SdkRoot
    ANDROID_HOME = $SdkRoot
    ANDROID_ADB_SERVER_PORT = "$ServerPort"
    ADB_SERVER_PORT = "$ServerPort"
    ADB_MDNS_AUTO_CONNECT = '0'
    ADB_LOCAL_TRANSPORT_MAX_PORT = "$($ConsolePort + 1)"
}
. (Join-Path $PSScriptRoot 'Remove-ManagedGlassAvd.ps1')

function Write-CommandAudit([string]$Kind, [string]$Executable, [string[]]$Arguments) {
    if ($CommandLogFile) {
        $entry = [ordered]@{
            Timestamp  = [DateTimeOffset]::Now.ToString('o')
            RunId      = $RunId
            Action     = $Action
            Kind       = $Kind
            Executable = $Executable
            Arguments  = $Arguments
        } | ConvertTo-Json -Compress
        Add-Content -LiteralPath $CommandLogFile -Value $entry -Encoding utf8
    }
}

function Get-PortOwnerInfo([int]$Port) {
    $conn = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($conn.Count -eq 0) { return $null }
    $ownerPid = [int]$conn[0].OwningProcess
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerPid" -ErrorAction SilentlyContinue
    [pscustomobject]@{
        Port        = $Port
        Pid         = $ownerPid
        ParentPid   = if ($proc -and $null -ne $proc.ParentProcessId) { [int]$proc.ParentProcessId } else { 0 }
        ProcessName = if ($proc) { [string]$proc.Name } else { '' }
        CommandLine = if ($proc -and $null -ne $proc.CommandLine) { [string]$proc.CommandLine } else { '' }
    }
}

function Test-IsOwnedEmulatorProcess($OwnerInfo, [int]$RecordedEmuPid, [int]$RecordedQemuPid, [string]$AvdName) {
    if ($null -eq $OwnerInfo -or $RecordedEmuPid -le 0) { return $false }
    $pidMatch = ($OwnerInfo.Pid -eq $RecordedEmuPid) -or
                ($RecordedQemuPid -gt 0 -and $OwnerInfo.Pid -eq $RecordedQemuPid) -or
                ($OwnerInfo.ParentPid -eq $RecordedEmuPid)
    $nameMatch = $OwnerInfo.ProcessName -match '^(emulator|qemu-system).*\.exe$'
    $cmdMatch = ($OwnerInfo.CommandLine -match [regex]::Escape($AvdName)) -and ($OwnerInfo.CommandLine -match "-port\s+$ConsolePort\b")
    return ($pidMatch -and $nameMatch -and $cmdMatch)
}

function Assert-InstanceOwnership([switch]$ForStart, [switch]$ForStop) {
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
        throw "Ownership check failed: instance.json does not exist for RunId '$RunId' at $statePath."
    }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $recordedRunId = if ($state.PSObject.Properties.Name -contains 'RunId') { [string]$state.RunId } else { ($state.AvdName -replace '^mirad_', '') }
    if ($recordedRunId -ne $RunId -or $state.AvdName -ne $avdName -or $state.Serial -ne $serial -or [int]$state.ServerPort -ne $ServerPort -or [int]$state.ConsolePort -ne $ConsolePort) {
        throw "Ownership check failed: instance.json metadata (RunId=$recordedRunId, AvdName=$($state.AvdName), Serial=$($state.Serial), ServerPort=$($state.ServerPort), ConsolePort=$($state.ConsolePort)) does not match requested RunId=$RunId / ports $ServerPort,$ConsolePort."
    }

    $recordedAdbPid = if ($state.PSObject.Properties.Name -contains 'AdbServerPid') { [int]$state.AdbServerPid } else { 0 }
    $recordedEmuPid = if ($state.PSObject.Properties.Name -contains 'EmulatorPid') { [int]$state.EmulatorPid } else { 0 }
    $recordedQemuPid = if ($state.PSObject.Properties.Name -contains 'QemuPid') { [int]$state.QemuPid } else { 0 }
    $recordedAdbCmd = if ($state.PSObject.Properties.Name -contains 'AdbServerCommandLine') { [string]$state.AdbServerCommandLine } else { '' }

    $serverOwner = Get-PortOwnerInfo $ServerPort
    $consoleOwner = Get-PortOwnerInfo $ConsolePort
    $adbTransportOwner = Get-PortOwnerInfo ($ConsolePort + 1)

    if ($ForStart) {
        if ($null -eq $serverOwner -and $null -eq $consoleOwner -and $null -eq $adbTransportOwner) {
            if ($recordedAdbPid -gt 0 -or $recordedEmuPid -gt 0) {
                throw "Ownership check failed on Start: Stale PID metadata in instance.json (AdbServerPid=$recordedAdbPid, EmulatorPid=$recordedEmuPid) while ports $ServerPort/$ConsolePort are not listening."
            }
            return [pscustomobject]@{ Status = 'FREE'; State = $state }
        }
        if ($null -ne $serverOwner -and $null -ne $consoleOwner -and
            $recordedAdbPid -gt 0 -and $serverOwner.Pid -eq $recordedAdbPid -and
            $serverOwner.ProcessName -match '^adb(\.exe)?$' -and
            ($recordedAdbCmd -eq '' -or $serverOwner.CommandLine -eq $recordedAdbCmd) -and
            (Test-IsOwnedEmulatorProcess $consoleOwner $recordedEmuPid $recordedQemuPid $avdName)) {
            return [pscustomobject]@{ Status = 'OWNED_RUNNING'; State = $state }
        }
        throw "Ownership check failed on Start: Ports $ServerPort/$ConsolePort/$($ConsolePort+1) are occupied by another instance or unowned process (ServerPortOwner=$($serverOwner | ConvertTo-Json -Compress), ConsolePortOwner=$($consoleOwner | ConvertTo-Json -Compress), RecordedAdbPid=$recordedAdbPid, RecordedEmuPid=$recordedEmuPid)."
    }

    if ($recordedAdbPid -le 0 -or $recordedEmuPid -le 0) {
        throw "Ownership check failed: Instance '$RunId' has not recorded active PIDs (AdbServerPid=$recordedAdbPid, EmulatorPid=$recordedEmuPid)."
    }

    if ($null -eq $serverOwner) {
        throw "Ownership check failed: Dedicated ADB port $ServerPort is not listening (RecordedAdbPid=$recordedAdbPid). Refusing to auto-start or touch other services."
    }
    if ($serverOwner.Pid -ne $recordedAdbPid -or $serverOwner.ProcessName -notmatch '^adb(\.exe)?$' -or ($recordedAdbCmd -ne '' -and $serverOwner.CommandLine -ne $recordedAdbCmd)) {
        throw "Ownership check failed: Dedicated ADB port $ServerPort is owned by PID $($serverOwner.Pid) ($($serverOwner.ProcessName) '$($serverOwner.CommandLine)'), expected PID $recordedAdbPid ('$recordedAdbCmd')."
    }

    if ($null -ne $consoleOwner) {
        if (-not (Test-IsOwnedEmulatorProcess $consoleOwner $recordedEmuPid $recordedQemuPid $avdName)) {
            throw "Ownership check failed: Emulator port $ConsolePort is owned by PID $($consoleOwner.Pid) (ParentPid=$($consoleOwner.ParentPid), '$($consoleOwner.CommandLine)'), expected EmulatorPid $recordedEmuPid / QemuPid $recordedQemuPid with AVD '$avdName'."
        }
        if ($recordedQemuPid -ne $consoleOwner.Pid) {
            $state | Add-Member -NotePropertyName QemuPid -NotePropertyValue $consoleOwner.Pid -Force
            $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
        }
    } elseif (-not $ForStop) {
        $emuProc = Get-CimInstance Win32_Process -Filter "ProcessId = $recordedEmuPid" -ErrorAction SilentlyContinue
        if (-not $emuProc -or [string]$emuProc.CommandLine -notmatch [regex]::Escape($avdName)) {
            throw "Ownership check failed: Emulator process PID $recordedEmuPid for '$avdName' is not running."
        }
    }

    if ($null -ne $adbTransportOwner -and -not (Test-IsOwnedEmulatorProcess $adbTransportOwner $recordedEmuPid $recordedQemuPid $avdName)) {
        throw "Ownership check failed: Emulator ADB transport port $($ConsolePort + 1) is owned by PID $($adbTransportOwner.Pid) (ParentPid=$($adbTransportOwner.ParentPid)), expected EmulatorPid $recordedEmuPid."
    }

    return [pscustomobject]@{ Status = 'OWNED_RUNNING'; State = $state }
}

function Invoke-TaskProcess([string]$Executable, [string[]]$Arguments, [int]$TimeoutMs = 30000, [string]$InputText = '', [string]$OutputFile = '') {
    Write-CommandAudit 'TaskProcess' $Executable $Arguments
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $info.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $info.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    foreach ($entry in $taskEnvironment.GetEnumerator()) { $info.Environment[$entry.Key] = $entry.Value }
    $process = [Diagnostics.Process]::Start($info)
    $errorTask = $process.StandardError.ReadToEndAsync()
    if ($OutputFile) {
        $outputStream = [IO.File]::Open($OutputFile, [IO.FileMode]::Create, [IO.FileAccess]::Write)
        $outputTask = $process.StandardOutput.BaseStream.CopyToAsync($outputStream)
    } else {
        $outputTask = $process.StandardOutput.ReadToEndAsync()
    }
    if ($InputText) { $process.StandardInput.Write($InputText) }
    $process.StandardInput.Close()
    try {
        if (-not $process.WaitForExit($TimeoutMs)) {
            $process.Kill($true)
            throw "Process timed out after $TimeoutMs ms: $Executable"
        }
        $outputTask.GetAwaiter().GetResult() | Out-Null
        $stderr = $errorTask.GetAwaiter().GetResult()
        $stdout = if ($OutputFile) { '' } else { $outputTask.Result }
        [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout; Error = $stderr }
    } finally {
        if ($OutputFile) { $outputStream.Dispose() }
        $process.Dispose()
    }
}

function Invoke-InstanceAdb([string[]]$Arguments, [int]$TimeoutMs = 30000, [string]$OutputFile = '', [switch]$ForStop) {
    Assert-InstanceOwnership -ForStop:$ForStop | Out-Null
    Invoke-TaskProcess $adb (@('-P', "$ServerPort", '-s', $serial) + $Arguments) $TimeoutMs '' $OutputFile
}

switch ($Action) {
    'Create' {
        if (Test-Path -LiteralPath $statePath) { throw 'This run already exists; use a new RunId.' }
        foreach ($directory in @('android-home', 'avd', 'captures', 'logs')) {
            New-Item -ItemType Directory -Path (Join-Path $runRoot $directory) -Force | Out-Null
        }
        $classPath = "$StudioRoot\plugins\android\lib\*;$StudioRoot\lib\*;$StudioRoot\plugins\Kotlin\kotlinc\lib\*"
        $arguments = @("-Dcom.android.sdkmanager.toolsdir=$SdkRoot\tools\bin", '-cp', $classPath,
            'com.android.sdklib.tool.AvdManagerCli', 'create', 'avd', '-n', $avdName,
            '-p', (Join-Path $runRoot "avd/$avdName.avd"),
            '-k', 'system-images;android-37.0;google_apis_playstore_ps16k;x86_64')
        $result = Invoke-TaskProcess (Join-Path $StudioRoot 'jbr/bin/java.exe') $arguments 60000 "no`n"
        ($result.Output + $result.Error) | Set-Content -LiteralPath (Join-Path $runRoot 'logs/create.log') -Encoding utf8
        if ($result.ExitCode -ne 0) { throw "AVD creation failed ($($result.ExitCode)); see $runRoot\logs\create.log" }
        $config = Join-Path $runRoot "avd/$avdName.avd/config.ini"
        $entries = [ordered]@{}
        foreach ($line in Get-Content -LiteralPath $config) {
            if ($line -match '^([^=]+)=(.*)$') { $entries[$Matches[1]] = $Matches[2] }
        }
        $entries['hw.lcd.width'] = '1080'
        $entries['hw.lcd.height'] = '2400'
        $entries['hw.lcd.density'] = '440'
        $entries['hw.ramSize'] = '3072'
        $entries['hw.cpu.ncore'] = '4'
        $entries['hw.gpu.enabled'] = 'yes'
        $entries['hw.gpu.mode'] = 'auto'
        $entries['hw.keyboard'] = 'yes'
        $entries['hw.mainKeys'] = 'no'
        $entries['hw.audioInput'] = 'no'
        $entries['hw.camera.back'] = 'none'
        $entries['hw.camera.front'] = 'none'
        $entries['showDeviceFrame'] = 'no'
        $entries['disk.dataPartition.size'] = '4G'
        $entries['image.sysdir.1'] = (Join-Path $SdkRoot 'system-images/android-37.0/google_apis_playstore_ps16k/x86_64/')
        $lines = foreach ($entry in $entries.GetEnumerator()) { "$($entry.Key)=$($entry.Value)" }
        $lines | Set-Content -LiteralPath $config -Encoding utf8
        [ordered]@{
            RunId                = $RunId
            RunRoot              = $runRoot
            AvdName              = $avdName
            Serial               = $serial
            ServerPort           = $ServerPort
            ConsolePort          = $ConsolePort
            EmulatorPid          = 0
            AdbServerPid         = 0
            AdbServerCommandLine = ''
            CleanupPolicy        = 'owned-glass-avd-v1'
            ManagedAvdRoot       = (Join-Path $runRoot 'avd')
            CreatedAt            = [DateTimeOffset]::Now.ToString('o')
        } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
        Get-Content -LiteralPath $statePath
    }
    'Start' {
        $ownership = Assert-InstanceOwnership -ForStart
        if ($ownership.Status -eq 'OWNED_RUNNING') {
            return $ownership.State
        }
        if ($ownership.State.PSObject.Properties.Name -contains 'CleanupPolicy' -and
            $ownership.State.CleanupPolicy -eq 'owned-glass-avd-v1' -and
            -not (Test-Path -LiteralPath (Join-Path $runRoot 'avd') -PathType Container)) {
            throw "The temporary AVD for RunId '$RunId' has been cleaned; create a new RunId."
        }
        # A USB serial that cannot identify a phone prevents this dedicated server from attaching to USB devices.
        $server = Invoke-TaskProcess $adb @('-P', "$ServerPort", '--one-device', "mirad-isolated-$RunId", 'start-server')
        ($server.Output + $server.Error) | Set-Content -LiteralPath (Join-Path $runRoot 'logs/adb-server.log') -Encoding utf8
        if ($server.ExitCode -ne 0) { throw 'Dedicated ADB server did not start.' }
        $arguments = @('-avd', $avdName, '-port', "$ConsolePort", '-no-window', '-no-snapshot', '-no-boot-anim', '-no-audio', '-no-metrics', '-gpu', 'auto')
        Write-CommandAudit 'StartEmulator' $emulator $arguments
        $process = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden -Environment $taskEnvironment -PassThru -RedirectStandardOutput (Join-Path $runRoot 'logs/emulator.stdout.log') -RedirectStandardError (Join-Path $runRoot 'logs/emulator.stderr.log')
        $serverOwner = Get-PortOwnerInfo $ServerPort
        if ($null -eq $serverOwner) {
            throw "Dedicated ADB server on port $ServerPort failed to bind listener."
        }
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $state | Add-Member -NotePropertyName RunId -NotePropertyValue $RunId -Force
        $state.EmulatorPid = $process.Id
        $state | Add-Member -NotePropertyName AdbServerPid -NotePropertyValue $serverOwner.Pid -Force
        $state | Add-Member -NotePropertyName AdbServerCommandLine -NotePropertyValue $serverOwner.CommandLine -Force
        $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
        $state
    }
    'Status' {
        $ownership = Assert-InstanceOwnership
        $state = $ownership.State
        $process = Get-Process -Id $state.EmulatorPid -ErrorAction SilentlyContinue
        $boot = Invoke-InstanceAdb @('shell', 'getprop', 'sys.boot_completed') 10000
        [pscustomobject]@{ Serial = $serial; ServerPort = $ServerPort; EmulatorRunning = ($null -ne $process); BootCompleted = $boot.Output.Trim(); ExitCode = $boot.ExitCode; Error = $boot.Error.Trim() }
    }
    'Install' {
        if (-not $Apk -or -not (Test-Path -LiteralPath $Apk)) { throw 'Pass an existing preview APK.' }
        if (-not $ExpectedPackage.StartsWith('com.mirad.reader.') -or $ExpectedPackage -notmatch 'preview$') {
            throw 'Only a separate Mirad preview application ID is accepted.'
        }
        $aapt = Join-Path $SdkRoot 'build-tools/36.1.0/aapt2.exe'
        $metadata = Invoke-TaskProcess $aapt @('dump', 'badging', (Resolve-Path -LiteralPath $Apk).Path)
        if ($metadata.ExitCode -ne 0 -or $metadata.Output -notmatch "package: name='([^']+)'") { throw 'Cannot verify APK identity.' }
        if ($Matches[1] -ne $ExpectedPackage) { throw 'Refusing to install an APK that is not the isolated preview application.' }
        $result = Invoke-InstanceAdb @('install', '-r', (Resolve-Path -LiteralPath $Apk).Path) 60000
        $result
        if ($result.ExitCode -ne 0) { throw 'Preview APK installation failed.' }
    }
    'Capture' {
        if ($Name -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Capture name must be a simple file name.' }
        $output = Join-Path $runRoot "captures/$Name.png"
        $result = Invoke-InstanceAdb @('exec-out', 'screencap', '-p') 15000 $output
        if ($result.ExitCode -ne 0) { throw "Screenshot failed: $($result.Error)" }
        $bytes = [IO.File]::ReadAllBytes($output)
        if ($bytes.Length -lt 24 -or [Convert]::ToHexString($bytes[0..7]) -ne '89504E470D0A1A0A') { throw 'Screenshot is not a valid PNG stream.' }
        [pscustomobject]@{ Path = $output; Bytes = $bytes.Length }
    }
    'Adb' {
        if ($AdbArgs.Count -eq 0 -or $AdbArgs[0] -notin @('shell', 'exec-out', 'logcat', 'get-state', 'get-serialno')) {
            throw 'Only device-scoped shell, exec-out, logcat and identity commands are accepted.'
        }
        $result = Invoke-InstanceAdb $AdbArgs
        $result
        if ($result.ExitCode -ne 0) { throw 'Device command failed.' }
    }
    'Stop' {
        $ownership = Assert-InstanceOwnership -ForStop
        $state = $ownership.State
        $isManagedAvd = $state.PSObject.Properties.Name -contains 'CleanupPolicy' -and $state.CleanupPolicy -eq 'owned-glass-avd-v1'
        $stoppedProcessIds = @($state.EmulatorPid, $state.AdbServerPid)
        if ($state.PSObject.Properties.Name -contains 'QemuPid') { $stoppedProcessIds += $state.QemuPid }
        $result = Invoke-InstanceAdb @('emu', 'kill') 10000 -ForStop
        $result
        if ($isManagedAvd -and $result.ExitCode -ne 0) {
            throw 'The isolated emulator did not stop successfully; its AVD and ownership state were retained.'
        }
        # Kill only the verified server on the private port; the user's default server remains untouched.
        $serverResult = Invoke-TaskProcess $adb @('-P', "$ServerPort", 'kill-server')
        $serverResult
        if ($isManagedAvd -and $serverResult.ExitCode -ne 0) {
            throw 'The dedicated ADB server did not stop successfully; the AVD and ownership state were retained.'
        }
        $state.EmulatorPid = 0
        $state.AdbServerPid = 0
        $state | Add-Member -NotePropertyName QemuPid -NotePropertyValue 0 -Force
        $state | Add-Member -NotePropertyName AdbServerCommandLine -NotePropertyValue '' -Force
        $state | Add-Member -NotePropertyName StoppedAt -NotePropertyValue ([DateTimeOffset]::Now.ToString('o')) -Force
        $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8

        if ($isManagedAvd) {
            $cleanupStatus = 'Skipped'
            $cleanupError = ''
            try {
                if ($KeepAvdOnStop) {
                    $cleanupStatus = 'KeptByRequest'
                } else {
                    # 仅等待已记录的进程退出；不停止或修改任何其他进程。
                    foreach ($processId in @($stoppedProcessIds | Where-Object { $_ -gt 0 } | Select-Object -Unique)) {
                        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
                            Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
                        }
                    }
                    $cleanup = Remove-ManagedGlassAvd -ProjectRoot $projectRoot -RunId $RunId -State $state -StoppedProcessIds $stoppedProcessIds -ServerPort $ServerPort -ConsolePort $ConsolePort
                    $cleanupStatus = $cleanup.Reason
                }
            } catch {
                $cleanupStatus = 'Failed'
                $cleanupError = $_.Exception.Message
                Write-Warning "Temporary AVD cleanup did not complete; inspect the run before reuse: $cleanupError"
            }
            $state | Add-Member -NotePropertyName CleanupStatus -NotePropertyValue $cleanupStatus -Force
            $state | Add-Member -NotePropertyName CleanupError -NotePropertyValue $cleanupError -Force
            if ($cleanupStatus -eq 'Removed') {
                $state | Add-Member -NotePropertyName AvdCleanedAt -NotePropertyValue ([DateTimeOffset]::Now.ToString('o')) -Force
            }
            $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
            $logPath = Join-Path $runRoot 'logs/avd-cleanup.log'
            Add-Content -LiteralPath $logPath -Value ("{0} {1} {2}" -f [DateTimeOffset]::Now.ToString('o'), $cleanupStatus, $cleanupError) -Encoding utf8
        }
    }
}
