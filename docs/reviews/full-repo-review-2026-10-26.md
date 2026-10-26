# 墨读 MiradReader 全仓审查报告（1.1.1 / versionCode 20）

- **日期**：2026-10-26
- **对象**：`main @ 458f9c9`（工作区干净，无未提交改动）
- **方式**：只读静态审查 + 实测回归；逐项复核 2026-10-25 审计（`full-repo-audit-2026-10-25.md`）的关闭状态
- **实测**：`:app:testDebugUnitTest --rerun-tasks` → **55 套件 / 409 用例 / 0 失败 / 0 错误 / 0 跳过**（2m12s）；lint 报告为 **0 error / 43 warning / 6 hint**
- **本次结论**：**P1×8、P2×9**；其中 4 项是上轮审计遗留未修，2 项为本次新发现的具体缺陷

---

## 一、总体判断

这是一份工程纪律明显高于同类个人项目的 Android 代码库：注释固化历史踩坑点（"为什么不能这么做"写在代码里）、事务边界意识强、验证体系分层（JVM 单测 + Chromium 回归 + 模拟器套件 + 签名核验脚本）、四条关键不变量至今未破（桥接别名模式 9/10 处、内容变化语义信号、`windowed()` 只认显式 `true`、`blockedByOverlay` 复位链）。

**但它已经进入"规模超过结构承载能力"的阶段**，三个信号同时出现：

| 信号 | 数据 |
|---|---|
| UI 层体量失控 | `ui/` 13,002 行，占主代码 **57%**；单函数 `ReaderContent` 约 770 行 |
| 资源口径分裂 | 导入走 80MB 流式，FTS 建索引 / BookMaintenance 仍是全量内存（同一本书两条内存曲线差一个数量级） |
| 状态机靠肉眼维护 | `ReaderSession`（1,156 行）同时管 WebView、自动阅读、进度、标注、搜索导航、TTS 协调 |

代码规模：主代码 **137 文件 / 22,496 行**；测试 **51 文件 / 7,783 行**（测试/主代码 ≈ 0.35，对一个以 WebView 为渲染层的项目算合理）；`reader.js` **2,783 行 / 105 个函数 / 对外 29 个方法**；桥接 **10 个 `@JavascriptInterface`**；预览源集另计 2,468 行。

---

## 二、P1：应当在下个版本前处理

### P1-1（安全，最高优先）EPUB 内的脚本仍可在阅读 WebView 中执行

三条链路叠加，缺一不可：

1. `core/html/ChapterHtmlBuilder.kt:106` `wrapBody()` 只是把原 XHTML 包一层 `<div>`，**不做任何消毒**；
2. `reader/ReaderResourceProvider.kt:175-183` EPUB 分支把 `readEpubHtml()` 的原始内容交给 `injectHead()` 返回，`response()`（同文件 317-320）**只设 `Cache-Control`，没有 CSP 头**；
3. `reader/ReaderSession.kt:298` `javaScriptEnabled = true`（`reader.js` 必需，不能关）。

于是"**首次整章加载**"（`loadUrl` 顶层导航）这条路径里，`<script>`、`<iframe>`、`on*` 属性都会以 `https://mirad.local` 同源身份执行，可调用 `MiradBridge` 的全部 10 个回调伪造进度/标注/章节，或 `fetch` 读取已放行的字体、背景、封面资源。原地换章（`innerHTML`）是安全的，只有首屏这条路径有问题——这也解释了为什么模拟器验收一直没暴露。

**修法（推荐 CSP，改动最小且覆盖未来新增路径）**：在 `response()` 加

```
Content-Security-Policy: default-src 'none'; img-src https://mirad.local data:; style-src 'unsafe-inline' https://mirad.local; font-src https://mirad.local; script-src https://mirad.local
```

CSP 会同时挡掉内联脚本与 `javascript:` URL，并且不改动正文 DOM。若担心个别书依赖内联 `<style>`，`style-src` 已保留 `'unsafe-inline'`。**不要只靠正则剥 `<script>`**——`<svg onload>`、`<math href="javascript:">` 都能绕过。

### P1-2 网络导入取消会把批次状态永久卡在"导入中"

`data/imports/ImportPipeline.kt:74-79`：

```kotlin
private suspend fun runBatch(...) = mutex.withLock {
    for (index in sources.indices) importOne(sources[index], items[index].id, force)
    batches.complete(batch)      // ← 无 try/finally
}
```

`importOne` 的收尾在 `catch (t: Throwable)`（162-177）里，第一句就是 `withContext(Dispatchers.IO) { ... }`。协程已取消时，`withContext` 会**立即**抛 `CancellationException`：清理不执行、`updateItem` 不执行、`batches.complete()` 永不调用。`ImportBatchQueue.enqueue()` 把 `running=true`，只有 `complete()` 会把它改回来，于是导入浮层永远停在"导入中"，未入库的临时文件与封面留在私有目录。

触发条件很具体：`NetworkBookDownloader.downloadAndImport()` 里用户取消下载（1.1.1 刚加的取消链）→ `importAndWait` 所在协程被取消 → 命中上述路径。

**修法**：`catch` 内清理段包 `withContext(NonCancellable + Dispatchers.IO)`；`runBatch` 改 `try { ... } finally { batches.abort(batch) }`，`abort` 把未终态条目置 `FAILED("已取消")` 再收敛 `running`。项目在 `BookMaintenance.kt:363`、`SettingsRepository.kt:127` 已经有这个纪律，此处是唯一漏网点。

### P1-3 TTS 播放列表仍经 Intent extras 传递，超长章节必然崩

`tts/TtsController.kt:40-42` 把整章句子文本、起始、结束三个数组塞进 Intent；`TtsService.readPlaylist()`（160-163）再取回。Binder 事务上限约 1MB，EPUB 单 spine 整书一章（大量网文转换的 EPUB 就是这种结构）时直接 `TransactionTooLargeException`，且 `play()` 本身没有 `runCatching`，崩溃点在 `startForegroundService`。

**修法**：播放列表改为进程内持有（`TtsBus` 挂一个 `@Volatile var playlist`，Intent 只带 `bookId/chapter/index` 作凭据），或写临时 JSON 传路径。**注意**：服务可能被系统在后台重启，纯内存方案要接受"重启后需重新点朗读"，这比崩溃好。

### P1-4 FTS 索引构建仍是全书三份驻留内存

`data/repo/SearchRepository.kt` 相比上轮确实改成了"逐章读取"，但：

- `180`：`ftsCache = HashMap<Int, String>(chapters.size)`，把**每章原文**攒到全部读完；
- `224-239`：再构造 `fullBatch`，每个实体同时持 `content` 与 `contentLower = normalizeCase(content)`（又是一份全量副本）；
- `236-237`：`deleteForBook` + `insertAll` **不在同一事务**。

峰值 ≈ 3× 全书字符量。80MB 上限的 UTF-8 中文书约 2.6 亿字节 → 约 1.3 亿字符 → 三份约 **780MB**，`android:largeHeap="true"` 也兜不住。这直接否定了导入侧 80MB 流式上限的意义。

**修法**：每批 8–16 章，单事务 `deleteForBook`（首批）+ `insertAll(分批)`；`contentLower` 生成后立即写入、不留在列表里。这样峰值降到"一批章节"级别。索引期间若章节读取失败，回滚已写批次（现在"全书读完才写"的原子性可以保留为：任一批失败即 `deleteForBook` 并放弃）。

### P1-5 BookMaintenance 三条路径仍是全量内存

`data/repo/BookMaintenance.kt`：

- `previewChapters:54-55` `readBytes()` → `toString(UTF_8)`
- `applyChapters:66-70` 同上，并在事务内再 `substring` 每章
- `redetect:163-165` `rawBytes()` → `decode` → `normalize`（`TextNormalizer` 还可能产生新副本）

大书上这是 3–5 份全文峰值；`redetect` 的注释自己写了"大书可达秒级"，但没提内存。**同一本书，导入走 `StreamingTxtProcessor` 流式，重新识别却全量**——口径不一致，用户感知就是"重新识别编码时闪退"。

**修法**：`redetect` 直接复用 `StreamingTxtProcessor.process()`（它已能产出 slices + 写内容文件），把 `ChapterSplitter` 的重复实现去掉；`previewChapters` 用 `RandomAccessFile` 只校验首尾块 + 抽样，不必整本读入。

### P1-6 自建返回栈不随进程恢复

`ui/AppRoot.kt:76` `val backStack = remember { mutableStateListOf<Screen>(Screen.Shelf) }`。后台被杀再回来落书架，正在读的书要手动重开（进度不丢）。上轮标 P1，未修。

**修法**：`Screen` 声明 `Saver`（`Reader(bookId)` → `"reader:$bookId"`），用 `rememberSaveable(saver = listSaver(...))`。注意 `SettingsDetail(route)` 里的 `SettingsRoute` 也要可序列化。

### P1-7 `zipReader()` 并发竞态与句柄泄漏

`reader/ReaderResourceProvider.kt:91-101`，`if (zip == null || zipPath != path) { releaseZip(); zip = open(...) }`，"检查—释放—打开—赋值"无同步；`intercept()` 由 Chromium 多线程并发调用。竞态下会 `close()` 掉别的线程正在用的 `ZipFile`（`ZipFile` 关闭后其 stream 读取抛异常 → 章节空白）或泄漏句柄。

**修法**：`@Synchronized private fun zipReader()`；`releaseZip()` 同样同步。或者更彻底：`ZipEntryReader` 内部持有独立 `RandomAccessFile` + 自建读锁。

### P1-8 `prepare()` 失败后 session 不释放，音量键被死会话接管

`ui/reader/ReaderScreen.kt:147-152`：

```kotlin
val created = ReaderSession(container, loaded, scope)   // init 里已注册 ReaderKeys.pager
runCatching { created.prepare(...) }.onFailure { error = it.message ?: "打开失败" }
book = loaded; session = created
```

失败后 `error != null` 分支直接 `return`，`DisposableEffect`（172-174）**不会注册**，于是：`resources` 未释放、`ReaderKeys.pager` 仍指向这个会话。用户回到书架后按音量键，`handleVolumeKey` 因 `ready=false` 返回 false 尚可，但只要再进任何一本书就会覆盖——真正的风险是下次 prepare 失败时旧 handler 与 zip 句柄叠加。

**修法**：`onFailure { created.release(); error = ... }`，并且不要把 `session` 赋值（或赋值后立即 `release` 并置空）。

---

## 三、P2：健壮性、性能与整洁度

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | `win.switching` 无 try/finally，任意异常后窗口滚动引擎永久卡死（整章滚动不再接管） | `reader.js:282-302`（`buildTextModel`/`applyLayout`/`reportState` 任一抛错） | 改成 `try { ... } finally { win.switching = false; }` |
| 2 | 搜索结果列表无 key，更新/滚动时行复用错位 | `ReaderSheets.kt:616` `items(results)` | `items(results, key = { "${it.chapterIndex}:${it.charOffset}" })` |
| 3 | EPUB 每章正文读取都新开一次 zip（搜索扫描 N 章 = N 次中心目录解析） | `ChapterTextProvider.kt:57-67`、`69-76` | 复用 `ReaderResourceProvider` 已缓存的 reader，或按 bookId 缓存 `ZipEntryReader` |
| 4 | XXE 防线用错 API，`disallow-doctype-decl` 从未生效 | `EpubParser.kt:287` `factory.setAttribute(...)` | 改 `factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)` |
| 5 | TTS 无音频焦点，朗读时与音乐/播客混播 | `TtsService` 全文件无 `requestAudioFocus` | `AudioFocusRequest` + `AUDIOFOCUS_LOSS` 时暂停 |
| 6 | TTS 状态字段跨线程读写无同步（`index`/`playing`/`texts`） | `TtsService.kt:59-95` 回调在 binder 线程，`onStartCommand` 在主线程 | 全部状态收敛到单线程（`HandlerThread`）或 `@Synchronized` |
| 7 | `WAKE_LOCK` 权限声明但从未申请锁（仅读 `PowerManager` 省电状态，不需要该权限） | `AndroidManifest.xml` vs `grep WakeLock` 无命中 | 删掉该权限 |
| 8 | `WebViewGlassBackdrop` / `BackdropCaptureController` 生产代码零引用，仅 preview/gpuPreview 使用 | 全仓 `grep` 只有预览源集与自身 | 移到 `src/preview`，连带那个单测 |
| 9 | proguard 全量 keep 整个 core 包，R8 对核心逻辑零优化 | `app/proguard-rules.pro:2` `-keep class com.mirad.reader.core.** { *; }` | core 是纯 Kotlin 直接调用，先删规则跑 R8，按实际报错逐类补 |

其他值得记录的小问题：

- `NetworkBookDownloader` 成功回调里传的 `File` 在 `delete()` 之后（`149`、`229`），是一次"指向已删文件的 Success"——目前只用来取名字，但语义上是坑。
- `isHtmlPage()`（277-282）依赖 `contentType.contains("html")`，某些服务器返回 `text/plain` 的 HTML 页面会被当文件下载，最终按 TXT 导入成乱码正文。
- `parseWebArticle()`（322-367）用正则处理不可信 HTML，遇到 `<p>` 嵌套或未闭合标签会丢正文；作为"轻量启发式"可接受，但失败时应明确提示"未能解析"，不要产出半截书。

---

## 四、专题：三个结构性问题

### 4.1 UI 层 13,002 行，单函数 770 行

`ReaderScreen.kt` 的 `ReaderContent`（194-967）一个 `@Composable` 里塞了：状态收集、外观绑定、`ReaderHardwareScene`、8 个弹层开关、TTS 全部协调逻辑、`ensureTtsSentences`、权限请求、点按分区、工具栏、页眉页脚。这类函数的问题是**任何改动都要重新理解 770 行上下文**，而且重组范围无法用肉眼推断。

同类：`ShelfScreen.kt` 1,290 行、`ReaderSession.kt` 1,156 行、`GlassPolicy.kt` 1,081 行。

**建议**（按投入产出排序）：
1. 把 TTS 协调（`ensureTtsSentences`/`startTts`/`ttsSentences`/`ttsChapterIndex`）整体提成 `rememberTtsCoordinator(book, session)`，ReaderScreen 只留一个对象——约减 150 行，且这块本来就有 F06 竞态史。
2. 弹层开关 8 个 `mutableStateOf` 收成一个 `ReaderOverlayState` data class + 一个 `show(overlay)` 函数，配合已有的 `modalVisible` 派生。
3. `ReaderSession` 拆成"WebView 生命周期 / 进度持久化 / 标注渲染 / 自动阅读编排"四个协作对象，`state` 由一个 `ReaderSessionState` 聚合，避免外部 20+ 处 `_state.update`。

### 4.2 内存口径必须统一

现在同一本书有两条曲线：

| 路径 | 策略 | 80MB 书峰值 |
|---|---|---|
| 导入 | `StreamingTxtProcessor` 流式 | ~几十 MB |
| 全文搜索建索引 | 全书 3 份驻留 | ~780MB |
| 重新识别编码 | 全量 3–5 份 | ~500MB+ |
| 重新分章预览/应用 | 全量 2–3 份 | ~300MB |

`largeHeap="true"` 是在给这个问题打补丁，而不是解决它——它只是把 OOM 阈值抬高，代价是所有进程优先级都变差。**建议把"流式优先"写成一条不变量**（AGENTS.md 里加一条），并在 CI/单测里加一个"10 万章 × 大文本"的合成内存回归（用 `Runtime.totalMemory-freeMemory` 断言，或至少在测试里断言不构造全量 `List<String>`）。

### 4.3 玻璃材质 2,664 行，是最大的隐性复杂度

`GlassPolicy.kt`（1,081，含对比度方程、alpha 求解、保护场）+ `GlassSurface.kt`（974，含 `GlassBackdropState`）+ `LiquidGlassMaterial.kt`（239）+ `HardwareGlassBackdrop.kt`（371）+ 死代码 238。它在解决一个真实难题（自采样递归、对比度下限），但**算法与 Compose 组件混在同一批文件里**，`internal` 函数 20+ 个全靠注释维系。

**建议**：把纯数学部分（`linearSrgb`/`contrastRatio`/`solveRequiredAlpha*`/`buildContinuousProtectionField`）抽到 `core/glass/`（无 Android 依赖，可像 `ProgressMath` 一样直接单测），组件层只留采样与合成。这一步不影响任何行为，但能让未来改玻璃效果的人不必读完 2,000 行。

---

## 五、工程化：最容易被忽视的短板

1. **没有 CI**。`.github/workflows` 不存在。项目有 409 单测 + 44/75 项浏览器回归 + 发版密钥纪律，全靠本地手动执行。"发版必须同密钥、必须过验证"这种纪律，应当由流水线保证而不是由记忆保证。最小可行：`push/PR → testDebugUnitTest + lintDebug + assembleDebug + npm --prefix tools/browser-checks test`（Playwright 在 GitHub Runner 上需 `npx playwright install --with-deps chromium`）。

2. **依赖漂移已经很明显**（lint `GradleDependency` 10 条，`NewerVersionAvailable` 4 条）：

| 依赖 | 当前 | 最新 | 风险 |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.10.1 | 1.19.1 | 与 Compose BOM 2026.02 同期严重错配 |
| `lifecycle-runtime-ktx` | 2.6.1 | 2.11.0 | `LocalLifecycleOwner`/`collectAsStateWithLifecycle` 行为差异 |
| `activity-compose` | 1.8.0 | 1.13.0 | 预测性返回、`BackHandler` 语义变化 |
| `compose-bom` | 2026.02.01 | 2026.09.00 | 与上面三项必须同批升 |
| `webkit` | 1.14.0 | 1.18.0 | WebView 兼容性（项目重度依赖） |
| AGP / Kotlin | 9.2.0 / 2.2.10 | 9.4.1 / 2.4.21 | 升级需单独一轮回归 |

**建议**：分两批。第一批（低风险、收益高）：`core-ktx` + `lifecycle` + `activity-compose` + `compose-bom` 同批升到同一时间线，跑完整回归；第二批：AGP/Kotlin/Compose 编译器，单独发一个 `1.1.2` 只做升级、不改功能，便于二分定位。

3. **lint 43 warning 全是"可清零"类型**：`DefaultLocale` 6（`String.format` 缺 `Locale`，在土耳其语等区域会显示错位数字）、`UseKtx` 9、`AutoboxingStateCreation` 6（`mutableIntStateOf`）、`ObsoleteSdkInt` 3（minSdk 已是 26，`Build.VERSION` 判断是死分支）、`ModifierParameter` 3、`ConfigurationScreenWidthHeight` 3。这些一次清完大约半天，之后可以把 lint 设成 `warningsAsErrors`（或至少 `abortOnError` + baseline 只留依赖类），防止回涨。

4. **零 instrumentation 测试**。`app/src/` 只有 `main/test/preview/gpuPreview`，没有 `androidTest`。当前所有 UI 行为验证靠模拟器 + 人工 + 一批"policy 纯函数"测试（`ShelfEntryTest`、`ReaderOverlayGeometryTest` 等）。这不是错，但意味着**重组错误、无障碍缺失、返回栈行为**这三类问题没有自动化防线——恰好也是当前 P2 最集中的地方。最小投入：加 5–8 个 Compose UI 测试（书架多选、阅读器工具栏显示/关闭、导入进度浮层、返回栈）。

5. **文档与代码已漂移**：`agent.md` 写"versionCode=12 / 1.0.11 / 203 项测试"，`AGENTS.md` 写"约 342 项"，实际是 20 / 1.1.1 / 409 项。建议 AGENTS.md **不再写具体数字**（写"见最新测试输出"），否则每轮都要改且容易误判进度。

6. `AndroidManifest.xml` 的 `usesCleartextTraffic="true"`：用户直链导入确实需要 http，但可以改 `networkSecurityConfig` + `cleartextTrafficPermitted="true"`（保留行为）并在代码层对非 http(s) scheme 做白名单，比全局放开更可控。`largeHeap="true"` 见 4.2。

---

## 六、建议排期

| 批次 | 内容 | 依据 |
|---|---|---|
| **1.1.2（安全+稳定性，尽快）** | P1-1 CSP、P1-2 取消收敛、P1-3 TTS 播放列表、P1-7 同步、P1-8 prepare 失败释放 | 全部是"用户可感知的错误行为"或安全面，改动都在单文件内 |
| **1.2.0（内存与体验）** | P1-4 FTS 分批、P1-5 redetect 流式、P1-6 返回栈 saveable、P2-2 列表 key、P2-3 zip 复用 | 需要改动数据路径，必须配大书回归 |
| **随做随清** | P2-1/4/5/6/7/8/9、lint 清零、CI、依赖第一批 | 单点小改，适合夹在功能之间 |
| **单独一轮** | UI 拆分（4.1）、玻璃数学下沉（4.3）、AGP/Kotlin 升级 | 不改变行为，但 diff 大，不要和功能混在一起 |

---

## 七、值得保留的做法

审查中也确认了一批"别改坏"的东西，建议写进 AGENTS.md：

- 桥接 `val callback = this.onXxx` 别名模式（10 个回调中 9 处一致，剩 1 处建议补齐）；
- `SettingsRepository` 的代次 + 双锁 + 400ms 防抖 + `NonCancellable`，是这份代码里最成熟的一块；
- `BookMaintenance.ReindexCoordinator` 的"提交索引 → 原子换正文 → 失败恢复旧索引"三段式，以及 `ContentReplacer` 的两级回退，是全仓最有价值的可靠性设计；
- `ImportPipeline` 的"拷贝前预检大小"、"指纹才算重复"、"入库三步同事务"——注释里写清了每个决定的反面案例；
- 唯一一处需要补的是 `importOne` 的取消路径（P1-2），补上后导入链就完整了。

---

## 附：本次验证记录

```
./gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
BUILD SUCCESSFUL in 2m 12s
55 套件 / 409 用例 / 0 失败 / 0 错误 / 0 跳过

lint-results-debug.xml: 0 error / 43 warning / 6 hint
GradleDependency 10 · UseKtx 9 · DefaultLocale 6 · AutoboxingStateCreation 6
NewerVersionAvailable 4 · ObsoleteSdkInt 3 · ModifierParameter 3 · ConfigurationScreenWidthHeight 3
```

浏览器回归（`tools/browser-checks`：auto-read / font-browser / modern-upgrades / anchor-regressions）本次未执行，结论沿用 1.1.1 发布记录。
