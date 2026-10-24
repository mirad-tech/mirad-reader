[CmdletBinding()]
param(
    [string]$RunId = 'reader-gpu-integration-20261024',
    [string]$Apk = 'app/build/outputs/apk/preview/app-preview.apk',
    [string]$OutputDirectory = 'build/verification/top-halo-fix-20261024'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$output = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$allowed = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/verification')) + [IO.Path]::DirectorySeparatorChar

if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must remain under build/verification.'
}
if (Test-Path -LiteralPath $output) {
    Remove-Item -LiteralPath $output -Recurse -Force
}
New-Item -ItemType Directory -Path $output | Out-Null

$apkPath = (Resolve-Path -LiteralPath (Join-Path $projectRoot $Apk)).Path
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
$package = 'com.mirad.reader.preview'
$component = "$package/com.mirad.reader.preview.SettingsImmersionPreviewActivity"
$action = 'com.mirad.reader.preview.IMMERSION_CHECK'

$checks = [Collections.Generic.List[object]]::new()
$captures = [Collections.Generic.List[object]]::new()
$counter = 0

function Device([string[]]$Arguments) {
    $r = & $driver -Action Adb -RunId $RunId -AdbArgs $Arguments
    if ($r.ExitCode -ne 0) { throw "Isolated command failed: $($r.Error) $($r.Output)" }
    return [string]$r.Output
}

function Check([bool]$Condition, [string]$Name) {
    $checks.Add([pscustomobject]@{ Name = $Name; Passed = $Condition })
    if (-not $Condition) { throw "CHECK FAILED: $Name" }
    Write-Host "PASS $Name"
}

function Command([string]$Operation, [string[]]$Extra = @()) {
    [void](Device (@('shell', 'am', 'broadcast', '-a', $action, '--es', 'operation', $Operation) + $Extra))
}

function Dump {
    $script:counter++
    $id = [Guid]::NewGuid().ToString('N')
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        try {
            Command 'dump' @('--es', 'label', $id)
            $json = Device @('shell', 'cat', "/sdcard/Android/data/$package/files/hardware-reader-state.json")
            $state = $json | ConvertFrom-Json
            if ($state.label -eq $id -and $state.PSObject.Properties.Name -contains 'web' -and $state.web.ready) {
                $json | Set-Content -LiteralPath (Join-Path $output "state-$script:counter.json") -Encoding utf8
                return $state
            }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    throw 'No current, ready hardware reader state.'
}

function Capture([string]$Name) {
    $state = Dump
    $shot = & $driver -Action Capture -RunId $RunId -Name "halo-fix-$Name"
    $path = Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $shot.Path -Destination $path
    $state | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $output "$Name-state.json") -Encoding utf8
    $capture = [pscustomobject]@{ Name = $Name; Path = $path; State = $state }
    $captures.Add($capture)
    return $capture
}

function Metric($State, [string]$Name) {
    $m = [regex]::Match([string]$State.hardware.sceneStats, "(?:^|\s)$([regex]::Escape($Name))=([^\s]+)")
    if (-not $m.Success) { throw "Missing hardware metric $Name" }
    return $m.Groups[1].Value
}

try {
    Write-Host "Starting emulator with RunId $RunId..."
    & $driver -Action Start -RunId $RunId | Out-Null

    # Wait for boot
    Write-Host "Waiting for device boot..."
    $booted = $false
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $status = & $driver -Action Status -RunId $RunId
            if ($status.BootCompleted -eq '1') {
                $booted = $true
                break
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Check $booted 'Isolated emulator booted successfully'

    # Install Preview APK
    Write-Host "Installing preview APK..."
    $install = & $driver -Action Install -RunId $RunId -Apk $apkPath -ExpectedPackage $package
    Check ($install.ExitCode -eq 0) 'Preview APK installed successfully'

    $installed = (Device @('shell', 'pm', 'path', $package)).Trim()
    if ($installed -notmatch '^package:(/data/app/[^\r\n]+/base\.apk)$') { throw 'Unexpected package path.' }
    $installedHash = ((Device @('shell', 'sha256sum', $Matches[1])) -split '\s+')[0]
    Check ($installedHash -eq $apkHash) 'Installed APK matches built APK hash'

    # Reset emulator display settings
    [void](Device @('shell', 'settings', 'put', 'system', 'font_scale', '1.0'))
    [void](Device @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0'))
    [void](Device @('shell', 'settings', 'put', 'system', 'user_rotation', '0'))
    [void](Device @('shell', 'cmd', 'uimode', 'night', 'no'))
    [void](Device @('shell', 'cmd', 'overlay', 'disable', 'com.android.internal.display.cutout.emulation.tall'))

    # 1. Day Mode 100% (Chrome & Reader)
    Write-Host "1. Testing Day Mode 100%..."
    $launch = Device @('shell', 'am', 'start', '-S', '-W', '-n', $component,
        '--es', 'scene', 'appearance', '--es', 'entry', 'reader', '--ez', 'hardware', 'true',
        '--es', 'mode', 'DAY', '--ez', 'chrome', 'true', '--ei', 'transparency', '100',
        '--ez', 'fullScreen', 'false', '--ez', 'showClock', 'false')
    Check ($launch -match 'Status: ok') 'Reader activity launched successfully in Day mode'
    Start-Sleep -Milliseconds 800
    $day100Chrome = Capture 'day-100-chrome'
    Check ($day100Chrome.State.hardwarePreview -and $day100Chrome.State.hardware.hostWindowMatchesActivity) 'Day 100% uses hardware scene'
    Check ((Metric $day100Chrome.State 'hardware') -eq 'true' -and (Metric $day100Chrome.State 'gpuActive') -eq 'true') 'Day 100% GPU active'
    Check ([double](Metric $day100Chrome.State 'baseOpacity') -le 0.04) 'Day 100% baseOpacity <= 0.04 (2% base tint)'

    # Day mode reader content (chrome hidden)
    Command 'chrome' @('--ez', 'visible', 'false')
    Start-Sleep -Milliseconds 500
    $day100Reader = Capture 'day-100-reader'
    Check (-not $day100Reader.State.chrome) 'Day 100% chrome hidden'

    # 2. Night Mode 100% (Chrome & Reader)
    Write-Host "2. Testing Night Mode 100%..."
    Command 'mode' @('--es', 'mode', 'NIGHT')
    Command 'transparency' @('--ei', 'percent', '100')
    Command 'chrome' @('--ez', 'visible', 'true')
    Start-Sleep -Milliseconds 500
    $night100Chrome = Capture 'night-100-chrome'
    Check ($night100Chrome.State.effectiveDark) 'Night 100% effectiveDark is true'
    Check ((Metric $night100Chrome.State 'gpuActive') -eq 'true') 'Night 100% GPU active'

    Command 'chrome' @('--ez', 'visible', 'false')
    Start-Sleep -Milliseconds 500
    $night100Reader = Capture 'night-100-reader'
    Check (-not $night100Reader.State.chrome) 'Night 100% chrome hidden'

    # 3. Image Background 100%
    Write-Host "3. Testing Image Background..."
    Command 'mode' @('--es', 'mode', 'DAY')
    Command 'config' @('--es', 'background', 'dawn')
    Command 'chrome' @('--ez', 'visible', 'true')
    Start-Sleep -Milliseconds 600
    $imageBgChrome = Capture 'image-bg-chrome'
    Check ($imageBgChrome.State.web.image -match 'backgrounds/dawn.jpg') 'Dawn background image loaded'

    Command 'chrome' @('--ez', 'visible', 'false')
    Start-Sleep -Milliseconds 500
    $imageBgReader = Capture 'image-bg-reader'

    # 4. Cutout & Status Bar Visible
    Write-Host "4. Testing Cutout and Status Bar Visible..."
    [void](Device @('shell', 'cmd', 'overlay', 'enable', 'com.android.internal.display.cutout.emulation.tall'))
    Start-Sleep -Milliseconds 600
    Command 'chrome' @('--ez', 'visible', 'false')
    $cutoutReader = Capture 'cutout-statusbar-reader'
    Check ($cutoutReader.State.statusInsetTop -gt 0) 'Status bar top inset is present'

    Command 'chrome' @('--ez', 'visible', 'true')
    Start-Sleep -Milliseconds 500
    $cutoutChrome = Capture 'cutout-statusbar-chrome'
    Check ($cutoutChrome.State.chrome) 'Chrome visible under cutout'

    # Disable cutout overlay
    [void](Device @('shell', 'cmd', 'overlay', 'disable', 'com.android.internal.display.cutout.emulation.tall'))
    Start-Sleep -Milliseconds 500

    # 5. Position and Anchor checks (Scroll & Paged)
    Write-Host "5. Testing Positioning & Anchors..."
    # Switch back to plain day mode, fullScreen false
    Command 'config' @('--es', 'pageMode', 'SCROLL', '--es', 'theme', 'DEFAULT')
    Command 'scroll' @('--ei', 'y', '360')
    Start-Sleep -Milliseconds 400
    $scrollBefore = Dump
    Check ($scrollBefore.web.scrollY -gt 300) 'Scroll position advanced'
    $scrollGlyphBefore = $scrollBefore.web.glyph

    # Open sheet (TOC) and verify scroll position & character anchor preserved
    Command 'panel' @('--es', 'name', 'toc')
    Start-Sleep -Milliseconds 500
    $tocState = Dump
    Check ($tocState.hardware.modalVisible) 'TOC modal is visible'
    Check ([Math]::Abs($scrollBefore.web.scrollY - $tocState.web.scrollY) -le 1) 'TOC preserves scrollY'
    Check ($scrollGlyphBefore.offset -eq $tocState.web.glyph.offset) 'TOC preserves character anchor offset'

    # Close TOC
    [void](Device @('shell', 'input', 'keyevent', 'KEYCODE_BACK'))
    Start-Sleep -Milliseconds 400
    $scrollAfter = Dump
    Check (-not $scrollAfter.hardware.modalVisible) 'TOC closed'
    Check ([Math]::Abs($scrollBefore.web.scrollY - $scrollAfter.web.scrollY) -le 1) 'Scroll position preserved after closing TOC'

    # PAGED mode check
    Command 'config' @('--es', 'pageMode', 'PAGED')
    Start-Sleep -Milliseconds 500
    $paged1 = Dump
    Check ($paged1.web.state.mode -eq 'PAGED') 'Switched to PAGED mode'
    $page1Num = $paged1.web.state.page
    $page1Offset = $paged1.web.state.offset

    # Turn page via volume key / cover command
    Command 'cover' @('--ei', 'direction', '1', '--ei', 'durationMs', '260', '--ez', 'hold', 'false')
    Start-Sleep -Milliseconds 600
    $paged2 = Dump
    Check ($paged2.web.state.page -gt $page1Num) 'Paged mode advanced to next page'
    Check ($paged2.web.state.offset -gt $page1Offset) 'Paged mode advanced character offset'

    # 6. 0% -> 100% Transition
    Write-Host "6. Testing 0% -> 100% Transparency Transition..."
    Command 'transparency' @('--ei', 'percent', '0')
    Start-Sleep -Milliseconds 500
    $toggle0 = Capture 'toggle-0-percent'
    Check ((Metric $toggle0.State 'gpuActive') -eq 'false') '0% transparency disables GPU material'
    Check ([double](Metric $toggle0.State 'baseOpacity') -ge 0.99) '0% transparency has solid baseOpacity'

    Command 'transparency' @('--ei', 'percent', '100')
    Start-Sleep -Milliseconds 500
    $toggle100 = Capture 'toggle-100-percent'
    Check ((Metric $toggle100.State 'gpuActive') -eq 'true') '100% transparency restores GPU material'
    Check ([double](Metric $toggle100.State 'baseOpacity') -le 0.04) '100% transparency restores <= 0.04 baseOpacity'
    Check ((Metric $toggle100.State 'hardware') -eq 'true') '100% transparency hardware rendering is ready'

    Write-Host "All targeted checks passed!"

    [ordered]@{
        Passed = $true
        Apk = $apkPath
        SHA256 = $apkHash
        Checks = $checks
        Captures = $captures
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8

} finally {
    Write-Host "Cleaning up emulator..."
    try {
        [void](Device @('shell', 'cmd', 'overlay', 'disable', 'com.android.internal.display.cutout.emulation.tall'))
    } catch { }
    try {
        & $driver -Action Stop -RunId $RunId | Out-Null
    } catch { }
}
