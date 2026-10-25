# 全仓库审计报告（1.1.0 @ cfa8424）

- **日期**：2026-10-25，1.1.0 发布后对 main 全量审计
- **方式**：五路并行独立只读审查（core / data / reader+JS+TTS / UI / 外围与构建），只查不改
- **总计**：**P0×1、P1×10、P2×约 30**；无安全问题达到"必须立即发补丁"级别，但有 1 个用户可见的错误行为与一批健壮性/安全收紧项

## P0（用户可感知的错误行为）

### 1. 网络导入失败仍上报"下载成功"
`data/imports/NetworkBookDownloader.kt:107-110、159-162`——HTML 与文件两个分支都丢弃 `importAndWait` 返回的 `ImportSummary`，EPUB 损坏/解析失败时导入器内部标 FAILED，下载器却无条件置 `Success` 并弹"正在将《X》加入书架"，书架里永远没有这本书。
**修法**：检查 `summary.success == 0` 时置 `Error`、清理缓存文件。

## P1（下版本前修）

### 2. 恶意 EPUB 脚本可在阅读 WebView 内执行（三路审查独立确认，最高置信度）
`core/html/ChapterHtmlBuilder.kt:106`（wrapBody 不消毒）+ `ReaderResourceProvider.kt:175-183`（EPUB 整章直接返回）+ `core/html/HtmlUrlRewriter.kt:13`（`javascript:` 进保留前缀）+ `EpubParser.kt:275`（`.js` 资源供 mime）。原地换章走 innerHTML 无脚本执行，但**首次整章加载**（loadUrl 顶层导航）路径保留 `<script>/<iframe>/on*`，以 `mirad.local` 同源身份运行，可伪造 `MiradBridge` 全部回调（污染进度/标注）并 fetch 读字体/背景。现有补偿控制强（无网络外传、桥接全良性回调），实际影响限会话内篡改与 DoS。
**修法**：渲染前统一消毒（剥 script/iframe/on*/javascript:），整章加载与原地换章两条路径策略一致。

### 3. XXE 防线的 setAttribute 用错 API，从未生效
`core/epub/EpubParser.kt:287`——`disallow-doctype-decl` 是 SAX feature，`setAttribute` 会抛异常且被 runCatching 吞掉；现靠空 EntityResolver + expandEntityReferences=false 兜底。
**修法**：改 `factory.setFeature(..., true)`。

### 4. 导入协程取消：孤儿文件 + 批次状态永久卡死 + 违反 NonCancellable 纪律
`data/imports/ImportPipeline.kt:158-173`——catch 块的清理在已取消协程中再抛 CancellationException，文件/DB 补偿不执行、批次永驻"导入中"。
**修法**：清理段包 `NonCancellable`，runBatch 加 try/finally 收敛批次状态。

### 5. 直链下载无大小上限
`NetworkBookDownloader.kt:118-134`——80MB 限制在写满 cacheDir 之后才生效。
**修法**：下载循环内限长即删。

### 6. FTS 索引构建全书双份驻留内存，大书必 OOM
`data/repo/SearchRepository.kt:180、216-230`——全书章节缓存 + lowercase 副本 ≈ 320MB 峰值，与导入路径的流式口径不一致。
**修法**：逐章分批 insert，失败回滚。

### 7. BookMaintenance 三个入口全量内存处理（峰值约 560MB）
`data/repo/BookMaintenance.kt:54-56、66-92、163-172`——redetect 明明有流式 StreamingTxtProcessor 可用。
**修法**：redetect 改流式；apply/preview 消除双份全文复制。

### 8. ReaderResourceProvider.zipReader 并发竞态，ZipFile 句柄泄漏
`reader/ReaderResourceProvider.kt:91-101`——Chromium 多线程并发拦截时"检查-打开-赋值"无同步。
**修法**：`@Synchronized`。

### 9. TTS 整章句子经 Intent extras 传递，超大章节 TransactionTooLargeException 崩溃
`tts/TtsController.kt:28-43`——EPUB 单 spine 整书一章时必崩；`play()` 无 runCatching。
**修法**：播放列表不走 Intent（内存单例/文件/分批）。

### 10. TTS 暂停后应用内朗读入口全部失效
`ui/reader/ReaderScreen.kt:701-707、729` + `tts/TtsService.kt:119-125`——按钮判 `isSpeaking`（active），ACTION_PAUSE 只清 playing 不清 active → 暂停后按钮恒"暂停"、TtsSheet 打不开、resume 无入口，只能靠通知栏。
**修法**：入口判断改 `playing`。

### 11. 自建返回栈不随进程恢复
`ui/AppRoot.kt:76`——普通 remember；后台被杀回应用落书架，正在读的书需手动重开（进度无损）。
**修法**：backStack 序列化为 saveable。

## P2 概览（约 30 项，按主题归组）

**安全收紧**：全局 `usesCleartextTraffic=true` 且重定向允许 https→http 降级（改 networkSecurityConfig）；EPUB 资源流无解压上限（zip 炸弹残余）；TOC 递归解析无深度上限；preview/gpuPreview 验证面全设备 exported（改 NOT_EXPORTED 后 shell 仍可达）；proguard 全量 keep core 包削弱 minify；内置 OFL 字体未附许可文本。
**健壮性**：reader.js `win.switching` 无 try/finally（异常后窗口引擎永久卡死）；renderHighlights 跨章竞态；onChapterRendered 重试回调不随 release 取消（destroyed WebView 上 evaluate）；TTS 错误后状态失真/无线程同步/无音频焦点；下载不可中断、失败缓存残留；FTS 删+插非同事务；防抖 DB 写未包 NonCancellable（global/shelf 与 appearance 口径不一致）；`ImportSources.fromUris` 主线程 Binder IPC；UTF-8 严格校验只看前 64KB；字符偏移 Int 溢出无防护；ProgressMath 负章长度抛异常；ReaderConfig 枚举收敛不对称。
**UI/性能**：prepare 失败的 session 不 release；搜索结果 LazyColumn 无 key；ChoiceChip/SegmentedRow 缺 selected 语义、ColorDotRow 无标签（无障碍）；玻璃背景分析在主线程与注释相悖；WebViewGlassBackdrop+BackdropCaptureController 为死代码；UrlImportDialog 下载中无法取消被困；书架多选按返回直接退出应用；笔记页全量加载所有书所有章节。

## 各维度"无问题"结论（摘）

- 四条关键不变量（桥接别名模式、语义内容信号、windowed 显式 true、blockedByOverlay 复位链）全部保持
- WebView 配置（file/content 禁用、无网络外传、导航全拒）、权限最小化、备份排除、签名零泄漏、`git ls-files` 卫生、docs 与代码一致性——全部通过
- 导入幂等/事务边界/删除链方向、指纹方案、Compose 读改写纪律、GPU 释放对偶与 API 26-28 fallback、文案合规——通过

## 总体评价

五路审查一致认为该仓库工程纪律高于同类平均水平：注释固化历史坑位、事务边界意识强、验证体系（378 单测 + 44 浏览器回归 + 模拟器套件 + 发布核验脚本）完整。短板集中在三类：**不可信 EPUB 内容的消毒**（三路独立确认的脚本执行面是下版本最优先项）、**资源口径不一致**（导入 80MB 流式上限被 FTS/BookMaintenance 全量内存路径打破，大书确定性 OOM）、**错误回传链断裂**（网络导入假成功、TTS 暂停死锁、导入取消卡死——用户可见的状态错乱）。

## 建议的修复排期

- **1.1.1（小版本，尽快）**：P0-1 + P1 2/3/8/9/10（数据真实性与稳定性）+ P1 4/5（导入链）
- **1.2.0**：P1 6/7/11（内存与导航体验）+ P2 中的安全收紧组
- **随做随清**：P2 健壮性与 UI 组
