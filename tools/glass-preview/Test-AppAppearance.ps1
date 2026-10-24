[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$StartupOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$output = [IO.Path]::GetFullPath($OutputDirectory)
$allowed = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/verification')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use an evidence directory under build/verification.' }
if (Test-Path -LiteralPath $output) { throw 'Evidence directory already exists.' }
$status = & $driver -Action Status -RunId $RunId
if ($status.BootCompleted -ne '1' -or $status.ExitCode -ne 0) { throw 'The owned isolated emulator is not ready.' }
New-Item -ItemType Directory -Path $output | Out-Null
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
$component = 'com.mirad.reader.preview/com.mirad.reader.preview.SettingsImmersionPreviewActivity'
$package = 'com.mirad.reader.preview'
$action = 'com.mirad.reader.preview.IMMERSION_CHECK'
$prefix = [IO.Path]::GetFileName($output) -replace '[^a-zA-Z0-9-]', '-'
$checks = [Collections.Generic.List[object]]::new()
$captures = [Collections.Generic.List[object]]::new()
$counter = 0
$uiCounter = 0
$passed = $false
$installedHash = $null

function Device([string[]]$Arguments) {
    $result = & $driver -Action Adb -RunId $RunId -AdbArgs $Arguments
    if ($result.ExitCode -ne 0) { throw "Isolated ADB command failed: $($Arguments[0]) $($Arguments[1])" }
    [string]$result.Output
}
function Check([bool]$Condition, [string]$Name) {
    [void]$checks.Add([pscustomobject]@{Name=$Name;Passed=$Condition})
    if (-not $Condition) { throw $Name }
    Write-Host "PASS $Name"
}
function Command([string]$Operation, [string[]]$Extra=@()) {
    [void](Device (@('shell','am','broadcast','-a',$action,'--es','operation',$Operation)+$Extra))
}
function Dump {
    $script:counter++
    $label="$prefix-$script:counter"
    for($attempt=0; $attempt -lt 4; $attempt++) {
        Command 'dump' @('--es','label',$label)
        $logs=Device @('logcat','-d','-s','ImmersionPreview:I','*:S')
        $rows=@($logs -split "`n" | Where-Object { $_.Contains('"label":"'+$label+'"') -and $_ -match 'dump (\{.+\})' })
        if($rows.Count -gt 0) {
            $json=[regex]::Match($rows[-1],'dump (\{.+\})').Groups[1].Value
            $json | Set-Content -LiteralPath (Join-Path $output "state-$script:counter.json") -Encoding utf8
            return ($json | ConvertFrom-Json)
        }
        Start-Sleep -Milliseconds 200
    }
    throw "Missing appearance state dump $label"
}
function ReadUi {
    $script:uiCounter++
    $remote="/sdcard/appearance-$prefix-$script:uiCounter.xml"
    [void](Device @('shell','uiautomator','dump',$remote))
    $raw=Device @('shell','cat',$remote)
    $raw | Set-Content -LiteralPath (Join-Path $output "ui-$script:uiCounter.xml") -Encoding utf8
    [xml]$tree=$raw
    return ,$tree
}
function FindControl($Tree,[string]$Text) {
    $items=@($Tree.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -eq $Text -or $_.GetAttribute('content-desc') -eq $Text })
    foreach($item in $items) {
        $match=[regex]::Match($item.GetAttribute('bounds'),'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if($match.Success) {
            $v=@($match.Groups | Select-Object -Skip 1 | ForEach-Object {[int]$_.Value})
            if($v[2] -gt $v[0] -and $v[3] -gt $v[1]) {
                return [pscustomobject]@{Left=$v[0];Top=$v[1];Right=$v[2];Bottom=$v[3];X=[int](($v[0]+$v[2])/2);Y=[int](($v[1]+$v[3])/2)}
            }
        }
    }
    return $null
}
function Tap([string]$Text) {
    $tree=ReadUi
    $bounds=FindControl $tree $Text
    if($null -eq $bounds) { throw "Visible control not found: $Text" }
    [void](Device @('shell','input','tap',"$($bounds.X)","$($bounds.Y)"))
}
function Capture([string]$Name, [string[]]$RequiredControls = @('跟随系统')) {
    $state=Dump
    $tree=ReadUi
    Check (@($tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('package') -eq $package}).Count -gt 0) "$Name shows the isolated application"
    foreach($label in $RequiredControls) {
        Check ($null -ne (FindControl $tree $label)) "$Name has composed its [$label] control before capture"
    }
    $image=& $driver -Action Capture -RunId $RunId -Name "$prefix-$Name"
    $target=Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $image.Path -Destination $target
    [void]$captures.Add([pscustomobject]@{Name=$Name;Path=$target;State=$state})
    return $state
}

$originalScale=(Device @('shell','settings','get','system','font_scale')).Trim()
$originalSize=Device @('shell','wm','size')
$originalDensity=Device @('shell','wm','density')
$originalNight=Device @('shell','cmd','uimode','night')
$originalAutoRotation=(Device @('shell','settings','get','system','accelerometer_rotation')).Trim()
$originalRotation=(Device @('shell','settings','get','system','user_rotation')).Trim()
$restoreSize=if($originalSize -match 'Override size:\s*(\d+x\d+)'){$Matches[1]}else{'reset'}
$restoreDensity=if($originalDensity -match 'Override density:\s*(\d+)'){$Matches[1]}else{'reset'}
$restoreNight=if($originalNight -match 'Night mode:\s*(yes|no|auto|custom)'){$Matches[1]}else{'auto'}
try {
    & $driver -Action Install -RunId $RunId -Apk $apkPath | Out-Null
    $installedPath=(Device @('shell','pm','path',$package)).Trim()
    if($installedPath -notmatch '^package:(/data/app/[^\r\n]+/base\.apk)$') { throw 'Unexpected installed preview APK path.' }
    $installedApk=$Matches[1]
    $installedHash=((Device @('shell','sha256sum',$installedApk)) -split '\s+')[0]
    Check ($installedHash -eq $apkHash) 'the installed preview APK matches the pinned artifact'
    [void](Device @('shell','settings','put','system','font_scale','1.0'))
    [void](Device @('shell','settings','put','system','accelerometer_rotation','0'))
    [void](Device @('shell','settings','put','system','user_rotation','0'))
    [void](Device @('shell','cmd','uimode','night','no'))
    [void](Device @('shell','am','start','-S','-W','-n',$component,'--es','scene','appearance','--es','entry','settings','--es','category','THEME','--es','theme','GREEN','--ez','fullScreen','false'))
    # THEME 页承载日/夜样式分区编辑；应用模式控件在 APPEARANCE 页
    $initial=Capture 'initial-day' @('日间样式','夜间样式')
    Check ($initial.unifiedAppearance -and $initial.appearance.mode -eq 'SYSTEM' -and $initial.config.theme -eq 'GREEN') 'legacy green migrates into the shared day profile'
    if(-not $StartupOnly) {
    Tap '纸色'
    # 编辑夜间样式分区只写夜间组，不切换应用模式（分区编辑 ≠ 模式切换）
    Tap '夜间样式'
    Tap '纯黑'
    $nightEdited=Dump
    Check ($nightEdited.appearance.night.theme -eq 'BLACK' -and $nightEdited.config.theme -eq 'PAPER' -and -not $nightEdited.uiDark) 'editing the night section updates the night profile without switching the app mode'
    Command 'navigate' @('--es','scene','settings','--es','category','APPEARANCE')
    Tap '夜间'
    Command 'config' @('--es','background','night','--ef','imageAlpha','0.55')
    $night=Capture 'night-profile'
    Check ($night.uiDark -and $night.config.theme -eq 'BLACK') 'explicit night mode updates the settings surface and fine palette'
    Tap '日间'
    Command 'config' @('--es','background','paper','--ef','imageAlpha','0.8')
    $day=Capture 'day-profile'
    Check (-not $day.uiDark -and $day.config.theme -eq 'PAPER') 'returning to day restores its paper palette'
    Tap '夜间'
    $remembered=Dump
    Check ($remembered.config.theme -eq 'BLACK' -and $remembered.config.backgroundImageUri -eq 'builtin:night' -and [Math]::Abs($remembered.config.backgroundImageAlpha-0.55) -lt 0.001) 'night image and opacity are independent of day choices'

    Command 'navigate' @('--es','scene','reader-settings','--es','category','THEME')
    $reader=Capture 'reader-night' @('日间样式','夜间样式')
    Check ($reader.web.ready -and $reader.uiDark -and $reader.config.theme -eq 'BLACK') 'reader settings consume the same shared night mode'
    Command 'scroll' @('--ei','y','360')
    $before=Dump
    # 面板内切到应用外观子页执行模式切换；reader-settings 场景不重建正文
    Command 'navigate' @('--es','scene','reader-settings','--es','category','APPEARANCE')
    Tap '日间'
    $readerDay=Capture 'reader-day'
    Check ($readerDay.loads -eq $before.loads) 'switching appearance does not reload the reader document'
    Check ($readerDay.config.theme -eq 'PAPER' -and -not $readerDay.uiDark -and $readerDay.web.image -match 'paper.jpg') 'reader restores day palette and image'
    Check ([Math]::Abs($readerDay.web.scrollY-$before.web.scrollY) -le 1 -and [Math]::Abs($readerDay.web.glyph.top-$before.web.glyph.top) -le 1 -and $readerDay.web.glyph.offset -eq $before.web.glyph.offset) 'appearance switch preserves scroll and independent character position'
    Tap '跟随系统'
    [void](Device @('shell','cmd','uimode','night','yes'))
    $followNight=Capture 'follow-system-night'
    Check ($followNight.appearance.mode -eq 'SYSTEM' -and $followNight.systemDark -and $followNight.config.theme -eq 'BLACK') 'system night selects the saved night profile'
    [void](Device @('shell','input','keyevent','KEYCODE_HOME'))
    [void](Device @('shell','cmd','uimode','night','no'))
    [void](Device @('shell','am','start','-W','-f','0x20000','-n',$component))
    $resumed=Capture 'resumed-day'
    Check ($resumed.appearance.mode -eq 'SYSTEM' -and -not $resumed.systemDark -and $resumed.config.theme -eq 'PAPER') 'resume applies the current system day mode and remembered profile'
    Command 'reopen-store'
    $reopened=Dump
    Check ($reopened.repositoryGeneration -eq 1 -and $reopened.savedAppearance.day.theme -eq 'PAPER' -and $reopened.savedAppearance.night.theme -eq 'BLACK') 'a fresh repository reads both profiles from the retained preference DAO'

    [void](Device @('shell','wm','size','640x1280'))
    [void](Device @('shell','wm','density','320'))
    [void](Device @('shell','settings','put','system','font_scale','1.6'))
    foreach($entry in @('settings','reader-settings')) {
        foreach($mode in @('DAY','NIGHT')) {
            Command 'navigate' @('--es','scene',$entry,'--es','category','THEME')
            Command 'mode' @('--es','mode',$mode)
            $layout=Capture "narrow-$entry-$mode" @('日间样式','夜间样式')
            Check ($layout.screenWidthDp -le 320 -and [Math]::Abs($layout.fontScale-1.6) -lt 0.01) "$entry $mode uses an actual narrow viewport and large font setting"
            $tree=ReadUi
            foreach($label in @('日间样式','夜间样式')) {
                $bounds=FindControl $tree $label
                Check ($null -ne $bounds -and $bounds.Left -ge 0 -and $bounds.Right -le 640) "$entry $mode has a visible bounded $label control"
            }
            # 应用模式控件在 APPEARANCE 页：同一轮窄屏布局下逐页校验
            Command 'navigate' @('--es','scene',$entry,'--es','category','APPEARANCE')
            $modeTree=ReadUi
            foreach($label in @('跟随系统','日间','夜间')) {
                $bounds=FindControl $modeTree $label
                Check ($null -ne $bounds -and $bounds.Left -ge 0 -and $bounds.Right -le 640) "$entry $mode has a visible bounded $label control on the appearance page"
            }
        }
    }
    }
    $files=Device @('shell','run-as',$package,'ls')
    Check (-not (@($files -split '\s+') -contains 'databases')) 'the fixture has not created a business database directory'
    $processId=(Device @('shell','pidof',$package)).Trim()
    $crashes=Device @('logcat','-d','--pid',$processId,'-s','AndroidRuntime:E','*:S')
    $crashes | Set-Content -LiteralPath (Join-Path $output 'android-runtime.log') -Encoding utf8
    Check ($crashes -notmatch 'FATAL EXCEPTION') 'the tested application process has no fatal runtime exception'
    $passed=$true
} finally {
    if($originalScale -match '^\d+(\.\d+)?$') { [void](Device @('shell','settings','put','system','font_scale',$originalScale)) }
    else { [void](Device @('shell','settings','delete','system','font_scale')) }
    [void](Device @('shell','wm','size',$restoreSize))
    [void](Device @('shell','wm','density',$restoreDensity))
    [void](Device @('shell','cmd','uimode','night',$restoreNight))
    if($originalRotation -match '^[0-3]$') { [void](Device @('shell','settings','put','system','user_rotation',$originalRotation)) }
    else { [void](Device @('shell','settings','delete','system','user_rotation')) }
    if($originalAutoRotation -match '^[01]$') { [void](Device @('shell','settings','put','system','accelerometer_rotation',$originalAutoRotation)) }
    else { [void](Device @('shell','settings','delete','system','accelerometer_rotation')) }
    [ordered]@{Passed=$passed;StartupOnly=[bool]$StartupOnly;RunId=$RunId;Apk=$apkPath;SHA256=$apkHash;InstalledSHA256=$installedHash;ScriptSHA256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash;Checks=$checks;Captures=$captures;Scope='Isolated emulator and in-memory preference DAO; not physical-device performance or a durable Room migration test'} |
        ConvertTo-Json -Depth 18 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8
}
Write-Host "Appearance verification passed: $output"
