# 审查问题修复完成报告

日期：2026-10-25（首轮）/ 2026-10-25（复核补修，第二轮）
状态：三项问题已修复并完成验证；其中两项数据安全问题按复核意见完成第二轮补修与生产协调路径回归。改动保留在工作区，未提交、未推送、未签名发布。
**执行分支**：首轮在 `Zcode/glm5.3`；第二轮复核补修按方案文档要求在 `feature/settings-refactor` 上执行（未切换分支）。
对应修复方案：[glm5.3-review-fix-plan-2026-10-25.md](../plans/glm5.3-review-fix-plan-2026-10-25.md)（含复核补充）
比较基线：`e5d007deff899eafb54825e6a6f07fcbd60f1a4a`（与 origin/main 一致）。

> **第二轮勘误**：首轮报告中"替换步骤移出清理 try 块、发布失败保留 staging"的描述与当时代码不符——`ContentReplacer.publish` 实际位于 try 块内，发布失败会被外层 catch 删除 staging，且数据库已提交后替换失败会留下"新索引配旧正文"的混合状态。复核属实，已在第二轮补修（见下文"复核补修"）。

---

## 一、修复内容

### P1 重新识别编码的临时文件与发布安全

**位置**：`app/src/main/java/com/mirad/reader/data/repo/BookMaintenance.kt`（`redetect` + 文件末尾新增 `ContentReplacer`）

问题：共用固定 `content.utf8.txt.tmp` 的两次重试任务可以重叠——被取消的旧任务（取消延迟到秒级同步分章计算之后的挂起点才生效）的异常清理会删掉新任务的临时文件；新任务提交数据库后改名失败，又先删除唯一有效正文，最终造成"新章节索引 + 正文缺失"。

修复：

- **独占临时文件**：`ContentReplacer.stagingFile()` 生成 `content.utf8.txt.<8位随机>.tmp`，每次操作独占；被取消任务的清理只删自己的输出。
- **同书互斥**：`redetectLocks`（per-book `ConcurrentHashMap<Long, Mutex>`）串行同一本书的"发布、失败恢复、清理"全程，不同书互不阻塞。
- **发布前取消检查**：分章计算后、进事务前各一次 `ensureActive()`；取消异常保持取消语义原样重抛，已取消的任务不提交结果。
- **可恢复的提交顺序**：`ContentReplacer.publish()` 采用"直改 → 备份-替换-回滚"两级策略（`target`→`.bak`、`staging`→`target`、失败则 `.bak`→`target` 恢复）；任何一步失败原正文都在 target 或 .bak 中可读，staging（新正文唯一副本）永不删除；替换步骤移出清理 try 块，原先"二次替换前先 `target.delete()`"的路径已消除。

针对性回归：新增 `ContentReplacerTest`（7 项）——独占文件名、直改成功、注入 rename 失败序列下的备份替换/原正文恢复/全失败保全、目标不存在场景，以及"任务 A 取消清理不影响重试任务 B"的文件层交错复现。

> 注：本节"可恢复的提交顺序"中对 try 块位置与 staging 保留的描述经复核与当时代码不符，数据库一致性在第二轮补修中闭环，以"复核补修（第二轮）"一节为准。

### P2 背景图片必须在配置成功落盘后清理

**位置**：`app/src/main/java/com/mirad/reader/data/repo/SettingsRepository.kt`、`app/src/main/java/com/mirad/reader/AppContainer.kt`、`app/src/main/java/com/mirad/reader/ui/common/AppAppearanceState.kt`

问题：清理挂在 `updateReadingConfig` 的 `onApplied` 回调上——该回调只证明内存配置更新，`commitReadingConfig()` 是异步保存；写失败或进程在落盘前退出时，数据库仍引用已被删除的旧图片，重启后原背景无法恢复。

修复：

- `SettingsRepository` 新增 `lastPersistedAppearance`（加载值即初始基准，仅在外观写锁内读写）与构造回调 `onBackgroundImageDropped`：仅在 `persistAppearanceSnapshotLocked` **成功落库后**，按"前一次持久化值 → 本次持久化值"的引用差集触发；内置素材（`builtin:`）排除；回调异常不影响保存。
- 内存更新（`onApplied`/防抖排队）与保存失败都不触发清理；不新增任何直写偏好通路，完整沿用现有编辑/代次/互斥/防抖/NonCancellable 链（符合 AGENTS.md 外观事实源纪律）。
- `AppContainer` 注入清理实现：应用级协程里只删**背景目录内**的文件；删除失败按方案要求容忍（保留未引用文件，不回滚外观）。
- 撤销了此前挂在内存回调上的过早清理。

针对性回归：`SettingsRepositoryTest` 新增 5 项——防抖未到期不清理、落库成功才清理、两组共享 URI 永不清理、保存失败保留旧图直到重试成功、A→B→C 快速替换逐次差集、内置素材不进清理列表。

### P2 外观回归脚本匹配当前导航

**位置**：`tools/glass-preview/Test-AppAppearance.ps1`、`tools/glass-preview/Test-ImmersionSettings.ps1`

问题：脚本进入 THEME 页后立即要求"跟随系统"控件并点击"夜间/日间"，而模式控件已收敛到 APPEARANCE 页、THEME 页只显示"日间样式/夜间样式"分区，普通运行与 `-StartupOnly` 均在首次捕获失败。

修复（Test-AppAppearance，统一外观模式 `scene=appearance`）：

- `Capture` 的内置"跟随系统"断言参数化为按页面传入期望控件。
- THEME 页流程：「日间分区选纸色 → 夜间样式分区选纯黑 → Dump 断言**编辑分区只写夜间组且不切换应用模式**」；模式切换（Tap 夜间/日间/跟随系统）全部导航到 APPEARANCE 页执行；`config` 命令保持在正确模式生效状态下写入目标组。
- 阅读器段经面板内导航到 APPEARANCE 子页切模式（reader-settings 场景不重建正文，"不重载/位置保持"断言保留）。
- 窄屏循环改为 THEME 断言「日间样式/夜间样式」+ APPEARANCE 断言「跟随系统/日间/夜间」两页各自边界校验。
- 迁移、两组记忆、alpha 记忆、reopen-store 等断言全部保留。

修复（Test-ImmersionSettings，legacy 模式 `scene=settings`）：

- theme/layout 套件运行在 legacy 外观模式（无 appearance 状态、APPEARANCE 页不渲染模式控件），`config.theme -eq 'SYSTEM'` 断言在该模式下本就成立——保留原语义，模式控件相关步骤按 legacy 现实调整。
- 既有失配一并修正：`Tap '自定义'` → `Tap '自定义浅色'`（当前 ChoiceRow 文案）；自定义演示后用 `Command 'config' --es theme SYSTEM` 恢复 SYSTEM 主题（等价旧 UI 的"跟随系统"步骤，供 system light/dark 段使用）。
- layout 套件 THEME 页捕获断言分区控件，移除 legacy 下不存在的模式控件轮。

---

## 二、重新检查中发现的修正

模拟器实测暴露并修正了以下问题：

1. **本轮自身的误判**：初次修改把 Test-ImmersionSettings 的断言改成了统一外观语义（`appearance.mode`），实测立即失败（legacy 模式 dump 无 `appearance` 字段）——已恢复 legacy 语义。
2. **既有失配**：`Tap '自定义'` 全等匹配不到控件（文案实为"自定义浅色"），该套件此前从未跑通过此步——已修正。
3. **既有失配**：自定义演示后 legacy 主题停留在 CUSTOM，破坏后续 SYSTEM 段断言（原恢复步骤对应的旧 UI 已不存在）——已用等价命令恢复。

---

## 三、验证结果（全部来自本次输出）

### JVM 层

| 命令 | 结果 |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **355 项，0 失败**（含新增 12 项针对性回归） |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew :app:assemblePreview` | BUILD SUCCESSFUL |
| `./gradlew :app:lintDebug` | BUILD SUCCESSFUL |

两个脚本 Parser 解析（UTF-8）无错误。本轮未修改 `reader.js`，按项目规则未跑浏览器回归。

### 模拟器实测（隔离模拟器 `fix3-1025`，固定 preview APK）

| 运行 | 结果 |
|---|---|
| `Test-AppAppearance.ps1 -StartupOnly` | **7/7 通过**（此前在此必失败的首次捕获路径） |
| `Test-AppAppearance.ps1` 完整流程 | **67/67 通过**：分区编辑只写目标组且不切换模式、APPEARANCE 模式切换、夜间/日间配色生效、两组记忆独立、阅读器切换模式不重载且字符位置保持、跟随系统、HOME 恢复、仓库重开读取两组、窄屏大字下两页控件边界 |
| `Test-ImmersionSettings.ps1 -Suite theme` | 全程无失败退出：背景预设保留 SYSTEM 主题与不透明度、纯色/图片切换、自定义色板、真实系统日夜切换刷新阅读器 CSS 且位置保持、手动主题不被系统覆盖 |
| `Test-ImmersionSettings.ps1 -Suite layout` | 全程无失败退出：3 档字号 × 2 旋转 × 2 入口的实际布局与字号缩放 |

模拟器已按规约关闭（`Invoke-GlassPreview.ps1 -Action Stop`）。

---

## 四、真实模拟器截图（29 张）

截图均为隔离模拟器实跑的屏幕捕获原件，位于本地证据目录（`build/` 不入库）。

### Test-AppAppearance 完整流程（统一外观模式）

| 截图 | 内容 |
|---|---|
| `appappearance-fix3-1025-full/initial-day.png` | THEME 页·日间：「日间样式/夜间样式」分区切换、护眼配色（旧配置迁移）选中态 |
| `appappearance-fix3-1025-full/night-profile.png` | APPEARANCE 页·夜间：「跟随系统/日间/夜间」模式选择器、玻璃质感卡（透明度 40%） |
| `appappearance-fix3-1025-full/day-profile.png` | APPEARANCE 页·切回日间后的界面 |
| `appappearance-fix3-1025-full/reader-night.png` | 阅读器内同窗设置面板：纯黑正文 + 夜间样式分区 + 玻璃透出正文 |
| `appappearance-fix3-1025-full/reader-day.png` | 面板内切日间后：正文未重载、纸色背景恢复 |
| `appappearance-fix3-1025-full/follow-system-night.png` | 跟随系统 + 系统夜间生效状态 |
| `appappearance-fix3-1025-full/resumed-day.png` | HOME 退后台、系统切日间、恢复后的阅读器 |
| `appappearance-fix3-1025-full/narrow-settings-DAY.png`（及 NIGHT） | 窄屏（640×1280）+ 1.6 倍大字下的 THEME 页布局 |
| `appappearance-fix3-1025-full/narrow-reader-settings-DAY.png`（及 NIGHT） | 同布局参数下的阅读器内设置面板 |

### Test-ImmersionSettings theme 套件（legacy 模式）

| 截图 | 内容 |
|---|---|
| `immersion-theme-fix3-1025/theme-system-image.png` | THEME 页图片背景 + 37% 不透明度（SYSTEM 主题保持） |
| `immersion-theme-fix3-1025/theme-custom.png` | 「自定义浅色」选中后的自定义色板 |
| `immersion-theme-fix3-1025/system-light.png` / `system-dark.png` | 真实系统日夜切换刷新阅读器 CSS（同章节同位置） |
| `immersion-theme-fix3-1025/system-resumed.png` | 恢复前台后跟随系统日间、无跳变 |
| `immersion-theme-fix3-1025/manual-paper-system-dark.png` | 系统夜间下手动纸色主题不被覆盖 |

### Test-ImmersionSettings layout 套件

`immersion-layout-fix3-1025/` 下 12 张（3 档字号 × 2 旋转 × settings/reader-settings 两入口）。

### 视觉检查结论（三张关键截图逐张确认）

- **THEME 页（日间）**：分区切换正常、日间选中，配色选项完整且护眼（迁移自旧配置 GREEN）处于选中态——与"legacy green migrates"断言互证；页内无模式控件；布局无重叠截断。
- **APPEARANCE 页（夜间）**：模式三选项齐全、夜间选中；玻璃质感卡正常（透明度 40% + 恢复默认）；暗色玻璃材质渲染正常、文字可读。
- **阅读器内设置面板（夜间）**：纯黑正文正常（页眉/章节标题/段落排版完好），面板以同窗弹层覆盖正文，「夜间样式」分区与「纯黑」配色选中，半透明玻璃透出正文、无破损。

---

## 五、证据目录

- `build/verification/appappearance-fix3-1025-startup/`（summary.json + 截图 + 状态回读）
- `build/verification/appappearance-fix3-1025-full/`（67 项检查记录、12 张截图、逐次状态 JSON、UI 层级 XML）
- `build/verification/immersion-theme-fix3-1025/`、`build/verification/immersion-layout-fix3-1025/`
- 模拟器实例目录：`build/verification/glass-native-preview/fix3-1025/`（captures 原件与 instance.json）

---

## 六、复核补修（第二轮，分支 `feature/settings-refactor`）

复核确认首轮遗留两个未闭环路径，本轮补修如下；脚本导航项（P2 第三项）经复核已通过核验，未重新实施。

### P1 补修：正文替换失败后的文件与数据库一致性

**位置**：`BookMaintenance.kt`（`redetect` 重构 + 新增 `ReindexCoordinator` / `ReindexRecoveryException`）

首轮代码在数据库事务提交后才调用 `ContentReplacer.publish`，且该调用位于清理 try 块内：替换失败时 staging 被删除、数据库已是新索引而正文仍是旧的——混合状态且新正文副本丢失。

补修：

- **生产协调边界**：从 `redetect` 提取 `ReindexCoordinator`（提交新索引 → 原子替换正文 → 失败恢复），生产与测试共用同一段编排。
- **旧状态快照**：`redetect` 在发布前读取 `oldBook`/`oldChapters`；替换失败时协调器以 `withContext(NonCancellable)` 恢复旧章节与旧书籍字段（符合 AGENTS.md 的 DB 写入纪律），保证**完整旧状态**（旧正文可读 + 章节字节区间/编码恢复旧值），恢复正常后清理 staging 并抛出可重试错误。
- **恢复失败保现场**：恢复事务也失败时抛专用 `ReindexRecoveryException`，`redetect` 的最外层清理对其豁免——staging 与原正文全部保留待补救，绝不误删恢复所需副本。
- **取消边界**：提交前/提交中取消 = 数据库未提交，完整旧状态、取消语义透传；提交成功后 `publish` 为纯同步文件操作，不存在被打断的中间挂起点。

针对性回归：新增 `ReindexCoordinatorTest`（6 项，直接执行 redetect 实际调用的协调器）——**数据库已提交后替换失败 → 恢复旧目录恰好一次、原正文可读、staging 清理**（复核验收 4 关键场景）；恢复也失败 → 专用异常 + 现场完整保留（验收 6）；提交失败/提交取消 → 透传且不发布、不恢复（验收 5）；提交挂起期间外部取消 → 不发布（验收 5 取消边界）。

### P2 补修：新旧两份外观配置都成功持久化后才能清理旧图

**位置**：`SettingsRepository.kt`

首轮实现只确认 `app_appearance_v1` 成功即通知清理；`reader_config_global` 的兼容字段由独立异步任务保存——新键先成功、旧键延迟或失败时，旧版 APK 回退读到引用旧图的配置而文件已删，破坏旧版回退契约。

补修：

- **双键协调**：新增 `lastPersistedGlobal`（最近一次成功落库的兼容配置）与 `pendingBackgroundCleanup`（清理候选暂存）。外观键成功落库后差集候选**暂存不删除**；`maybeNotifyBackgroundCleanup` 仅在 `pendingGlobal.value === lastPersistedGlobal`（兼容键当前排队值已全部确认成功落库）时才把候选交给清理回调。
- **失败不崩、候选不丢**：`persistGlobal` 的写入包 `runCatching`——失败不更新 `lastPersistedGlobal`，清理持续推迟（原来该路径异常会直接崩掉防抖 job）；推迟期间继续替换的候选逐批合并不丢失，兼容键重试成功后一次补发。
- 兼容键确认成功时，其样式字段与外观键当前生效组一致（双写），因此外观差集已隐含兼容键引用核对；测试中另按持久化值断言兼容键不再引用被删图。

针对性回归：`SettingsRepositoryTest` 新增 5 项（全部走 `queueReadingConfig` 的真实双写路径，非单键模拟）——**兼容键 put 阻塞 → 不清理，解除后补发并核对兼容键引用**（复核验收 5）；**兼容键失败 → 不清理，重试成功后清理**（验收 5）；兼容键先成功、新键后到 → 成功即发（验收 7 相反顺序）；阻塞期间 A→B→C 两批候选合并一次补发（验收 6）；新键失败 → 不清理，重试后按最终持久化状态清理（验收 7）。

### 第二轮验证结果（本次输出）

| 命令 | 结果 |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **366 项，0 失败**（首轮 355 + 本轮新增 11：ReindexCoordinatorTest 6 项 + 双键协调 5 项） |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew :app:lintDebug` | BUILD SUCCESSFUL |

两项新增回归分别针对复核指出的故障边界构造（提交后替换失败、兼容键受阻/失败）——在首轮代码上这些用例必然失败（无恢复目录的调用、appearance 成功即发通知），补修后全绿。本轮未修改 `reader.js` 与两个 PowerShell 脚本，未重跑模拟器矩阵（按方案文档，既有 7/7、67/67、theme/layout 通过记录为脚本流程证据，两项数据安全补丁不触及那些流程）。

---

## 七、未验证项及原因

- **P1 的真机端到端交错**（真实大 TXT 的取消-重试、真 Room 事务上的替换失败注入）：JVM 测试环境无 Robolectric/内存 Room 基础设施，无法构造真 Room 事务；`ReindexCoordinator` 是 `redetect` 实际调用的生产协调边界，其全部故障分支已由 6 项回归覆盖，`redetect` 侧的接线（旧状态快照、补偿事务绑定）留待有书库数据的验收环境整机复验。
- **P2 的进程中途 kill 注入**：需对运行中进程定点杀死，本轮未执行；"双键未确认成功不清理"已由阻塞/失败/重试用例覆盖，进程死亡时未落库的键自然不满足清理条件。
- **Test-ImmersionSettings 其余套件**（epub/window/reader 等）：不受本次修改影响，属既有验证矩阵，未扩大范围。
- 第二轮未重跑模拟器矩阵与浏览器回归：本轮只改 `BookMaintenance.kt`/`SettingsRepository.kt` 及测试，未触及 `reader.js` 与 PowerShell 脚本流程。

---

## 八、边界遵守

- 仅修改两项补修直接涉及的代码与回归测试，并据实更新本报告；脚本导航修复经复核通过后保持不动。
- 保留工作区原有无关改动；未切换分支（第二轮在 `feature/settings-refactor` 上执行）。
- 未修改 Room 表结构、版本号、包名、签名或用户数据格式。
- 未提交、未推送、未打标签、未发布、未制作签名包；未操作真实手机；未覆盖历史 APK；未使用真实书籍或背景图片做破坏性验证。
