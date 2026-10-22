[CmdletBinding()]
param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Keystore = $env:MIRAD_KEYSTORE,
    [string]$StorePasswordFile = $env:MIRAD_KEYSTORE_PASSWORD_FILE,
    [string]$KeyAlias = 'mirad-reader-release',
    [string]$BuildToolsVersion = '37.0.0',
    [string]$OutputDirectory,
    [string]$ExpectedCertificate = '5AC7DBE93C8D02663FCD563C442A281C32B3D8BCF12411F43C85030A37BAF0B6'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -or -not $Keystore -or -not $StorePasswordFile) {
    throw 'Set ANDROID_HOME, MIRAD_KEYSTORE and MIRAD_KEYSTORE_PASSWORD_FILE, or pass their parameters.'
}
$buildTools = Join-Path $AndroidSdk "build-tools/$BuildToolsVersion"
$signer = Join-Path $buildTools 'apksigner.bat'
$aligner = Join-Path $buildTools 'zipalign.exe'
$aapt = Join-Path $buildTools 'aapt.exe'
foreach ($required in @($signer, $aligner, $aapt, $Keystore, $StorePasswordFile)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw 'A required signing input or Android build tool is missing.' }
}
Push-Location $projectRoot
try {
    & .\gradlew.bat :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    $metadata = Get-Content -LiteralPath 'app/build/outputs/apk/release/output-metadata.json' -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.mirad.reader' -or @($metadata.elements).Count -ne 1) { throw 'Unexpected release artifact metadata.' }
    $artifact = $metadata.elements[0]
    $version = $artifact.versionName
    if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot "build/release/publish-$version" }
    $OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
    $apk = Join-Path $OutputDirectory "mirad-reader-$version-release.apk"
    if (Test-Path -LiteralPath $apk) { throw 'Output APK already exists. Choose a new OutputDirectory; historical artifacts are never overwritten.' }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $unsigned = Join-Path 'app/build/outputs/apk/release' $artifact.outputFile
    # Omit key-pass: PKCS12 uses the same password and the file must only be consumed once.
    & $signer sign --ks $Keystore --ks-key-alias $KeyAlias --ks-pass "file:$StorePasswordFile" --out $apk $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Release signing failed.' }
    $verification = & $signer verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
    if (-not ($verification -match [regex]::Escape($ExpectedCertificate))) { throw 'Signing certificate differs from the expected release identity.' }
    & $aligner -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed.' }
    $badging = & $aapt dump badging $apk
    if ($LASTEXITCODE -ne 0 -or $badging -match 'application-debuggable') { throw 'Invalid or debuggable release APK.' }
    if (-not ($badging -match "versionCode='$($artifact.versionCode)' versionName='$version'")) { throw 'APK version does not match output metadata.' }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'SHA256SUMS.txt'), "$hash  $([IO.Path]::GetFileName($apk))`n", [Text.UTF8Encoding]::new($false))
    $verification | Set-Content -LiteralPath (Join-Path $OutputDirectory 'signature-verification.txt') -Encoding utf8
    [pscustomobject]@{ Path = $apk; Version = $version; VersionCode = $artifact.versionCode; SHA256 = $hash; Bytes = (Get-Item -LiteralPath $apk).Length }
} finally { Pop-Location }
