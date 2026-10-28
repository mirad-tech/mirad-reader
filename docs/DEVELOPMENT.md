# 开发、构建与发布

## 环境

- Android SDK：`platforms;android-36.1`；由 AGP 选择所需构建工具。本机发行签名工具使用 `build-tools;37.0.0`。
- Java 21：`gradle/gradle-daemon-jvm.properties` 指定 Daemon JVM 21；首次运行可能由 Gradle 下载对应工具链。
- 使用仓库中的 Gradle Wrapper，不依赖全局 Gradle。
- 在本机 `local.properties` 配置 `sdk.dir`，或者配置 `ANDROID_HOME`；不要提交本机路径。

## Android 检查

从仓库根目录执行（Windows）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
.\gradlew.bat :app:lintDebug --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

macOS / Linux 将 `.\gradlew.bat` 换成 `./gradlew`。

报告位置：

- 单测：`app/build/reports/tests/testDebugUnitTest/` 与 `app/build/test-results/testDebugUnitTest/`。
- Lint：`app/build/reports/lint-results-debug.html`。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 未签名 Release APK：`app/build/outputs/apk/release/app-release-unsigned.apk`。

## Chromium 阅读器回归

Node.js 20 或更新版本；Playwright 版本由 `tools/browser-checks/package-lock.json` 固定。

阅读引擎源码位于 `app/src/main/reader/`。其中 `reader.js` 是固定顺序的拼接入口，`// @include` 对应同目录的职责文件；所有片段仍在同一个闭包中执行。Gradle 为 Debug、Preview、Release 分别生成 `app/build/generated/readerAssets/<构建类型>/reader/reader.js`，APK 中的加载路径仍为 `/reader/reader.js`。修改源码片段后必须运行本节回归，不直接编辑生成文件。

浏览器脚本通过 `reader-source.cjs` 读取同一个入口并拼接源码，不需要先构建 Android；普通 Gradle 构建也不依赖 Node.js。

```powershell
npm ci --prefix tools/browser-checks
npm --prefix tools/browser-checks exec -- playwright install chromium
npm --prefix tools/browser-checks test
```

也可单独运行 `test:auto-read`、`test:font`、`test:modern`、`test:anchors`。这些脚本从自身位置定位仓库，不依赖个人电脑的缓存目录。截图和结果写入 `build/verification/`。

- 自动阅读脚本覆盖速度、暂停、滚动回报和旧单章换章路径，显式关闭窗口引擎。
- 字体脚本使用保存的主题 CSS 夹具，覆盖分页、单章滚动和连续滚动中的非首章；通过独立的 DOM 字符矩形检查字号往返定位、导航后的锚点更新、标记和布局。分页要求原字符仍可见，滚动允许最多 1 CSS px 的坐标舍入误差。
- 现代阅读器脚本覆盖部分 CFI、TextQuote 和短章场景；裁剪用例通过真实滚动事件和生产延时裁剪路径检查章节集合、正文位置与下方保留高度。
- 锚点回归覆盖常用字号往返、12/60 字号及零间距的列边界、延迟图片触发的真实重排，以及字号调整与连续滚动裁剪、前插章节交错。定时用例使用 Playwright 时钟执行生产回调，并检查补偿后正常导航仍会更新锚点。
- 上述浏览器检查不是 Compose、Android WebView、Room 迁移或真实触摸手势的设备测试。

`fixtures/theme-14.css`、`theme-30.css` 是当前字体夹具；`fixtures/baseline/` 下的同名文件保留历史 CSS 的提前闭合括号。`font-browser.cjs` 分别读取两组作字体变更对照，它们不是重复副本，不应合并。

## 正式 APK 签名

正式 APK 必须沿用历史发行证书。签名文件和密码文件都放在仓库外，不上传到 Git 或日志。

本项目的正式证书 SHA-256：

```text
5AC7DBE93C8D02663FCD563C442A281C32B3D8BCF12411F43C85030A37BAF0B6
```

在 Windows PowerShell 中设置仓库外的签名文件路径，再调用发行工具：

```powershell
$env:ANDROID_HOME = '<Android SDK 目录>'
$env:MIRAD_KEYSTORE = '<正式密钥库文件>'
$env:MIRAD_KEYSTORE_PASSWORD_FILE = '<密码文件>'
.\tools\package-release.ps1
```

工具执行 Release 构建、签名、证书核验、APK 对齐与版本信息核验，默认输出到 `artifacts/apk/release/<版本>/<yyyyMMdd-HHmmss>/`，其中包含 `mirad-reader-<版本>-release.apk`、`SHA256SUMS.txt` 与签名验证记录。已有同名 APK 时仍拒绝覆盖；显式传入 `-OutputDirectory` 时遵循指定目录，建议继续使用版本与批次分层。

PKCS12 使用与密钥库相同的密钥密码时，不要把同一个单行密码文件同时传给 `--ks-pass` 和 `--key-pass`，以免读取到文件末尾。

## 仓库与发布约定

- 源码、Room schema、单测和可复用工具纳入 Git；APK、测试输出、SDK 路径、依赖目录及签名资料由 `.gitignore` 排除。
- `agent.md` 为本机交接记录，保留在本地，不作为公共源码上传；公共操作说明以本文件为准。
- 新正式版本递增 `versionName` 与 `versionCode`，更新 README 和对应的 `docs/releases/<版本>.md`。
- 保留已发布标签及附件，不覆盖旧版 APK，不重写公开历史。
- 长期保留的安装包统一进入 `artifacts/apk/`：正式构建位于 `release/`，调试包位于 `debug/`，独立预览包位于 `preview/`；同一批次的 APK、idsig、校验和及验证记录一起保存。交付副本也放在该批次内，不再复制到仓库根目录。
- `app/build/outputs/` 是 Gradle 固定输出；验证目录中与截图、日志绑定的 APK 原位保留。2026-10-26 的整理清单与前后路径、SHA-256 记录见本机 `artifacts/apk/organization-20261026.json`。
- 发布前检查实际差异、单测、Lint 与签名；发布后核对远端提交、标签、Release 状态，并下载 APK 比对 SHA-256。
- 用户设备安装、生产数据操作及额外发布行为需要明确授权。验证记录须区分源码、JVM、Chromium 和 Android 设备证据。

## 文档收尾

- 常规修复的结果、验证范围和保留限制写入 `docs/releases/<版本>.md`，不默认每轮新增计划和报告。确有跨阶段协作需要时才保留独立计划。
- 一轮工作随版本收尾后，将对应计划、实施报告和审查记录按原文件名移入 `docs/archive/{plans,reports,reviews}/`。归档表示保留当时记录，不表示未完成事项已关闭；版本说明必须保留实际限制。
- `docs/README.md` 对历史文档只保留一个归档入口；详细目录维护在 `docs/archive/README.md`。不为删除模型名而单独重命名历史文件。
- 移动时保留正文内容，同步调整索引及相对链接。只在本机存在的 `build/verification/`、APK、日志和忽略文件以纯文本路径记录，不生成克隆后必坏的文件链接。
- 移动后运行 `python tools/check-doc-links.py`（Python 3.9+）；检查仓库内相对文件链接是否纳入 Git，外链和页内锚点不在此检查范围内。
