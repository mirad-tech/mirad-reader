[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Drawing
$driver = Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$package = 'com.mirad.reader.gpupreview'
$component = "$package/com.mirad.reader.preview.ReaderGpuPreviewActivity"
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Use a new evidence directory; previous evidence is never overwritten.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
$results = [Collections.Generic.List[object]]::new()
$captureIndex = 0

function Invoke-Device([string[]]$Arguments) {
    $result = & $driver -Action Adb -RunId $RunId -AdbArgs $Arguments
    if ($result.ExitCode -ne 0) { throw "ADB failed: $($result.Error) $($result.Output)" }
    return $result.Output
}

function Set-Probe([string[]]$Options) {
    $response = Invoke-Device (@('shell','am','start','-W','-n',$component) + $Options)
    if ($response -notmatch 'Status: ok') { throw "Activity did not reach a successful launch: $response" }
}

function Read-Probe {
    $lastFailure='No current report'
    for($attempt=0; $attempt -lt 5; $attempt++) {
        try {
            $requestId=[Guid]::NewGuid().ToString('N')
            Set-Probe @('--es','action','report','--es','requestId',$requestId)
            $reportText = Invoke-Device @('shell','cat',"/sdcard/Android/data/$package/files/gpu-probe.json")
            $report = $reportText | ConvertFrom-Json
            if($report.requestId -ne $requestId) { throw 'An older asynchronous report is still on disk.' }
            $webText = $report.webStateAfterRun | ConvertFrom-Json
            $web = if ($webText -is [string]) { $webText | ConvertFrom-Json } else { $webText }
            if (-not $report.ready -or $web.text -lt 1000) { throw 'Reader has not finished its real text fixture.' }
            return [pscustomobject]@{ Report=$report; Web=$web }
        } catch { $lastFailure=$_.Exception.Message }
        Start-Sleep -Milliseconds 150
    }
    throw "Reader readiness check failed: $lastFailure"
}

function Capture-Probe([string]$Name) {
    $script:captureIndex++
    $state = Read-Probe
    $capture = & $driver -Action Capture -RunId $RunId -Name "gpu-$($script:captureIndex)-$Name"
    $path = Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $capture.Path -Destination $path
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output "$Name.json") -Encoding utf8
    [pscustomobject]@{ Path=$path; State=$state }
}

function Compare-Panel($First, $Second, [switch]$RequireEqual) {
    $bounds = $First.State.Report.panelBounds
    $margin = [Math]::Ceiling(28 * $First.State.Report.density)
    $left = [int][Math]::Ceiling($bounds[0] + $margin)
    $top = [int][Math]::Ceiling($bounds[1] + $margin)
    $right = [int][Math]::Floor($bounds[2] - $margin)
    $bottom = [int][Math]::Floor($bounds[3] - $margin)
    $a = [Drawing.Bitmap]::FromFile($First.Path)
    $b = [Drawing.Bitmap]::FromFile($Second.Path)
    try {
        $samples=0; $different=0; $maximum=0
        for($y=$top; $y -lt $bottom; $y+=2) {
            for($x=$left; $x -lt $right; $x+=2) {
                $ca=$a.GetPixel($x,$y); $cb=$b.GetPixel($x,$y)
                $delta=[Math]::Max([Math]::Abs([int]$ca.R-$cb.R),[Math]::Max([Math]::Abs([int]$ca.G-$cb.G),[Math]::Abs([int]$ca.B-$cb.B)))
                $samples++
                if($delta -gt 0) { $different++ }
                $maximum=[Math]::Max($maximum,$delta)
            }
        }
        if($samples -lt 1000) { throw 'The sampled panel region is too small.' }
        if($RequireEqual -and $different -ne 0) { throw "Raw GPU content differs from the window content: $different/$samples, max=$maximum" }
        [pscustomobject]@{ Samples=$samples; Different=$different; MaxChannelDelta=$maximum }
    } finally { $a.Dispose(); $b.Dispose() }
}

function Compare-RawCase([string]$Name) {
    $gpu=Capture-Probe "$Name-gpu"
    if(-not $gpu.State.Report.hardwareCanvas -or -not $gpu.State.Report.gpuActive -or $gpu.State.Report.mode -ne 'gpu' -or -not $gpu.State.Report.raw) { throw 'Invalid GPU test configuration.' }
    if($gpu.State.Report.totalCpuDisplayCaptures -ne 0) { throw 'GPU path unexpectedly performed a CPU display capture.' }
    Set-Probe @('--es','mode','off')
    $reference=Capture-Probe "$Name-reference"
    if($gpu.State.Web.scrollY -ne $reference.State.Web.scrollY) { throw 'Reference moved between captures.' }
    $comparison=Compare-Panel $gpu $reference -RequireEqual
    $results.Add([pscustomobject]@{ Name=$Name; ScrollY=$gpu.State.Web.scrollY; Pixels=$comparison })
    Write-Output "$Name passed ($($comparison.Samples) equal pixels)"
    Set-Probe @('--es','mode','gpu')
}

$install = & $driver -Action Install -RunId $RunId -Apk $apkPath -ExpectedPackage $package
if($install.ExitCode -ne 0) { throw 'The isolated preview could not be installed.' }
Invoke-Device @('shell','am','force-stop',$package) | Out-Null
Set-Probe @('--es','mode','gpu','--ez','raw','true','--ez','dark','false','--ei','percent','100','--ei','scroll','0')
Compare-RawCase 'initial'
foreach($scroll in @(137,820,32)) {
    # Mode/geometry are already stable: only WebView scroll changes the content RenderNode.
    Set-Probe @('--es','action','scroll','--ei','scroll',"$scroll")
    Compare-RawCase "scroll-$scroll"
}
Set-Probe @('--es','action','chapter')
Compare-RawCase 'chapter'
Set-Probe @('--es','action','image')
$imageState=Read-Probe
if($imageState.Web.loadedImages -lt 1) { throw 'The delayed image did not load.' }
Compare-RawCase 'delayed-image'

$beforeTouch=Read-Probe
Invoke-Device @('shell','input','swipe','540','1000','540','500','350') | Out-Null
$afterTouch=Read-Probe
if($beforeTouch.Web.scrollY -eq $afterTouch.Web.scrollY) { throw 'The Compose overlay prevented a real touch scroll from reaching the WebView.' }
Compare-RawCase 'touch-scroll'

Set-Probe @('--ez','raw','false','--ez','liquid','true')
$blurBefore=Capture-Probe 'blur-before-scroll'
Set-Probe @('--es','action','scroll','--ei','scroll','1700')
$blurAfter=Capture-Probe 'blur-after-scroll'
$blurDifference=Compare-Panel $blurBefore $blurAfter
if($blurDifference.Different -lt 1000) { throw 'Blurred hardware content did not visibly update after scrolling.' }
$results.Add([pscustomobject]@{Name='blurred-content-updates';Pixels=$blurDifference})
Set-Probe @('--ez','dark','true')
Capture-Probe 'liquid-dark' | Out-Null

# Keep the visual matrix tied to the same installed APK as the pixel checks.
foreach($theme in @('day','night')) {
    $isDark=if($theme -eq 'night') { 'true' } else { 'false' }
    foreach($percent in @(0,60,100)) {
        Set-Probe @('--ez','dark',$isDark,'--ei','percent',"$percent",'--ez','liquid','true')
        Capture-Probe "$theme-$percent" | Out-Null
    }
}

# The liquid shader is confined to the edge; disabling it must preserve the interior.
$withLiquid=Capture-Probe 'liquid-edge-on'
Set-Probe @('--ez','liquid','false')
$withoutLiquid=Capture-Probe 'liquid-edge-off'
$center=Compare-Panel $withLiquid $withoutLiquid
if($center.MaxChannelDelta -gt 1) { throw 'The edge shader unexpectedly changed the panel interior.' }
$bounds=$withLiquid.State.Report.panelBounds
$density=$withLiquid.State.Report.density
$a=[Drawing.Bitmap]::FromFile($withLiquid.Path)
$b=[Drawing.Bitmap]::FromFile($withoutLiquid.Path)
try {
    $edgeDifferent=0; $edgeSamples=0; $edgeMaximum=0
    $yStart=[int][Math]::Ceiling($bounds[1]+2*$density)
    $yEnd=[int][Math]::Floor($bounds[1]+8*$density)
    $xStart=[int][Math]::Ceiling($bounds[0]+30*$density)
    $xEnd=[int][Math]::Floor($bounds[2]-30*$density)
    for($y=$yStart;$y -lt $yEnd;$y++) {
        for($x=$xStart;$x -lt $xEnd;$x++) {
            $ca=$a.GetPixel($x,$y); $cb=$b.GetPixel($x,$y)
            $delta=[Math]::Max([Math]::Abs([int]$ca.R-$cb.R),[Math]::Max([Math]::Abs([int]$ca.G-$cb.G),[Math]::Abs([int]$ca.B-$cb.B)))
            $edgeSamples++; if($delta -gt 0) { $edgeDifferent++ }
            $edgeMaximum=[Math]::Max($edgeMaximum,$delta)
        }
    }
    if($edgeDifferent -eq 0) { throw 'No visible shader effect was detected at the panel edge.' }
    $results.Add([pscustomobject]@{Name='liquid-edge-only';Interior=$center;EdgeSamples=$edgeSamples;EdgeDifferent=$edgeDifferent;EdgeMaxChannelDelta=$edgeMaximum})
} finally { $a.Dispose(); $b.Dispose() }
Set-Probe @('--ez','liquid','true')

$summary=[ordered]@{
    Passed=$true; Apk=$apkPath; SHA256=$apkHash; RunId=$RunId
    DeviceModel=$blurAfter.State.Report.model; Sdk=$blurAfter.State.Report.sdk
    RequestedRefreshRateHz=$blurAfter.State.Report.requestedRefreshRateHz
    ObservedRefreshRateHz=$blurAfter.State.Report.refreshRateHz
    Scope='Isolated emulator hardware/WebView rendering; not target-phone performance approval.'
    Cases=$results
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8
$summary | ConvertTo-Json -Depth 8
