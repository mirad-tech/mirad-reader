# Isolated native glass preview

This driver uses the existing Android Studio runtime, Android SDK, and API 37 image. It does not install dependencies. Each run owns an AVD, Android preferences, logs, captures, and a private ADB server under `build/verification/glass-native-preview/<run-id>/`.

Run with PowerShell 7.4 or newer from the repository root:

```powershell
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Create -RunId glass-20261022
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Start -RunId glass-20261022
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Status -RunId glass-20261022
```

`Start` launches hidden background processes and returns immediately. Check `Status` after other independent work; `BootCompleted = 1` confirms startup. On this workstation, the Codex filesystem sandbox cannot create the ADB socketpair or connect to the host's listener. Run `Start`, `Status`, and all subsequent device actions in the authorized host environment. The script refuses to let a client implicitly start an unrestricted ADB server.

The default device identity is `emulator-5580`, with the ADB server on port `5049`. The dedicated server uses a deliberately unmatched `--one-device` USB filter and disables mDNS auto-connect. No command enumerates devices or uses the default ADB server. Existing AVDs and phones are not targets.

New instances disable hardware navigation keys so gesture/three-button navigation insets can actually be exercised. `Capture-SafeAreaChecks.ps1` temporarily enables the emulator's tall cutout and three-button overlays, captures both landscape rotations in LTR/RTL, then restores the gesture overlay and portrait rotation.

```powershell
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Install -Apk '<absolute preview APK path>'
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Adb -AdbArgs @('shell', 'am', 'start', '-W', '-n', 'com.mirad.reader.preview/com.mirad.reader.preview.GlassPreviewActivity', '--es', 'scene', 'reader')
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Capture -Name shelf-light
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Adb -AdbArgs @('shell', 'input', 'tap', '540', '1000')
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Adb -AdbArgs @('shell', 'dumpsys', 'gfxinfo', 'com.mirad.reader.preview')
```

`Install` verifies the APK package with the existing `aapt2` before installation. The default accepted ID is `com.mirad.reader.preview`; a different explicit `ExpectedPackage` must still be a separate Mirad ID ending in `preview`. Never install or launch the normal business application for these previews. The preview application must use an independent Application and memory fixtures without `AppContainer`, Room, importing, or external services.

`Capture` copies stdout as binary bytes and checks the PNG signature. Screenshots go to the run's `captures` directory. API 37 can exercise current GPU effects; it does not prove API 26–30 compatibility. Forced fallback screenshots must be labeled accordingly.

The `preview` build variant uses a plain `android.app.Application`, disables the business activity, and includes a separate preview activity. Build it from existing caches with `./gradlew.bat --offline :app:assemblePreview`. Its UI uses in-memory sample metadata/text from the existing regression fixtures, without constructing repositories or a Room database. Debug and Release do not include this activity.

After installing `app/build/outputs/apk/preview/app-preview.apk`:

```powershell
& './tools/glass-preview/Capture-GlassMatrix.ps1'
& './tools/glass-preview/Test-GlassPreview.ps1'
& './tools/glass-preview/Measure-GlassPreview.ps1'
```

The matrix covers light/dark themes, menus/dialogs, reader settings, fallback materials, large fonts, long titles and a wide viewport. Use `-Names @('reader-settings-light','reader-settings-dark')` for a targeted rerun. The driver waits for the activity's first frame and the reader's explicit readiness signal before capturing. `legacy-material-*` is the old-material mode of this same preview host, **not a screenshot of a historical APK**.

The interaction check records slider commit counts, menu/sheet behavior, snapshot release, crash logs, frame statistics, memory and the absence of a database directory under `logs/`. The measurement script compares the same finite interactions with legacy and glass materials; emulator Debug timing is diagnostic evidence, not a physical-device frame-rate guarantee.

The reader repair also has two focused checks:

```powershell
& './tools/glass-preview/Test-BackdropProbe.ps1' -RunId glass-fix-20261022
& './tools/glass-preview/Test-ReaderGlassFix.ps1' -RunId glass-fix-20261022
```

The backdrop probe compares the sampled viewport with independent Window PixelCopy pixels after small/large scrolls, cross-chapter transitions, continuous painting and delayed image/font loads. It checks idle/hidden behavior, reopening, full-screen and visible system bars on real sheet/dialog windows, and unchanged viewport/reading position. The timed legacy draw comparison executes the former no-scroll-transform capture on the same WebView; it is not a historical app timing measurement. `Test-ReaderGlassFix` verifies toolbar touch isolation, foreground/background transitions, complete font semantics and independent deletion targets using only in-memory fixtures.

The matrix includes `font-{narrow,wide}-{light,dark}-{10,13,16}` for typography at 1.0/1.3/1.6 font scale, plus `reader-dialog-fullscreen`, `reader-dialog-system-bars` and `reader-settings-fullscreen`. These scene/intent switches only exist in the preview variant.

After all preview work has finished:

```powershell
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Stop
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in @(5049, 5580, 5581) }
```

The stop action targets the recorded emulator serial and private ADB port. It leaves the run's files and evidence intact. If startup fails, inspect `logs/create.log`, `logs/adb-server.log`, and the emulator stdout/stderr logs before considering another attempt; do not download tools or reuse an existing AVD to work around the failure.
