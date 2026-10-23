param([string]$RunId = 'glass-20261022')
$ErrorActionPreference = 'Stop'
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logRoot = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/logs"
$package = 'com.mirad.reader.preview'
$results = [Collections.Generic.List[object]]::new()
function Device([string[]]$Command) { (& $driver -Action Adb -RunId $RunId -AdbArgs $Command).Output }
function Check([string]$Name, [bool]$Passed) {
    $results.Add(@{Name=$Name; Passed=$Passed})
    if (-not $Passed) { throw "Failed: $Name" }
    Write-Output "PASS $Name"
}
function Ui {
    Device @('shell','uiautomator','dump','/data/local/tmp/mirad-glass-preview.xml') | Out-Null
    [xml](Device @('shell','cat','/data/local/tmp/mirad-glass-preview.xml'))
}
function Bounds($Node) {
    if ($null -eq $Node -or $Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Expected a visible UI node.' }
    @{Left=[int]$Matches[1]; Top=[int]$Matches[2]; Right=[int]$Matches[3]; Bottom=[int]$Matches[4]}
}
function Tap([string]$Attribute, [string]$Value) {
    $document = Ui
    $node = $document.SelectSingleNode("//node[@$Attribute='$Value']")
    $rect = Bounds $node
    Device @('shell','input','tap',"$([int](($rect.Left+$rect.Right)/2))","$([int](($rect.Top+$rect.Bottom)/2))") | Out-Null
    Start-Sleep -Milliseconds 600
}
try {
    Device @('shell','wm','size','reset') | Out-Null
    Device @('shell','wm','density','reset') | Out-Null
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','logcat','-c') | Out-Null
    Device @('shell','am','start','-W','-n',"$package/com.mirad.reader.preview.GlassPreviewActivity",'--es','scene','reader') | Out-Null
    $previewProcessId = ((Device @('shell','pidof',$package)).Trim() -split '\s+')[0]
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $log = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
        if ($log -match 'readerReady') { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    Check 'reader assets initialized' ($log -match 'readerReady')
    Start-Sleep -Seconds 1
    Device @('shell','dumpsys','gfxinfo',$package,'reset') | Out-Null
    Device @('shell','dumpsys','meminfo','-s',$package) | Set-Content -LiteralPath (Join-Path $logRoot 'memory-before.txt') -Encoding utf8

    $document = Ui
    $slider = $document.SelectSingleNode("//node[@class='android.widget.SeekBar']")
    $rect = Bounds $slider
    $y = [int](($rect.Top+$rect.Bottom)/2)
    $startX = [Math]::Max($rect.Left + 120, 200)
    $endX = [Math]::Min($rect.Right - 120, 880)
    Write-Output "DEBUG slider bounds: [$($rect.Left),$($rect.Top)][$($rect.Right),$($rect.Bottom)] y=$y swipe: $startX -> $endX pid=$previewProcessId"
    Device @('shell','input','swipe',"$startX","$y","$endX","$y",'650') | Out-Null
    $commitDeadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 400
        $log = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
    } while (([regex]::Matches($log,'sliderCommit=')).Count -eq 0 -and [DateTime]::UtcNow -lt $commitDeadline)
    $commitCount = ([regex]::Matches($log,'sliderCommit=')).Count
    $debugText = "bounds: [$($rect.Left),$($rect.Top)][$($rect.Right),$($rect.Bottom)] y=$y pid=$previewProcessId count=$commitCount`nlog:`n$log"
    $debugText | Set-Content -LiteralPath (Join-Path $logRoot 'debug-slider.txt') -Encoding utf8
    Write-Output "DEBUG: $debugText"
    Check 'progress commits exactly once on release' ($commitCount -eq 1)

    $moreNode = (Ui).SelectSingleNode("//node[@content-desc='更多']")
    $anchorNode = if ($moreNode) { $moreNode.SelectSingleNode("ancestor-or-self::node[@clickable='true'][1]") } else { $null }
    if ($null -eq $anchorNode) { $anchorNode = $moreNode }
    $anchor = Bounds $anchorNode
    Tap 'content-desc' '更多'
    $document = Ui
    Check 'reader menu opens' ($null -ne $document.SelectSingleNode("//node[@text='笔记与书签']"))
    $menuBounds = Bounds ($document.SelectSingleNode("//node[@text='目录']").ParentNode)
    Check 'reader menu is aligned to the more button' ([Math]::Abs($menuBounds.Right-$anchor.Right) -le 24 -and $menuBounds.Left -gt 540)
    & $driver -Action Capture -RunId $RunId -Name 'reader-menu' | Out-Null
    Tap 'text' '阅读设置'
    $document = Ui
    Check 'menu opens settings sheet' ($null -ne $document.SelectSingleNode("//node[@text='阅读设置']"))
    Device @('shell','input','keyevent','4') | Out-Null
    Start-Sleep -Milliseconds 600
    $document = Ui
    Check 'back dismisses settings sheet' ($null -eq $document.SelectSingleNode("//node[@text='阅读设置']"))

    Tap 'text' '设置'
    $document = Ui
    $rect = Bounds $document.SelectSingleNode("//node[@text='阅读设置']")
    Device @('shell','input','swipe','540',"$($rect.Top-70)",'540','2350','650') | Out-Null
    Start-Sleep -Milliseconds 800
    $document = Ui
    Check 'sheet drag dismisses' ($null -eq $document.SelectSingleNode("//node[@text='阅读设置']"))

    Device @('shell','input','tap','540','800') | Out-Null
    Start-Sleep -Milliseconds 700
    $log = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
    $cacheEvents = @($log -split "`n" | Where-Object {$_ -match 'backdropFrame=|backdropReleased'})
    Check 'hidden controls release backdrop' ($cacheEvents[-1] -match 'backdropReleased')
    $framesBefore = ([regex]::Matches($log,'backdropFrame=')).Count
    Device @('shell','input','swipe','540','1500','540','650','600') | Out-Null
    Start-Sleep -Seconds 1
    $log = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
    Check 'hidden reader scrolling does not capture frames' (([regex]::Matches($log,'backdropFrame=')).Count -eq $framesBefore)
    for ($round=1; $round -le 3; $round++) {
        for ($i=0; $i -lt 12; $i++) {
            Device @('shell','input','tap','540','800') | Out-Null
            Start-Sleep -Milliseconds 300
        }
        Start-Sleep -Milliseconds 600
        Device @('shell','dumpsys','meminfo','-s',$package) | Set-Content -LiteralPath (Join-Path $logRoot "memory-round-$round.txt") -Encoding utf8
    }
    Start-Sleep -Seconds 1
    Device @('shell','dumpsys','meminfo','-s',$package) | Set-Content -LiteralPath (Join-Path $logRoot 'memory-after.txt') -Encoding utf8
    Device @('shell','dumpsys','gfxinfo',$package,'framestats') | Set-Content -LiteralPath (Join-Path $logRoot 'reader-frames.txt') -Encoding utf8
    $log = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
    $log | Set-Content -LiteralPath (Join-Path $logRoot 'native-interactions.txt') -Encoding utf8
    $crashes = Device @('logcat','-d','-b','crash','--pid',$previewProcessId)
    $crashes | Set-Content -LiteralPath (Join-Path $logRoot 'native-crashes.txt') -Encoding utf8
    Check 'preview has no crash record' ($crashes -notmatch 'FATAL EXCEPTION')
    $directories = Device @('shell','run-as',$package,'ls')
    $directories | Set-Content -LiteralPath (Join-Path $logRoot 'preview-data-directories.txt') -Encoding utf8
    Check 'preview did not create a database directory' ($directories -notmatch '(?m)^databases\s*$')
} finally {
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logRoot 'native-interactions.json') -Encoding utf8
}
