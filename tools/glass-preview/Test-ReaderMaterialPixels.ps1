[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$OutputDirectory,
    # Native screenshot pixels: left, top, width, height. Never inferred from the result.
    [ValidateCount(4,4)][int[]]$CropRectangle
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
$capturePrefix='material-pixels-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$checks=[Collections.Generic.List[object]]::new()
$captures=[Collections.Generic.List[object]]::new()
$cleanupErrors=[Collections.Generic.List[string]]::new()
$counter=0
$passed=$false
$launched=$false
$failure=$null
$installedHash=$null
$deviceStatus=$null
$crop=$null
$cropEvidence=$null
$pixelResults=$null
$cleanupState=$null

# RGB values are on the 0..255 scale. The similarity limits allow tiny GPU/quantization
# variation, not missing effects. The normalized Laplacian also rejects simple tinting:
# reducing contrast alone lowers the raw gradient but not Laplacian/stddev.
$thresholds=[ordered]@{
    MinimumCropPixels=1024
    MaximumRecoveryMeanChannelDelta=3.0
    MaximumRecoveryP95PixelDelta=8
    MinimumBareLuminanceStdDev=12.0
    MinimumBareGradientRms=5.0
    MinimumBareLaplacianRms=4.0
    MinimumGlassLuminanceStdDev=2.0
    MaximumGlassToBareGradientRatio=0.40
    MaximumGlassToBareLaplacianRatio=0.40
    MaximumGlassToBareNormalizedLaplacianRatio=0.60
}

$pixelAnalyzerSource=@'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public sealed class MiradMaterialTexture {
    public int Width { get; set; }
    public int Height { get; set; }
    public long Samples { get; set; }
    public double LuminanceMean { get; set; }
    public double LuminanceStdDev { get; set; }
    public double GradientRms { get; set; }
    public double LaplacianRms { get; set; }
    public double NormalizedLaplacianRms { get; set; }
}
public sealed class MiradMaterialDifference {
    public long Samples { get; set; }
    public long PixelsDifferent { get; set; }
    public int MaxPixelDelta { get; set; }
    public int P95PixelDelta { get; set; }
    public double MeanChannelDelta { get; set; }
}
public static class MiradMaterialPixels {
    private static byte[] Read(string path, int left, int top, int width, int height) {
        using (var image = new Bitmap(path)) {
            if (left < 0 || top < 0 || width < 3 || height < 3 ||
                left + width > image.Width || top + height > image.Height)
                throw new ArgumentOutOfRangeException("crop", "Material crop is outside the screenshot or too small.");
            using (var crop = image.Clone(new Rectangle(left, top, width, height), PixelFormat.Format32bppArgb)) {
                var bytes = new byte[width * height * 4];
                BitmapData data = null;
                try {
                    data = crop.LockBits(new Rectangle(0, 0, width, height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                    for (int y = 0; y < height; y++)
                        Marshal.Copy(IntPtr.Add(data.Scan0, y * data.Stride), bytes, y * width * 4, width * 4);
                } finally { if (data != null) crop.UnlockBits(data); }
                return bytes;
            }
        }
    }
    public static MiradMaterialTexture Analyze(string path, int left, int top, int width, int height) {
        var pixels = Read(path, left, top, width, height);
        var values = new double[width * height];
        double sum = 0, sumSquares = 0, gradientSquares = 0, laplacianSquares = 0;
        long gradientSamples = 0, laplacianSamples = 0;
        for (int i = 0; i < values.Length; i++) {
            int at = i * 4;
            double value = .2126 * pixels[at + 2] + .7152 * pixels[at + 1] + .0722 * pixels[at];
            values[i] = value; sum += value; sumSquares += value * value;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = y * width + x;
                if (x + 1 < width) { double d = values[at + 1] - values[at]; gradientSquares += d * d; gradientSamples++; }
                if (y + 1 < height) { double d = values[at + width] - values[at]; gradientSquares += d * d; gradientSamples++; }
                if (x > 0 && x + 1 < width && y > 0 && y + 1 < height) {
                    double lap = values[at - 1] + values[at + 1] + values[at - width] + values[at + width] - 4 * values[at];
                    laplacianSquares += lap * lap; laplacianSamples++;
                }
            }
        }
        double mean = sum / values.Length;
        double deviation = Math.Sqrt(Math.Max(0, sumSquares / values.Length - mean * mean));
        double laplacian = Math.Sqrt(laplacianSquares / laplacianSamples);
        return new MiradMaterialTexture {
            Width = width, Height = height, Samples = values.Length,
            LuminanceMean = mean, LuminanceStdDev = deviation,
            GradientRms = Math.Sqrt(gradientSquares / gradientSamples), LaplacianRms = laplacian,
            NormalizedLaplacianRms = laplacian / Math.Max(deviation, 1.0)
        };
    }
    public static MiradMaterialDifference Compare(string first, string second, int left, int top, int width, int height) {
        using (var a = new Bitmap(first)) using (var b = new Bitmap(second)) {
            if (a.Width != b.Width || a.Height != b.Height)
                throw new InvalidOperationException("Screenshot dimensions changed between material captures.");
        }
        var firstPixels = Read(first, left, top, width, height);
        var secondPixels = Read(second, left, top, width, height);
        var histogram = new long[256];
        var result = new MiradMaterialDifference { Samples = (long)width * height };
        long channelSum = 0;
        for (int at = 0; at < firstPixels.Length; at += 4) {
            int b = Math.Abs((int)firstPixels[at] - secondPixels[at]);
            int g = Math.Abs((int)firstPixels[at + 1] - secondPixels[at + 1]);
            int r = Math.Abs((int)firstPixels[at + 2] - secondPixels[at + 2]);
            int delta = Math.Max(b, Math.Max(g, r));
            channelSum += b + g + r;
            histogram[delta]++;
            if (delta > 0) result.PixelsDifferent++;
            result.MaxPixelDelta = Math.Max(result.MaxPixelDelta, delta);
        }
        long target = (long)Math.Ceiling(result.Samples * .95), accumulated = 0;
        for (int i = 0; i < histogram.Length; i++) {
            accumulated += histogram[i];
            if (accumulated >= target) { result.P95PixelDelta = i; break; }
        }
        result.MeanChannelDelta = (double)channelSum / (result.Samples * 3);
        return result;
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
function Metric($State,[string]$Name) {
    $match=[regex]::Match([string]$State.hardware.sceneStats,"(?:^|\s)$([regex]::Escape($Name))=([^\s]+)")
    if(-not $match.Success) {throw "Missing hardware metric $Name"}
    return $match.Groups[1].Value
}
function Dump {
    $script:counter++
    $lastFailure='No current state'
    for($attempt=0;$attempt -lt 5;$attempt++) {
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
    $remote="/sdcard/reader-material-$id.xml"
    [void](Device @('shell','uiautomator','dump','--compressed',$remote))
    $raw=Device @('shell','cat',$remote)
    $path=Join-Path $output "ui-$id.xml"
    $raw | Set-Content -LiteralPath $path -Encoding utf8
    return [pscustomobject]@{Tree=([xml]$raw);Path=$path}
}
function Capture([string]$Name,[bool]$ChromeExpected) {
    $null=Dump
    $ui=Ui
    $state=Dump
    Check ($state.chrome -eq $ChromeExpected -and -not $state.hardware.modalVisible -and -not $state.imeVisible) "$Name has the expected toolbar visibility with no modal or IME"
    $more=@($ui.Tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('content-desc') -eq '更多' -and $_.GetAttribute('package') -eq $package})
    Check (($more.Count -gt 0) -eq $ChromeExpected) "$Name has settled its toolbar semantics"
    $shot=& $driver -Action Capture -RunId $RunId -Name "$capturePrefix-$Name"
    $path=Join-Path $output "$Name.png"
    Copy-Item -LiteralPath $shot.Path -Destination $path
    $state | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath (Join-Path $output "$Name-state.json") -Encoding utf8
    $captures.Add([pscustomobject]@{Name=$Name;Path=$path;Ui=$ui.Path;State=$state})
    return [pscustomobject]@{Name=$Name;Path=$path;State=$state;Tree=$ui.Tree;Ui=$ui.Path}
}
function NodeBounds($Node) {
    $match=[regex]::Match($Node.GetAttribute('bounds'),'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if(-not $match.Success) {return $null}
    $v=@($match.Groups | Select-Object -Skip 1 | ForEach-Object {[int]$_.Value})
    if($v[2] -le $v[0] -or $v[3] -le $v[1]) {return $null}
    return [pscustomobject]@{Left=$v[0];Top=$v[1];Right=$v[2];Bottom=$v[3];Width=$v[2]-$v[0];Height=$v[3]-$v[1]}
}
function Resolve-Crop($Capture) {
    $more=$Capture.Tree.SelectNodes('//node') | Where-Object {$_.GetAttribute('content-desc') -eq '更多' -and $_.GetAttribute('package') -eq $package} | Select-Object -First 1
    if($null -eq $more) {throw 'The toolbar menu semantic node is absent.'}
    $toolbar=$more.ParentNode
    while($null -ne $toolbar -and $toolbar -is [System.Xml.XmlElement]) {
        $descriptions=@($toolbar.SelectNodes('.//node') | ForEach-Object {$_.GetAttribute('content-desc')})
        if($descriptions -contains '书签' -and $descriptions -contains '返回') {break}
        $toolbar=$toolbar.ParentNode
    }
    if($null -eq $toolbar -or $toolbar -isnot [System.Xml.XmlElement] -or $toolbar.Name -ne 'node') {throw 'Cannot identify a common ReaderTopBar semantic container.'}
    $bounds=NodeBounds $toolbar
    if($null -eq $bounds) {throw 'The toolbar has no nonempty native rectangle.'}
    $foreground=@($toolbar.SelectNodes('.//node') | Where-Object {$_.GetAttribute('text') -ne '' -or $_.GetAttribute('content-desc') -ne ''} | ForEach-Object {
        $b=NodeBounds $_
        if($b) {[pscustomobject]@{Text=$_.GetAttribute('text');Description=$_.GetAttribute('content-desc');Bounds=$b}}
    })
    if($null -ne $CropRectangle) {
        $candidate=[pscustomobject]@{Left=$CropRectangle[0];Top=$CropRectangle[1];Width=$CropRectangle[2];Height=$CropRectangle[3]}
        $method='explicit native pixel rectangle'
    } else {
        # Calibrated against the production ReaderTopBar, not a window-wide magic point.
        # The region lies behind the last part of the chapter heading, right of the
        # foreground title and left of the bookmark icon. Its raw text provides texture.
        $left=[int][Math]::Ceiling($bounds.Left+$bounds.Width*.58)
        $right=[int][Math]::Floor($bounds.Left+$bounds.Width*.73)
        $top=[int][Math]::Ceiling($bounds.Top+$bounds.Height*.15)
        $bottom=[int][Math]::Floor($bounds.Top+$bounds.Height*.45)
        $candidate=[pscustomobject]@{Left=$left;Top=$top;Width=$right-$left;Height=$bottom-$top}
        $method='semantic toolbar rectangle: x58%-73%, y15%-45%'
    }
    $right=$candidate.Left+$candidate.Width
    $bottom=$candidate.Top+$candidate.Height
    Check ($candidate.Width -ge 3 -and $candidate.Height -ge 3 -and $candidate.Width*$candidate.Height -ge $thresholds.MinimumCropPixels) 'the material crop has enough native pixels'
    Check ($candidate.Left -ge $bounds.Left -and $candidate.Top -ge $bounds.Top -and $right -le $bounds.Right -and $bottom -le $bounds.Bottom) 'the crop stays inside the top toolbar'
    # A foreground glyph or its nearby protection halo must not become a blur sample.
    $margin=[int][Math]::Ceiling($bounds.Height*.04)
    foreach($item in $foreground) {
        $b=$item.Bounds
        $intersects=($candidate.Left -lt $b.Right+$margin -and $right -gt $b.Left-$margin -and $candidate.Top -lt $b.Bottom+$margin -and $bottom -gt $b.Top-$margin)
        Check (-not $intersects) "the crop avoids foreground '$($item.Text)$($item.Description)'"
    }
    $script:cropEvidence=[pscustomobject]@{Method=$method;Toolbar=$bounds;Foreground=$foreground;ForegroundMarginPx=$margin;Ui=$Capture.Ui}
    return $candidate
}
function Check-Position($Before,$After,[string]$Label) {
    Check ($Before.loads -eq $After.loads) "$Label does not reload the reader document"
    Check ($Before.readerBounds.left -eq $After.readerBounds.left -and $Before.readerBounds.top -eq $After.readerBounds.top -and $Before.readerBounds.width -eq $After.readerBounds.width -and $Before.readerBounds.height -eq $After.readerBounds.height) "$Label preserves the native reader rectangle"
    Check ($Before.web.width -eq $After.web.width -and $Before.web.height -eq $After.web.height -and [Math]::Abs($Before.web.scrollY-$After.web.scrollY) -le 1 -and $Before.web.glyph.offset -eq $After.web.glyph.offset -and [Math]::Abs($Before.web.glyph.top-$After.web.glyph.top) -le 1) "$Label preserves viewport and independent character position"
}
function Texture([string]$Path) {
    return [MiradMaterialPixels]::Analyze($Path,$crop.Left,$crop.Top,$crop.Width,$crop.Height)
}

try {
    Add-Type -AssemblyName System.Drawing
    if(-not ('MiradMaterialPixels' -as [type])) {
        $drawingReferences=@([Drawing.Bitmap].Assembly.Location,[Drawing.Rectangle].Assembly.Location)
        $drawingReferences+=@([Drawing.Bitmap].GetInterfaces() | Where-Object {$_.Assembly.GetName().Name -like 'System.Private.Windows.*'} | ForEach-Object {$_.Assembly.Location})
        Add-Type -TypeDefinition $pixelAnalyzerSource -ReferencedAssemblies @($drawingReferences | Select-Object -Unique)
    }
    $deviceStatus=& $driver -Action Status -RunId $RunId
    Check ($deviceStatus.ExitCode -eq 0 -and $deviceStatus.BootCompleted -eq '1') 'the owned isolated emulator is ready'
    $install=& $driver -Action Install -RunId $RunId -Apk $apkPath -ExpectedPackage $package
    Check ($install.ExitCode -eq 0) 'the isolated preview APK installs successfully'
    $installed=(Device @('shell','pm','path',$package)).Trim()
    if($installed -notmatch '^package:(/data/app/[^\r\n]+/base\.apk)$') {throw 'Unexpected preview package path.'}
    $installedHash=((Device @('shell','sha256sum',$Matches[1])) -split '\s+')[0]
    Check ($installedHash -eq $apkHash) 'installed APK matches the pinned test artifact'
    $launch=Device @('shell','am','start','-S','-W','-n',$component,'--es','scene','appearance','--es','entry','reader','--ez','hardware','true','--es','mode','DAY','--es','theme','PAPER','--ez','chrome','true','--ei','transparency','100','--es','pageMode','SCROLL','--ez','fullScreen','false','--ez','showClock','false')
    Check ($launch -match 'Status: ok') 'the real reader Activity launches successfully'
    $launched=$true
    $processId=(Device @('shell','pidof',$package)).Trim()
    [void](Dump)
    $initial=Capture 'initial-glass' $true
    Check ($initial.State.hardwarePreview -and $initial.State.hardware.hostWindowMatchesActivity) 'the fixture uses the production same-window hardware scene'
    Check (-not $initial.State.config.showClock -and $initial.State.appearance.mode -eq 'DAY') 'the comparison fixes day appearance and removes the reader clock'
    Check ((Metric $initial.State 'hardware') -eq 'true' -and (Metric $initial.State 'gpuActive') -eq 'true' -and (Metric $initial.State 'cpuDisplayCaptures') -eq '0') 'initial glass uses the GPU source without CPU display captures'
    $crop=Resolve-Crop $initial
    $initialCropEvidence=$cropEvidence

    # Only broadcasts: do not recreate or hide the toolbar between these two settings.
    Command 'transparency' @('--ei','percent','0')
    $zero=Capture 'solid-zero' $true
    Check-Position $initial.State $zero.State 'setting zero transparency'
    Check ((Metric $zero.State 'gpuActive') -eq 'false' -and [double](Metric $zero.State 'baseOpacity') -eq 1.0) 'zero transparency reaches the solid path before recovery'
    Command 'transparency' @('--ei','percent','100')
    $restored=Capture 'restored-glass' $true
    Check-Position $initial.State $restored.State 'restoring transparency'
    Check ((Metric $restored.State 'gpuActive') -eq 'true' -and (Metric $restored.State 'cpuDisplayCaptures') -eq '0') 'restored glass reactivates the hardware source'
    Check ((Device @('shell','pidof',$package)).Trim() -eq $processId) 'the same application process owns the entire transparency transition'
    $restoredCrop=Resolve-Crop $restored
    $cropEvidence=[ordered]@{Initial=$initialCropEvidence;Restored=$cropEvidence}
    Check ($restoredCrop.Left -eq $crop.Left -and $restoredCrop.Top -eq $crop.Top -and $restoredCrop.Width -eq $crop.Width -and $restoredCrop.Height -eq $crop.Height) 'restoration preserves toolbar geometry and the foreground-free crop'

    Command 'chrome' @('--ez','visible','false')
    $bare=Capture 'bare-reader' $false
    Check-Position $initial.State $bare.State 'removing toolbar chrome'
    $initialPixels=Texture $initial.Path
    $restoredPixels=Texture $restored.Path
    $barePixels=Texture $bare.Path
    $recovery=[MiradMaterialPixels]::Compare($initial.Path,$restored.Path,$crop.Left,$crop.Top,$crop.Width,$crop.Height)
    $ratios=@($initialPixels,$restoredPixels) | ForEach-Object {
        [pscustomobject]@{
            Gradient=if($barePixels.GradientRms -gt 0){$_.GradientRms/$barePixels.GradientRms}else{[double]::PositiveInfinity}
            Laplacian=if($barePixels.LaplacianRms -gt 0){$_.LaplacianRms/$barePixels.LaplacianRms}else{[double]::PositiveInfinity}
            NormalizedLaplacian=if($barePixels.NormalizedLaplacianRms -gt 0){$_.NormalizedLaplacianRms/$barePixels.NormalizedLaplacianRms}else{[double]::PositiveInfinity}
        }
    }
    $pixelResults=[ordered]@{Algorithm='BT.709-weighted PNG channel values (0..255), adjacent-pixel gradient RMS, four-neighbor Laplacian RMS; normalization divides Laplacian by luma standard deviation';Crop=$crop;Initial=$initialPixels;Restored=$restoredPixels;Bare=$barePixels;RecoveryDifference=$recovery;InitialToBare=$ratios[0];RestoredToBare=$ratios[1];Thresholds=$thresholds}
    $pixelResults | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $output 'pixels.json') -Encoding utf8
    Check ($barePixels.LuminanceStdDev -ge $thresholds.MinimumBareLuminanceStdDev -and $barePixels.GradientRms -ge $thresholds.MinimumBareGradientRms -and $barePixels.LaplacianRms -ge $thresholds.MinimumBareLaplacianRms) 'the uncovered crop contains enough actual text edges to prove a blur difference'
    foreach($index in @(0,1)) {
        $label=if($index -eq 0){'initial'}else{'restored'}
        $sample=if($index -eq 0){$initialPixels}else{$restoredPixels}
        $ratio=$ratios[$index]
        Check ($sample.LuminanceStdDev -ge $thresholds.MinimumGlassLuminanceStdDev) "$label glass retains background variation instead of an opaque flat fill"
        Check ($ratio.Gradient -le $thresholds.MaximumGlassToBareGradientRatio -and $ratio.Laplacian -le $thresholds.MaximumGlassToBareLaplacianRatio -and $ratio.NormalizedLaplacian -le $thresholds.MaximumGlassToBareNormalizedLaplacianRatio) "$label glass visibly removes high-frequency text edges (gradientRatio=$($ratio.Gradient), laplacianRatio=$($ratio.Laplacian), normalizedRatio=$($ratio.NormalizedLaplacian))"
    }
    Check ($recovery.MeanChannelDelta -le $thresholds.MaximumRecoveryMeanChannelDelta -and $recovery.P95PixelDelta -le $thresholds.MaximumRecoveryP95PixelDelta) "restored glass pixels match the initial material (meanRGBDelta=$($recovery.MeanChannelDelta), p95PixelDelta=$($recovery.P95PixelDelta))"
    $passed=$true
} catch {
    $failure=$_.Exception.Message
    throw
} finally {
    if($launched) {
        try {Command 'chrome' @('--ez','visible','false')} catch {$cleanupErrors.Add($_.Exception.Message)}
        try {Command 'transparency' @('--ei','percent','100')} catch {$cleanupErrors.Add($_.Exception.Message)}
        try {$cleanupState=Dump} catch {$cleanupErrors.Add($_.Exception.Message)}
    }
    [ordered]@{
        Passed=($passed -and $cleanupErrors.Count -eq 0);Failure=$failure;RunId=$RunId;Device=$deviceStatus
        Apk=$apkPath;SHA256=$apkHash;InstalledSHA256=$installedHash
        ScriptSHA256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        Checks=$checks;Crop=$crop;CropEvidence=$cropEvidence;Thresholds=$thresholds;Pixels=$pixelResults;Captures=$captures
        CleanupErrors=$cleanupErrors;CleanupState=$cleanupState
        Scope='Owned emulator, fixed reader fixture and production toolbar. Native pixel similarity plus raw and contrast-normalized high-frequency metrics; not a blurDp assertion or a target-phone performance claim.'
    } | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding utf8
    if($cleanupErrors.Count -gt 0 -and -not $failure) {throw ($cleanupErrors -join '; ')}
}
Write-Host "Reader material pixel verification passed: $output"
