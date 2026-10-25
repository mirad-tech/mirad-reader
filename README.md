# 墨读 · MiradReader

Android 本地优先的 TXT / EPUB 阅读器，使用 Kotlin、Jetpack Compose、Room 和 WebView。

**当前构建版本：1.1.1（versionCode 20）**

[下载已发布版本](https://github.com/mirad-tech/mirad-reader/releases/latest) · [本次更新](docs/releases/1.1.1.md) · [开发与验证](docs/DEVELOPMENT.md) · [文档目录](docs/README.md)

## 安装

[下载墨读 1.1.1 正式 APK](https://github.com/mirad-tech/mirad-reader/releases/download/v1.1.1/mirad-reader-1.1.1-release.apk) · [SHA-256 校验文件](https://github.com/mirad-tech/mirad-reader/releases/download/v1.1.1/SHA256SUMS.txt) · [Release 页面](https://github.com/mirad-tech/mirad-reader/releases/tag/v1.1.1)。本版本包含本轮审查补修及下载 20 秒超时修复；验证范围和保留的待办见[本次更新](docs/releases/1.1.1.md)。

本机安装包与校验文件统一放在 `artifacts/apk/`，按构建类型、版本和批次归档，本机索引为 `artifacts/apk/README.md`。该目录不入 Git；新克隆的仓库需先下载或构建安装包，归档约定见[开发文档](docs/DEVELOPMENT.md#仓库与发布约定)。

支持 Android 8.0 及以上。正式版本沿用同一发行证书，可覆盖同签名的旧正式版；无需先卸载旧版。

如需核验文件完整性，请对照同一 Release 附带的 `SHA256SUMS.txt`。Debug 包与正式包可能使用不同签名，不能据此假定它们可以互相覆盖安装。

## 功能

- **导入与书架**：TXT / EPUB 文件、多文件及目录导入，接收其他应用分享，输入 HTTP/HTTPS 文件直链或网页长文；支持封面、搜索、排序、分组、置顶、隐藏和批量操作，网格/列表布局会被记住。
- **阅读**：左右分页、上下滚动、目录跳转、进度记忆、可关闭的音量键翻页、自动阅读，以及字体、字号、行距、字间距、背景和主题设置。
- **记录与检索**：书签、划线、笔记、Markdown 导出、全书搜索和阅读统计。
- **朗读**：使用系统 TTS 引擎，提供语速、音调、句级高亮和通知栏控制。
- **统一外观**：全应用共享跟随系统、日间、夜间模式；日夜分别记忆阅读配色、背景图片和图片不透明度，阅读弹层与当前模式一致。
- **玻璃外观**：全局设置与阅读设置共享 0–100% 玻璃透明度，默认 60%；高透明度复杂背景下仍有文字对比度限制，详见[本次更新](docs/releases/1.0.17.md)。

### 阅读设置的作用范围

从 1.0.12 起，阅读设置全书通用：在阅读器中调整的字体、主题等设置会用于其他书籍，已有单书覆盖不再优先生效。阅读进度、书签和笔记仍分别属于各本书。恢复默认时可分范围勾选（排版与交互、日间配色、夜间配色）。

### 数据与联网

无需注册或登录。书籍、阅读进度、书签、笔记和统计保存在设备的应用私有目录与本地数据库，卸载应用会清除这些数据。应用关闭了系统备份及设备迁移。

输入网址导入时会访问对应网站；系统 TTS 引擎也可能使用在线语音服务。本地阅读不要求在线书城或云账号。应用目前不提供云同步、WebDAV、PDF、MOBI 或 AZW3 支持。

## 开发

- Android：minSdk 26、targetSdk 36、compileSdk 36.1。
- Kotlin 2.2.10、Compose Material3、Room；版本以 `gradle/libs.versions.toml` 为准。
- Gradle Wrapper 9.4.1；项目的 Daemon JVM 配置指定 Java 21。

配置 Android SDK 后，在 Windows PowerShell 中运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

macOS / Linux 使用 `./gradlew`。Release 构建与签名、浏览器验证和覆盖范围详见[开发文档](docs/DEVELOPMENT.md)。

## 仓库结构

```text
app/src/main/java/com/mirad/reader/
  core/       编码、分章、EPUB、定位模型与阅读样式
  data/       Room、导入管线与数据仓库
  reader/     WebView 桥接、资源加载和阅读会话
  tts/        朗读控制与服务
  ui/         Compose 页面、公共组件与主题
app/src/main/assets/reader/   WebView 阅读引擎
app/src/test/                JVM 单元测试
app/schemas/                 Room schema 历史
tools/                       素材工具、浏览器检查与打包脚本
docs/plans/                  设计与修复计划
docs/reviews/                审查记录
docs/reports/                实施与验证报告
docs/releases/               版本说明
artifacts/apk/               本机安装包与配套校验文件（不入 Git）
```

`tools/` 包含背景、字体、样书生成工具，以及可独立安装依赖的 `browser-checks/`。测试输出统一放入被忽略的 `build/verification/`；本机临时验证数据放在被忽略的 `verification/`。

`app/build/outputs/` 保留 Gradle 自动生成的当前构建产物；与截图、日志绑定的验证 APK 保留在原验证目录。长期保留的发布包和交付副本归档到 `artifacts/apk/`，不再散放仓库根目录或 `build/release/`。

## 验证边界

JVM 单测和 Chromium 检查不能替代 Android 真机验收。TTS 音频质量、系统选词工具条、WebView 版本差异及特定机型的翻页表现仍需设备验证。内置字体为子集，未包含的生僻字由系统字体回退。

历史方案和交付报告保留在 `docs/` 供追溯，其中的旧版本测试数量、性能描述及截图路径不作为当前版本的验证结论。
