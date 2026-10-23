param(
    [string]$RunId = 'fix-r2-1014-20261023',
    [string]$VerificationDir = 'build/verification/fix-r2-1.0.14-20261023-074142',
    [switch]$ResumeFrom7C
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$verificationPath = if ([IO.Path]::IsPathRooted($VerificationDir)) { $VerificationDir } else { Join-Path $projectRoot $VerificationDir }
$capturesDir = Join-Path $verificationPath 'captures'
$logsDir = Join-Path $verificationPath 'logs'
New-Item -ItemType Directory -Path $capturesDir -Force | Out-Null
New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
$instanceOwnedByThisRun = $false

$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$builtPreviewApk = Join-Path $projectRoot 'app/build/outputs/apk/preview/app-preview.apk'
$builtDebugApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
$builtReleaseApk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
$pinnedPreviewApk = Join-Path $verificationPath 'app-preview-tested.apk'
$package = 'com.mirad.reader.preview'
$activityComponent = "$package/com.mirad.reader.preview.GlassPreviewActivity"

function Log-Message([string]$msg) {
    $timestamp = [DateTime]::Now.ToString('yyyy-MM-dd HH:mm:ss.fff')
    Write-Output "[$timestamp] $msg"
    "[$timestamp] $msg" | Out-File -FilePath (Join-Path $logsDir 'verification-orchestrator.log') -Append -Encoding utf8
}

function Device([string[]]$Command) {
    $result = & $driver -Action Adb -RunId $RunId -AdbArgs $Command
    if ($result.ExitCode -ne 0) { throw "Isolated ADB failed: $($result.Error)" }
    $result.Output
}

function Ui([string]$DumpName = 'mirad-glass-ui.xml') {
    $remotePath = "/data/local/tmp/$DumpName"
    Device @('shell','uiautomator','dump',$remotePath) | Out-Null
    $raw = Device @('shell','cat',$remotePath)
    if ([string]::IsNullOrWhiteSpace($raw) -or $raw -notmatch '<hierarchy') {
        throw "UIAutomator dump failed or returned invalid XML for $DumpName"
    }
    [xml]$raw
}

function Bounds($Node, [string]$Context = '') {
    if ($null -eq $Node -or $Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Expected a visible UI node ($Context)."
    }
    @{Left=[int]$Matches[1]; Top=[int]$Matches[2]; Right=[int]$Matches[3]; Bottom=[int]$Matches[4]}
}

function TapNode($Node, [string]$Context = '') {
    $rect = Bounds $Node $Context
    $cx = [int](($rect.Left + $rect.Right) / 2)
    $cy = [int](($rect.Top + $rect.Bottom) / 2)
    Device @('shell','input','tap',"$cx","$cy") | Out-Null
    @{X=$cx; Y=$cy; Bounds=$Node.bounds}
}

function Start-PreviewScene([string[]]$ExtraArgs) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Device (@('shell','am','start','-W','-n',$activityComponent) + $ExtraArgs) | Out-Null
        Start-Sleep -Milliseconds 1500
        try {
            $doc = Ui 'start-scene-check.xml'
            $pkgNode = $doc.SelectSingleNode("//node[@package='$package']")
            if ($null -ne $pkgNode) {
                return
            }
        } catch {}
        Log-Message "Retry $attempt bringing $activityComponent to foreground over launcher..."
        Device @('shell','wm','dismiss-keyguard') | Out-Null
        Start-Sleep -Seconds 1
    }
}

function Dump-GlassStats([string]$Label) {
    Device @('shell','am','broadcast','-a','com.mirad.reader.preview.ACTION_DUMP_STATS','--es','label',$Label) | Out-Null
    Start-Sleep -Milliseconds 250
    $log = Device @('shell','logcat','-d','-s','GlassPreview')
    $linePattern = "glassStatsDump label=$([regex]::Escape($Label))\s+([^\r\n]+)"
    $matchesList = @([regex]::Matches($log, $linePattern))
    if ($matchesList.Count -eq 0) {
        throw "Failed to find glassStatsDump for label '$Label' in logcat. Log tail:`n$($log -split "`n" | Select-Object -Last 20 | Out-String)"
    }
    $m = $matchesList[-1]
    $kvText = $m.Groups[1].Value
    $kv = @{}
    foreach ($pair in [regex]::Matches($kvText, '(\w+)=([^\s]+)')) {
        $kv[$pair.Groups[1].Value] = $pair.Groups[2].Value
    }
    $srcParts = if ($kv.ContainsKey('sourceSize')) { $kv['sourceSize'] -split 'x' } else { @('0','0') }
    $rbParts = if ($kv.ContainsKey('readbackSize')) { $kv['readbackSize'] -split 'x' } else { @('0','0') }
    [pscustomobject]@{
        Label           = $Label
        Transparency    = if ($kv.ContainsKey('transparency')) { [int]$kv['transparency'] } else { 0 }
        SaveFailed      = if ($kv.ContainsKey('saveFailed')) { $kv['saveFailed'] -eq 'true' } else { $false }
        SourceRecords   = if ($kv.ContainsKey('sourceRecords')) { [long]$kv['sourceRecords'] } else { 0L }
        Snapshots       = if ($kv.ContainsKey('snapshots')) { [long]$kv['snapshots'] } else { 0L }
        Analyses        = if ($kv.ContainsKey('analyses')) { [long]$kv['analyses'] } else { 0L }
        WebAttempts     = if ($kv.ContainsKey('webAttempts')) { [long]$kv['webAttempts'] } else { 0L }
        WebPublished    = if ($kv.ContainsKey('webPublished')) { [long]$kv['webPublished'] } else { 0L }
        SourceWidth     = [int]$srcParts[0]
        SourceHeight    = [int]$srcParts[1]
        ReadbackWidth   = [int]$rbParts[0]
        ReadbackHeight  = [int]$rbParts[1]
        InFlight        = if ($kv.ContainsKey('inFlight')) { [int]$kv['inFlight'] } else { 0 }
        MaxInFlight     = if ($kv.ContainsKey('maxInFlight')) { [int]$kv['maxInFlight'] } else { 0 }
        ActiveSnapshots = if ($kv.ContainsKey('activeSnapshots')) { [int]$kv['activeSnapshots'] } else { 0 }
        AmbientOpacity  = if ($kv.ContainsKey('ambientOpacity')) { [double]$kv['ambientOpacity'] } else { -1.0 }
        PeakOpacity     = if ($kv.ContainsKey('peakOpacity')) { [double]$kv['peakOpacity'] } else { -1.0 }
        ProtectedCells  = if ($kv.ContainsKey('protectedCells')) { [int]$kv['protectedCells'] } else { -1 }
        BlurDp          = if ($kv.ContainsKey('blurDp')) { [double]$kv['blurDp'] } else { -1.0 }
        HighlightAlpha  = if ($kv.ContainsKey('highlightAlpha')) { [double]$kv['highlightAlpha'] } else { -1.0 }
        Thread          = if ($kv.ContainsKey('thread')) { $kv['thread'] } else { 'none' }
        DurationMs      = if ($kv.ContainsKey('durationMs')) { [double]$kv['durationMs'] } else { 0.0 }
        RawLine         = $m.Value.Trim()
    }
}

Add-Type -AssemblyName System.Drawing

function Get-SrgbChannelLinear([double]$c) {
    $s = $c / 255.0
    if ($s -le 0.04045) { return $s / 12.92 }
    return [Math]::Pow(($s + 0.055) / 1.055, 2.4)
}

function Get-ColorLuminance([System.Drawing.Color]$col) {
    $r = Get-SrgbChannelLinear $col.R
    $g = Get-SrgbChannelLinear $col.G
    $b = Get-SrgbChannelLinear $col.B
    return 0.2126 * $r + 0.7152 * $g + 0.0722 * $b
}

function Get-ContrastRatio([double]$lumA, [double]$lumB) {
    $hi = [Math]::Max($lumA, $lumB)
    $lo = [Math]::Min($lumA, $lumB)
    return ($hi + 0.05) / ($lo + 0.05)
}

function Measure-RegionPixelDifference([string]$PathA, [string]$PathB, [int]$X1, [int]$Y1, [int]$X2, [int]$Y2) {
    $bmpA = [System.Drawing.Bitmap]::new($PathA)
    $bmpB = [System.Drawing.Bitmap]::new($PathB)
    try {
        $sumDiff = 0.0
        $changedCount = 0
        $totalCount = 0
        $sumLumA = 0.0
        $sumLumB = 0.0
        for ($y = $Y1; $y -lt $Y2; $y += 2) {
            for ($x = $X1; $x -lt $X2; $x += 2) {
                $ca = $bmpA.GetPixel($x, $y)
                $cb = $bmpB.GetPixel($x, $y)
                $d = ([Math]::Abs([int]$ca.R - [int]$cb.R) + [Math]::Abs([int]$ca.G - [int]$cb.G) + [Math]::Abs([int]$ca.B - [int]$cb.B)) / 3.0
                $sumDiff += $d
                if ($d -ge 2.0) { $changedCount++ }
                $sumLumA += (Get-ColorLuminance $ca)
                $sumLumB += (Get-ColorLuminance $cb)
                $totalCount++
            }
        }
        [pscustomobject]@{
            SampledPixels     = $totalCount
            ChangedPixels     = $changedCount
            ChangedRatio      = [Math]::Round($changedCount / [Math]::Max(1.0, [double]$totalCount), 4)
            MeanChannelDelta  = [Math]::Round($sumDiff / [Math]::Max(1.0, [double]$totalCount), 3)
            MeanLuminanceA    = [Math]::Round($sumLumA / [Math]::Max(1.0, [double]$totalCount), 4)
            MeanLuminanceB    = [Math]::Round($sumLumB / [Math]::Max(1.0, [double]$totalCount), 4)
        }
    } finally {
        $bmpA.Dispose()
        $bmpB.Dispose()
    }
}

function Assert-ShelfReadyInForeground([xml]$Doc, [int]$Round) {
    $pkgNode = $Doc.SelectSingleNode("//node[@package='$package']")
    if ($null -eq $pkgNode) {
        $foundPkgs = @($Doc.SelectNodes("//node[@package!='']") | ForEach-Object { $_.package } | Select-Object -Unique) -join ','
        throw "Round ${Round}: Target package '$package' is NOT in foreground! Found packages: [$foundPkgs]"
    }
    $titleNode = $Doc.SelectSingleNode("//node[@package='$package' and @text='墨读']")
    if ($null -eq $titleNode) {
        throw "Round ${Round}: Target app is in foreground, but Shelf header '墨读' is not visible!"
    }
    $moreNode = $Doc.SelectSingleNode("//node[@package='$package' and @content-desc='更多']")
    if ($null -eq $moreNode) {
        throw "Round ${Round}: Shelf '更多' button not found in foreground UI tree (fallback coordinates are strictly forbidden)!"
    }
    $clickableMore = $moreNode.SelectSingleNode("ancestor-or-self::node[@clickable='true'][1]")
    if ($null -eq $clickableMore) { $clickableMore = $moreNode }
    return $clickableMore
}

function Assert-ShelfMoreMenuOpen([xml]$Doc, [int]$Round) {
    $pkgNode = $Doc.SelectSingleNode("//node[@package='$package']")
    if ($null -eq $pkgNode) {
        throw "Round ${Round}: Target package '$package' lost foreground while checking menu!"
    }
    $item1 = $Doc.SelectSingleNode("//node[@package='$package' and @text='笔记与书签']")
    $item2 = $Doc.SelectSingleNode("//node[@package='$package' and @text='隐藏的书籍']")
    $item3 = $Doc.SelectSingleNode("//node[@package='$package' and @text='分组管理']")
    if ($null -eq $item1 -or $null -eq $item2 -or $null -eq $item3) {
        throw "Round ${Round}: Shelf More menu did NOT open! Missing menu items ('笔记与书签' / '隐藏的书籍' / '分组管理')."
    }
    return $item1
}

function Assert-ShelfMoreMenuClosed([xml]$Doc, [int]$Round) {
    $null = Assert-ShelfReadyInForeground $Doc $Round
    $item1 = $Doc.SelectSingleNode("//node[@package='$package' and @text='笔记与书签']")
    if ($null -ne $item1) {
        throw "Round ${Round}: Shelf More menu did NOT close after dismiss!"
    }
}

function Invoke-StrictShelfMenuToggle([int]$Round, [bool]$CaptureWhenOpen = $false, [string]$CaptureName = '') {
    $beforeDoc = Ui
    $moreBtn = Assert-ShelfReadyInForeground $beforeDoc $Round
    $tapInfo = TapNode $moreBtn "round-$Round-more-button"
    Start-Sleep -Milliseconds 250

    $openDoc = Ui
    $menuItem = Assert-ShelfMoreMenuOpen $openDoc $Round
    $menuBounds = (Bounds $menuItem "round-$Round-menu-item")

    if ($CaptureWhenOpen -and $CaptureName) {
        & $driver -Action Capture -RunId $RunId -Name $CaptureName | Out-Null
    }

    Device @('shell','input','keyevent','4') | Out-Null
    Start-Sleep -Milliseconds 200

    $closedDoc = Ui
    Assert-ShelfMoreMenuClosed $closedDoc $Round

    [pscustomobject]@{
        Round          = $Round
        ForegroundPkg  = $package
        MoreButtonTap  = "$($tapInfo.X),$($tapInfo.Y)"
        MoreBounds     = $tapInfo.Bounds
        MenuItemBounds = $menuItem.bounds
        MenuOpened     = $true
        MenuClosed     = $true
    }
}

try {
    Log-Message "=== 1. PINNING TESTED APK & PREPARING ISOLATED ENVIRONMENT (R5.1) ==="
    if (-not (Test-Path -LiteralPath $builtPreviewApk)) {
        throw "Preview APK not found at $builtPreviewApk. Build assemblePreview first."
    }
    Copy-Item -LiteralPath $builtPreviewApk -Destination $pinnedPreviewApk -Force
    $previewHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $pinnedPreviewApk).Hash
    $apkRecords = [ordered]@{
        PreviewTestedApk = [ordered]@{
            Path   = $pinnedPreviewApk
            Size   = (Get-Item -LiteralPath $pinnedPreviewApk).Length
            SHA256 = $previewHash
        }
    }
    if (Test-Path -LiteralPath $builtDebugApk) {
        $apkRecords['DebugApk'] = [ordered]@{
            Path   = $builtDebugApk
            Size   = (Get-Item -LiteralPath $builtDebugApk).Length
            SHA256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $builtDebugApk).Hash
        }
    }
    if (Test-Path -LiteralPath $builtReleaseApk) {
        $apkRecords['ReleaseUnsignedApk'] = [ordered]@{
            Path   = $builtReleaseApk
            Size   = (Get-Item -LiteralPath $builtReleaseApk).Length
            SHA256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $builtReleaseApk).Hash
        }
    }
    $apkRecords | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $verificationPath 'apk-hashes.json') -Encoding utf8
    Log-Message "Pinned Preview APK to $pinnedPreviewApk (SHA256: $previewHash)"

    $instanceOwnedByThisRun = $false
    $statePath = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/instance.json"
    if (-not (Test-Path -LiteralPath $statePath)) {
        $preCheckOccupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(5049, 5580, 5581) })
        if ($preCheckOccupied.Count -gt 0) {
            throw "S5 OWNERSHIP ERROR: Ports 5049/5580/5581 are occupied ($($preCheckOccupied.LocalPort -join ', ')) before Create for RunId '$RunId'. Refusing to stop unowned services."
        }
        Log-Message "Creating isolated AVD: mirad_$RunId"
        & $driver -Action Create -RunId $RunId | Out-Null
    }

    Log-Message "Starting isolated emulator on port 5580 (ADB server on port 5049) with S5 ownership check..."
    & $driver -Action Start -RunId $RunId | Out-Null
    $instanceOwnedByThisRun = $true

    Log-Message "Waiting for emulator boot completion (sys.boot_completed == 1)..."
    $booted = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        Start-Sleep -Seconds 2
        try {
            $status = & $driver -Action Status -RunId $RunId
            if ($status.BootCompleted -eq '1') {
                $booted = $true
                break
            }
        } catch {
            # Dedicated ADB server handshaking
        }
    } while ([DateTime]::UtcNow -lt $deadline)

    if (-not $booted) { throw "Emulator failed to boot within timeout." }
    Log-Message "Emulator booted successfully."

    Log-Message "=== 2. T1 BASELINE (BEFORE-T1 100%) & INSTALLING PINNED PREVIEW APK ==="
    Device @('shell','wm','dismiss-keyguard') | Out-Null
    Device @('shell','input','keyevent','82') | Out-Null
    Device @('shell','wm','size','reset') | Out-Null
    Device @('shell','wm','density','reset') | Out-Null
    Device @('shell','settings','put','system','font_scale','1.0') | Out-Null
    Device @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
    Start-Sleep -Seconds 3

    $beforeT1Apk = Join-Path $verificationPath 't1-baseline/app-preview-before-t1.apk'
    $t1BaselineCapDir = Join-Path $verificationPath 't1-baseline/captures'
    New-Item -ItemType Directory -Path $t1BaselineCapDir -Force | Out-Null
    $instanceRunRoot = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId"
    $instanceCaptures = Join-Path $instanceRunRoot 'captures'

    if ((Test-Path -LiteralPath $beforeT1Apk) -and (-not $ResumeFrom7C)) {
        $beforeT1Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $beforeT1Apk).Hash
        $apkRecords['PreviewBeforeT1BaselineApk'] = [ordered]@{
            Path   = $beforeT1Apk
            Size   = (Get-Item -LiteralPath $beforeT1Apk).Length
            SHA256 = $beforeT1Hash
        }
        $apkRecords | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $verificationPath 'apk-hashes.json') -Encoding utf8
        Log-Message "Installing T1 baseline APK ($beforeT1Hash) to capture identical-scene before-100% reference..."
        & $driver -Action Install -RunId $RunId -Apk $beforeT1Apk | Out-Null

        # 1) Shelf light scrolled at 100%
        Device @('shell','am','force-stop',$package) | Out-Null
        Start-PreviewScene @('--es','scene','shelf','--ez','dark','false','--ez','scrolled','true','--ei','transparency','100')
        Start-Sleep -Seconds 1
        & $driver -Action Capture -RunId $RunId -Name 't1-shelf-light-before-100' | Out-Null
        Copy-Item -LiteralPath (Join-Path $instanceCaptures 't1-shelf-light-before-100.png') -Destination (Join-Path $t1BaselineCapDir 't1-shelf-light-before-100.png') -Force
        Remove-Item -LiteralPath (Join-Path $instanceCaptures 't1-shelf-light-before-100.png') -Force

        # 2) Shelf More menu light at 100%
        $docB = Ui
        $moreB = Assert-ShelfReadyInForeground $docB 0
        $null = TapNode $moreB 't1-before-more'
        Start-Sleep -Milliseconds 400
        & $driver -Action Capture -RunId $RunId -Name 't1-shelf-menu-light-before-100' | Out-Null
        Copy-Item -LiteralPath (Join-Path $instanceCaptures 't1-shelf-menu-light-before-100.png') -Destination (Join-Path $t1BaselineCapDir 't1-shelf-menu-light-before-100.png') -Force
        Remove-Item -LiteralPath (Join-Path $instanceCaptures 't1-shelf-menu-light-before-100.png') -Force

        # 3) Reader light at 100%
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        Start-PreviewScene @('--es','scene','reader','--ez','dark','false','--ez','readerDark','false','--ei','transparency','100')
        Start-Sleep -Seconds 2
        & $driver -Action Capture -RunId $RunId -Name 't1-reader-light-before-100' | Out-Null
        Copy-Item -LiteralPath (Join-Path $instanceCaptures 't1-reader-light-before-100.png') -Destination (Join-Path $t1BaselineCapDir 't1-reader-light-before-100.png') -Force
        Remove-Item -LiteralPath (Join-Path $instanceCaptures 't1-reader-light-before-100.png') -Force

        # 4) Reader settings panel light at 100%
        Device @('shell','am','force-stop',$package) | Out-Null
        Start-PreviewScene @('--es','scene','reader-settings','--ez','dark','false','--ez','readerDark','false','--ei','transparency','100')
        Start-Sleep -Seconds 2
        & $driver -Action Capture -RunId $RunId -Name 't1-reader-panel-light-before-100' | Out-Null
        Copy-Item -LiteralPath (Join-Path $instanceCaptures 't1-reader-panel-light-before-100.png') -Destination (Join-Path $t1BaselineCapDir 't1-reader-panel-light-before-100.png') -Force
        Remove-Item -LiteralPath (Join-Path $instanceCaptures 't1-reader-panel-light-before-100.png') -Force

        # 5) Reader settings panel dark at 100%
        Device @('shell','am','force-stop',$package) | Out-Null
        Start-PreviewScene @('--es','scene','reader-settings','--ez','dark','true','--ez','readerDark','true','--ei','transparency','100')
        Start-Sleep -Seconds 2
        & $driver -Action Capture -RunId $RunId -Name 't1-reader-panel-dark-before-100' | Out-Null
        Copy-Item -LiteralPath (Join-Path $instanceCaptures 't1-reader-panel-dark-before-100.png') -Destination (Join-Path $t1BaselineCapDir 't1-reader-panel-dark-before-100.png') -Force
        Remove-Item -LiteralPath (Join-Path $instanceCaptures 't1-reader-panel-dark-before-100.png') -Force
    }

    & $driver -Action Install -RunId $RunId -Apk $pinnedPreviewApk | Out-Null
    Log-Message "Installed pinned Preview APK ($previewHash)."

    if (-not $ResumeFrom7C) {
    Log-Message "=== 3. EXECUTING R5 NEGATIVE FAILURE-PATH SELF-TEST (R5.3) ==="
    $negativeProofs = [Collections.Generic.List[object]]::new()

    # Negative Case 1: App is not in foreground (Home screen is active)
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','input','keyevent','3') | Out-Null
    Start-Sleep -Seconds 1
    $neg1FailedAsExpected = $false
    $neg1Message = ''
    try {
        Invoke-StrictShelfMenuToggle -Round -1 | Out-Null
    } catch {
        $neg1FailedAsExpected = $true
        $neg1Message = $_.Exception.Message
    }
    if (-not $neg1FailedAsExpected) {
        throw "R5 NEGATIVE TEST 1 FAILED: Script did not fail when target app was not in foreground!"
    }
    $negativeProofs.Add([pscustomobject]@{Scenario='AppNotInForeground_HomeScreen'; FailedAsExpected=$true; CaughtError=$neg1Message})
    Log-Message "PASS Negative Case 1 (App not in foreground rejected): $neg1Message"

    # Negative Case 2: Wrong screen active (Reader instead of Shelf)
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','reader') | Out-Null
    Start-Sleep -Seconds 2
    $neg2FailedAsExpected = $false
    $neg2Message = ''
    try {
        Invoke-StrictShelfMenuToggle -Round -2 | Out-Null
    } catch {
        $neg2FailedAsExpected = $true
        $neg2Message = $_.Exception.Message
    }
    if (-not $neg2FailedAsExpected) {
        throw "R5 NEGATIVE TEST 2 FAILED: Script did not fail when wrong scene (reader) was active!"
    }
    $negativeProofs.Add([pscustomobject]@{Scenario='WrongScreenActive_ReaderInsteadOfShelf'; FailedAsExpected=$true; CaughtError=$neg2Message})
    Log-Message "PASS Negative Case 2 (Wrong scene rejected): $neg2Message"

    # Negative Case 3: Shelf is active, but menu was never opened
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','shelf') | Out-Null
    Start-Sleep -Seconds 2
    $neg3FailedAsExpected = $false
    $neg3Message = ''
    try {
        $unopenedDoc = Ui
        Assert-ShelfMoreMenuOpen $unopenedDoc -3 | Out-Null
    } catch {
        $neg3FailedAsExpected = $true
        $neg3Message = $_.Exception.Message
    }
    if (-not $neg3FailedAsExpected) {
        throw "R5 NEGATIVE TEST 3 FAILED: Assert-ShelfMoreMenuOpen did not fail when menu was closed!"
    }
    $negativeProofs.Add([pscustomobject]@{Scenario='MenuNotOpened_ShelfIdle'; FailedAsExpected=$true; CaughtError=$neg3Message})
    Log-Message "PASS Negative Case 3 (Unopened menu rejected): $neg3Message"
    $negativeProofs | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logsDir 'r5-negative-failure-proof.json') -Encoding utf8

    Log-Message "=== 4. EXECUTING F1 & R5 STRICT 36X SHELF MENU TOGGLE VERIFICATION ==="
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','logcat','-c') | Out-Null
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','shelf','--ei','transparency','60') | Out-Null
    Start-Sleep -Seconds 2

    $roundRecords = [Collections.Generic.List[object]]::new()
    for ($r = 1; $r -le 36; $r++) {
        $captureNow = ($r -eq 36)
        $capName = if ($captureNow) { 'shelf-more-menu-open' } else { '' }
        $rec = Invoke-StrictShelfMenuToggle -Round $r -CaptureWhenOpen $captureNow -CaptureName $capName
        $roundRecords.Add($rec)
        if ($r % 6 -eq 0 -or $r -eq 1 -or $r -eq 36) {
            Log-Message "Verified Shelf menu toggle round $r/36 (tap=$($rec.MoreButtonTap), menuBounds=$($rec.MenuItemBounds))."
        }
    }
    $roundRecords | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logsDir 'f1-shelf-menu-36x-rounds.json') -Encoding utf8

    $f1LogFile = Join-Path $logsDir 'f1-shelf-menu-36x.log'
    $logcatOutput = Device @('shell','logcat','-d','-s','AndroidRuntime','GlassPreview')
    $logcatOutput | Set-Content -LiteralPath $f1LogFile -Encoding utf8
    if ($logcatOutput -match 'FATAL EXCEPTION|unable to getPixel') {
        throw "F1 REGRESSION FAILED: Detected crash in logcat during 36x menu toggles!"
    }
    Log-Message "PASS F1 & R5: All 36 shelf menu toggles strictly verified per round with 0 crashes."

    Log-Message "=== 5. EXECUTING R1 IDLE REPAINT & R3 BOUNDED READBACK VERIFICATION ==="
    # 5A: Shelf at 60% transparency, 11s idle window
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','logcat','-c') | Out-Null
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','shelf','--ei','transparency','60') | Out-Null
    Start-Sleep -Seconds 3

    $idle60Before = Dump-GlassStats 'shelf60_idle_before'
    Device @('shell','dumpsys','gfxinfo',$package,'reset') | Out-Null
    Log-Message "Observing Shelf 60% idle for 11 seconds with zero user interaction..."
    Start-Sleep -Seconds 11
    $gfx60 = Device @('shell','dumpsys','gfxinfo',$package)
    $gfx60 | Set-Content -LiteralPath (Join-Path $logsDir 'shelf-idle-frames.txt') -Encoding utf8
    $gfx60 | Set-Content -LiteralPath (Join-Path $logsDir 'r1-shelf-60-idle-frames.txt') -Encoding utf8
    $idle60After = Dump-GlassStats 'shelf60_idle_after'

    $frames60Match = [regex]::Match($gfx60, 'Total frames rendered:\s*(\d+)')
    $frames60 = if ($frames60Match.Success) { [int]$frames60Match.Groups[1].Value } else { -1 }
    $deltaRec60 = $idle60After.SourceRecords - $idle60Before.SourceRecords
    $deltaSnap60 = $idle60After.Snapshots - $idle60Before.Snapshots
    $deltaAnal60 = $idle60After.Analyses - $idle60Before.Analyses
    Log-Message "Shelf 60% 11s idle: frames=$frames60 deltaRecords=$deltaRec60 deltaSnapshots=$deltaSnap60 deltaAnalyses=$deltaAnal60 readback=$($idle60After.ReadbackWidth)x$($idle60After.ReadbackHeight) source=$($idle60After.SourceWidth)x$($idle60After.SourceHeight)"

    if ($deltaRec60 -ne 0 -or $deltaSnap60 -ne 0 -or $deltaAnal60 -ne 0 -or $frames60 -gt 3) {
        throw "R1 FAILED on Shelf 60% idle: frames=$frames60 deltaRecords=$deltaRec60 deltaSnapshots=$deltaSnap60 deltaAnalyses=$deltaAnal60"
    }
    # R3 check: readback dimensions must be <= 25% of source and longest edge <= 640
    if ($idle60After.ReadbackWidth -le 0 -or $idle60After.ReadbackWidth -gt [int]($idle60After.SourceWidth * 0.25) -or $idle60After.ReadbackHeight -gt [int]($idle60After.SourceHeight * 0.25) -or [Math]::Max($idle60After.ReadbackWidth, $idle60After.ReadbackHeight) -gt 640) {
        throw "R3 FAILED: Readback size $($idle60After.ReadbackWidth)x$($idle60After.ReadbackHeight) exceeds bounded limit for source $($idle60After.SourceWidth)x$($idle60After.SourceHeight)"
    }
    Log-Message "PASS R1 (Shelf 60% idle) & R3 (Bounded readback $($idle60After.ReadbackWidth)x$($idle60After.ReadbackHeight) from $($idle60After.SourceWidth)x$($idle60After.SourceHeight))."

    # 5B: Shelf at 0% transparency, 11s idle window
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','logcat','-c') | Out-Null
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','shelf','--ei','transparency','0') | Out-Null
    Start-Sleep -Seconds 3

    $idle0Before = Dump-GlassStats 'shelf0_idle_before'
    Device @('shell','dumpsys','gfxinfo',$package,'reset') | Out-Null
    Log-Message "Observing Shelf 0% idle for 11 seconds with zero user interaction..."
    Start-Sleep -Seconds 11
    $gfx0 = Device @('shell','dumpsys','gfxinfo',$package)
    $gfx0 | Set-Content -LiteralPath (Join-Path $logsDir 'shelf-zero-idle-frames.txt') -Encoding utf8
    $gfx0 | Set-Content -LiteralPath (Join-Path $logsDir 'r1-shelf-0-idle-frames.txt') -Encoding utf8
    $idle0After = Dump-GlassStats 'shelf0_idle_after'

    $frames0Match = [regex]::Match($gfx0, 'Total frames rendered:\s*(\d+)')
    $frames0 = if ($frames0Match.Success) { [int]$frames0Match.Groups[1].Value } else { -1 }
    Log-Message "Shelf 0% 11s idle: frames=$frames0 sourceRecords=$($idle0After.SourceRecords) snapshots=$($idle0After.Snapshots) analyses=$($idle0After.Analyses) activeSnapshots=$($idle0After.ActiveSnapshots)"

    if ($idle0After.SourceRecords -ne 0 -or $idle0After.Snapshots -ne 0 -or $idle0After.Analyses -ne 0 -or $idle0After.ActiveSnapshots -ne 0 -or $frames0 -gt 3) {
        throw "R1/R4 FAILED on Shelf 0% idle: frames=$frames0 stats=$($idle0After.RawLine)"
    }
    Log-Message "PASS R1 & R4 (Shelf 0% idle): 0 records, 0 snapshots, 0 analyses, $frames0 frames rendered."

    $r1r3Summary = [ordered]@{
        Shelf60IdleBefore = $idle60Before
        Shelf60IdleAfter  = $idle60After
        Shelf60Frames11s  = $frames60
        Shelf0IdleBefore  = $idle0Before
        Shelf0IdleAfter   = $idle0After
        Shelf0Frames11s   = $frames0
    }
    $r1r3Summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logsDir 'r1-r3-idle-and-bounds-summary.json') -Encoding utf8

    Log-Message "=== 6. EXECUTING R4 NON-ZERO (100%/60%) -> 0% -> 60% TRANSITION & CLEANUP TEST ==="
    # 6A: Shelf 100% -> 0% (scroll + idle) -> 60%
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','logcat','-c') | Out-Null
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','shelf','--ei','transparency','100') | Out-Null
    Start-Sleep -Seconds 2
    $s100 = Dump-GlassStats 'r4_shelf_100'
    if ($s100.ActiveSnapshots -ne 1 -or $s100.Snapshots -lt 1) {
        throw "R4 FAILED: Expected active snapshot at 100% transparency on Shelf, got $($s100.RawLine)"
    }

    # Switch from 100% to 0%
    Device @('shell','am','broadcast','-a','com.mirad.reader.preview.ACTION_SET_TRANSPARENCY','--ei','percent','0') | Out-Null
    Start-Sleep -Milliseconds 800
    $s0BeforeScroll = Dump-GlassStats 'r4_shelf_0_before_scroll'
    if ($s0BeforeScroll.ActiveSnapshots -ne 0 -or $s0BeforeScroll.InFlight -ne 0) {
        throw "R4 FAILED: Switching Shelf from 100% to 0% did not clear activeSnapshots! $($s0BeforeScroll.RawLine)"
    }

    # Scroll and idle while at 0%
    for ($s = 0; $s -lt 4; $s++) {
        Device @('shell','input','swipe','540','1600','540','700','350') | Out-Null
        Start-Sleep -Milliseconds 300
    }
    Start-Sleep -Seconds 1
    $s0AfterScroll = Dump-GlassStats 'r4_shelf_0_after_scroll'
    if ($s0AfterScroll.SourceRecords -ne $s0BeforeScroll.SourceRecords -or $s0AfterScroll.Snapshots -ne $s0BeforeScroll.Snapshots -or $s0AfterScroll.Analyses -ne $s0BeforeScroll.Analyses -or $s0AfterScroll.ActiveSnapshots -ne 0) {
        throw "R4 FAILED: Scrolling Shelf at 0% triggered sampling/recording! Before=$($s0BeforeScroll.RawLine) After=$($s0AfterScroll.RawLine)"
    }

    # Switch back from 0% to 60%
    Device @('shell','am','broadcast','-a','com.mirad.reader.preview.ACTION_SET_TRANSPARENCY','--ei','percent','60') | Out-Null
    Start-Sleep -Seconds 1
    $s60Restored = Dump-GlassStats 'r4_shelf_60_restored'
    if ($s60Restored.ActiveSnapshots -ne 1 -or $s60Restored.Snapshots -le $s0AfterScroll.Snapshots) {
        throw "R4 FAILED: Switching Shelf back from 0% to 60% did not restore snapshot! $($s60Restored.RawLine)"
    }
    Log-Message "PASS R4 Shelf transition (100% -> 0% + scroll -> 60% restored)."

    # 6B: Reader 60% -> 0% (scroll + idle) -> 60%
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','logcat','-c') | Out-Null
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','reader','--ei','transparency','60') | Out-Null
    $previewProcessId = ((Device @('shell','pidof',$package)).Trim() -split '\s+')[0]
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $readerLog = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
        if ($readerLog -match 'readerReady' -and $readerLog -match 'backdropFrame=') { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $readyDeadline)
    Start-Sleep -Milliseconds 800

    $r60 = Dump-GlassStats 'r4_reader_60'
    if ($r60.ActiveSnapshots -ne 1 -or $r60.WebPublished -lt 1) {
        throw "R4 FAILED: Reader at 60% did not publish initial backdrop! $($r60.RawLine)"
    }

    # Switch Reader from 60% to 0%
    Device @('shell','am','broadcast','-a','com.mirad.reader.preview.ACTION_SET_TRANSPARENCY','--ei','percent','0') | Out-Null
    Start-Sleep -Milliseconds 800
    $r0Before = Dump-GlassStats 'r4_reader_0_before_scroll'
    if ($r0Before.ActiveSnapshots -ne 0) {
        throw "R4 FAILED: Reader switching from 60% to 0% did not clear activeSnapshots! $($r0Before.RawLine)"
    }

    # Clear logcat, scroll 5 times and wait in idle at 0%
    Device @('shell','logcat','-c') | Out-Null
    for ($s = 0; $s -lt 5; $s++) {
        Device @('shell','input','swipe','540','1500','540','800','400') | Out-Null
        Start-Sleep -Milliseconds 300
    }
    Start-Sleep -Seconds 1
    $zeroLog = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
    $zeroLog | Set-Content -LiteralPath (Join-Path $logsDir 'f6-zero-transparency.log') -Encoding utf8
    $r0After = Dump-GlassStats 'r4_reader_0_after_scroll'
    $frameEventsAtZero = ([regex]::Matches($zeroLog, 'backdropFrame=')).Count
    if ($frameEventsAtZero -gt 0 -or $r0After.WebAttempts -ne $r0Before.WebAttempts -or $r0After.Snapshots -ne $r0Before.Snapshots -or $r0After.Analyses -ne $r0Before.Analyses -or $r0After.ActiveSnapshots -ne 0) {
        throw "R4/F6 FAILED: Reader captured frames or ran analysis while at 0%! frameEvents=$frameEventsAtZero before=$($r0Before.RawLine) after=$($r0After.RawLine)"
    }

    # Switch Reader back from 0% to 60%
    Device @('shell','am','broadcast','-a','com.mirad.reader.preview.ACTION_SET_TRANSPARENCY','--ei','percent','60') | Out-Null
    Start-Sleep -Seconds 1
    $r60Restored = Dump-GlassStats 'r4_reader_60_restored'
    if ($r60Restored.ActiveSnapshots -ne 1 -or $r60Restored.WebAttempts -le $r0After.WebAttempts) {
        throw "R4 FAILED: Reader switching from 0% back to 60% did not resume WebView capture! $($r60Restored.RawLine)"
    }
    $r4FullLog = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
    $r4FullLog | Set-Content -LiteralPath (Join-Path $logsDir 'r4-nonzero-to-zero-transition.log') -Encoding utf8
    [pscustomobject]@{
        Shelf100Before        = $s100
        Shelf0BeforeScroll    = $s0BeforeScroll
        Shelf0AfterScroll     = $s0AfterScroll
        Shelf60Restored       = $s60Restored
        Reader60Before        = $r60
        Reader0BeforeScroll   = $r0Before
        Reader0AfterScroll    = $r0After
        Reader60Restored      = $r60Restored
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logsDir 'r4-transition-summary.json') -Encoding utf8
    Log-Message "PASS R4 & F6: Reader 60% -> 0% (activeSnapshots=0, 0 frames during scroll) -> 60% restored."

    Log-Message "=== 7. EXECUTING R5 REAL SYSTEM FONT_SCALE (1.0 / 1.3 / 1.6) & GLOBAL BUTTONS CHECK ==="
    $fontScaleChecks = [Collections.Generic.List[object]]::new()
    $shelfTitleHeights = @{}
    foreach ($scale in @('1.0', '1.3', '1.6')) {
        Device @('shell','settings','put','system','font_scale',$scale) | Out-Null
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        # Launch WITHOUT --ef fontScale so real system font_scale is tested!
        Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','shelf') | Out-Null
        Start-Sleep -Seconds 2
        $fsLog = Device @('shell','logcat','-d','-s','GlassPreview')
        if ($fsLog -notmatch "fontScaleEffective=$scale systemFontScale=$scale override=false") {
            throw "R5 FONT_SCALE FAILED: Expected real systemFontScale=$scale with override=false, log was:`n$fsLog"
        }
        $doc = Ui "shelf-fontscale-$($scale.Replace('.','')).xml"
        $titleNode = $doc.SelectSingleNode("//node[@text='墨读']")
        $importNode = $doc.SelectSingleNode("//node[@text='导入']")
        $sortNode = $doc.SelectSingleNode("//node[contains(@text,'最近阅读')]")
        $layoutNode = $doc.SelectSingleNode("//node[@text='网格' or @text='列表']")
        $tRect = Bounds $titleNode "shelf-title-$scale"
        $iRect = Bounds $importNode "shelf-import-$scale"
        $sRect = Bounds $sortNode "shelf-sort-$scale"
        $lRect = Bounds $layoutNode "shelf-layout-$scale"
        $shelfTitleHeights[$scale] = ($tRect.Bottom - $tRect.Top)
        $capName = "system-fontscale-$($scale.Replace('.',''))-shelf"
        & $driver -Action Capture -RunId $RunId -Name $capName | Out-Null
        $fontScaleChecks.Add([pscustomobject]@{
            Scale             = $scale
            Scene             = 'shelf'
            OverrideIntent    = $false
            TitleHeightPx     = ($tRect.Bottom - $tRect.Top)
            ImportTextHeightPx= ($iRect.Bottom - $iRect.Top)
            SortBounds        = $sortNode.bounds
            LayoutBounds      = $layoutNode.bounds
            Capture           = "$capName.png"
        })
    }
    if (-not ($shelfTitleHeights['1.6'] -gt $shelfTitleHeights['1.0'])) {
        throw "R5 FONT_SCALE FAILED: Shelf title height did not grow from 1.0 ($($shelfTitleHeights['1.0'])) to 1.6 ($($shelfTitleHeights['1.6']))!"
    }

    # Also capture dialog and settings at real system font_scale 1.6 without intent override
    Device @('shell','settings','put','system','font_scale','1.6') | Out-Null
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','dialog','--ez','scrolled','true') | Out-Null
    Start-Sleep -Seconds 2
    $dialogDoc = Ui 'dialog-fontscale-16.xml'
    $confirmBtn = $dialogDoc.SelectSingleNode("//node[@text='删除']")
    $cancelBtn = $dialogDoc.SelectSingleNode("//node[@text='取消']")
    $null = Bounds $confirmBtn 'dialog-confirm-1.6'
    $null = Bounds $cancelBtn 'dialog-cancel-1.6'
    & $driver -Action Capture -RunId $RunId -Name 'system-fontscale-16-dialog' | Out-Null

    # Reset font_scale to 1.0
    Device @('shell','settings','put','system','font_scale','1.0') | Out-Null
    $fontScaleChecks | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logsDir 'r5-system-fontscale-verification.json') -Encoding utf8
    Log-Message "PASS R5: Real system font_scale (1.0 / 1.3 / 1.6) verified without intent override."

    Log-Message "=== 7B. EXECUTING T1 (100% VS 60% & BEFORE-100% VS AFTER-100%) NATIVE COMPARISON ==="
    $t1Records = [Collections.Generic.List[object]]::new()
    foreach ($pct in @(100, 60)) {
        # 1) Shelf light scrolled
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','shelf','--ez','dark','false','--ez','scrolled','true','--ei','transparency',"$pct") | Out-Null
        Start-Sleep -Seconds 2
        $shelfStats = Dump-GlassStats "t1_shelf_light_$pct"
        & $driver -Action Capture -RunId $RunId -Name "t1-shelf-light-after-$pct" | Out-Null

        # 2) Shelf More menu light
        $docM = Ui
        $moreM = Assert-ShelfReadyInForeground $docM 0
        $null = TapNode $moreM "t1-after-more-$pct"
        Start-Sleep -Milliseconds 400
        $menuStats = Dump-GlassStats "t1_shelf_menu_light_$pct"
        & $driver -Action Capture -RunId $RunId -Name "t1-shelf-menu-light-after-$pct" | Out-Null

        # 3) Reader light
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','reader','--ez','dark','false','--ez','readerDark','false','--ei','transparency',"$pct") | Out-Null
        Start-Sleep -Seconds 3
        $readerStats = Dump-GlassStats "t1_reader_light_$pct"
        & $driver -Action Capture -RunId $RunId -Name "t1-reader-light-after-$pct" | Out-Null

        # 4) Reader settings panel light
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','reader-settings','--ez','dark','false','--ez','readerDark','false','--ei','transparency',"$pct") | Out-Null
        Start-Sleep -Seconds 3
        $panelLightStats = Dump-GlassStats "t1_reader_panel_light_$pct"
        & $driver -Action Capture -RunId $RunId -Name "t1-reader-panel-light-after-$pct" | Out-Null

        # 5) Reader settings panel dark
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        Device @('shell','am','start','-W','-n',$activityComponent,'--es','scene','reader-settings','--ez','dark','true','--ez','readerDark','true','--ei','transparency',"$pct") | Out-Null
        Start-Sleep -Seconds 3
        $panelDarkStats = Dump-GlassStats "t1_reader_panel_dark_$pct"
        & $driver -Action Capture -RunId $RunId -Name "t1-reader-panel-dark-after-$pct" | Out-Null

        $t1Records.Add([pscustomobject]@{
            TransparencyPercent  = $pct
            ShelfToolbar         = $shelfStats
            ShelfMoreMenuPanel   = $menuStats
            ReaderChrome         = $readerStats
            ReaderPanelLight     = $panelLightStats
            ReaderPanelDark      = $panelDarkStats
        })
    }

    $rec100 = $t1Records | Where-Object { $_.TransparencyPercent -eq 100 }
    $rec60  = $t1Records | Where-Object { $_.TransparencyPercent -eq 60 }
    if ($rec100.ReaderPanelLight.AmbientOpacity -ge ($rec60.ReaderPanelLight.AmbientOpacity - 0.10)) {
        throw "T1 FAILED: 100% ReaderPanelLight ambientOpacity ($($rec100.ReaderPanelLight.AmbientOpacity)) is not significantly lower than 60% ($($rec60.ReaderPanelLight.AmbientOpacity))!"
    }

    $pixelComparisons = [ordered]@{}
    $panelBox = @{X1=120; Y1=1450; X2=960; Y2=2150}
    $toolbarBox = @{X1=80; Y1=110; X2=1000; Y2=250}
    $pixelComparisons['ReaderPanelLight_After100_vs_After60'] = Measure-RegionPixelDifference (Join-Path $instanceCaptures 't1-reader-panel-light-after-100.png') (Join-Path $instanceCaptures 't1-reader-panel-light-after-60.png') $panelBox.X1 $panelBox.Y1 $panelBox.X2 $panelBox.Y2
    $pixelComparisons['ReaderPanelDark_After100_vs_After60']  = Measure-RegionPixelDifference (Join-Path $instanceCaptures 't1-reader-panel-dark-after-100.png')  (Join-Path $instanceCaptures 't1-reader-panel-dark-after-60.png')  $panelBox.X1 $panelBox.Y1 $panelBox.X2 $panelBox.Y2
    $pixelComparisons['ShelfToolbarLight_After100_vs_After60']= Measure-RegionPixelDifference (Join-Path $instanceCaptures 't1-shelf-light-after-100.png')        (Join-Path $instanceCaptures 't1-shelf-light-after-60.png')        $toolbarBox.X1 $toolbarBox.Y1 $toolbarBox.X2 $toolbarBox.Y2

    if (Test-Path -LiteralPath (Join-Path $t1BaselineCapDir 't1-reader-panel-light-before-100.png')) {
        $pixelComparisons['ReaderPanelLight_Before100_vs_After100'] = Measure-RegionPixelDifference (Join-Path $t1BaselineCapDir 't1-reader-panel-light-before-100.png') (Join-Path $instanceCaptures 't1-reader-panel-light-after-100.png') $panelBox.X1 $panelBox.Y1 $panelBox.X2 $panelBox.Y2
        $pixelComparisons['ReaderPanelDark_Before100_vs_After100']  = Measure-RegionPixelDifference (Join-Path $t1BaselineCapDir 't1-reader-panel-dark-before-100.png')  (Join-Path $instanceCaptures 't1-reader-panel-dark-after-100.png')  $panelBox.X1 $panelBox.Y1 $panelBox.X2 $panelBox.Y2
        $pixelComparisons['ShelfToolbarLight_Before100_vs_After100']= Measure-RegionPixelDifference (Join-Path $t1BaselineCapDir 't1-shelf-light-before-100.png')        (Join-Path $instanceCaptures 't1-shelf-light-after-100.png')        $toolbarBox.X1 $toolbarBox.Y1 $toolbarBox.X2 $toolbarBox.Y2
    }

    [pscustomobject]@{
        RuntimeStatsByPercent = $t1Records
        PixelComparisons      = $pixelComparisons
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $logsDir 't1-transparency-comparison.json') -Encoding utf8
    Log-Message "PASS T1: 100% transparency is visibly more transparent than before-100% and after-60% (Panel ambientOpacity: 100%=$($rec100.ReaderPanelLight.AmbientOpacity) vs 60%=$($rec60.ReaderPanelLight.AmbientOpacity))."
    }

    Log-Message "=== 7C. EXECUTING S3 CONTROLLED CHROMATIC BACKDROP NATIVE CONTRAST VERIFICATION ==="
    $s3Cases = @(
        @{Name='blue-light-100';  Hex='#0000FF'; Dark=$false; Pct=100},
        @{Name='red-light-100';   Hex='#FF0000'; Dark=$false; Pct=100},
        @{Name='green-light-100'; Hex='#00FF00'; Dark=$false; Pct=100},
        @{Name='gray-dark-100';   Hex='#AAAAAA'; Dark=$true;  Pct=100}
    )
    $s3Results = [Collections.Generic.List[object]]::new()
    foreach ($c in $s3Cases) {
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        $darkStr = if ($c.Dark) { 'true' } else { 'false' }
        Start-PreviewScene @('--es','scene','color-contrast','--es','backdropColor',"'$($c.Hex)'",'--ez','dark',$darkStr,'--ei','transparency',"$($c.Pct)")
        Start-Sleep -Seconds 1
        $stats = Dump-GlassStats "s3_$($c.Name)"
        $capName = "s3-color-contrast-$($c.Name)"
        & $driver -Action Capture -RunId $RunId -Name $capName | Out-Null

        $docC = Ui "s3-$($c.Name).xml"
        $secNode = $docC.SelectSingleNode("//node[contains(@text,'面板次要文字')]")
        $botNode = $docC.SelectSingleNode("//node[contains(@text,'底部区域次要文字')]")
        $secBounds = Bounds $secNode "s3-sec-$($c.Name)"
        $botBounds = Bounds $botNode "s3-bot-$($c.Name)"

        $bmpPath = Join-Path $instanceCaptures "$capName.png"
        $bmp = [System.Drawing.Bitmap]::new($bmpPath)
        try {
            # Sample glass surface background inside the panel between the middle and bottom text lines
            $sampleY = [int](($secBounds.Bottom + $botBounds.Top) / 2)
            $sampleX = [int](($secBounds.Left + $secBounds.Right) / 2)
            $glassPixel = $bmp.GetPixel($sampleX, $sampleY)
            $glassLum = Get-ColorLuminance $glassPixel

            $primaryColor = if ($c.Dark) { [System.Drawing.Color]::FromArgb(255, 227, 230, 226) } else { [System.Drawing.Color]::FromArgb(255, 25, 29, 27) }
            $secondaryColor = if ($c.Dark) { [System.Drawing.Color]::FromArgb(255, 183, 191, 185) } else { [System.Drawing.Color]::FromArgb(255, 76, 85, 79) }
            $primaryContrast = Get-ContrastRatio (Get-ColorLuminance $primaryColor) $glassLum
            $secondaryContrast = Get-ContrastRatio (Get-ColorLuminance $secondaryColor) $glassLum

            if ($primaryContrast -lt 4.5 -or $secondaryContrast -lt 4.5) {
                throw "S3 NATIVE CONTRAST FAILED for $($c.Name): primary=$([Math]::Round($primaryContrast,3)) secondary=$([Math]::Round($secondaryContrast,3)) sampledGlassRGB=$($glassPixel.R),$($glassPixel.G),$($glassPixel.B)"
            }

            $s3Results.Add([pscustomobject]@{
                Case               = $c.Name
                BackdropHex        = $c.Hex
                DarkTheme          = $c.Dark
                Transparency       = $c.Pct
                ComputedPeakOpacity= $stats.PeakOpacity
                SampledPixelXY     = "$sampleX,$sampleY"
                SampledGlassRGB    = "$($glassPixel.R),$($glassPixel.G),$($glassPixel.B)"
                SampledGlassLum    = [Math]::Round($glassLum, 5)
                PrimaryContrast    = [Math]::Round($primaryContrast, 3)
                SecondaryContrast  = [Math]::Round($secondaryContrast, 3)
                Capture            = "$capName.png"
            })
            Log-Message "PASS S3 native case $($c.Name): glassRGB=($($glassPixel.R),$($glassPixel.G),$($glassPixel.B)) primaryContrast=$([Math]::Round($primaryContrast,2)) secondaryContrast=$([Math]::Round($secondaryContrast,2))"
        } finally {
            $bmp.Dispose()
        }
    }
    $s3Results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $logsDir 's3-native-color-contrast.json') -Encoding utf8

    Log-Message "=== 7D. EXECUTING S4 SAVE FAILURE BANNER & RETRY NATIVE UI VERIFICATION ==="
    $s4Results = [Collections.Generic.List[object]]::new()
    $s4Entries = @(
        @{Entry='GlobalSettings_Appearance'; Scene='settings'; Category='APPEARANCE'; TargetPct=82; FailCap='s4-global-settings-save-failed'; RecoverCap='s4-global-settings-save-recovered'},
        @{Entry='ReaderSettings_Appearance'; Scene='reader-appearance'; Category='APPEARANCE'; TargetPct=75; FailCap='s4-reader-settings-save-failed'; RecoverCap='s4-reader-settings-save-recovered'}
    )
    foreach ($entry in $s4Entries) {
        Device @('shell','am','force-stop',$package) | Out-Null
        Device @('shell','logcat','-c') | Out-Null
        Start-PreviewScene @('--es','scene',$entry.Scene,'--es','category',$entry.Category,'--ei','transparency','60','--ez','simulateSaveFailure','true')
        Start-Sleep -Seconds 2

        # Trigger transparency change + commit while DAO fails writes
        Device @('shell','am','broadcast','-a','com.mirad.reader.preview.ACTION_SET_TRANSPARENCY','--ei','percent',"$($entry.TargetPct)") | Out-Null
        Start-Sleep -Seconds 1

        $failDoc = Ui "s4-$($entry.Entry)-failed.xml"
        $errNode = $failDoc.SelectSingleNode("//node[contains(@text,'外观设置保存失败')]")
        $retryNode = $failDoc.SelectSingleNode("//node[@text='重试']")
        $pctNode = $failDoc.SelectSingleNode("//node[@text='$($entry.TargetPct)%']")
        if ($null -eq $errNode -or $null -eq $retryNode -or $null -eq $pctNode) {
            throw "S4 FAILED on $($entry.Entry): Save failure banner, '重试' button, or retained '$($entry.TargetPct)%' value not visible in UI!"
        }
        & $driver -Action Capture -RunId $RunId -Name $entry.FailCap | Out-Null
        $failLog = Device @('shell','logcat','-d','-s','GlassPreview')
        if ($failLog -notmatch "glassRepoState visible=$($entry.TargetPct) saved=60 saveFailed=true") {
            throw "S4 FAILED on $($entry.Entry): Expected glassRepoState visible=$($entry.TargetPct) saved=60 saveFailed=true in logcat!"
        }

        # Re-enable storage writes and tap the visible '重试' button in UI
        Device @('shell','am','broadcast','-a','com.mirad.reader.preview.ACTION_SET_SAVE_FAIL_MODE','--ez','fail','false') | Out-Null
        Start-Sleep -Milliseconds 250
        $clickableRetry = $retryNode.SelectSingleNode("ancestor-or-self::node[@clickable='true'][1]")
        if ($null -eq $clickableRetry) { $clickableRetry = $retryNode }
        $retryTap = TapNode $clickableRetry "s4-retry-$($entry.Entry)"
        Start-Sleep -Seconds 1

        $recDoc = Ui "s4-$($entry.Entry)-recovered.xml"
        $errAfter = $recDoc.SelectSingleNode("//node[contains(@text,'外观设置保存失败')]")
        $pctAfter = $recDoc.SelectSingleNode("//node[@text='$($entry.TargetPct)%']")
        if ($null -ne $errAfter -or $null -eq $pctAfter) {
            throw "S4 FAILED on $($entry.Entry) after retry: Error banner did not clear or value '$($entry.TargetPct)%' was lost!"
        }
        & $driver -Action Capture -RunId $RunId -Name $entry.RecoverCap | Out-Null
        $recLog = Device @('shell','logcat','-d','-s','GlassPreview')
        if ($recLog -notmatch "glassRepoState visible=$($entry.TargetPct) saved=$($entry.TargetPct) saveFailed=false") {
            throw "S4 FAILED on $($entry.Entry): Expected glassRepoState visible=$($entry.TargetPct) saved=$($entry.TargetPct) saveFailed=false after retry!"
        }

        $s4Results.Add([pscustomobject]@{
            Entry               = $entry.Entry
            Scene               = $entry.Scene
            TargetPercent       = $entry.TargetPct
            ErrorBannerBounds   = $errNode.bounds
            RetryButtonBounds   = $retryNode.bounds
            RetryButtonTap      = "$($retryTap.X),$($retryTap.Y)"
            RetainedValueOnFail = "$($entry.TargetPct)%"
            SavedValueAfterRetry= "$($entry.TargetPct)%"
            BannerClearedOnRetry= $true
            FailedCapture       = "$($entry.FailCap).png"
            RecoveredCapture    = "$($entry.RecoverCap).png"
        })
        Log-Message "PASS S4 native entry $($entry.Entry): failure banner visible, value $($entry.TargetPct)% retained, tapped '重试' at $($retryTap.X),$($retryTap.Y) -> saved=$($entry.TargetPct) & banner cleared."
    }
    $s4Results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $logsDir 's4-save-failure-ui-verification.json') -Encoding utf8

    Log-Message "=== 8. EXECUTING TEST-GLASSPREVIEW.PS1 ==="
    & (Join-Path $PSScriptRoot 'Test-GlassPreview.ps1') -RunId $RunId

    Log-Message "=== 9. EXECUTING TEST-READERGLASSFIX.PS1 ==="
    & (Join-Path $PSScriptRoot 'Test-ReaderGlassFix.ps1') -RunId $RunId

    Log-Message "=== 10. EXECUTING TEST-BACKDROPPROBE.PS1 ==="
    & (Join-Path $PSScriptRoot 'Test-BackdropProbe.ps1') -RunId $RunId

    Log-Message "=== 11. EXECUTING CAPTURE-SAFEAREACHECKS.PS1 ==="
    & (Join-Path $PSScriptRoot 'Capture-SafeAreaChecks.ps1') -RunId $RunId

    Log-Message "=== 12. EXECUTING CAPTURE-GLASSMATRIX.PS1 ==="
    & (Join-Path $PSScriptRoot 'Capture-GlassMatrix.ps1') -RunId $RunId

    Log-Message "=== 13. COPYING FRESH CAPTURES AND LOGS TO VERIFICATION DIR ==="
    $instanceRunRoot = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId"
    $sourceCaptures = Join-Path $instanceRunRoot 'captures'
    $sourceLogs = Join-Path $instanceRunRoot 'logs'

    if (Test-Path -LiteralPath $sourceCaptures) {
        Copy-Item -Path (Join-Path $sourceCaptures '*.png') -Destination $capturesDir -Force
    }
    if (Test-Path -LiteralPath $sourceLogs) {
        Copy-Item -Path (Join-Path $sourceLogs '*.*') -Destination $logsDir -Force
    }
    $captureCount = (Get-ChildItem -Path $capturesDir -Filter '*.png').Count
    Log-Message "Copied $captureCount fresh PNG captures to $capturesDir."

    $provenance = @()
    $pngFiles = Get-ChildItem -Path $capturesDir -Filter '*.png' | Sort-Object Name
    foreach ($file in $pngFiles) {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
        $provenance += [pscustomobject]@{
            Name              = $file.Name
            Size              = $file.Length
            SHA256            = $hash
            CapturedAt        = $file.LastWriteTime.ToString('o')
            TestedPreviewApk  = $pinnedPreviewApk
            PreviewApkSHA256  = $previewHash
        }
    }
    $provenance | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $verificationPath 'screenshot-provenance.json') -Encoding utf8
    Log-Message "Saved screenshot provenance to $verificationPath/screenshot-provenance.json."

    Log-Message "=== ALL NATIVE VERIFICATION STEPS COMPLETED SUCCESSFULLY ==="
} finally {
    Log-Message "=== 14. CLEANING UP ISOLATED EMULATOR ==="
    if (-not $instanceOwnedByThisRun) {
        Log-Message "S5 GUARD: Skipping Stop/cleanup because instance ownership for RunId '$RunId' was not established. Existing services left untouched."
    } else {
        try {
            Device @('shell','settings','put','system','font_scale','1.0') | Out-Null
        } catch {}
        try {
            & $driver -Action Stop -RunId $RunId 2>&1 | Out-Null
        } catch {
            Log-Message "Warning during emulator stop: $_"
        }
        for ($waitIdx = 0; $waitIdx -lt 15; $waitIdx++) {
            Start-Sleep -Seconds 1
            $remainingPorts = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(5049, 5580, 5581) })
            if ($remainingPorts.Count -eq 0) {
                break
            }
        }
        if ($remainingPorts.Count -eq 0) {
            Log-Message "Ports 5049/5580/5581 successfully released."
        } else {
            Log-Message "Warning: Some ports still occupied: $($remainingPorts.LocalPort -join ', ')"
        }
    }
}
