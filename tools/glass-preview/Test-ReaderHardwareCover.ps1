[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output=[IO.Path]::GetFullPath($OutputDirectory)
$allowed=[IO.Path]::GetFullPath((Join-Path $project 'build/verification'))+[IO.Path]::DirectorySeparatorChar
if(-not $output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) {throw 'Evidence must remain under build/verification.'}
if(Test-Path -LiteralPath $output) {throw 'Use a fresh evidence directory.'}
$apkPath=(Resolve-Path -LiteralPath $Apk).Path
$apkHash=(Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
New-Item -ItemType Directory -Path $output | Out-Null
$driver=Join-Path $PSScriptRoot 'Invoke-GlassPreview.ps1'
$package='com.mirad.reader.preview'
$component="$package/com.mirad.reader.preview.SettingsImmersionPreviewActivity"
$action='com.mirad.reader.preview.IMMERSION_CHECK'
$capturePrefix='cover-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$checks=[Collections.Generic.List[object]]::new()
$captures=[Collections.Generic.List[object]]::new()
$comparisons=[Collections.Generic.List[object]]::new()
$cleanupErrors=[Collections.Generic.List[string]]::new()
$counter=0
$passed=$false
$launched=$false
$failure=$null
$installedHash=$null
$deviceStatus=$null
$crop=$null
$cleanupState=$null

# The comparison runs in compiled code over locked bitmap rows. No per-pixel PowerShell loop.
$pixelComparerSource = @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public sealed class MiradCoverPixelResult {
    public int Width { get; set; }
    public int Height { get; set; }
    public long Samples { get; set; }
    public long PixelsDifferent { get; set; }
    public long PixelsOverTolerance { get; set; }
    public int MaxChannelDelta { get; set; }
    public double MeanChannelDelta { get; set; }
    public double ChangedFraction { get; set; }
}

public static class MiradCoverPixels {
    public static MiradCoverPixelResult Compare(string first, string second,
        int left, int top, int width, int height, int tolerance) {
        using (var imageA = new Bitmap(first))
        using (var imageB = new Bitmap(second)) {
            if (imageA.Width != imageB.Width || imageA.Height != imageB.Height)
                throw new InvalidOperationException("Screenshot dimensions changed between comparisons.");
            var region = new Rectangle(left, top, width, height);
            if (left < 0 || top < 0 || width <= 0 || height <= 0 ||
                region.Right > imageA.Width || region.Bottom > imageA.Height)
                throw new ArgumentOutOfRangeException("region", "Reader crop is outside the screenshot.");
            using (var a = imageA.Clone(region, PixelFormat.Format32bppArgb))
            using (var b = imageB.Clone(region, PixelFormat.Format32bppArgb)) {
                var full = new Rectangle(0, 0, width, height);
                BitmapData dataA = null;
                BitmapData dataB = null;
                try {
                    dataA = a.LockBits(full, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                    dataB = b.LockBits(full, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                    var rowA = new byte[width * 4];
                    var rowB = new byte[width * 4];
                    var result = new MiradCoverPixelResult { Width = width, Height = height };
                    long sum = 0;
                    for (int y = 0; y < height; y++) {
                        Marshal.Copy(IntPtr.Add(dataA.Scan0, y * dataA.Stride), rowA, 0, rowA.Length);
                        Marshal.Copy(IntPtr.Add(dataB.Scan0, y * dataB.Stride), rowB, 0, rowB.Length);
                        for (int x = 0; x < width; x++) {
                            int at = x * 4;
                            int blue = Math.Abs((int)rowA[at] - rowB[at]);
                            int green = Math.Abs((int)rowA[at + 1] - rowB[at + 1]);
                            int red = Math.Abs((int)rowA[at + 2] - rowB[at + 2]);
                            int delta = Math.Max(blue, Math.Max(green, red));
                            result.Samples++;
                            if (delta > 0) result.PixelsDifferent++;
                            if (delta > tolerance) result.PixelsOverTolerance++;
                            result.MaxChannelDelta = Math.Max(result.MaxChannelDelta, delta);
                            sum += blue + green + red;
                        }
                    }
                    result.MeanChannelDelta = (double)sum / (result.Samples * 3);
                    result.ChangedFraction = (double)result.PixelsOverTolerance / result.Samples;
                    return result;
                } finally {
                    if (dataA != null) a.UnlockBits(dataA);
                    if (dataB != null) b.UnlockBits(dataB);
                }
            }
        }
    }
}
'@

function Device([string[]]$Arguments) {
    $r=& $driver -Action Adb -RunId $RunId -AdbArgs $Arguments
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
    $lastFailure='No current state'
    for($attempt=0;$attempt -lt 4;$attempt++) {
        try {
            $id=[Guid]::NewGuid().ToString('N')
            Command 'dump' @('--es','label',$id)
            $json=Device @('shell','cat',"/sdcard/Android/data/$package/files/hardware-reader-state.json")
            $state=$json | ConvertFrom-Json
            if($state.label -ne $id) {throw 'The asynchronous dump still contains an earlier request.'}
            if($state.PSObject.Properties.Name -notcontains 'web' -or -not $state.web.ready) {throw 'The real reader document is not ready.'}
            $json | Set-Content -LiteralPath (Join-Path $output "state-$script:counter.json") -Encoding utf8
            return $state
        } catch {$lastFailure=$_.Exception.Message}
        Start-Sleep -Milliseconds 150
    }
    throw "No current, ready hardware reader state: $lastFailure"
}
function Ui {
    $id=[Guid]::NewGuid().ToString('N')
    $remote="/sdcard/reader-cover-$id.xml"
    [void](Device @('shell','uiautomator','dump',$remote))
    $raw=Device @('shell','cat',$remote)
    $raw | Set-Content -LiteralPath (Join-Path $output "ui-$id.xml") -Encoding utf8
    return ,([xml]$raw)
}
function Capture([string]$Name) {
    $tree=Ui
    Check (@($tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('package') -eq $package}).Count -gt 0) "$Name contains the isolated reader"
    $state=Dump
    $shot=& $driver -Action Capture -RunId $RunId -Name "$capturePrefix-$Name"
    $path=Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $shot.Path -Destination $path
    $state | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $output "$Name-state.json") -Encoding utf8
    $capture=[pscustomobject]@{Name=$Name;Path=$path;State=$state}
    $captures.Add($capture)
    return $capture
}
function Metric($State,[string]$Name) {
    $m=[regex]::Match([string]$State.hardware.sceneStats,"(?:^|\s)$([regex]::Escape($Name))=([^\s]+)")
    if(-not $m.Success) {throw "Missing hardware metric $Name"}
    return $m.Groups[1].Value
}
function Wait-Cover([string]$Expected) {
    $state=$null
    for($attempt=0;$attempt -lt 6;$attempt++) {
        $state=Dump
        if($state.hardware.pageSnapshotStatus -eq $Expected) {return $state}
        if($state.hardware.pageSnapshotStatus -eq 'failed') {throw "Hardware COVER capture failed: $($state.hardware.sceneStats)"}
        Start-Sleep -Milliseconds 200
    }
    throw "COVER did not reach '$Expected'; last status=$($state.hardware.pageSnapshotStatus)."
}
function Check-Viewport($Before,$After,[string]$Label) {
    Check ($Before.loads -eq $After.loads) "$Label does not reload the document"
    Check ($Before.readerBounds.width -eq $After.readerBounds.width -and $Before.readerBounds.height -eq $After.readerBounds.height -and $Before.readerBounds.left -eq $After.readerBounds.left -and $Before.readerBounds.top -eq $After.readerBounds.top) "$Label preserves the native reader rectangle"
    Check ($Before.web.width -eq $After.web.width -and $Before.web.height -eq $After.web.height) "$Label preserves the JavaScript viewport"
}
function Compare-Body($First,$Second,[string]$Name) {
    $pixels=[MiradCoverPixels]::Compare($First.Path,$Second.Path,$crop.Left,$crop.Top,$crop.Width,$crop.Height,1)
    $comparison=[pscustomobject]@{Name=$Name;First=$First.Name;Second=$Second.Name;Tolerance=1;Crop=$crop;Pixels=$pixels}
    $comparisons.Add($comparison)
    $comparison | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output "$Name-pixels.json") -Encoding utf8
    return $pixels
}

try {
    Add-Type -AssemblyName System.Drawing
    if(-not ('MiradCoverPixels' -as [type])) {
        # PowerShell on .NET 10 exposes additional Windows drawing interfaces on Bitmap.
        $drawingReferences=@([Drawing.Bitmap].Assembly.Location,[Drawing.Rectangle].Assembly.Location)
        $drawingReferences+=@([Drawing.Bitmap].GetInterfaces() | Where-Object {$_.Assembly.GetName().Name -like 'System.Private.Windows.*'} | ForEach-Object {$_.Assembly.Location})
        Add-Type -TypeDefinition $pixelComparerSource -ReferencedAssemblies @($drawingReferences | Select-Object -Unique)
    }
    $deviceStatus=& $driver -Action Status -RunId $RunId
    Check ($deviceStatus.ExitCode -eq 0 -and $deviceStatus.BootCompleted -eq '1') 'the owned isolated emulator is ready'
    $install=& $driver -Action Install -RunId $RunId -Apk $apkPath -ExpectedPackage $package
    Check ($install.ExitCode -eq 0) 'the isolated preview APK installs successfully'
    $installed=(Device @('shell','pm','path',$package)).Trim()
    if($installed -notmatch '^package:(/data/app/[^\r\n]+/base\.apk)$') {throw 'Unexpected preview package path.'}
    $installedApk=$Matches[1]
    $installedHash=((Device @('shell','sha256sum',$installedApk)) -split '\s+')[0]
    Check ($installedHash -eq $apkHash) 'installed APK matches the pinned test artifact'
    $launch=Device @('shell','am','start','-S','-W','-n',$component,'--es','scene','appearance','--es','entry','reader','--ez','hardware','true','--es','mode','DAY','--ez','chrome','false','--ei','transparency','100','--es','pageMode','PAGED','--ez','fullScreen','false')
    Check ($launch -match 'Status: ok') 'the paged reader Activity launches successfully'
    $launched=$true
    [void](Dump)
    $before=Capture 'before'
    Check ($before.State.hardwarePreview -and $before.State.hardware.hostWindowMatchesActivity) 'COVER uses the production scene in the Activity window'
    Check ($before.State.web.state.mode -eq 'PAGED' -and $before.State.web.state.pages -gt 2) 'the fixture contains several real pages'
    Check ((Metric $before.State 'hardware') -eq 'true' -and (Metric $before.State 'cpuDisplayCaptures') -eq '0') 'the baseline uses hardware drawing with zero CPU display captures'
    $bounds=$before.State.readerBounds
    # Exclude header/footer, including their independently updated page/time labels.
    $left=[int][Math]::Ceiling($bounds.left+$bounds.width*.10)
    $right=[int][Math]::Floor($bounds.left+$bounds.width*.90)
    $top=[int][Math]::Ceiling($bounds.top+$bounds.height*.25)
    $bottom=[int][Math]::Floor($bounds.top+$bounds.height*.75)
    $crop=[pscustomobject]@{Left=$left;Top=$top;Width=$right-$left;Height=$bottom-$top;ExcludedTopBottomFraction=.25;ExcludedSideFraction=.10}
    Check ($crop.Width*$crop.Height -ge 10000) 'the body comparison covers a meaningful interior area'

    # Broadcasts preserve the running Activity: am start would pause it and invalidate the test.
    Command 'cover' @('--ei','direction','1','--ei','durationMs','260','--ez','hold','true')
    [void](Wait-Cover 'held')
    $held=Capture 'held'
    Check ($held.State.hardware.pageSnapshotStatus -eq 'held') 'the old page remains held at x=0'
    Check ($held.State.hardware.pageSnapshotConfig -eq 'HARDWARE') 'COVER owns a GPU hardware bitmap'
    Check ($held.State.hardware.pageSnapshotWidth -eq $before.State.readerBounds.width -and $held.State.hardware.pageSnapshotHeight -eq $before.State.readerBounds.height) 'the GPU snapshot matches the native document dimensions'
    Check ($held.State.hardware.pageSnapshotSerial -eq $before.State.hardware.pageSnapshotSerial+1) 'the held operation creates one new page snapshot'
    Check ($held.State.web.state.page -gt $before.State.web.state.page -and $held.State.web.state.offset -gt $before.State.web.state.offset -and $held.State.web.snapshot.offset -gt $before.State.web.snapshot.offset) 'the WebView beneath the held image advances page and character position'
    Check-Viewport $before.State $held.State 'holding COVER'
    Check ((Metric $held.State 'cpuDisplayCaptures') -eq '0') 'holding COVER does not add CPU display captures'
    $heldPixels=Compare-Body $before $held 'held-preserves-old-page'
    Check ($heldPixels.PixelsOverTolerance -eq 0) "held body equals the old page within one color level (overTolerance=$($heldPixels.PixelsOverTolerance)/$($heldPixels.Samples), max=$($heldPixels.MaxChannelDelta))"

    Command 'hide-cover'
    [void](Wait-Cover 'hidden')
    $newPage=Capture 'new-page'
    Check ($newPage.State.hardware.pageSnapshotStatus -eq 'hidden' -and -not $newPage.State.hardware.coverRunning) 'removing the held image leaves no COVER animation running'
    Check ($newPage.State.web.state.page -eq $held.State.web.state.page -and $newPage.State.web.snapshot.offset -eq $held.State.web.snapshot.offset) 'removing COVER preserves the newly reached page and character position'
    Check-Viewport $held.State $newPage.State 'removing COVER'
    $newPixels=Compare-Body $before $newPage 'underlying-new-page'
    Check ($newPixels.PixelsOverTolerance -ge 1000 -and $newPixels.ChangedFraction -ge .03) "the uncovered new page changes at least three percent of body pixels (changed=$($newPixels.PixelsOverTolerance)/$($newPixels.Samples), max=$($newPixels.MaxChannelDelta))"

    Command 'cover' @('--ei','direction','1','--ei','durationMs','260','--ez','hold','false')
    [void](Wait-Cover 'finished')
    $animated=Capture 'animated-finished'
    Check ($animated.State.hardware.pageSnapshotStatus -eq 'finished' -and -not $animated.State.hardware.coverRunning) 'ordinary COVER animation finishes and releases its running state'
    Check ($animated.State.hardware.pageSnapshotConfig -eq 'HARDWARE' -and $animated.State.hardware.pageSnapshotSerial -eq $held.State.hardware.pageSnapshotSerial+1) 'ordinary COVER uses another GPU hardware snapshot'
    Check ($animated.State.web.state.page -gt $newPage.State.web.state.page -and $animated.State.web.snapshot.offset -gt $newPage.State.web.snapshot.offset) 'ordinary COVER also advances the real page and character position'
    Check-Viewport $newPage.State $animated.State 'animated COVER'
    Check ((Metric $animated.State 'cpuDisplayCaptures') -eq '0' -and (Metric $animated.State 'pageFailures') -eq '0') 'both COVER paths complete without CPU display capture or snapshot failure'
    $processId=(Device @('shell','pidof',$package)).Trim()
    Check ($processId -match '^\d+$') 'the tested reader process remains alive'
    $crashes=Device @('logcat','-d','--pid',$processId,'-s','AndroidRuntime:E','*:S')
    $crashes | Set-Content -LiteralPath (Join-Path $output 'android-runtime.log') -Encoding utf8
    Check ($crashes -notmatch 'FATAL EXCEPTION') 'the tested reader process has no fatal exception'
    $passed=$true
} catch {
    $failure=$_.Exception.Message
    throw
} finally {
    if($launched) {
        foreach($operation in @('stop-animation','hide-cover')) {
            try {Command $operation} catch {$cleanupErrors.Add("${operation}: $($_.Exception.Message)")}
        }
        try {
            $cleanupState=Dump
            if($cleanupState.hardware.pageSnapshotStatus -ne 'hidden' -or $cleanupState.hardware.coverRunning -or $cleanupState.hardware.animationRunning) {
                $cleanupErrors.Add('The reader still reports an active animation or visible held cover after cleanup.')
            }
        } catch {$cleanupErrors.Add("cleanup state: $($_.Exception.Message)")}
    }
    [ordered]@{
        Passed=($passed -and $cleanupErrors.Count -eq 0);Failure=$failure;RunId=$RunId;Device=$deviceStatus
        Apk=$apkPath;SHA256=$apkHash;InstalledSHA256=$installedHash
        ScriptSHA256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        Checks=$checks;Crop=$crop;Comparisons=$comparisons;Captures=$captures
        CleanupErrors=$cleanupErrors;CleanupState=$cleanupState
        Scope='Owned emulator, real reader pagination and production GPU page capture with fixture content. Pixel equality allows at most one RGB level; this does not establish target-phone performance.'
    } | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8
    if($cleanupErrors.Count -gt 0 -and -not $failure) {throw ($cleanupErrors -join '; ')}
}
Write-Host "Reader GPU COVER verification passed: $output"
