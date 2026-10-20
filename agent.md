# Agent 交接与关键信息

最后更新：2026-10-20（Asia/Shanghai）  
项目：`G:\mirad\mirad-server\book`（Android 阅读器「墨读 / MiradReader」）

本文件记录后续 agent 最容易踩坑、且从代码里看不出来的信息。**不要提交签名密钥或密码**。

---

## 1. 当前工作区状态

- 分支：`main`
- 当前版本：`versionCode = 12` / `versionName = "1.0.11"`。
- **1.0.11 release 已构建、已签名**：
  - 根目录：`墨读-1.0.11-release.apk`
  - 归档：`build/release/mirad-reader-1.0.11-release.apk`
  - SHA-256：`2220904039A14F3E91F07EDA615EF8BC0F4C2B5A3685F3DECBAA6D7496BA22CC`
  - 签名证书 SHA-256：`5AC7DBE93C8D02663FCD563C442A281C32B3D8BCF12411F43C85030A37BAF0B6`（与 1.0.0–1.0.10 同一把）
- 本版本包含现代阅读器架构升级（连续滚动视图、CFI 与 TextQuote 选区、定位模型 Locator、大文件 TXT 极速分章、标点挤压与避头尾、SQLite FTS4 全书搜索、网络书源/网址导入、默认 UTF-8 编码）。
- **1.0.10 release 已构建、已签名并通过升级安装验证**：
  - 根目录：`墨读-1.0.10-release.apk`
  - 归档：`build/release/mirad-reader-1.0.10-release.apk`
  - SHA-256：`A3C4E86E368F90B0414A0E86466DD3C56432B10F2B5B3575D6D3866EA7B4DD52`
  - 签名证书 SHA-256：`5AC7DBE93C8D02663FCD563C442A281C32B3D8BCF12411F43C85030A37BAF0B6`（与 1.0.0–1.0.9 同一把）
  - 已在模拟器上验证 `1.0.9 → 1.0.10` 覆盖安装成功，versionCode=11；Release（R8）包导入 TXT、打开阅读器、打开自动阅读弹层并自动翻页均正常。
- 最后可用的上一正式版：`build/release/mirad-reader-1.0.9-release.apk`。
- 最近一次成功 Debug 构建在版本号改为 1.0.10 之前；`app/build/outputs/apk/debug/app-debug.apk` 可用于模拟器验收。
- 不提交、不 push、不发布，除非用户明确要求。

---

## 2. 发行签名（最容易踩坑）

### 必须使用的原发行密钥

```text
目录：G:\ASL\.android-signing\mirad-reader\
密钥库：mirad-reader-release.p12
类型：PKCS12
Alias：mirad-reader-release
密码文件：store-password.txt（目录内，勿复制到仓库）
证书 SHA-256：5AC7DBE93C8D02663FCD563C442A281C32B3D8BCF12411F43C85030A37BAF0B6
```

- 1.0.0–1.0.9 的 `build/release/mirad-reader-*-release.apk` 全部由这把密钥签名。
- **后续 release 必须继续使用同一密钥**，否则无法覆盖安装，用户必须卸载，会丢本地书库和进度。
- `G:\ASL\.android-signing\mirad-reader\README.txt` 也有说明。

### 绝对不要用的密钥

```text
C:\Users\ASL\.mirad\release.jks
```

这是另一把 RSA 4096 的密钥，证书 SHA-256 为
`F6CE2FACF8786657FEFD266B4B569C67AAFCF35F01120383F78C19176BE244A0`，
与历史 release **不匹配**。用它签出来的包无法覆盖 1.0.9。

> 这次构建花了很久，就是因为先找到并试签了这把错误的密钥，之后才通过历史会话记录找到正确位置。以后先查 `G:\ASL\.android-signing`，不要再试 `~/.mirad`。

### 签名产物命名

历史约定：

```text
build/release/mirad-reader-<versionName>-release.apk
```

例如 `build/release/mirad-reader-1.0.9-release.apk`。需要给用户单文件时，可另复制到项目根目录，例如 `墨读-1.0.10-release.apk`。

---

## 3. Release 构建 / 签名 / 校验流程

环境：

```text
JDK：C:\Users\ASL\.jdks\ms-17.0.17
Android SDK：S:\Android\Sdk
build-tools：S:\Android\Sdk\build-tools\37.0.0
Gradle 用户目录：S:\Android\.gradle
```

步骤（PowerShell，密码不落日志）：

```powershell
# 1. 构建未签名 release（R8 / 资源压缩已在 build.gradle.kts 开启）
.\gradlew.bat :app:assembleRelease --console=plain

# 2. 签名（输出到 build/release，先建目录）
New-Item -ItemType Directory -Force build/release | Out-Null
$apksigner = 'S:\Android\Sdk\build-tools\37.0.0\apksigner.bat'
$ksDir = 'G:\ASL\.android-signing\mirad-reader'
$pwFile = Join-Path $ksDir 'store-password.txt'
& $apksigner sign `
  --ks (Join-Path $ksDir 'mirad-reader-release.p12') `
  --ks-key-alias mirad-reader-release `
  --ks-pass "file:$pwFile" `
  --key-pass "file:$pwFile" `
  --out 'build\release\mirad-reader-1.0.10-release.apk' `
  'app\build\outputs\apk\release\app-release-unsigned.apk'

# 3. 校验：签名证书必须是 5ac7db…，并且 v2/v3 通过
& $apksigner verify --verbose --print-certs 'build\release\mirad-reader-1.0.10-release.apk'

# 4. 对齐与元数据
& 'S:\Android\Sdk\build-tools\37.0.0\zipalign.exe' -c -P 16 4 'build\release\mirad-reader-1.0.10-release.apk'
& 'S:\Android\Sdk\build-tools\37.0.0\aapt.exe' dump badging 'build\release\mirad-reader-1.0.10-release.apk'
# 确认 application-debuggable 不出现，versionCode=11，versionName=1.0.10
```

说明：

- `assembleRelease` 只生成 `app/build/outputs/apk/release/app-release-unsigned.apk`，Gradle 没有配置 signingConfig。
- 签名前确认版本号已经递增。当前工作区已是 1.0.10 / 11。
- 不要覆盖 `build/release` 里已有的历史 APK。
- 签名密钥目录在仓库外；`agent.md` 只记录路径，不记录密码。

---

## 4. 测试与验收状态

### JVM 单元测试

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
```

最近一次强制重跑：**32 个套件、203 项、0 失败、0 错误、0 跳过**。  
本轮新增：

- `app/src/test/java/com/mirad/reader/reader/AutoReadControllerTest.kt`（17 项）
- `app/src/test/java/com/mirad/reader/core/model/AutoReadConfigTest.kt`（6 项）

### Debug 构建 / lint

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:lintDebug --console=plain
```

- `assembleDebug`：成功。
- `lintDebug`：**0 error，29 warning，5 hint**；历史警告未清空，不要声称“lint 全绿”。

### 浏览器验证（reader.js）

```powershell
node verification/auto-read/check.cjs
node verification/font-browser/check.cjs
```

- 自动阅读/滚动：`verification/auto-read/check.cjs`，10/10 通过，结果在
  `verification/auto-read/results.json`。
- 既有字号/锚点回归：`verification/font-browser/check.cjs` 通过；14↔30px 往返后锚点 1784 恢复、3 个标注保留。
- Playwright 在：
  `C:/Users/ASL/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright`

### Android 模拟器

- AVD：`Pixel_10_Pro_Fold`，Android 17 / API 37，分辨率 2076×2152。
- adb：`S:\Android\Sdk\platform-tools\adb.exe`
- 辅助脚本：`python verification/emulator_ui.py dump|tap|screen`
- 已验收：
  - TXT / EPUB 自动阅读入口、分页定时翻页、跨章；
  - 滚动模式匀速下滑、触摸暂停、菜单正文点击只关菜单；
  - 切后台暂停、旋转暂停、长按选词不翻页；
  - 重新打开书籍自动阅读为关闭、速度按书保存；
  - 横屏/竖屏显示与安全区域。
- **未验收**：真实 TTS 出声与通知栏恢复、人工手感、设备端帧率/速度精密测量、COVER 覆盖动画下的自动翻页、多章滚动停留精确定时。
- 模拟器验证结束后执行 `adb emu kill`；不要操作真实手机。

---

## 5. 自动阅读实现要点

- 配置字段：`ReaderConfig.autoPageSeconds`（默认 20，5–120）与
  `ReaderConfig.autoScrollSecondsPerScreen`（默认 30，5–120）。
  只保存速度，不保存运行状态；旧配置缺字段自动回落默认值。
- 状态机：`app/src/main/java/com/mirad/reader/reader/AutoReadController.kt`
  - `OFF / RUNNING / PAUSED`，唯一计时 Job；
  - 分页：到点翻页，暂停/继续重算完整间隔；
  - 滚动：JS 单个 `requestAnimationFrame` 循环，章末停留一个「翻页间隔」再接章；
  - 结束/加载失败关闭并提示；会话/章节代次防止旧回调复活。
- JS：
  - 自动下滑、点按翻屏、定位动画共用可取消机制；
  - 触摸按下立即暂停并取消旧动画；
  - 滚动位置回报 200ms 节流，停止后补报最终位置；
  - 手动拖动/惯性期间延后资源重排；
  - 手动跨章只在手势开始时位于边界 8px 内且继续外滑 ≥48px 时触发。
- 菜单点击优先级在 `ReaderScreen.kt`：
  弹层 → 工具栏显示时只关工具栏 → 原有点击分区。
- `windowedScroll` 仍默认关闭；`reader.js` 的 `windowed()` 现在只有显式 `true` 才启用，不要改回宽松判断。

---

## 6. 其他约束

- 不开启尚未稳定的连续窗口引擎（`windowedScroll` 保持默认 `false`）。
- 不新增横向拖动翻页。
- 不修改数据库结构；自动阅读只加 JSON 配置字段。
- 不操作用户真实手机；模拟器验收结束关闭。
- 不提交/推送/发布/制作签名包，除非用户明确要求。
- 发行密钥和 `store-password.txt` 不得进入 Git、日志或回复内容。
- 相关计划文档：
  - `docs/repository-review-fix-plan.md`
  - `docs/scroll-chapter-review-fix-plan.md`
  - `docs/continuous-scroll-plan.md`
