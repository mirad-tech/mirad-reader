# Isolated native glass preview

当前维护的构建入口为 `debug`、`release` 和 `preview`。独立 GPU 原型已退役；阅读 GPU 检查使用下文 Integrated reader hardware checks 中的生产组件预览。三个单轮修复脚本已从当前树移除，需要追溯时从 Git 历史查看，不再作为常规验收入口。

This driver uses the existing Android Studio runtime, Android SDK, and API 37 image. It does not install dependencies. Each run owns an AVD, Android preferences, logs, captures, and a private ADB server under `build/verification/glass-native-preview/<run-id>/`.

新建的隔离实例在测试结束并执行 `Stop` 后，会先确认本次模拟器及专用 ADB 已停止、相关端口已释放，再仅删除该运行目录的 `avd` 子目录；`captures`、`logs` 和 `instance.json` 保留。旧实例或未带本工具所有权标记的实例不会自动删除。清理状态和失败原因记录在 `instance.json` 与 `logs/avd-cleanup.log`；无法确认安全时不开始清理，清理过程出错时可能留下部分文件并输出警告。需要稍后继续使用同一临时模拟器时，可在停止时传入 `-KeepAvdOnStop`；已清理的运行应使用新的 RunId 创建实例。

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

预览启动参数 `transparency` 表示清晰度 0–100，`glassEnabled` 独立控制开关（默认 true）；0 不表示关闭。两个入口的内存偏好在首次订阅前初始化完整的新玻璃记录。可用以下命令运行预览夹具回归（AGP 9 默认只为 debug 创建单测任务，因此显式开启其他构建类型的单测）：

```powershell
.\gradlew.bat '-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false' :app:testPreviewUnitTest --tests com.mirad.reader.preview.PreviewInMemoryPreferenceDaoTest --console=plain
```

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

外层测试流程必须在 `finally` 中停止自己创建的实例。多个套件共用一个实例时，由创建方在全部套件结束后执行以下收尾；确需后续复用才传入 `-KeepAvdOnStop`：

```powershell
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Stop
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in @(5049, 5580, 5581) }
```

The stop action targets the recorded emulator serial and private ADB port. It preserves evidence and cleans only the owned temporary AVD unless -KeepAvdOnStop is specified. If startup fails, inspect `logs/create.log`, `logs/adb-server.log`, and the emulator stdout/stderr logs before considering another attempt; do not download tools or reuse an existing AVD to work around the failure.

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

`Test-ReaderMaterialPixels.ps1` 使用相同的 `RunId`、固定 APK 和全新证据目录；固定清晰度为 60，显式关闭再开启玻璃，对比工具栏前景之外的像素区及未遮挡正文。清晰度 0 不是关闭、100 不包含模糊，因此不再使用旧的 100→0→100 作为模糊恢复测试。默认取样区由标题与书签的原生语义边界决定，也可用 `CropRectangle` 事先指定；前景排除、纹理强度和高频抑制阈值保持不变。`layout` 还核对真实输入法上方的可编辑区域及关闭键盘后的实际草稿保存，不能仅凭输入法出现判定可用。
