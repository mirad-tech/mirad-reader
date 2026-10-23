param(
    [string]$RunId = 'glass-fix-20261022',
    [ValidateSet('reader-probe','reader-windowed-probe')][string[]]$Scenes = @('reader-probe','reader-windowed-probe')
)
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
try {
    Device @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
    foreach ($scene in $Scenes) {
        foreach ($fullScreen in @('true','false')) {
            Device @('shell','am','force-stop',$package) | Out-Null
            Device @('shell','am','start','-W','-n',"$package/com.mirad.reader.preview.GlassPreviewActivity",'--es','scene',$scene,'--ez','fullScreen',$fullScreen) | Out-Null
            $previewProcessId = ((Device @('shell','pidof',$package)).Trim() -split '\s+')[0]
            $deadline = [DateTime]::UtcNow.AddSeconds(35)
            do {
                $log = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassProbe','AndroidRuntime')
                if ($log -match 'DONE |FAIL |FATAL EXCEPTION') { break }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
            $name = "$scene-fullscreen-$fullScreen"
            $log | Set-Content -LiteralPath (Join-Path $logRoot "$name.txt") -Encoding utf8
            $passed = $log -match 'DONE ' -and $log -notmatch 'FAIL |FATAL EXCEPTION'
            $results.Add(@{Name=$name; Passed=$passed; Log="$name.txt"})
            if (-not $passed) { throw "Native backdrop probe failed: $name (see saved log)." }
            ($log -split "`n" | Where-Object { $_ -match 'DONE |draw-cost' }) | Write-Output
        }
    }
} finally {
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logRoot 'backdrop-probes.json') -Encoding utf8
}
