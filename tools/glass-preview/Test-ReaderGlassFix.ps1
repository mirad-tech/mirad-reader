param([string]$RunId = 'glass-fix-20261022')
$ErrorActionPreference = 'Stop'
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logRoot = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/logs"
$package = 'com.mirad.reader.preview'
$results = [Collections.Generic.List[object]]::new()
function Device([string[]]$Command) {
    $result = & $driver -Action Adb -RunId $RunId -AdbArgs $Command
    if ($result.ExitCode -ne 0) { throw "Isolated ADB failed: $($result.Error)" }
    $result.Output
}
function Check([string]$Name, [bool]$Passed) {
    $results.Add(@{Name=$Name; Passed=$Passed})
    if (-not $Passed) { throw "Failed: $Name" }
    Write-Output "PASS $Name"
}
function Ui {
    Device @('shell','uiautomator','dump','/data/local/tmp/mirad-glass-fix.xml') | Out-Null
    [xml](Device @('shell','cat','/data/local/tmp/mirad-glass-fix.xml'))
}
function Bounds($Node, [string]$Context = '') {
    if ($null -eq $Node) {
        $uiRaw = Device @('shell','cat','/data/local/tmp/mirad-glass-fix.xml')
        $uiRaw | Set-Content -LiteralPath (Join-Path $logRoot 'failed-node-dump.xml') -Encoding utf8
        Write-Output "DEBUG Bounds: node is null (Context: $Context)"
        throw "Expected a visible UI node (Context: $Context)."
    }
    if ($Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Expected a visible UI node (Bounds invalid: '$($Node.bounds)' in Context: $Context)."
    }
    @{Left=[int]$Matches[1]; Top=[int]$Matches[2]; Right=[int]$Matches[3]; Bottom=[int]$Matches[4]}
}
function TapNode($Node, [string]$Context = '') {
    $rect = Bounds $Node $Context
    Device @('shell','input','tap',"$([int](($rect.Left+$rect.Right)/2))","$([int](($rect.Top+$rect.Bottom)/2))") | Out-Null
    Start-Sleep -Milliseconds 350
}
function Launch([string]$Scene) {
    Device @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
    Device @('shell','am','force-stop',$package) | Out-Null
    Device @('shell','logcat','-c') | Out-Null
    Device @('shell','am','start','-W','-n',"$package/com.mirad.reader.preview.GlassPreviewActivity",'--es','scene',$Scene,'--ez','fullScreen','true') | Out-Null
    $script:previewProcessId = ((Device @('shell','pidof',$package)).Trim() -split '\s+')[0]
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $log = Logs
        if ($log -match 'readerReady') { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($log -notmatch 'readerReady') { throw "Reader did not initialize: $Scene" }
    Start-Sleep -Milliseconds 700
    $doc = Ui
    $gotIt = $doc.SelectSingleNode("//node[@text='Got it' or @text='知道了']")
    if ($null -ne $gotIt) {
        TapNode $gotIt 'dismiss-got-it'
        Start-Sleep -Milliseconds 500
    }
}
function Logs { Device @('logcat','-d','--pid',$script:previewProcessId,'-s','GlassPreview') }
function RevealCustomFont {
    $font = (Ui).SelectSingleNode("//node[@text='霞鹜文楷']")
    $rect = Bounds $font
    $y = [int](($rect.Top+$rect.Bottom)/2)
    Device @('shell','input','swipe','940',"$y",'150',"$y",'450') | Out-Null
    Start-Sleep -Milliseconds 350
}
try {
    Device @('shell','wm','size','reset') | Out-Null
    Device @('shell','wm','density','reset') | Out-Null
    Launch 'reader'
    $before = ([regex]::Matches((Logs),'readerTouch')).Count
    $doc = Ui
    $title = $doc.SelectSingleNode("//node[@text='第一章 雪夜出城']")
    if ($null -eq $title) { $title = $doc.SelectSingleNode("//node[contains(@text,'第一章')]") }
    if ($null -eq $title) { $title = $doc.SelectSingleNode("//node[contains(@text,'雪夜')]") }
    if ($null -eq $title) {
        $allTexts = @($doc.SelectNodes("//node[@text!='']")) | ForEach-Object { "$($_.text) [$($_.bounds)]" }
        Write-Output "DEBUG available texts in UI: $($allTexts -join ' ; ')"
    }
    TapNode $title 'title'
    $rect = Bounds $title 'title'
    $y = [int](($rect.Top+$rect.Bottom)/2)
    Device @('shell','input','swipe',"$($rect.Left+5)","$y","$($rect.Right-5)","$y",'450') | Out-Null
    Check 'toolbar title tap and drag do not reach WebView' (([regex]::Matches((Logs),'readerTouch')).Count -eq $before)
    $more = (Ui).SelectSingleNode("//node[@content-desc='更多']")
    TapNode $more 'more'
    Check 'toolbar button remains operable' ($null -ne (Ui).SelectSingleNode("//node[@text='阅读设置']"))
    Device @('shell','input','keyevent','4') | Out-Null
    Device @('shell','input','tap','540','850') | Out-Null
    Start-Sleep -Milliseconds 500
    Check 'body tap remains operable' ((Logs) -match 'chromeVisible=false')
    Device @('shell','input','tap','540','850') | Out-Null
    Start-Sleep -Milliseconds 500
    Device @('shell','input','keyevent','3') | Out-Null
    Start-Sleep -Milliseconds 800
    Check 'background stops sampling and releases bitmap' ((Logs) -match 'backgroundCaptureStopped=true')
    Device @('shell','am','start','-W','--activity-reorder-to-front','-n',"$package/com.mirad.reader.preview.GlassPreviewActivity") | Out-Null
    Start-Sleep -Milliseconds 700
    $events = @((Logs) -split "`n" | Where-Object { $_ -match 'backdropFrame=|backdropReleased' })
    Check 'foreground refreshes backdrop' ($events[-1] -match 'backdropFrame=')

    Launch 'reader-typography'
    TapNode ((Ui).SelectSingleNode("//node[@text='霞鹜文楷']")) 'wenkai'
    Check 'current custom font shows catalog label' ($null -ne (Ui).SelectSingleNode("//node[@text='当前使用：霞鹜文楷']"))
    RevealCustomFont
    $document = Ui
    Check 'long font retains complete accessibility text' ($null -ne $document.SelectSingleNode("//node[@text='BarlowCondensed-Bold']"))
    $delete = $document.SelectSingleNode("//node[@content-desc='删除字体：BarlowCondensed-Bold']/ancestor-or-self::node[@clickable='true'][1]")
    $rect = Bounds $delete 'delete'
    Check 'font delete has independent 48dp hit area' (($rect.Right-$rect.Left) -ge 132 -and ($rect.Bottom-$rect.Top) -ge 132)
    TapNode $delete 'delete-tap'
    Check 'deleting unselected font preserves selection' ($null -ne (Ui).SelectSingleNode("//node[@text='当前使用：霞鹜文楷']"))
    Check 'deleted font removed from in-memory list' ($null -eq (Ui).SelectSingleNode("//node[@text='BarlowCondensed-Bold']"))

    Launch 'reader-typography'
    RevealCustomFont
    TapNode ((Ui).SelectSingleNode("//node[@text='BarlowCondensed-Bold']")) 'barlow'
    Check 'selected imported font shows catalog label' ($null -ne (Ui).SelectSingleNode("//node[@text='当前使用：BarlowCondensed-Bold']"))
    TapNode ((Ui).SelectSingleNode("//node[@content-desc='删除字体：BarlowCondensed-Bold']")) 'delete-barlow'
    Check 'deleting current font keeps existing system fallback' ((Logs) -match 'fontDeleted current=SYSTEM')
    Check 'fallback clears custom font label' ($null -eq (Ui).SelectSingleNode("//node[starts-with(@text,'当前使用：')]"))
    (Logs) | Set-Content -LiteralPath (Join-Path $logRoot 'font-delete-events.txt') -Encoding utf8
} finally {
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logRoot 'reader-fix-interactions.json') -Encoding utf8
}
