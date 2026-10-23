param([string]$RunId = 'glass-20261022', [string[]]$Names = @())
$ErrorActionPreference = 'Stop'
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$component = 'com.mirad.reader.preview/com.mirad.reader.preview.GlassPreviewActivity'
$cases = @(
    @{Name='shelf-light'; Scene='shelf'},
    @{Name='shelf-light-scrolled'; Scene='shelf'; Scrolled=$true},
    @{Name='shelf-dark'; Scene='shelf'; Dark=$true},
    @{Name='shelf-dark-scrolled'; Scene='shelf'; Dark=$true; Scrolled=$true},
    @{Name='reader-light'; Scene='reader'},
    @{Name='reader-dark'; Scene='reader'; Dark=$true},
    @{Name='reader-settings-light'; Scene='reader-settings'},
    @{Name='reader-settings-dark'; Scene='reader-settings'; Dark=$true},
    @{Name='settings-light'; Scene='settings'},
    @{Name='settings-dark'; Scene='settings'; Dark=$true},
    @{Name='dialog-light'; Scene='dialog'; Scrolled=$true},
    @{Name='dialog-dark'; Scene='dialog'; Dark=$true; Scrolled=$true},
    @{Name='empty-light'; Scene='empty'},
    @{Name='fallback-reader'; Scene='reader'; Fallback=$true},
    @{Name='fallback-shelf'; Scene='shelf'; Scrolled=$true; Fallback=$true},
    @{Name='legacy-material-shelf'; Scene='shelf'; Scrolled=$true; Baseline=$true},
    @{Name='legacy-material-reader'; Scene='reader'; Baseline=$true},
    @{Name='reader-large-font-long-title'; Scene='reader'; FontScale='1.6'; LongTitle=$true},
    @{Name='settings-large-font'; Scene='settings'; FontScale='1.6'},
    @{Name='reader-wide'; Scene='reader'; Wide=$true},
    @{Name='reader-settings-wide'; Scene='reader-settings'; Wide=$true},
    @{Name='reader-dialog-fullscreen'; Scene='reader-dialog'; FullScreen=$true},
    @{Name='reader-dialog-system-bars'; Scene='reader-dialog'},
    @{Name='reader-settings-fullscreen'; Scene='reader-settings'; FullScreen=$true}
)
foreach ($wide in @($false, $true)) {
    foreach ($dark in @($false, $true)) {
        foreach ($scale in @('1.0','1.3','1.6')) {
            $widthName = if ($wide) { 'wide' } else { 'narrow' }
            $themeName = if ($dark) { 'dark' } else { 'light' }
            $cases += @{Name="font-$widthName-$themeName-$($scale.Replace('.',''))"; Scene='reader-typography'; FontScale=$scale; Wide=$wide; Dark=$dark; FullScreen=$true}
        }
    }
}
$results = @()
if ($Names.Count) { $cases = @($cases | Where-Object { $_.Name -in $Names }) }
& $driver -Action Adb -RunId $RunId -AdbArgs @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
foreach ($case in $cases) {
    & $driver -Action Adb -RunId $RunId -AdbArgs @('shell','am','force-stop','com.mirad.reader.preview') | Out-Null
    # Dialog windows create their own density from Configuration; a composition-local
    # override alone only scales the Activity and does not exercise actual dialog text.
    $systemFontScale = if ($case.ContainsKey('FontScale')) { $case['FontScale'] } else { '1.0' }
    & $driver -Action Adb -RunId $RunId -AdbArgs @('shell','settings','put','system','font_scale',$systemFontScale) | Out-Null
    if ($case.ContainsKey('Wide') -and $case['Wide']) {
        & $driver -Action Adb -RunId $RunId -AdbArgs @('shell','wm','size','1920x1200') | Out-Null
        & $driver -Action Adb -RunId $RunId -AdbArgs @('shell','wm','density','240') | Out-Null
    } else {
        & $driver -Action Adb -RunId $RunId -AdbArgs @('shell','wm','size','reset') | Out-Null
        & $driver -Action Adb -RunId $RunId -AdbArgs @('shell','wm','density','reset') | Out-Null
    }
    $arguments = @('shell','am','start','-W','-n',$component,'--es','scene',$case.Scene)
    foreach ($flag in @('Dark','Scrolled','Fallback','Baseline','LongTitle','FullScreen')) {
        if ($case.ContainsKey($flag) -and $case[$flag]) {
            $extra = $flag.Substring(0,1).ToLowerInvariant() + $flag.Substring(1)
            $arguments += @('--ez',$extra,'true')
        }
    }
    # Rely on system font_scale set above rather than overriding via intent extra (R5)
    & $driver -Action Adb -RunId $RunId -AdbArgs $arguments | Out-Null
    if ($case.Scene.StartsWith('reader')) {
        $processResult = & $driver -Action Adb -RunId $RunId -AdbArgs @('shell','pidof','com.mirad.reader.preview')
        $previewProcessId = ($processResult.Output.Trim() -split '\s+')[0]
        $ready = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $webLog = & $driver -Action Adb -RunId $RunId -AdbArgs @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
            $ready = $webLog.Output -match 'readerReady'
            if (-not $ready) { Start-Sleep -Milliseconds 500 }
        } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
        if (-not $ready) { throw "WebView did not become ready for $($case.Name)." }
    }
    # am start -W waits for the Activity's first draw. Then allow the finite
    # sheet transition and the first visible backdrop copy to settle.
    Start-Sleep -Seconds 2
    $capture = & $driver -Action Capture -RunId $RunId -Name $case.Name
    $results += [ordered]@{Case=$case; Path=$capture.Path; Bytes=$capture.Bytes}
    Write-Output "Captured $($case.Name)"
}
& $driver -Action Adb -RunId $RunId -AdbArgs @('shell','wm','size','reset') | Out-Null
& $driver -Action Adb -RunId $RunId -AdbArgs @('shell','wm','density','reset') | Out-Null
& $driver -Action Adb -RunId $RunId -AdbArgs @('shell','settings','put','system','font_scale','1.0') | Out-Null
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/capture-matrix.json") -Encoding utf8
