# 墨读 · MiradReader

一个**完全离线**的安卓小说阅读器：导入 `txt` / `epub`，书架管理，阅读器内可调字体、背景、排版与翻页方式，并提供目录、进度记忆、书签、划线笔记、全文搜索、阅读统计与 TTS 朗读。

- 包名 / applicationId：`com.mirad.reader`
- minSdk 26（Android 8.0） · targetSdk 36 · compileSdk 36.1
- Kotlin 2.2.10 · Jetpack Compose(Material3) · Room · AGP 9.2.0 · Gradle 9.4.1

## 功能

### 导入
- 支持 `txt` 与 `epub`，可一次多选；也可整目录导入（SAF 目录选择器）。
- 可接收其它 App 分享/打开的 `text/plain`、`application/epub+zip` 文件。
- TXT 编码自动识别：BOM → juniversalchardet → 严格 UTF-8 校验 → GB18030 / Big5 / Shift_JIS 回退；中文 GBK 小说不会乱码。
- TXT 分章：内置中文章节规则（第X章/节/回/卷、带括号、数字编号、Chapter N、序章楔子等）自动择优；无标记时按字数兜底分节。
- EPUB：解析 `container.xml → OPF(metadata/manifest/spine) → 目录(EPUB3 nav 或 EPUB2 NCX)`，读取封面，**不重写原书 XHTML/CSS**。
- 去重（同名同大小跳过并说明原因）、导入进度、单文件失败不影响其它文件。
- 封面：EPUB 使用内嵌封面；TXT 自动生成标题占位封面。

### 阅读器
- **阅读方式**：左右分页 / 上下滚动；翻页动画：无动画 / 滑动 / 覆盖。
- **排版**：字号、行距、段间距、首行缩进、页边距、字体（系统/宋体/黑体/等宽）、对齐（两端/左/居中）、正文加粗。
- **背景与主题**：白、纸色、米黄、护眼、夜间、纯黑、跟随系统、自定义背景色；支持自定义背景图片与透明度。
- **EPUB 排版模式**：统一排版（强制字号行距，保留图片/加粗/层级）或保留原书样式；可开关插图显示。
- **屏幕**：亮度调节（或跟随系统）、屏幕常亮、全屏沉浸、屏幕方向。
- **页眉页脚**：章节名、时间、电量、页码、进度百分比，均可单独开关。
- **交互**：点击区域（左中右三分 / 左右两分 / 仅呼出菜单）、音量键翻页、长按选词。
- 目录（卷/章层级，当前章高亮）、进度条拖动跳转、进度自动记忆（精确到章内字符偏移，换字号/换模式后仍能回到原处）。
- 书签、划线、笔记（长按选中文字后使用原生工具条上的「划线 / 笔记 / 搜索」）。
- 全文搜索：全书逐章扫描，结果含章节名与片段高亮，点击跳转并就近精确定位。
- 阅读统计：今日/本周/本月/累计时长、连续阅读天数、今日字数、最近 30 天柱状图。
- TTS 朗读：系统语音，句级高亮、自动下一章、语速音调可调，前台服务 + 通知栏控制（上一句/暂停/下一句/停止）。
- 每本书独立阅读配置，可一键恢复全局默认。

### 书架
- 网格/列表切换，封面、作者、文件大小、阅读进度。
- 排序：最近阅读 / 添加时间 / 书名 / 作者 / 文件大小。
- 分组（新建/重命名/删除，移入移出）、置顶、隐藏、多选批量操作、编辑书名作者、删除（连同文件与阅读数据）。

## 构建与安装

前置条件：JDK 17+ 与 Android SDK（`platforms;android-36` + `build-tools;36.0.0`）。
在 `local.properties` 中配置 SDK 路径：

```properties
sdk.dir=C\:\\Android\\Sdk
```

```powershell
.\gradlew.bat :app:testDebugUnitTest          # 单元测试
.\gradlew.bat :app:assembleDebug              # 生成 Debug APK
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
```

## 架构

```
app/src/main/java/com/mirad/reader/
├─ core/            纯 Kotlin，无 Android 依赖，可被 JVM 单元测试直接覆盖
│  ├─ text/         编码检测、正文归一化、章节规则与分章器（字符/字节偏移一次遍历得出）
│  ├─ epub/         zip 条目读取、EPUB/OPF/NCX/nav 解析、路径规范化
│  ├─ html/         HTML 转义/反转义、纯文本抽取、章节 HTML 生成与 body 包裹
│  ├─ model/        ReaderConfig、主题配色、定位与进度换算、目录树、统计聚合
│  └─ reader/       配置 → CSS 映射
├─ data/            Room 实体与 DAO、仓库、导入管线、封面生成
├─ reader/          WebView 阅读引擎：资源拦截、JS 桥、阅读会话（进度/标注/统计）
├─ tts/             句子切分、TTS 前台服务与状态总线
└─ ui/              Compose 界面：书架、阅读器、设置、笔记、搜索结果、统计
```

正文渲染统一走 WebView，章节与图片都由 `shouldInterceptRequest` 从内存返回，**App 不发起任何网络请求**：

```
https://mirad.local/reader/…               内置 reader.css / reader.js
https://mirad.local/book/<id>/chapter/<i>  TXT 生成的 HTML 或注入后的 EPUB XHTML
https://mirad.local/book/<id>/asset/<p>    EPUB zip 内图片 / CSS / 字体（用 <base> 解析相对路径）
```

- 分页用 CSS 多列：`pageCount = ceil(scrollWidth / (视口宽 + 列间距))`，翻页即动画化 `scrollLeft`。
- 文本模型（reader.js）把 DOM 抽成稳定的字符流，进度、划线、朗读、搜索共用同一套偏移。
- 阅读中修改字号/主题/背景时，只替换 `<style id="mirad-theme">` 的内容并重排，**不重新加载章节**。

## 测试与验证

- 单元测试 65 个，全部通过：`.\gradlew.bat :app:testDebugUnitTest`
  - 编码检测（UTF-8/BOM/UTF-16/GBK/ASCII/手工指定）
  - 章节规则与分章器（中文章节、带括号、分卷、字数兜底、字符↔字节偏移一致性、空文本）
  - EPUB 解析（EPUB2+NCX、EPUB3 nav、缺失 container、路径规范化、无目录时用标题兜底）
  - HTML 工具、章节 HTML 生成与 body 包裹
  - 阅读配置 JSON 往返与未知字段容错、主题配色与自动夜间、进度换算
  - 搜索匹配与片段高亮下标、阅读统计聚合与连续天数
  - 朗读句子切分与偏移对应
- 模拟器回归（Pixel 10 Pro Fold / android-37.0）已完成并通过的界面：导入（含去重提示）、书架进度、GBK 中文 TXT 阅读、目录与跨章跳转、EPUB 原书排版与插图、阅读设置面板、夜间主题实时切换、杀进程后进度精确恢复（第四章 59%）、滚动/分页切换、全文搜索、阅读统计。
- 回归样书由 `tools/make_samples.py` 生成（GBK TXT / 无章节 UTF-8 TXT / 含插图的 EPUB3），截图见 `verification/shots/`。

### 尚未验证或未实现

- **TTS 音频**：模拟器语音引擎可能无中文语音数据，只验证了服务启动与状态流转；音质与真机表现需在手机上确认。
- **划线/笔记的原生选词流程**：代码与数据链路已完成，但长按选词 + 工具条操作依赖触摸手势，未在模拟器上逐项点验。
- 覆盖翻页动画在部分机型（WebView 无法把内容画进位图）会自动退化为普通翻页。
- 明确不做：在线书源/书城、WebDAV/局域网传书、云同步、PDF/MOBI/AZW3、竖排/RTL、多语言 UI、桌面小部件、release 签名打包。

## 数据与隐私

书籍、封面、进度、书签、笔记、统计全部保存在应用私有目录（`filesDir`）与本地 SQLite，卸载即清除；除系统 TTS 引擎外不访问网络。
