[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('settings','theme','window','layout')][string]$Suite = 'settings',
    [switch]$WindowSmoke
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$output = [IO.Path]::GetFullPath($OutputDirectory)
$allowedOutput = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/verification')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowedOutput, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use a fresh directory under build/verification.' }
if (Test-Path -LiteralPath $output) { throw 'Evidence directory already exists; never relabel previous captures.' }
New-Item -ItemType Directory -Path $output | Out-Null
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
$component = 'com.mirad.reader.preview/com.mirad.reader.preview.SettingsImmersionPreviewActivity'
$action = 'com.mirad.reader.preview.IMMERSION_CHECK'
$prefix = ([IO.Path]::GetFileName($output) -replace '[^a-zA-Z0-9-]', '-')
$captures = [Collections.Generic.List[object]]::new()
$checks = [Collections.Generic.List[object]]::new()
$dumpNumber = 0
$uiNumber = 0
$deviceWidth = 1080
$deviceHeight = 2400
$completed = $false
$scriptHash = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash

function Device([string[]]$AdbArguments) {
    $result = & $driver -Action Adb -RunId $RunId -AdbArgs $AdbArguments
    if ($result.ExitCode -ne 0) { throw "ADB operation failed: $($AdbArguments[0]) $($AdbArguments[1])" }
    return [string]$result.Output
}
function Check([bool]$Condition, [string]$Message) {
    [void]$checks.Add([pscustomobject]@{ Name=$Message; Passed=$Condition })
    if (-not $Condition) { throw $Message }
    Write-Host "PASS $Message"
}
function Command([string]$Operation, [string[]]$Extra = @()) {
    [void](Device (@('shell','am','broadcast','-a',$action,'--es','operation',$Operation) + $Extra))
}
function StartScene([string[]]$Extra) {
    [void](Device (@('shell','am','start','-S','-W','-n',$component) + $Extra))
    Start-Sleep -Milliseconds 800
}
function Navigate([string]$Scene, [string]$Category = '') {
    $extra = @('--es','scene',$Scene)
    if ($Category) { $extra += @('--es','category',$Category) }
    Command 'navigate' $extra
}
function Dump {
    $script:dumpNumber++
    $label = "$prefix-$script:dumpNumber"
    Command 'dump' @('--es','label',$label)
    for ($attempt=0; $attempt -lt 3; $attempt++) {
        $logs = Device @('logcat','-d','-s','ImmersionPreview:I','*:S')
        $match = @($logs -split "`n" | Where-Object { $_ -match 'dump (\{.+\})' -and $_.Contains('"label":"'+$label+'"') })
        if ($match.Count -gt 0) {
            $json = [regex]::Match($match[-1], 'dump (\{.+\})').Groups[1].Value
            $json | Set-Content -LiteralPath (Join-Path $output "state-$script:dumpNumber.json") -Encoding utf8
            return ($json | ConvertFrom-Json)
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Missing native state dump $label"
}
function ReadUi([bool]$DismissedTutorial = $false) {
    $script:uiNumber++
    $remoteUi="/sdcard/immersion-$prefix-$script:uiNumber.xml"
    for($attempt=0;$attempt -lt 2;$attempt++) {
        try {
            $dumpOutput=Device @('shell','uiautomator','dump',$remoteUi)
            if($dumpOutput -notmatch 'dumped to:'){throw 'UI hierarchy was not written.'}
            break
        } catch {
            if($attempt -eq 1){throw}
            Write-Host 'Retrying one failed UI hierarchy read.'
            Start-Sleep -Milliseconds 300
        }
    }
    $raw = Device @('shell','cat',$remoteUi)
    $raw | Set-Content -LiteralPath (Join-Path $output "ui-$script:uiNumber.xml") -Encoding utf8
    [xml]$tree = $raw
    $tutorial = @($tree.SelectNodes('//node') | Where-Object { $_.GetAttribute('package') -eq 'com.android.systemui' -and $_.GetAttribute('text') -eq 'Viewing full screen' })
    $confirm = @($tree.SelectNodes('//node') | Where-Object { $_.GetAttribute('package') -eq 'com.android.systemui' -and $_.GetAttribute('text') -eq 'Got it' })
    if ($tutorial.Count -gt 0 -and $confirm.Count -eq 1) {
        if ($DismissedTutorial) { throw 'System immersive tutorial did not dismiss.' }
        $button = Bounds $confirm[0]
        [void](Device @('shell','input','tap',"$($button.X)","$($button.Y)"))
        Write-Host 'Dismissed the owned emulator immersive-mode tutorial.'
        return ReadUi $true
    }
    return ,$tree
}
function Bounds($Node) {
    $m = [regex]::Match($Node.GetAttribute('bounds'), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { return $null }
    $v = @($m.Groups | Select-Object -Skip 1 | ForEach-Object { [int]$_.Value })
    if ($v[2] -le $v[0] -or $v[3] -le $v[1]) { return $null }
    return [pscustomobject]@{ Left=$v[0];Top=$v[1];Right=$v[2];Bottom=$v[3];X=[int](($v[0]+$v[2])/2);Y=[int](($v[1]+$v[3])/2) }
}
function ScrollPage([bool]$Down = $true) {
    $x=[int]($script:deviceWidth*.55)
    $high=[int]($script:deviceHeight*.43)
    $low=[int]($script:deviceHeight*.85)
    if ($Down) { [void](Device @('shell','input','swipe',"$x","$low","$x","$high",'350')) }
    else { [void](Device @('shell','input','swipe',"$x","$high","$x","$low",'350')) }
}
function PageTexts([int]$Pages = 2) {
    $texts = [Collections.Generic.HashSet[string]]::new()
    for ($page=0; $page -lt $Pages; $page++) {
        $tree = ReadUi
        foreach ($node in $tree.SelectNodes('//node')) {
            if ($null -ne (Bounds $node)) {
                foreach ($value in @($node.GetAttribute('text'), $node.GetAttribute('content-desc'))) {
                    if ($value) { [void]$texts.Add($value) }
                }
            }
        }
        if ($page -lt $Pages-1) { ScrollPage }
    }
    return ,$texts
}
function Tap([string]$Text, [switch]$Switch) {
    for ($attempt=0; $attempt -lt 4; $attempt++) {
        $tree = ReadUi
        $nodes = @($tree.SelectNodes('//node') | Where-Object { ($_.GetAttribute('text') -eq $Text -or $_.GetAttribute('content-desc') -eq $Text) -and $null -ne (Bounds $_) })
        if ($nodes.Count -gt 0) {
            $b = Bounds $nodes[0]
            if ($Switch) {
                $switchNodes = @($tree.SelectNodes('//node') | Where-Object { $_.GetAttribute('checkable') -eq 'true' -and $_.GetAttribute('clickable') -eq 'true' -and $null -ne (Bounds $_) } | Sort-Object { [Math]::Abs((Bounds $_).Y - $b.Y) })
                if ($switchNodes.Count -eq 0) { throw "No switch near $Text" }
                $near = Bounds $switchNodes[0]
                if ([Math]::Abs($near.Y - $b.Y) -gt 80) { throw "Switch row mismatch for $Text" }
                $b = $near
            }
            [void](Device @('shell','input','tap',"$($b.X)","$($b.Y)"))
            return
        }
        ScrollPage
    }
    throw "Control not found: $Text"
}
function Capture([string]$Name, [string]$ExpectedScene, [string]$ExpectedText='') {
    $state=Dump
    Check ($state.scene -eq $ExpectedScene) "$Name scene=$ExpectedScene"
    $tree=ReadUi
    Check (@($tree.SelectNodes('//node')|Where-Object{$_.GetAttribute('package') -eq 'com.mirad.reader.preview'}).Count -gt 0) "$Name foreground preview UI"
    if ($ExpectedText) {
        Check (@($tree.SelectNodes('//node')|Where-Object{$_.GetAttribute('text') -eq $ExpectedText -or $_.GetAttribute('content-desc') -eq $ExpectedText}).Count -gt 0) "$Name target control present"
    }
    if ($ExpectedScene -eq 'reader') { Check ([bool]$state.web.ready) "$Name reader ready" }
    $captureName=("$prefix-$Name" -replace '[^a-zA-Z0-9_-]', '-')
    $source=Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/captures/$captureName.png"
    if(Test-Path -LiteralPath $source){throw 'Capture name already exists.'}
    & $driver -Action Capture -RunId $RunId -Name $captureName | Out-Null
    $target=Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $source -Destination $target
    [void]$captures.Add([pscustomobject]@{Name=$Name;Path=$target;SHA256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash;ApkSHA256=$apkHash;State=$state})
    return $state
}

# Compare painted body lines in this fixed portrait fixture. System inset updates can
# change document scrollY while the same character stays at the same screen position.
function ReaderTextLines([string]$Name,[bool]$Dark) {
    Add-Type -AssemblyName System.Drawing
    $bitmap=[Drawing.Bitmap]::new((Join-Path $output "$Name.png"))
    $bands=[Collections.Generic.List[object]]::new()
    try {
        $start=-1
        for($y=250;$y -lt $bitmap.Height-200;$y++) {
            $count=0
            for($x=100;$x -lt $bitmap.Width-100;$x+=4) {
                $pixel=$bitmap.GetPixel($x,$y)
                $value=($pixel.R+$pixel.G+$pixel.B)/3
                if(($Dark -and $value -gt 160) -or (-not $Dark -and $value -lt 90)){$count++}
            }
            if($count -ge 10){if($start -lt 0){$start=$y}}
            elseif($start -ge 0){
                if($y-$start -ge 3){$bands.Add([pscustomobject]@{Start=$start;End=$y-1})}
                $start=-1
            }
        }
        if($start -ge 0){$bands.Add([pscustomobject]@{Start=$start;End=$bitmap.Height-201})}
    } finally {$bitmap.Dispose()}
    $lines=[Collections.Generic.List[object]]::new()
    foreach($band in $bands) {
        # Join gaps within a glyph; the fixture's separate text lines are over 50px apart.
        if($lines.Count -gt 0 -and $band.Start-$lines[$lines.Count-1].End -le 32){$lines[$lines.Count-1].End=$band.End}
        else{$lines.Add([pscustomobject]@{Start=$band.Start;End=$band.End})}
    }
    return ,$lines.ToArray()
}
function ReaderPositionMatches($Before,$After,[string]$BeforeName,[string]$AfterName) {
    $beforeLines=ReaderTextLines $BeforeName $Before.effectiveDark
    $afterLines=ReaderTextLines $AfterName $After.effectiveDark
    $maxDelta=-1
    if($beforeLines.Count -ge 10 -and $beforeLines.Count -eq $afterLines.Count) {
        # Ignore the two crop-edge lines: a partial glyph there has color-dependent ink bounds.
        $deltas=for($i=1;$i -lt $beforeLines.Count-1;$i++) {
            [Math]::Abs($beforeLines[$i].Start-$afterLines[$i].Start)
            [Math]::Abs($beforeLines[$i].End-$afterLines[$i].End)
        }
        $maxDelta=($deltas|Measure-Object -Maximum).Maximum
    }
    $sameAnchor=$Before.web.snapshot.cfi -eq $After.web.snapshot.cfi -and $Before.web.snapshot.offset -eq $After.web.snapshot.offset
    $sameViewport=$Before.web.width -eq $After.web.width -and $Before.web.height -eq $After.web.height
    $passed=$sameAnchor -and $sameViewport -and $maxDelta -ge 0 -and $maxDelta -le 2
    [pscustomobject]@{Passed=$passed;BeforeLines=$beforeLines;AfterLines=$afterLines;MaxPhysicalPixelDelta=$maxDelta;SameAnchor=$sameAnchor;SameViewport=$sameViewport;BeforeScrollY=$Before.web.scrollY;AfterScrollY=$After.web.scrollY} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output "position-$BeforeName-$AfterName.json") -Encoding utf8
    return $passed
}

try {
    $status=& $driver -Action Status -RunId $RunId
    if($status.BootCompleted -ne '1'){throw 'Owned preview emulator is not ready.'}
    & $driver -Action Install -RunId $RunId -Apk $apkPath | Out-Null
    [void](Device @('shell','settings','put','system','font_scale','1.0'))
    [void](Device @('shell','settings','put','system','accelerometer_rotation','0'))
    [void](Device @('shell','settings','put','system','user_rotation','0'))
    [void](Device @('shell','cmd','uimode','night','no'))

    if($Suite -eq 'settings') {
        StartScene @('--es','scene','settings')
        [void](Capture 'global-home' 'settings' '阅读设置')
        $text=PageTexts
        foreach($label in @('字体与排版','阅读主题与背景','翻页与交互','屏幕','页眉页脚','EPUB','朗读','应用设置','界面外观','书架管理','关于')){Check ($text.Contains($label)) "global category $label"}
        Navigate 'settings' 'INTERACTION'
        $text=PageTexts 1
        Check (-not $text.Contains('翻页动画') -and -not $text.Contains('点击区域')) 'scroll hides paged-only controls'
        Tap '左右翻页'
        $text=PageTexts 1
        Check ($text.Contains('翻页动画') -and $text.Contains('点击区域')) 'paged reveals animation and tap zones'
        Tap '上下滚动';Tap '左右翻页'
        $state=Capture 'interaction-paged' 'settings' '翻页动画'
        Check ($state.config.pageAnim -eq 'COVER' -and $state.config.tapZone -eq 'TWO') 'mode switches preserve saved child options'
        Navigate 'settings' 'HEADER_FOOTER'
        Tap '显示页眉' -Switch
        Tap '显示页脚' -Switch
        Navigate 'settings' 'HEADER_FOOTER'
        $text=PageTexts 3
        foreach($label in @('显示章节名','显示时间','显示电量','显示页码')){Check (-not $text.Contains($label)) "hidden header/footer child $label"}
        $state=Capture 'screen-children-hidden' 'settings'
        Check (-not $state.config.showHeader -and -not $state.config.showFooter -and $state.config.showClock -and $state.config.showBattery -and $state.config.showPageNumber) 'hidden children retain their values'
        Navigate 'settings' 'HEADER_FOOTER';Tap '显示页眉' -Switch;Tap '显示页脚' -Switch
        Navigate 'settings' 'HEADER_FOOTER';$text=PageTexts 3
        foreach($label in @('显示章节名','显示时间','显示电量','显示页码')){Check ($text.Contains($label)) "restored header/footer child $label"}
        Navigate 'settings' 'HEADER_FOOTER';Tap '显示章节名' -Switch
        $state=Capture 'chapter-title-off' 'settings'
        Check (-not $state.config.showChapterTitleInHeader) 'chapter title toggle turns the setting off'
        Navigate 'settings' 'HEADER_FOOTER';Tap '显示章节名' -Switch
        Navigate 'settings' 'EPUB';Tap '保留原书'
        Navigate 'settings' 'TYPOGRAPHY';$text=PageTexts 3
        Check ($text.Contains('字号') -and $text.Contains('行距')) 'global typography stays available with original EPUB preference'
        Navigate 'reader-settings'
        $text=PageTexts 3
        Check ($text.Contains('字号') -and -not $text.Contains('EPUB') -and $text.Contains('朗读')) 'TXT reader keeps typography and speech but hides EPUB'
        Command 'format' @('--ez','isEpub','true')
        Navigate 'reader-settings';$text=PageTexts 3
        Check (-not $text.Contains('字号') -and $text.Contains('EPUB')) 'original EPUB hides quick font size'
        Navigate 'reader-settings' 'TYPOGRAPHY';$text=PageTexts 4
        Check ($text.Contains('页边距') -and $text.Contains('EPUB 排版') -and $text.Contains('字号') -and $text.Contains('行距')) 'original EPUB keeps typography visible with recovery entry'
        Navigate 'reader-settings' 'TYPOGRAPHY'
        [void](Capture 'epub-original-typography' 'reader-settings' 'EPUB 排版')
        Tap 'EPUB 排版';Tap '统一排版'
        Navigate 'reader-settings' 'TYPOGRAPHY';$text=PageTexts 3
        Check ($text.Contains('字号') -and $text.Contains('行距')) 'unified EPUB restores typography'
        $state=Dump
        Check ($state.savedConfig.epubLayoutMode -eq 'UNIFIED' -and $state.savedConfig.fontSizeSp -eq 24) 'shared repository persists mode without resetting font size'
    }
    if($Suite -eq 'theme') {
        StartScene @('--es','scene','settings','--es','category','THEME','--es','theme','SYSTEM','--es','background','paper','--ef','imageAlpha','0.37')
        Tap '羊皮纸'
        $state=Dump
        Check ($state.config.theme -eq 'SYSTEM' -and [Math]::Abs($state.config.backgroundImageAlpha-.37) -lt .001 -and $state.config.backgroundImageUri -eq 'builtin:parchment') 'background preset preserves system theme and opacity'
        Tap '纯色背景'
        $text=PageTexts 1
        Check (-not $text.Contains('背景不透明度')) 'color mode hides image-only opacity'
        Tap '图片背景';$state=Dump
        Check ([Math]::Abs($state.config.backgroundImageAlpha-.37) -lt .001 -and $state.config.backgroundImageUri -eq 'builtin:parchment') 'return to image mode preserves image and opacity'
        [void](Capture 'theme-system-image' 'settings' '夜间样式')
        Tap '自定义浅色';[void](Capture 'theme-custom' 'settings' '自定义浅色')
        # legacy 模式（scene≠appearance）没有应用模式控件；自定义主题演示完成后恢复 SYSTEM 主题，
        # 与旧 UI 中"Tap 跟随系统"步骤等价，供后续 system light/dark 段使用。
        Command 'config' @('--es','theme','SYSTEM')
        Navigate 'reader'
        Command 'scroll' @('--ei','y','360')
        $light=Capture 'system-light' 'reader'
        [void](Device @('shell','cmd','uimode','night','yes'))
        $dark=Capture 'system-dark' 'reader'
        Check ($dark.systemDark -and $dark.effectiveDark -and $dark.web.background -eq 'rgb(26, 28, 30)') 'real system night mode refreshes reader CSS'
        Check ($dark.config.theme -eq 'SYSTEM' -and $dark.loads -eq $light.loads -and (ReaderPositionMatches $light $dark 'system-light' 'system-dark')) 'system theme refresh preserves preference, chapter and position'
        [void](Device @('shell','input','keyevent','3'))
        [void](Device @('shell','cmd','uimode','night','no'))
        [void](Device @('shell','am','start','-W','-n',$component,'-f','0x20000000'))
        $resumed=Capture 'system-resumed' 'reader'
        Check (-not $resumed.systemDark -and $resumed.web.background -eq 'rgb(255, 255, 255)' -and $resumed.loads -eq $dark.loads -and (ReaderPositionMatches $dark $resumed 'system-dark' 'system-resumed')) 'resume follows current system theme without reload or jump'
        Command 'config' @('--es','theme','PAPER')
        [void](Device @('shell','cmd','uimode','night','yes'))
        $manual=Capture 'manual-paper-system-dark' 'reader'
        Check ($manual.config.theme -eq 'PAPER' -and $manual.web.background -eq 'rgb(245, 241, 230)') 'manual theme is not overwritten by system changes'
        Navigate 'settings' 'THEME';Tap '纸纹'
        $manualImage=Dump
        Check ($manualImage.config.theme -eq 'PAPER' -and [Math]::Abs($manualImage.config.backgroundImageAlpha-.37) -lt .001 -and $manualImage.config.backgroundImageUri -eq 'builtin:paper') 'background preset also preserves manual theme and opacity'
    }
    if($Suite -eq 'window') {
        foreach($cutout in @($false,$true)) {
            if($cutout){[void](Device @('shell','cmd','overlay','enable','com.android.internal.display.cutout.emulation.tall'))}
            else{[void](Device @('shell','cmd','overlay','disable','com.android.internal.display.cutout.emulation.tall'))}
            foreach($rotation in @(0,1,3)) {
                [void](Device @('shell','settings','put','system','user_rotation',"$rotation"))
                $script:deviceWidth=if($rotation -eq 0){1080}else{2400};$script:deviceHeight=if($rotation -eq 0){2400}else{1080}
                foreach($theme in @('PAPER','DARK')) {
                    foreach($background in @('color','night')) {
                        if($WindowSmoke -and (-not $cutout -or $rotation -eq 0 -or $theme -ne 'PAPER' -or $background -ne 'color')){continue}
                        $extra=@('--es','scene','reader','--es','theme',$theme,'--ez','fullScreen','true')
                        if($background -ne 'color'){$extra+=@('--es','background',$background)}
                        StartScene $extra
                        $name="window-$cutout-$rotation-$theme-$background"
                        $state=Capture $name 'reader'
                        Check (-not $state.statusVisible -and -not $state.navigationVisible) "$name system bars hidden"
                        Check ($state.web.cutoutTextOverlaps -eq 0) "$name text avoids physical cutout"
                    }
                }
            }
        }
        foreach($rotation in @(1,3)) {
            [void](Device @('shell','settings','put','system','user_rotation',"$rotation"))
            StartScene @('--es','scene','reader','--es','theme','PAPER','--es','background','night','--es','pageMode','PAGED','--ez','chrome','true')
            $paged=Capture "paged-cutout-$rotation" 'reader' '更多'
            Check ($paged.web.cutoutTextOverlaps -eq 0) "paged rotation $rotation text avoids cutout"
        }
        [void](Device @('shell','settings','put','system','user_rotation','0'))
        $script:deviceWidth=1080;$script:deviceHeight=2400
        StartScene @('--es','scene','reader','--es','theme','PAPER','--es','background','night')
        Command 'scroll' @('--ei','y','360');$before=Dump
        Command 'config' @('--ez','fullScreen','false')
        $visible=Capture 'bars-visible' 'reader'
        Check ($visible.statusVisible -and $visible.navigationVisible) 'full-screen off restores system bars'
        Command 'config' @('--ez','fullScreen','true')
        $hidden=Capture 'bars-hidden-again' 'reader'
        Check (-not $hidden.statusVisible -and -not $hidden.navigationVisible -and [Math]::Abs($hidden.web.scrollY-$before.web.scrollY) -le 1) 'full-screen toggle preserves reading position'
        Check ($hidden.web.cutoutTextOverlaps -eq 0) 'scrolled text avoids cutout after full-screen toggle'
        Command 'chrome' @('--ez','visible','true');[void](Capture 'reader-chrome-cutout' 'reader' '更多')
        Tap '更多';[void](Capture 'reader-menu-cutout' 'reader')
        Tap '阅读设置';[void](Capture 'reader-settings-cutout' 'reader-settings' '阅读设置')
        Navigate 'reader';Command 'dialog' @('--ez','visible','true');[void](Capture 'reader-dialog-cutout' 'reader' '写笔记')
        Tap '保存';$closed=Capture 'reader-dialog-closed' 'reader'
        Check (-not $closed.statusVisible -and -not $closed.navigationVisible) 'closing dialog preserves immersive bars'
        [void](Device @('shell','input','swipe','280','1','280','450','350'))
        Start-Sleep -Seconds 4
        $transientUi=ReadUi
        $foregroundApp=@($transientUi.SelectNodes('//node')|Where-Object{$_.GetAttribute('package') -eq 'com.mirad.reader.preview'}).Count
        $foregroundSystem=@($transientUi.SelectNodes('//node')|Where-Object{$_.GetAttribute('package') -eq 'com.android.systemui'}).Count
        if($foregroundApp -eq 0 -and $foregroundSystem -gt 0){[void](Device @('shell','input','keyevent','4'))}
        $transient=Capture 'transient-bars-restored' 'reader'
        Check (-not $transient.statusVisible) 'transient system bars do not leave a permanent status strip'
    }
    if($Suite -eq 'layout') {
        foreach($scale in @('1.0','1.3','1.6')) {
            [void](Device @('shell','settings','put','system','font_scale',$scale))
            foreach($rotation in @(0,1)) {
                [void](Device @('shell','settings','put','system','user_rotation',"$rotation"))
                $script:deviceWidth=if($rotation -eq 0){1080}else{2400};$script:deviceHeight=if($rotation -eq 0){2400}else{1080}
                foreach($entry in @('settings','reader-settings')) {
                    StartScene @('--es','scene',$entry,'--es','category','THEME','--es','theme','SYSTEM')
                    $state=Capture "layout-$entry-$scale-$rotation" $entry '夜间样式'
                    Check ([Math]::Abs($state.fontScale-[float]$scale) -lt .001) "$entry actual system font scale $scale"
                }
            }
        }
    }
    $completed = $true
} catch {
    [void]$checks.Add([pscustomobject]@{ Name="suite completed: $($_.Exception.Message)"; Passed=$false })
    throw
} finally {
    [void](Device @('shell','settings','put','system','font_scale','1.0'))
    [void](Device @('shell','settings','put','system','user_rotation','0'))
    [void](Device @('shell','cmd','uimode','night','no'))
    [void](Device @('shell','cmd','overlay','disable','com.android.internal.display.cutout.emulation.tall'))
    $captures | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $output 'captures.json') -Encoding utf8
    $checks | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'checks.json') -Encoding utf8
    [pscustomobject]@{Suite=$Suite;WindowSmoke=[bool]$WindowSmoke;Completed=$completed;RunId=$RunId;Apk=$apkPath;ApkSHA256=$apkHash;ScriptSHA256=$scriptHash;Passed=@($checks|Where-Object{$_.Passed}).Count;Failed=@($checks|Where-Object{-not $_.Passed}).Count;Captures=$captures.Count} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8
}
