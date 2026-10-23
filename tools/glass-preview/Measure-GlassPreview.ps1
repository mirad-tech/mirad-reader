param([string]$RunId = 'glass-20261022')
$ErrorActionPreference = 'Stop'
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logRoot = Join-Path $projectRoot "build/verification/glass-native-preview/$RunId/logs"
$package = 'com.mirad.reader.preview'
function Device([string[]]$Command) { (& $driver -Action Adb -RunId $RunId -AdbArgs $Command).Output }
foreach ($scene in @('reader','shelf')) {
    foreach ($style in @('legacy','glass')) {
        Device @('shell','am','force-stop',$package) | Out-Null
        $arguments = @('shell','am','start','-W','-n',"$package/com.mirad.reader.preview.GlassPreviewActivity",'--es','scene',$scene)
        if ($style -eq 'legacy') { $arguments += @('--ez','baseline','true') }
        Device $arguments | Out-Null
        $previewProcessId = (Device @('shell','pidof',$package)).Trim()
        if ($scene -eq 'reader') {
            $deadline = [DateTime]::UtcNow.AddSeconds(30)
            do {
                $readyLog = Device @('logcat','-d','--pid',$previewProcessId,'-s','GlassPreview')
                if ($readyLog -match 'readerReady') { break }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($readyLog -notmatch 'readerReady') { throw 'Reader did not initialize.' }
        }
        Start-Sleep -Seconds 2
        Device @('shell','dumpsys','gfxinfo',$package,'reset') | Out-Null
        for ($i=0; $i -lt 8; $i++) {
            if ($scene -eq 'reader') {
                Device @('shell','input','tap','540','800') | Out-Null
            } elseif ($i % 2 -eq 0) {
                Device @('shell','input','swipe','540','1900','540','650','500') | Out-Null
            } else {
                Device @('shell','input','swipe','540','650','540','1900','500') | Out-Null
            }
            Start-Sleep -Milliseconds 350
        }
        Start-Sleep -Seconds 1
        Device @('shell','dumpsys','gfxinfo',$package,'framestats') | Set-Content -LiteralPath (Join-Path $logRoot "$scene-$style-frames.txt") -Encoding utf8
        Device @('shell','dumpsys','meminfo','-s',$package) | Set-Content -LiteralPath (Join-Path $logRoot "$scene-$style-memory.txt") -Encoding utf8
        Write-Output "Measured $scene $style"
    }
}
