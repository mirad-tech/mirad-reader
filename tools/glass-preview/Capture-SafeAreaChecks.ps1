param([string]$RunId = 'glass-fix-20261022')
$ErrorActionPreference = 'Stop'
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logRoot = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/logs"
$package = 'com.mirad.reader.preview'
$checks = [Collections.Generic.List[object]]::new()
function Device([string[]]$Command) { (& $driver -Action Adb -RunId $RunId -AdbArgs $Command).Output }
try {
    Device @('shell','settings','put','system','font_scale','1.0') | Out-Null
    Device @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
    Device @('shell','wm','size','reset') | Out-Null
    Device @('shell','wm','density','reset') | Out-Null
    Device @('shell','settings','put','system','accelerometer_rotation','0') | Out-Null
    Device @('shell','cmd','overlay','enable-exclusive','--category','com.android.internal.display.cutout.emulation.tall') | Out-Null
    Device @('shell','cmd','overlay','enable-exclusive','--category','com.android.internal.systemui.navbar.threebutton') | Out-Null
    Start-Sleep -Seconds 3
    foreach ($rotation in @('1','3')) {
        Device @('shell','settings','put','system','user_rotation',$rotation) | Out-Null
        Start-Sleep -Milliseconds 500
        foreach ($rtl in @('false','true')) {
            Device @('shell','am','force-stop',$package) | Out-Null
            Device @('shell','am','start','-W','-n',"$package/com.mirad.reader.preview.GlassPreviewActivity",'--es','scene','shelf','--ez','rtl',$rtl) | Out-Null
            $topDeadline = [DateTime]::UtcNow.AddSeconds(10)
            do {
                Start-Sleep -Milliseconds 400
                $top = Device @('shell','dumpsys','activity','activities')
            } while ($top -notmatch 'topResumedActivity=.*GlassPreviewActivity' -and [DateTime]::UtcNow -lt $topDeadline)
            Start-Sleep -Milliseconds 800
            $name = "shelf-cutout-rotation-$rotation-rtl-$rtl"
            & $driver -Action Capture -RunId $RunId -Name $name | Out-Null
            Device @('shell','uiautomator','dump','/data/local/tmp/mirad-safearea.xml') | Out-Null
            $uiText = Device @('shell','cat','/data/local/tmp/mirad-safearea.xml')
            $uiText | Set-Content -LiteralPath (Join-Path $logRoot "$name.xml") -Encoding utf8
            $insetsDeadline = [DateTime]::UtcNow.AddSeconds(5)
            do {
                $insetsText = Device @('shell','dumpsys','window','displays')
                $nav = [regex]::Match($insetsText,'type=navigationBars frame=\[(\d+),0\]\[(\d+),1080\] visible=true')
                if ($nav.Success) { break }
                Start-Sleep -Milliseconds 400
            } while ([DateTime]::UtcNow -lt $insetsDeadline)
            $insetsText | Set-Content -LiteralPath (Join-Path $logRoot "$name-insets.txt") -Encoding utf8
            if (-not $nav.Success) { throw 'Expected a visible side navigation bar on the landscape fixture.' }
            $cutout = [regex]::Match($insetsText,'mDisplayCutout=DisplayCutout\{insets=Rect\((\d+),\s*\d+\s*-\s*(\d+),')
            if (-not $cutout.Success) { throw 'Expected the emulator cutout insets.' }
            $safeLeft = [int]$cutout.Groups[1].Value
            $safeRight = 2400 - [int]$cutout.Groups[2].Value
            if ([int]$nav.Groups[1].Value -eq 0) { $safeLeft = [Math]::Max($safeLeft, [int]$nav.Groups[2].Value) }
            else { $safeRight = [Math]::Min($safeRight, [int]$nav.Groups[1].Value) }
            [xml]$ui = $uiText
            $targets = @($ui.SelectNodes("//node[@content-desc='搜索' or @content-desc='阅读统计' or @content-desc='设置' or @content-desc='更多' or @text='风雪归人' or @text='各奔东西']"))
            if ($targets.Count -lt 6) { throw 'Missing header or shelf nodes.' }
            foreach ($target in $targets) {
                $hit = $target.SelectSingleNode("ancestor-or-self::node[@clickable='true'][1]")
                if ($null -eq $hit -or $hit.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Missing clickable target bounds.' }
                if ([int]$Matches[1] -lt $safeLeft -or [int]$Matches[3] -gt $safeRight) { throw "Target overlaps a horizontal system inset: $name $($hit.bounds)" }
            }
            $more = $ui.SelectSingleNode("//node[@content-desc='更多']/ancestor-or-self::node[@clickable='true'][1]")
            if ($more.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Missing more-button bounds.' }
            $x = [int](([int]$Matches[1]+[int]$Matches[3])/2)
            $y = [int](([int]$Matches[2]+[int]$Matches[4])/2)
            Device @('shell','input','tap',"$x","$y") | Out-Null
            Device @('shell','uiautomator','dump','/data/local/tmp/mirad-safearea-menu.xml') | Out-Null
            [xml]$menu = Device @('shell','cat','/data/local/tmp/mirad-safearea-menu.xml')
            if ($null -eq $menu.SelectSingleNode("//node[@text='笔记与书签']")) { throw 'More-button tap did not open its menu.' }
            Device @('shell','input','keyevent','4') | Out-Null
            $checks.Add(@{Name=$name; Passed=$true; SafeLeft=$safeLeft; SafeRight=$safeRight; Targets=$targets.Count; MoreButtonOperable=$true})
            Write-Output "PASS $name horizontal hit targets avoid system insets and more-button opens menu"
        }
    }
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','am','start','-W','-n',"$package/com.mirad.reader.preview.GlassPreviewActivity",'--es','scene','reader-settings','--ez','fullScreen','false') | Out-Null
    $previewProcessId = ((Device @('shell','pidof',$package)).Trim() -split '\s+')[0]
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $log = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
        if ($log -match 'readerReady') { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($log -notmatch 'readerReady') { throw 'Reader did not initialize.' }
    Start-Sleep -Milliseconds 900
    & $driver -Action Capture -RunId $RunId -Name 'reader-sheet-cutout-side-nav' | Out-Null
    Write-Output 'Captured reader-sheet-cutout-side-nav'
} finally {
    $checks | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logRoot 'safearea-checks.json') -Encoding utf8
    Device @('shell','cmd','overlay','disable','com.android.internal.display.cutout.emulation.tall') | Out-Null
    Device @('shell','cmd','overlay','enable-exclusive','--category','com.android.internal.systemui.navbar.gestural') | Out-Null
    Device @('shell','settings','put','system','user_rotation','0') | Out-Null
    Device @('shell','settings','put','system','accelerometer_rotation','1') | Out-Null
    Device @('shell','settings','put','system','font_scale','1.0') | Out-Null
    Device @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
}
