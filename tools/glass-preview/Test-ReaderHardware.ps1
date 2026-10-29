[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('smoke','core','materials','layout','fallback','lifecycle')][string]$Suite='smoke'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output=[IO.Path]::GetFullPath($OutputDirectory)
$allowed=[IO.Path]::GetFullPath((Join-Path $project 'build/verification'))+[IO.Path]::DirectorySeparatorChar
if(-not $output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) {throw 'Evidence must remain under build/verification.'}
if(Test-Path -LiteralPath $output) {throw 'Use a fresh evidence directory.'}
New-Item -ItemType Directory -Path $output | Out-Null
$driver=Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$package='com.mirad.reader.preview'
$component="$package/com.mirad.reader.preview.SettingsImmersionPreviewActivity"
$action='com.mirad.reader.preview.IMMERSION_CHECK'
$apkPath=(Resolve-Path -LiteralPath $Apk).Path
$apkHash=(Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
$checks=[Collections.Generic.List[object]]::new()
$captures=[Collections.Generic.List[object]]::new()
$counter=0
$passed=$false

function Device([string[]]$Arguments) {
    $r=$null
    try { & $driver -Action Adb -RunId $RunId -AdbArgs $Arguments | ForEach-Object {$r=$_} }
    catch { throw "Isolated command failed: $($_.Exception.Message) $($r.Output) $($r.Error)" }
    if($r.ExitCode -ne 0) {throw "Isolated command failed: $($r.Error) $($r.Output)"}
    return [string]$r.Output
}
function Check([bool]$Condition,[string]$Name) {
    $checks.Add([pscustomobject]@{Name=$Name;Passed=$Condition})
    if(-not $Condition) {throw $Name}
    Write-Host "PASS $Name"
}
function Command([string]$Operation,[string[]]$Extra=@()) {
    [void](Device (@('shell','am','broadcast','-a',$action,'--es','operation',$Operation)+$Extra))
}
function Dump {
    $script:counter++
    $id=[Guid]::NewGuid().ToString('N')
    for($attempt=0;$attempt -lt 4;$attempt++) {
        Command 'dump' @('--es','label',$id)
        $json=Device @('shell','cat',"/sdcard/Android/data/$package/files/hardware-reader-state.json")
        $state=$json | ConvertFrom-Json
        if($state.label -eq $id -and $state.PSObject.Properties.Name -contains 'web' -and $state.web.ready) {
            $json | Set-Content -LiteralPath (Join-Path $output "state-$script:counter.json") -Encoding utf8
            return $state
        }
        Start-Sleep -Milliseconds 150
    }
    throw 'No current, ready hardware reader state.'
}
function Ui {
    $id=[Guid]::NewGuid().ToString('N')
    $remote="/sdcard/reader-hardware-$id.xml"
    for($attempt=0;$attempt -lt 3;$attempt++) {
        # Compressed mode follows accessibility importance. The default diagnostic hierarchy
        # deliberately includes views hidden from accessibility and cannot prove modal isolation.
        try { [void](Device @('shell','uiautomator','dump','--compressed',$remote)); break }
        catch {
            $_.Exception.Message | Add-Content -LiteralPath (Join-Path $output 'ui-readiness.log') -Encoding utf8
            if($attempt -eq 2) {throw}
            Start-Sleep -Milliseconds 200
        }
    }
    $xml=Device @('shell','cat',$remote)
    $xml | Set-Content -LiteralPath (Join-Path $output "ui-$id.xml") -Encoding utf8
    return ,([xml]$xml)
}
function Bounds($Node) {
    $m=[regex]::Match($Node.GetAttribute('bounds'),'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if(-not $m.Success) {return $null}
    $v=@($m.Groups | Select-Object -Skip 1 | ForEach-Object {[int]$_.Value})
    if($v[2] -le $v[0] -or $v[3] -le $v[1]) {return $null}
    return [pscustomobject]@{Left=$v[0];Top=$v[1];Right=$v[2];Bottom=$v[3];X=[int](($v[0]+$v[2])/2);Y=[int](($v[1]+$v[3])/2)}
}
function Tap([string]$Text,[switch]$EditText) {
    $tree=Ui
    $nodes=@($tree.SelectNodes('//node') | Where-Object {
        if($EditText) {$_.GetAttribute('class') -eq 'android.widget.EditText'}
        else {$_.GetAttribute('text') -eq $Text -or $_.GetAttribute('content-desc') -eq $Text}
    })
    foreach($node in $nodes) {
        $b=Bounds $node
        if($b) {[void](Device @('shell','input','tap',"$($b.X)","$($b.Y)"));return}
    }
    throw "No visible control: $Text"
}
function Capture([string]$Name) {
    $null=Dump
    $tree=Ui
    Check (@($tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('package') -eq $package}).Count -gt 0) "$Name contains the isolated reader"
    $state=Dump
    $capture=& $driver -Action Capture -RunId $RunId -Name "reader-$Suite-$Name"
    $path=Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $capture.Path -Destination $path
    $captures.Add([pscustomobject]@{Name=$Name;Path=$path;State=$state})
    return $state
}
function Metric($State,[string]$Name) {
    $m=[regex]::Match([string]$State.hardware.sceneStats,"(?:^|\s)$([regex]::Escape($Name))=([^\s]+)")
    if(-not $m.Success) {throw "Missing hardware metric $Name"}
    return $m.Groups[1].Value
}
function CheckPosition($Before,$After,[string]$Label) {
    Check ($Before.loads -eq $After.loads) "$Label does not reload the document"
    Check ($Before.readerBounds.width -eq $After.readerBounds.width -and $Before.readerBounds.height -eq $After.readerBounds.height) "$Label preserves native WebView bounds"
    Check ($Before.web.width -eq $After.web.width -and $Before.web.height -eq $After.web.height) "$Label preserves the JavaScript viewport"
    Check ([Math]::Abs($Before.web.scrollY-$After.web.scrollY) -le 1 -and $Before.web.glyph.offset -eq $After.web.glyph.offset -and [Math]::Abs($Before.web.glyph.top-$After.web.glyph.top) -le 1) "$Label preserves an independent character position"
}

$originalScale=(Device @('shell','settings','get','system','font_scale')).Trim()
$originalSize=Device @('shell','wm','size')
$originalDensity=Device @('shell','wm','density')
$originalNight=Device @('shell','cmd','uimode','night')
$rotation=(Device @('shell','settings','get','system','user_rotation')).Trim()
$autoRotation=(Device @('shell','settings','get','system','accelerometer_rotation')).Trim()
$restoreSize=if($originalSize -match 'Override size:\s*(\d+x\d+)'){$Matches[1]}else{'reset'}
$restoreDensity=if($originalDensity -match 'Override density:\s*(\d+)'){$Matches[1]}else{'reset'}
$restoreNight=if($originalNight -match 'Night mode:\s*(yes|no|auto|custom)'){$Matches[1]}else{'auto'}
try {
    & $driver -Action Install -RunId $RunId -Apk $apkPath | Out-Null
    $installed=(Device @('shell','pm','path',$package)).Trim()
    if($installed -notmatch '^package:(/data/app/[^\r\n]+/base\.apk)$') {throw 'Unexpected preview package path.'}
    $installedHash=((Device @('shell','sha256sum',$Matches[1])) -split '\s+')[0]
    Check ($installedHash -eq $apkHash) 'installed APK matches the pinned test artifact'
    [void](Device @('shell','settings','put','system','font_scale','1.0'))
    [void](Device @('shell','settings','put','system','accelerometer_rotation','0'))
    [void](Device @('shell','settings','put','system','user_rotation','0'))
    [void](Device @('shell','cmd','uimode','night','no'))
    $fallback=if($Suite -eq 'fallback'){'true'}else{'false'}
    $launch=Device @('shell','am','start','-S','-W','-n',$component,'--es','scene','appearance','--es','entry','reader','--ez','hardware','true','--es','mode','DAY','--ez','chrome','true','--ei','transparency','100','--ez','fullScreen','false','--ez','showClock','false','--ez','fallback',$fallback)
    Check ($launch -match 'Status: ok') 'reader Activity launches successfully'
    $initial=Capture 'initial'
    Check ($initial.hardwarePreview -and $initial.hardware.hostWindowMatchesActivity) 'production hardware scene and overlays use the Activity window'
    Check ((Metric $initial 'cpuDisplayCaptures') -eq '0') 'GPU display has zero CPU bitmap captures'
    if($Suite -ne 'fallback') {
    Check ((Metric $initial 'hardware') -eq 'true' -and (Metric $initial 'gpuActive') -eq 'true') 'the reader uses an active hardware content node'
    # 语义已改为"清晰度"：100% 是"最清晰"，按计划必须走显式的无 blur 分支（blur=0、薄底、不是 fallback）。
    Check ([Math]::Abs([double](Metric $initial 'baseOpacity') - .08) -lt .001 -and [double](Metric $initial 'blurDp') -eq 0) 'at 100% clarity the first toolbar frame uses zero blur and the 8% day tint'
    $midLaunch=Device @('shell','am','start','-S','-W','-n',$component,'--es','scene','appearance','--es','entry','reader','--ez','hardware','true','--es','mode','DAY','--ez','chrome','true','--ei','transparency','50','--ez','fullScreen','false','--ez','showClock','false','--ez','fallback','false')
    Start-Sleep -Milliseconds 900
    $mid=Capture 'clarity-50'
    Check ([double](Metric $mid 'blurDp') -gt 0) 'mid clarity keeps a non-zero blur radius (not a renamed opacity slider)'
    Check ([int](Metric $initial 'analysisSamples') -gt 0 -and (Metric $initial 'analysisFailures') -eq '0') 'background analysis succeeds'
    $dims=(Metric $initial 'analysisSize') -split 'x'
    Check ([int]$dims[0] -le 64 -and [int]$dims[1] -le 64 -and (Metric $initial 'maxAnalysisInFlight') -eq '1') 'analysis is bounded to 64 pixels and one task'
    Check ((Metric $initial 'analysisThread') -match 'DefaultDispatcher') 'pixel analysis runs on a worker thread'
    }

    if($Suite -eq 'core') {
        Command 'scroll' @('--ei','y','360')
        $before=Dump
        Command 'panel' @('--es','name','toc')
        $toc=Capture 'toc'
        Check ($toc.hardware.modalVisible) 'table of contents is modal'
        Check (-not $toc.imeVisible) 'opening the table of contents does not focus its text field'
        CheckPosition $before $toc 'opening a sheet'
        $tree=Ui
        Check (@($tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('class') -eq 'android.webkit.WebView'}).Count -eq 0) 'modal accessibility excludes the underlying WebView'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        Check (-not (Dump).hardware.modalVisible) 'Back closes the top sheet'
        Command 'panel' @('--es','name','toc')
        $dragTree=Ui
        $handle=$dragTree.SelectNodes('//node') | Where-Object {$_.GetAttribute('content-desc') -eq 'Drag handle'} | Select-Object -First 1
        if($null -eq $handle) {throw 'No accessible sheet drag handle.'}
        $handleBounds=Bounds $handle
        [void](Device @('shell','input','swipe',"$($handleBounds.X)","$($handleBounds.Y)","$($handleBounds.X)",'100','600'))
        $expanded=Capture 'toc-dragged'
        CheckPosition $before $expanded 'dragging a sheet'
        Check ((Metric $expanded 'fallbackMaterials') -eq '0') 'dragged material keeps its hardware background'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        $contentTree=Ui
        $contentNode=$contentTree.SelectNodes('//node') | Where-Object {$_.GetAttribute('class') -eq 'android.webkit.WebView'} | Select-Object -First 1
        if($null -eq $contentNode) {throw 'No visible reader content before opening the menu.'}
        $contentBounds=Bounds $contentNode
        Tap '更多'
        $menu=Capture 'menu'
        Check ($menu.hardware.modalVisible) 'reader dropdown is a same-window modal'
        [void](Device @('shell','input','tap',"$($contentBounds.X)","$([int]($contentBounds.Top + ($contentBounds.Bottom-$contentBounds.Top)*.75))"))
        $outside=Dump
        Check (-not $outside.hardware.modalVisible -and $outside.chrome) 'tapping outside dismisses only the menu'
        CheckPosition $before $outside 'dismissing the menu outside'
        Tap '更多'
        Tap '全书搜索'
        $search=Capture 'search'
        Tap '' -EditText
        [void](Device @('shell','input','text','snow'))
        $ime=Capture 'search-ime'
        Check ($ime.imeVisible) 'the search input opens the real IME'
        CheckPosition $before $ime 'opening the keyboard'
        [void](Device @('shell','input','keyevent','KEYCODE_VOLUME_DOWN'))
        CheckPosition $ime (Dump) 'volume keys inside a modal'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        $imeClosed=Dump
        Check (-not $imeClosed.imeVisible -and $imeClosed.hardware.modalVisible) 'first Back closes the IME and keeps the sheet'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        Check (-not (Dump).hardware.modalVisible) 'second Back closes the search sheet'
        foreach($panel in @('progress','autoread','note','notes')) {
            Command 'panel' @('--es','name',$panel)
            $state=Capture $panel
            Check ($state.hardware.modalVisible -and $state.hardware.hostWindowMatchesActivity) "$panel is presented in the reader window"
            [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        }
        Command 'panel' @('--es','name','notes')
        Tap '雪下了整整一夜'
        Tap '删除'
        $confirm=Capture 'delete-confirm'
        Check ($confirm.hardware.deleteConfirm -and $confirm.hardware.notesTarget -eq 1 -and $confirm.hardware.noteCount -eq 2) 'delete confirmation keeps the selected note unchanged'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        $cancel=Dump
        Check (-not $cancel.hardware.deleteConfirm -and $cancel.hardware.noteDetails -and $cancel.hardware.noteCount -eq 2) 'Back cancels only the top confirmation'
        Tap '删除'
        Tap '删除'
        $deleted=Dump
        Check ($deleted.hardware.notesDeleteCount -eq 1 -and $deleted.hardware.lastDeletedTarget -eq 1 -and $deleted.hardware.noteCount -eq 1 -and $deleted.hardware.modalVisible) 'confirming deletes exactly the selected fixture note and keeps the list open'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        Command 'navigate' @('--es','scene','reader-settings','--es','category','THEME')
        $settings=Capture 'settings-category'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        $tree=Ui
        Check (@($tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('text') -eq '阅读设置'}).Count -gt 0) 'Back returns from a settings category without dismissing the sheet'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        Check (-not (Dump).hardware.modalVisible) 'the following Back dismisses settings'
    }
    if($Suite -eq 'materials') {
        $idleBefore=Dump
        $idleAfter=Capture 'idle-material'
        Check ((Metric $idleBefore 'analysisSamples') -eq (Metric $idleAfter 'analysisSamples')) 'unchanged content does not trigger continuing analysis'
        Command 'navigate' @('--es','scene','reader-settings','--es','category','THEME')
        foreach($mode in @('DAY','NIGHT')) {
            Command 'mode' @('--es','mode',$mode)
            # 新语义：清晰度 0/25/50/75/100 五档；"关闭"用真实开关，不再用 0% 冒充。
            foreach($clarity in @(0,25,50,75,100)) {
                Command 'transparency' @('--ez','enabled','true','--ei','percent',"$clarity")
                $state=Capture "$mode-c$clarity"
                if($clarity -eq 100) {
                    Check ([double](Metric $state 'blurDp') -eq 0) "$mode 100% clarity takes the explicit no-blur branch"
                    $readableFloor=if($mode -eq 'DAY'){.92}else{.88}
                    Check ([double](Metric $state 'baseOpacity') -ge $readableFloor-.001) "$mode clear panels use a uniform readability tint without blurring the foreground"
                } else {
                    Check ([double](Metric $state 'blurDp') -gt 0) "$mode $clarity% clarity keeps a non-zero blur radius"
                }
                if($clarity -eq 0) {
                    Check ((Metric $state 'gpuActive') -eq 'true') "$mode 0% clarity still runs the material (it is not 'off')"
                }
            }
            Command 'transparency' @('--ez','enabled','false','--ei','percent','60')
            $off=Capture "$mode-glass-off"
            Check ((Metric $off 'gpuActive') -eq 'false') "$mode glass switch off disables GPU material work"
            $offAgain=Dump
            Check ((Metric $off 'analysisSamples') -eq (Metric $offAgain 'analysisSamples')) "$mode glass switch off stops background analysis"
            Command 'transparency' @('--ez','enabled','true','--ei','percent','60')
            $background=if($mode -eq 'DAY'){'dawn'}else{'night'}
            Command 'config' @('--es','background',$background)
            $image=Capture "$mode-image"
            Check ($image.web.image -match "backgrounds/$background.jpg" -and (Metric $image 'fallbackMaterials') -eq '0') "$mode image background is available to the hardware material"
        }
        $beforeImage=Dump
        Command 'image'
        $loaded=Capture 'loaded-inline-image'
        Check ($loaded.web.loadedImages -gt 0 -and [long](Metric $loaded 'analysisSamples') -gt [long](Metric $beforeImage 'analysisSamples')) 'a delayed image triggers a fresh bounded analysis'
        Command 'chapter' @('--ei','index','1')
        $chapter=Capture 'changed-chapter'
        Check ($chapter.hardware.currentChapter -eq 1 -and $chapter.loads -gt $loaded.loads -and (Metric $chapter 'fallbackMaterials') -eq '0') 'chapter switching keeps the hardware material ready'
        Command 'animate' @('--ei','durationMs','6000')
        $moving=Capture 'moving-background'
        Command 'stop-animation'
        $after=Dump
        Check ([double](Metric $after 'analysisMinIntervalMs') -ge 500) 'analysis starts at most twice per second'
        Check ((Metric $after 'analysisFailures') -eq '0') 'scrolling and theme changes do not fail analysis'
        Command 'panel' @('--es','name','none')
        Command 'navigate' @('--es','scene','reader')
        Command 'chrome' @('--ez','visible','false')
        $hidden=Dump
        $hiddenAgain=Capture 'all-glass-hidden'
        Check ((Metric $hiddenAgain 'gpuActive') -eq 'false' -and (Metric $hidden 'analysisSamples') -eq (Metric $hiddenAgain 'analysisSamples')) 'hidden glass stops sampling'
    }
    if($Suite -eq 'fallback') {
        Command 'navigate' @('--es','scene','reader-settings','--es','category','THEME')
        foreach($mode in @('DAY','NIGHT')) {
            Command 'mode' @('--es','mode',$mode)
            $state=Capture "fallback-$mode"
            Check ((Metric $state 'gpuActive') -eq 'false' -and (Metric $state 'analysisSamples') -eq '0') "$mode fallback does not capture or analyze the background"
            Check ($state.uiDark -eq ($mode -eq 'NIGHT')) "$mode fallback follows the unified theme"
        }
    }
    if($Suite -eq 'lifecycle') {
        Command 'scroll' @('--ei','y','360')
        $foreground=Capture 'foreground'
        [void](Device @('shell','input','keyevent','KEYCODE_HOME'))
        $background=Dump
        Check ((Metric $background 'gpuActive') -eq 'false') 'stopping the Activity immediately disables the hardware material'
        $backgroundAgain=Dump
        Check ((Metric $background 'analysisSamples') -eq (Metric $backgroundAgain 'analysisSamples')) 'the background Activity does not continue sampling'
        [void](Device @('shell','am','start','-W','-n',$component,'-f','0x00020000'))
        $resumed=Capture 'resumed'
        CheckPosition $foreground $resumed 'returning to the existing reader Activity'
        Check ((Metric $resumed 'gpuActive') -eq 'true' -and (Metric $resumed 'fallbackMaterials') -eq '0') 'resuming reactivates the hardware material'
    }
    if($Suite -eq 'layout') {
        [void](Device @('shell','wm','size','640x1280'))
        [void](Device @('shell','wm','density','320'))
        [void](Device @('shell','settings','put','system','font_scale','1.6'))
        foreach($mode in @('DAY','NIGHT')) {
            Command 'mode' @('--es','mode',$mode)
            Command 'navigate' @('--es','scene','reader-settings','--es','category','THEME')
            $state=Capture "narrow-$mode"
            Check ($state.screenWidthDp -le 320 -and [Math]::Abs($state.fontScale-1.6) -lt .01) "$mode uses a real narrow viewport and large font"
        }
        [void](Device @('shell','settings','put','system','user_rotation','1'))
        Command 'panel' @('--es','name','note')
        Tap '' -EditText
        [void](Device @('shell','input','text','note'))
        $landscape=Capture 'landscape-note-ime'
        Check ($landscape.imeVisible -and $landscape.hardware.modalVisible) 'landscape note opens the IME in the reader window'
        $fieldTree=Ui
        $field=$fieldTree.SelectNodes('//node') | Where-Object {$_.GetAttribute('class') -eq 'android.widget.EditText'} | Select-Object -First 1
        Check ($null -ne $field -and $field.GetAttribute('text') -match 'note') 'the focused note field retains the typed text'
        $fieldBounds=Bounds $field
        $minimumLine=[Math]::Ceiling(24*$landscape.fontScale*$landscape.readerBounds.width/$landscape.screenWidthDp)
        $keyboardTop=$landscape.readerBounds.height-$landscape.imeBottom
        Check ($null -ne $fieldBounds -and $fieldBounds.Bottom-$fieldBounds.Top -ge $minimumLine -and $fieldBounds.Top -ge $landscape.statusInsetTop-1 -and $fieldBounds.Bottom -le $keyboardTop+1) 'landscape input has a visible text line above the real keyboard'
        [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
        $withoutIme=Dump
        Check (-not $withoutIme.imeVisible -and $withoutIme.hardware.modalVisible) 'Back keeps the note open after hiding the landscape keyboard'
        Tap '保存'
        $saved=Dump
        Check ($saved.hardware.noteSaveCount -eq 1 -and $saved.hardware.noteDraft -eq 'note' -and -not $saved.hardware.modalVisible) 'the landscape note can be saved after keyboard dismissal'
    }
    $processId=(Device @('shell','pidof',$package)).Trim()
    $crashes=Device @('logcat','-d','--pid',$processId,'-s','AndroidRuntime:E','*:S')
    $crashes | Set-Content -LiteralPath (Join-Path $output 'android-runtime.log') -Encoding utf8
    Check ($crashes -notmatch 'FATAL EXCEPTION') 'the tested process has no fatal exception'
    $passed=$true
} finally {
    Command 'stop-animation'
    [void](Device @('shell','input','keyevent','KEYCODE_BACK'))
    [void](Device @('shell','wm','size',$restoreSize))
    [void](Device @('shell','wm','density',$restoreDensity))
    if($originalScale -match '^\d+(\.\d+)?$') {[void](Device @('shell','settings','put','system','font_scale',$originalScale))}
    else {[void](Device @('shell','settings','delete','system','font_scale'))}
    [void](Device @('shell','cmd','uimode','night',$restoreNight))
    if($rotation -match '^[0-3]$') {[void](Device @('shell','settings','put','system','user_rotation',$rotation))}
    else {[void](Device @('shell','settings','delete','system','user_rotation'))}
    if($autoRotation -match '^[01]$') {[void](Device @('shell','settings','put','system','accelerometer_rotation',$autoRotation))}
    else {[void](Device @('shell','settings','delete','system','accelerometer_rotation'))}
    [ordered]@{Passed=$passed;Suite=$Suite;RunId=$RunId;Apk=$apkPath;SHA256=$apkHash;Checks=$checks;Captures=$captures;Scope='Owned emulator; production hardware host/material/overlay components with memory fixtures, not production book data or target-phone performance acceptance.'} |
        ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8
}
Write-Host "Reader hardware verification passed: $output"
