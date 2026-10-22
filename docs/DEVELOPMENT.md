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

```powershell
npm ci --prefix tools/browser-checks
npm --prefix tools/browser-checks exec -- playwright install chromium
npm --prefix tools/browser-checks test
```

也可单独运行 `test:auto-read`、`test:font`、`test:modern`。这些脚本从自身位置定位仓库，不依赖个人电脑的缓存目录。截图和结果写入 `build/verification/`。

- 自动阅读脚本覆盖速度、暂停、滚动回报和旧单章换章路径，显式关闭窗口引擎。
- 字体脚本使用保存的主题 CSS 夹具，检查字号往返、标记和布局；其断言不保证每个场景的字符锚点零漂移。
- 现代阅读器脚本覆盖部分 CFI、TextQuote 和短章场景；其中的裁剪用例为算法级检查，不能替代生产函数及设备端集成验收。
- 上述浏览器检查不是 Compose、Android WebView、Room 迁移或真实触摸手势的设备测试。

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

工具执行 Release 构建、签名、证书核验、APK 对齐与版本信息核验，输出 `build/release/publish-<版本>/mirad-reader-<版本>-release.apk` 和 `SHA256SUMS.txt`。已有同名输出时拒绝覆盖；如需重新构建，可用 `-OutputDirectory` 指定新的目录。

PKCS12 使用与密钥库相同的密钥密码时，不要把同一个单行密码文件同时传给 `--ks-pass` 和 `--key-pass`，以免读取到文件末尾。

## 仓库与发布约定

- 源码、Room schema、单测和可复用工具纳入 Git；APK、测试输出、SDK 路径、依赖目录及签名资料由 `.gitignore` 排除。
- `agent.md` 为本机交接记录，保留在本地，不作为公共源码上传；公共操作说明以本文件为准。
- 新正式版本递增 `versionName` 与 `versionCode`，更新 README 和对应的 `docs/releases/<版本>.md`。
- 保留已发布标签及附件，不覆盖旧版 APK，不重写公开历史。
- 发布前检查实际差异、单测、Lint 与签名；发布后核对远端提交、标签、Release 状态，并下载 APK 比对 SHA-256。
- 用户设备安装、生产数据操作及额外发布行为需要明确授权。验证记录须区分源码、JVM、Chromium 和 Android 设备证据。
