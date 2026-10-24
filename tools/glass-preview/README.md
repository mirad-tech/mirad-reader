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

The settings and immersive-window checks use `SettingsImmersionPreviewActivity`, the production settings components and a `SettingsRepository` backed by an in-memory preference DAO. Pin the built preview APK before testing and give each suite a new evidence directory:

```powershell
& './tools/glass-preview/Test-ImmersionSettings.ps1' -RunId '<owned run id>' -Apk '<pinned preview APK>' -OutputDirectory '<repo>/build/verification/<new directory>' -Suite settings
```

The available suites are `settings` (category/context visibility and retained preferences), `theme` (image independence and actual system night-mode/resume changes), `window` (cutouts, rotation, backgrounds and window transitions), and `layout` (both settings entries at actual system font scales 1.0/1.3/1.6). Captures record the installed APK hash, current scene, control hierarchy, insets and reader state. Review the screenshots as well as `summary.json`; assertions alone do not prove visual continuity or unclipped text. The fixture never opens a business database, and its saved-value assertions cover the shared repository with an in-memory DAO, not durable user data. Each suite restores its emulator display settings but deliberately leaves the owned emulator running until the final stop below.

After all preview work has finished:

```powershell
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Stop
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in @(5049, 5580, 5581) }
```

The stop action targets the recorded emulator serial and private ADB port. It leaves the run's files and evidence intact. If startup fails, inspect `logs/create.log`, `logs/adb-server.log`, and the emulator stdout/stderr logs before considering another attempt; do not download tools or reuse an existing AVD to work around the failure.

## Same-window WebView GPU prototype

The separate `gpuPreview` variant is release-optimized, non-debuggable and profileable. Its package is `com.mirad.reader.gpupreview`, its launcher is **Mirad 玻璃测试**, and it uses the local test signing identity. It has a plain `Application`, no book database and no business initialization. The prototype Activity belongs to `src/gpuPreview/java`; it is not part of the normal Debug, Preview or Release application.

```powershell
./gradlew.bat --offline :app:assembleGpuPreview
& './tools/glass-preview/Test-GpuBackdrop.ps1' -RunId '<owned run id>' -Apk '<pinned app-gpuPreview.apk>' -OutputDirectory '<repo>/build/verification/<new directory>'
```

The finite check uses the existing isolated emulator driver. It compares raw hardware content with an independent screenshot after scroll-only invalidation, chapter changes, delayed images and real touch scrolling. It separately verifies that blurred content changes without changing the panel configuration, captures day/night at 0/60/100 percent, and compares the edge shader enabled/disabled while checking that the center stays unchanged. Each result contains the exact APK hash, current device identity, hardware-path state, viewport state and pixel comparison. An emulator pass is not approval of the physical target device or proof of a one-frame freshness bound.

For the target iQOO 12 Pro / Android 16, the user installs this separate APK manually. Compare **关闭玻璃**, **旧玻璃** and **GPU 玻璃** using the same scrolling and panel-drag operations. Check both day/night appearances, chapter changes and delayed image loading. **记录 30 秒** records one immutable run; **保存结果** exports that run without changing its labels or completion flag. Pausing the Activity ends an unfinished run as incomplete. A new automatic scroll sequence cancels the previous sequence.

Repeat each of the three modes three times, with the same 30-second operation sequence. Save each completed run before starting the next, using a distinct document name. Choose the mode, theme and opacity before recording; those controls are disabled during a run. Use ordinary touch input while recording: delivering another Activity intent may pause the Activity and correctly end the run as incomplete. Do chapter/image freshness checks separately from the fixed-configuration performance runs.

FrameMetrics collection uses a dedicated thread. Its exported timings are diagnostic data; formal performance acceptance still uses Perfetto FrameTimeline and the actual refresh rate granted by the device. Host `contentRecords` counts display-list recordings, not presented frames. The prototype requests 120Hz but reports the observed rate instead of assuming it was granted.

The physical-device prerequisite was confirmed for the iQOO 12 Pro / Android 16 on 2026-10-24. The production reader now uses the integrated hardware scene described below. The prototype remains a separate diagnostic application; its FrameMetrics results are not final integrated performance acceptance.

## Unified appearance checks

`SettingsImmersionPreviewActivity` has an `appearance` scene backed by the real `SettingsRepository` with an in-memory DAO. It exercises the shared mode and independent day/night profiles through the production settings and reader components.

```powershell
./gradlew.bat --offline :app:assemblePreview
& './tools/glass-preview/Test-AppAppearance.ps1' -RunId '<owned run id>' -Apk '<pinned app-preview.apk>' -OutputDirectory '<repo>/build/verification/<new directory>'
```

The check covers both settings entries, profile memory, system-mode changes, background/resume, a reopened repository, and 320dp at 1.6 font scale. Repository reopening preserves the in-memory DAO; it is not a business database or process-restart persistence test. Inspect the captures as well as the JSON assertions. Existing scene names remain available for the older focused checks.

## Integrated reader hardware checks

The `hardware=true` route of `SettingsImmersionPreviewActivity` uses the production `ReaderHardwareScene`, `ReaderOverlayHost`, glass material, reader WebView assets and shared panel content. It keeps the separate preview application and memory-only data. It does not instantiate the business database or an external TTS service.

```powershell
./gradlew.bat --offline :app:assemblePreview
& './tools/glass-preview/Test-ReaderHardware.ps1' -RunId '<owned run id>' -Apk '<pinned app-preview.apk>' -OutputDirectory '<repo>/build/verification/<new directory>' -Suite core
& './tools/glass-preview/Test-ReaderHardwareCover.ps1' -RunId '<owned run id>' -Apk '<pinned app-preview.apk>' -OutputDirectory '<repo>/build/verification/<another new directory>'
```

`Test-ReaderHardware` offers `smoke`, `core`, `materials`, `layout`, `fallback` and `lifecycle`. Core checks same-window modal interaction, dragging, menu dismissal, real IME input, native and JavaScript viewport stability, independent glyph position, volume-key blocking, nested delete confirmation and settings category Back handling. Its compressed UI hierarchy follows accessibility importance; the default uncompressed hierarchy deliberately includes hidden diagnostic nodes and must not be used to assert accessibility exposure. Lifecycle checks HOME and return to the existing Activity without resetting its reading position.

The materials suite covers day/night at 0/60/100 percent, image backgrounds, delayed inline images, chapter switching, stopped/active analysis and a finite scroll sequence. Layout uses actual display-size, density, font-scale and rotation settings. Forced fallback exercises the fallback branch on the same API 37 device and is not a substitute for running older Android releases.

The COVER check holds the immutable old-page HARDWARE bitmap while the WebView actually advances, compares the central body pixels, then removes the old page to verify the new content. It excludes changing header/footer metadata from that pixel comparison. Each suite pins the installed APK hash, saves JSON and screenshots in a new evidence directory, and restores modified emulator settings. Stop the owned emulator and private ADB server after all suites finish.

Integration diagnostics include a zero CPU display-capture counter, analysis dimensions/thread/interval/concurrency, material readiness and native viewport dimensions. These describe this implementation path. Formal refresh-rate, frame-deadline and one-display-period latency acceptance still requires the target phone and Perfetto FrameTimeline.

`Test-ReaderMaterialPixels.ps1` uses the same `RunId`, pinned `Apk` and new `OutputDirectory` parameters. It compares a foreground-free toolbar crop before and after 100→0→100, then compares both with the unoccluded document. Fixed pixel-difference and high-frequency thresholds reject a missing effect even when `blurDp` telemetry says that it is active. `layout` also checks actual editable text bounds above the real IME and the subsequent fixture-note save; IME visibility alone is not a usability assertion.
