[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('all','shelf','groups','choices')][string]$Suite = 'all'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$output = [IO.Path]::GetFullPath($OutputDirectory)
$boundary = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/verification')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must stay under build/verification.' }
if (Test-Path -LiteralPath $output) { throw 'Evidence directory already exists.' }
New-Item -ItemType Directory -Path $output | Out-Null
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
$component = 'com.mirad.reader.preview/com.mirad.reader.preview.ShelfInteractionPreviewActivity'
$action = 'com.mirad.reader.preview.SHELF_INTERACTION_CHECK'
$prefix = ([IO.Path]::GetFileName($output) -replace '[^a-zA-Z0-9_-]','-')
$checks = [Collections.Generic.List[object]]::new()
$captures = [Collections.Generic.List[object]]::new()
$uiNumber = 0
$stateNumber = 0
$completed = $false
$suiteExitCode = 1

function Device([string[]]$Arguments) {
    $result = & $driver -Action Adb -RunId $RunId -AdbArgs $Arguments
    if ($result.ExitCode -ne 0) { throw "Owned ADB operation failed: $($Arguments[0])" }
    return [string]$result.Output
}
function Check([bool]$Condition, [string]$Name) {
    $checks.Add([pscustomobject]@{Name=$Name;Passed=$Condition})
    if (-not $Condition) { throw $Name }
    Write-Host "PASS $Name"
}
function ReadUi {
    $script:uiNumber++
    $remote = "/sdcard/shelf-$prefix-$script:uiNumber.xml"
    $result = Device @('shell','uiautomator','dump','--compressed',$remote)
    if ($result -notmatch 'dumped to:') { throw 'Compressed UI hierarchy was not written.' }
    $raw = Device @('shell','cat',$remote)
    $raw | Set-Content -LiteralPath (Join-Path $output "ui-$script:uiNumber.xml") -Encoding utf8
    return ,([xml]$raw)
}
function Bounds($Node) {
    $m = [regex]::Match($Node.GetAttribute('bounds'),'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    if (-not $m.Success) { return $null }
    $v = @($m.Groups | Select-Object -Skip 1 | ForEach-Object { [int]$_.Value })
    if ($v[2] -le $v[0] -or $v[3] -le $v[1] -or $v[2] -le 0 -or $v[3] -le 0) { return $null }
    return [pscustomobject]@{X=[int](($v[0]+$v[2])/2);Y=[int](($v[1]+$v[3])/2)}
}
function FindLabel($Tree, [string]$Label, [switch]$First) {
    $matches = @($Tree.SelectNodes('//node') | Where-Object {
        ($_.GetAttribute('text') -eq $Label -or $_.GetAttribute('content-desc') -eq $Label) -and $null -ne (Bounds $_)
    })
    if ($matches.Count -eq 0) { return $null }
    if ($First) { return $matches[0] }
    return $matches[-1]
}
function Tap([string]$Label, [switch]$First, [switch]$Long) {
    $tree = ReadUi
    $node = FindLabel $tree $Label -First:$First
    # 半展开菜单可能把末项放在视口外；只按当前树中的拖动柄展开一次，再重新定位。
    if ($null -eq $node) {
        $handle = FindLabel $tree 'Drag handle'
        if ($null -ne $handle) {
            $drag = Bounds $handle
            $top = [int]($drag.Y * 0.35)
            [void](Device @('shell','input','swipe',"$($drag.X)","$($drag.Y)","$($drag.X)","$top",'500'))
            $tree = ReadUi
            $node = FindLabel $tree $Label -First:$First
        }
    }
    if ($null -eq $node) { throw "Control not found: $Label" }
    $point = Bounds $node
    if ($Long) { [void](Device @('shell','input','swipe',"$($point.X)","$($point.Y)","$($point.X)","$($point.Y)",'650')) }
    else { [void](Device @('shell','input','tap',"$($point.X)","$($point.Y)")) }
}
function Dump([string]$Operation='dump',[string]$Target='') {
    $script:stateNumber++
    $label = "$prefix-$script:stateNumber"
    $arguments = @('shell','am','broadcast','-p','com.mirad.reader.preview','-a',$action,'--es','operation',$Operation,'--es','label',$label)
    if ($Target) { $arguments += @('--es','target',$Target) }
    [void](Device $arguments)
    for ($attempt=0;$attempt -lt 6;$attempt++) {
        Start-Sleep -Milliseconds 150
        $raw = Device @('shell','run-as','com.mirad.reader.preview','cat','files/shelf-interaction-state.json')
        $state = $raw | ConvertFrom-Json
        if ($state.label -eq $label) {
            $raw | Set-Content -LiteralPath (Join-Path $output "state-$script:stateNumber.json") -Encoding utf8
            return $state
        }
    }
    throw "State not ready: $label"
}
function StartScene([string[]]$Extra) {
    [void](Device (@('shell','am','start','-S','-W','-n',$component) + $Extra))
    Start-Sleep -Milliseconds 600
    [void](ReadUi)
}
function Capture([string]$Name) {
    [void](ReadUi)
    $state = Dump
    $captureName = "$prefix-$Name"
    & $driver -Action Capture -RunId $RunId -Name $captureName | Out-Null
    $source = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/captures/$captureName.png"
    $target = Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $source -Destination $target
    $captures.Add([pscustomobject]@{Name=$Name;Path=$target;SHA256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash;State=$state})
}
function CheckChoice($State,[string]$Selected,[string[]]$Options) {
    foreach ($label in $Options) {
        $nodes = @($State.semantics | Where-Object { $_.clickable -and @($_.labels) -contains $label })
        Check ($nodes.Count -eq 1) "merged semantics identify one $label control"
        Check ($nodes[0].selected -eq ($label -eq $Selected)) "merged selection state $label"
    }
    $tree = ReadUi
    foreach ($label in $Options) {
        $node = FindLabel $tree $label
        Check ($null -ne $node) "compressed tree exposes $label"
        $selectedFlag = $false
        $current = $node
        while ($current -is [System.Xml.XmlElement]) {
            # Android把RadioButton的选择语义映射为checkable/checked；普通分段项使用selected。
            $selectedFlag = $selectedFlag -or $current.GetAttribute('selected') -eq 'true' -or
                ($current.GetAttribute('checkable') -eq 'true' -and $current.GetAttribute('checked') -eq 'true')
            if ($current.GetAttribute('clickable') -eq 'true') { break }
            $current = $current.ParentNode
        }
        Check ($selectedFlag -eq ($label -eq $Selected)) "compressed selection state $label"
    }
}
function CheckKeyboard([string[]]$Options) {
    $focused = $null
    for ($attempt=0;$attempt -lt 10;$attempt++) {
        [void](Device @('shell','input','keyevent','61'))
        $tree = ReadUi
        foreach ($node in @($tree.SelectNodes('//node[@focused="true"]'))) {
            $labels = @($node) + @($node.SelectNodes('.//node'))
            foreach ($candidate in $labels) {
                $name = $candidate.GetAttribute('content-desc')
                if (-not $name) { $name = $candidate.GetAttribute('text') }
                if ($Options -contains $name) { $focused=$name;break }
            }
            if ($focused) { break }
        }
        if ($focused) { break }
    }
    Check ([bool]$focused) 'keyboard reaches an enabled choice'
    $target = switch ($focused) { '左右翻页' {'scroll'} '上下滚动' {'paged'} '无动画' {'cover'} default {'none'} }
    [void](Dump 'semantics-click' $target)
    [void](Device @('shell','input','keyevent','23'))
    $state = Dump
    CheckChoice $state $focused $Options
}

try {
    $status = & $driver -Action Status -RunId $RunId
    Check ($status.BootCompleted -eq '1') 'owned isolated emulator ready'
    & $driver -Action Install -RunId $RunId -Apk $apkPath | Out-Null

    if ($Suite -in @('all','shelf')) {
        foreach ($layout in @('GRID','LIST')) {
            StartScene @('--es','layout',$layout)
            Tap '测试书甲'
            Check ((Dump).openedBook -eq 1) "$layout normal click still opens the book callback"
            Tap '测试书甲' -Long; Tap '多选'
            Check ((@((Dump).selection) -join ',') -eq '1') "$layout direct multi-select chooses the pressed book"
            Tap '测试书乙'; Tap '全选'
            Check ((@((Dump).selection | Sort-Object) -join ',') -eq '1,2') "$layout select and select-all use the visible books"
            Tap '批量操作'; Tap '置顶'
            $state=Dump
            Check (@($state.books | Where-Object { $_.id -in @(1,2) -and -not $_.pinned }).Count -eq 0) "$layout mixed batch pin preserves existing pins"
            Check (@($state.selection).Count -eq 0) "$layout batch pin exits selection"
            Tap '测试书甲' -Long; Tap '多选'; Tap '全选'; Tap '批量操作'; Tap '置顶'
            Check (@((Dump).books | Where-Object { $_.id -in @(1,2) -and -not $_.pinned }).Count -eq 0) "$layout repeated batch pin is idempotent"
            Tap '测试书甲' -Long; Tap '取消置顶'
            Check (-not (@((Dump).books | Where-Object { $_.id -eq 1 })[0].pinned)) "$layout single book can still unpin"
            Tap '测试书甲' -Long; Tap '置顶'
            Check (@((Dump).books | Where-Object { $_.id -eq 1 })[0].pinned) "$layout single book can still pin"
            Tap '测试书甲' -Long; Tap '删除这本书'; Tap '取消'
            $state=Dump
            Check (-not $state.confirmDelete -and @($state.selection).Count -eq 0) "$layout single-delete cancellation leaves no selection"
            Tap '测试书甲' -Long; Tap '多选'; Tap '测试书乙'; Tap '批量操作'; Tap '删除'; Tap '取消'
            $state=Dump
            Check (-not $state.confirmDelete -and (@($state.selection | Sort-Object) -join ',') -eq '1,2') "$layout batch-delete cancellation keeps selection"
            Tap '返回'
            Check (@((Dump).selection).Count -eq 0) "$layout selection exit clears selection"
            Capture "shelf-$layout"
        }
    }

    if ($Suite -in @('all','groups')) {
        foreach ($entry in @('manager','long-press')) {
            foreach ($count in @(1,2)) {
                $hidden = if ($count -eq 2) {'true'} else {'false'}
                $name = "group-$entry-$count"
                StartScene @('--ez','selectGroup','true','--ei','groupCount',"$count",'--ez','hidden',$hidden)
                if ($entry -eq 'manager') { Tap '更多'; Tap '分组管理'; Tap '删除' -First }
                else { Tap '测试分组' -Long; Tap '删除分组' }
                Tap '取消'
                $cancelled=Dump
                Check ($cancelled.selectedGroup -eq 7 -and @($cancelled.groups) -contains 7) "$name cancellation keeps group and filter"
                if ($entry -eq 'manager') { Tap '删除' -First }
                else { Tap '测试分组' -Long; Tap '删除分组' }
                Tap '删除'
                $state=Dump
                Check ($null -eq $state.selectedGroup -and @($state.groups) -notcontains 7) "$name deletion clears only the deleted filter"
                Check (@($state.books | Where-Object { $_.id -in @(1,2) -and $null -eq $_.group }).Count -eq 2) "$name group members survive as ungrouped books"
                if ($entry -eq 'manager') { [void](Device @('shell','input','keyevent','4')) }
                $state=Dump
                Check ((@($state.visibleIds | Sort-Object) -join ',') -eq '1,2') "$name books remain reachable without a group chip"
                if ($count -eq 2) {
                    Check (@($state.groups) -contains 9 -and @($state.books | Where-Object { $_.id -eq 3 -and $_.hidden }).Count -eq 1) "$name other group and hidden book stay unchanged"
                }
                Capture $name
            }
        }
    }

    if ($Suite -in @('all','choices')) {
        StartScene @('--es','scene','choices')
        $modes=@('左右翻页','上下滚动');$animations=@('无动画','滑动','覆盖')
        $state=Dump
        CheckChoice $state '上下滚动' $modes
        CheckChoice $state '覆盖' $animations
        Tap '左右翻页';CheckChoice (Dump) '左右翻页' $modes
        $state=Dump 'semantics-click' 'scroll'
        Check ($state.semanticClickAccepted) 'production semantic click action is available'
        CheckChoice $state '上下滚动' $modes
        Tap '滑动';CheckChoice (Dump) '滑动' $animations
        $state=Dump 'semantics-click' 'none'
        Check ($state.semanticClickAccepted) 'production radio semantic click action is available'
        CheckChoice $state '无动画' $animations
        Tap '不可用'
        Check ((Dump).disabledClicks -eq 0) 'disabled choice does not invoke its callback'
        CheckKeyboard $modes
        CheckKeyboard $animations
        Capture 'choices-final'
    }
    $completed=$true
    $suiteExitCode=0
} catch {
    $checks.Add([pscustomobject]@{Name="suite completed: $($_.Exception.Message)";Passed=$false})
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $output 'failure.txt') -Encoding utf8
    throw
} finally {
    $checks | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'checks.json') -Encoding utf8
    $captures | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $output 'captures.json') -Encoding utf8
    [pscustomobject]@{Suite=$Suite;Completed=$completed;ExitCode=$suiteExitCode;RunId=$RunId;Apk=$apkPath;ApkSHA256=$apkHash;
        ScriptSHA256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash;
        Passed=@($checks | Where-Object {$_.Passed}).Count;Failed=@($checks | Where-Object {-not $_.Passed}).Count;Captures=$captures.Count} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8
}
