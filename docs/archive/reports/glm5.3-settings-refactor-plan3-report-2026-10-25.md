# 设置重构计划 v3 剩余实施完成报告

- **分支**：`Zcode/glm5.3`（工作树，未提交）
- **日期**：2026-10-25
- **范围**：`settings_refactor_plan.md`（v3）剩余阶段 P1–P7 全部完成，外加三个新需求（书架布局记忆、阅读工具栏加宽、正文「？！」标点重合修复）。
- **前序报告**：审查修复与两轮复核补修见 `docs/reports/glm5.3-review-fix-report-2026-10-25.md`，本文不重复。

## 默认决策（提问未获答复，按合理默认执行）

| 决策点 | 采用 |
|---|---|
| 工具栏加宽方式 | 水平与高度都加宽（幅度温和：水平 12→4dp、外层 8→10dp、内层 4→8dp、滑块与章节行 48→56dp） |
| 计划剩余范围 | P1–P7 全做 |
| 主题页预览块 | 只画色块（底色块 + 文字色细条，无示例文字），与计划 §4.10 的"待确认前只显示色块"一致 |
| 书架记忆 | 只持久化网格/列表布局，排序不动 |

## 批次 0：三个新需求

1. **N1 书架布局记忆**：`SettingsRepository` 新增 `shelf_layout` 键三件套（`queueShelfLayout` 400ms 防抖 + `shelfLayoutGrid` Flow，无记录默认网格）；`ShelfScreen` 读写接线。离开书架再回来保持上次选择。
2. **N2 阅读顶/底工具栏加宽**：`ReaderChrome.kt` 顶栏与底栏玻璃面板水平留白 12→4dp、外层垂直 8→10dp、顶栏行内边距 4→8dp、底栏滑块与章节行最小高度 48→56dp。legacy（preview 矩形）分支不动。
3. **「？！」标点重合修复**（本轮追加）：`ReaderCss` 段落规则不再输出 `font-feature-settings:"chws" 1,"halt" 1`。该组合会在 Android WebView 的 CJK 字体上启用全角标点压缩，字体特性数据不匹配时连续标点（？！）字形叠印。修复依据：
   - Playwright 探针（霞鹜文楷 woff2）：连续「？！」的字形推进量与墨迹边界均正常（墨迹净距 18px@28px 字号），字体本身不重叠；
   - `text-justify:inter-ideograph` 探针：只拉伸不压缩，非重叠来源；
   - 删除压缩特性后不存在任何标点压缩路径，问题根除；代价是连续标点不再半宽紧排。
   - 新增 `punctuationCompressionFeaturesStayOff` 单测锁定该输出不回归。

## 批次 1（P1 结构拆分）

- 新建 `ui/settings/reading/`：`ReadingSettingsHost` + Typography/Theme/Interaction/Screen/HeaderFooter/Epub/Tts 七个 Section；原「屏幕与信息」拆为「屏幕」与「页眉页脚」两个分类页。
- `ui/settings/AppearancePage.kt`：应用级「界面外观」页（日夜模式 + 玻璃质感）。
- 接线：`AppRoot.SettingsDetail` 改持 `SettingsRoute`；`SettingsScreen` 首页改用 `ReadingSettingsPage`/`AppSettingsPage`；阅读面板改用 `ReadingSettingsPage`；预览包 intent 解析改 `SettingsRoute.parse`（旧名 `SCREEN` 仍指向「屏幕」页，脚本无感）。
- 删除 `ReaderSettingsContent.kt`；`ReaderSettingsPolicyTest` 迁至 `ui/settings` 并补路由解析用例。
- 连带完成（编译必然，提前于批次 2）：阅读面板不再有「界面外观」应用级入口（A4）；面板 subtitle 的 APPEARANCE 特判删除。

## 批次 2（P2 去重归位）

- **A3**：设置页「书架管理」分组区改为 `SettingNavRow` 打开 `GroupManagerSheet`，删除设置页内联重命名/删除对话框与相关状态。
- **快捷区重排**：新建 `QuickAdjustSection.kt`——`AppModeQuickRow`（日夜模式三选，写应用模式同字段；无外观记录的隔离预览不渲染）+ `QuickThemeSwatches`（当前生效组主题色板单行，点击写目标组并切回纯色背景）。面板首页顺序：快捷调节卡（日夜/色板/阅读方式）→ 字号 → 自动阅读与分类入口。快捷卡置顶是为窄屏大字体下面板高度受限时日夜行仍可达（验收脚本驱动）。
- **D1/C7**：`ReaderScreen` 页眉改传 `state.chapterTitle`（真实章节名），删除 `HeaderFooter` 内部的重复开关判断；开关文案「显示书名」→「显示章节名」。
- **B5**：EPUB「保留原书」时「字体」「排版」卡照常显示但整体置灰停用（`gatedChange` no-op + alpha 0.45），页边距/版心仍可用，保留跳转 EPUB 行。

## 批次 3（P3 剩余 + P4 交互补齐）

- **主题页预览块**：`BackgroundSection` 顶部新增目标组预览（底色块 + 左侧文字色细条，无示例文字）；编辑目标 ≠ 当前生效组时显示「切换到夜间模式后生效 / 日间模式后生效」。
- **P3 单测**：日间编辑夜间组不动另一组与模式（`ReadingConfigEditorTest` 既有 `slowFontImport...` 覆盖）；内置素材跨组清除 + 本机图片保留（`ReadingStyleNormalizationTest` 两条）；选图器目标组锁定（`targetDark` 为 UI rememberSaveable，无法纯 JVM 测，写入路径由编辑器分区测试覆盖，见未验证项）。
- **D3/C1 音量键开关**：`InteractionSection` 加「音量键翻页」开关；`ReaderSession.handleVolumeKey` 开头改为 `shouldHandleVolumeKey(ready, config)` 纯函数（`volumeKeyPage=false` 时按键交还系统）；`ReaderKeysTest` 覆盖三态。
- **D4/C3 字间距滑块**：`TypographySection` 加「字间距」0–0.30em（步进 0.02）；`ReaderCss` 的 letter-spacing 输出本就存在，补断言。
- **C8**：抽 `AutoReadSpeedControls`（翻页间隔/下滑速度按阅读方式二选一），`AutoReadSheet` 与 `InteractionSection`「自动阅读」卡共用。

## 批次 4（P5 恢复默认与保存状态）

- `ReadingConfigEditor.resetReading(scopes)`：`LAYOUT_INTERACTION` = 样式字段投影到 `ReaderConfig.DEFAULT`（排版交互恢复默认、日/夜配色不动）；`DAY_STYLE`/`NIGHT_STYLE` = 外观记录对应组恢复默认；删除旧 `reset()`。
- `ResetScope` 枚举 + `AppAppearanceController.resetReadingConfig(scopes)` 接口同步。
- **ResetDialog**（`ui/common/ResetDialog.kt`）：三范围多选（默认只勾排版与交互）、逐项明细、danger 确认；设置页「阅读设置」卡底部「恢复默认」入口与阅读面板底部按钮共用。
- **B3 横幅合并**：`AppearancePage` 顶部单条失败横幅（`appSaveFailed || glassSaveFailed`），重试依次调两个 `retrySave`；删除日夜模式行内与玻璃卡内两处独立错误 UI。
- 单测：三范围各一 + 组合（含旧 `reset` 用例改写），断言范围外字段不变。

## 批次 5（P6 模型与仓库清理）

- 删 `ReaderConfig` 的 `longPressSelect`/`verticalPaddingDp`/`windowedScroll`（均零产品引用）；`windowedScroll` 改为 `ReaderSession.WINDOWED_SCROLL = true` 常量。
- 删 `PageAnim.CURL` + `ReaderSession` 映射分支 + `reader.js` 注释里的 CURL；`ReaderConfigJson.decode` 将非法 `pageAnim`（含旧 CURL）收敛为 SLIDE。
- 删 `ReaderSystemThemeEffect`（legacy SYSTEM 适配器，产品链路由 `ReaderAppearanceEffect` 覆盖）；隔离预览的 SYSTEM 动态刷新改为 `LaunchedEffect(systemDark)` 等价实现；`ThemePaletteResolver` 的 SYSTEM 分支按计划保留为防御。
- **C9 仓库清理**：删除 `queueBookConfig`/`flushBookConfig`/`hasBookConfig`/`observeEffective`/`effective(bookId)`/`saveBookConfig`/`updateBookConfig`/`clearBookConfig`；`PreferenceDao` 新增 `removeByPrefix`；启动一次性清理 `reader_config_book_%` 遗留键（`migration_book_config_cleared` 标记保证只执行一次，失败下次重试）。测试改用 `queueGlobalConfig`/`currentGlobal`。
- 兼容测试：含全部已删字段 + `pageAnim:"CURL"` 的旧 JSON 解码不抛、未知字段忽略、pageAnim 收敛 SLIDE；迁移只清一次且不动其它键。

## 批次 6（P7 回收验收）与验证结果（全部来自本次输出）

| 验证项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **378 项全部通过**（52 个测试类） |
| `:app:assembleDebug` / `assemblePreview` / `compileGpuPreviewKotlin` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL（历史 warning 仍在，未宣称全绿） |
| 浏览器回归 `anchor-regressions`（reader.js 变更后） | **44/44 通过** |
| `Test-AppAppearance`（完整：StartupOnly 由脚本内部覆盖 + 窄屏大字体可见性） | 全部 PASS（含修复快捷卡置顶后复跑） |
| `Test-ImmersionSettings` settings / theme / layout 三套件 | 全部 PASS（脚本断言已同步新结构：屏幕+页眉页脚分类、「显示章节名」、EPUB 置灰照常显示） |
| 模拟器抽验截图 | 设置首页（7 阅读分类含页眉页脚 + 3 应用分类）、THEME 页预览块（米黄底+文字色条+无提示）、面板首页（日夜行+5 色板+阅读方式）、统一外观面板（日夜行「日间」选中）、EPUB 置灰排版页、ResetDialog（默认勾选排版与交互、三项明细、danger 按钮）——逐张视觉确认无渲染异常 |

脚本同步说明：`Test-ImmersionSettings.ps1` 的分类断言（屏幕与信息→屏幕+页眉页脚）、页眉页脚导航（SCREEN→HEADER_FOOTER）、「显示书名」→「显示章节名」、EPUB 置灰断言（隐藏→照常显示）按本轮有意的产品结构变化更新；这些是断言与产品对齐，不是绕过失败。

## 证据目录（本机 `build/verification/`，不入库）

- `batch6-final/appearance2/` — Test-AppAppearance 完整证据与截图
- `batch6-final/immersion-settings9/` — settings 套件证据（theme/layout 见 `immersion-theme`/`immersion-layout`）
- `glass-native-preview/fix5-chrome/captures/b6-*.png` — 批次 6 抽验截图
- `chrome-fix5/chrome-wide-day.png` / `chrome-wide-night.png` — 批次 0 工具栏加宽截图
- `glass-native-preview/fix5-chrome/captures/b12-*.png` — 批次 1/2 抽验截图

## 合并前审查与修复（第三轮，合并评估后）

对全部工作树改动做了两路独立只读审查（数据/模型层 + UI/仓库卫生），发现并修复：

1. **「显示章节名」开关失效（功能回归，必修）**：D1/C7 改造时把传参处与 `HeaderFooter` 内部的两处开关判断都删了，`showChapterTitleInHeader` 一度全仓库零消费。已在 `ReaderScreen.kt` 传参处恢复唯一一处判断（`HeaderFooter` 内部保持无判断），并在 `Test-ImmersionSettings.ps1` settings 套件补上行为断言 `chapter title toggle turns the setting off`（此前只在页眉整体关闭时断言，属自动化盲区）。修复后套件重跑通过。
2. **EPUB 置灰旁路**：`TypographySection` 置灰只门控了 `onChange`，导入/删除字体回调原样透传（置灰时点「导入字体」仍真实写入）。已将 `onImportFont`/`onDeleteFont` 一并纳入 `gatedImport`/`gatedDelete` 门控。
3. **仓库卫生**：`.gitignore` 新增 `/.zcode/`（会话状态）与 `/AGENTS.md`（含本机签名材料路径提示，与既有 `/agent.md` 规则同类）；未跟踪清单从 44 项降到 42 项，其余均确认应入库。
4. **审查确认无问题**（摘）：一次性迁移幂等无竞态、删字段双向兼容（旧 JSON/旧 APK）、`removeByPrefix` 对 schema 安全、锁序无死锁、preview 与 main 源集 API 同步、脚本断言改写与产品对齐、无调试残留、无密钥明文。

审查遗留的 P2 项（不阻断合并，留待后续）：`resetReading` 日/夜组不双写旧兼容键、书架布局无 ON_STOP flush、背景清理候选跨进程孤儿文件、redetect 双失败进度夹紧不回滚、`ReaderScreen` 无条件 `ReaderKeys.pager = null`（HEAD 已有）、`ContentReplacer` 最坏序列只剩 .bak、`collapseWindowToActive` 防御缺口、主题色块无 contentDescription、`fullScreen=false` 时页眉与状态栏可能重叠（建议真机核验）。

第三轮验证（本次输出）：378 项单测 + debug/preview 构建全绿；settings 套件（含新断言）全过；模拟器复验后已再次关闭。

**合并路径**：改动仍全部未提交（三分支同指 e5d007d）。建议顺序：`git add --renormalize .` 独立空提交（7 个 CRLF 文件）→ 按语义分批提交（GPU 双平面架构 / 设置重构与修复 / 验证脚本与文档）→ 合入 main；合并后真机翻一页含「？！」的正文做标点修复的实机确认。

## 未验证项及原因

1. **API 26–28 设备**：本机仅 android-37 镜像，简化材质路径未在 26–28 实机验证（按约定不擅自下载系统镜像）。
2. **选图器目标组锁定的 UI 层**（P3 第三条）：`rememberReadingBackgroundPicker` 的 `targetDark` 为 Compose 状态，无 JVM 测试入口；其写入走 `controller.updateReadingConfig(dark)` 的分区正确性已由编辑器测试覆盖，剩余风险为 UI 闭包捕获错误，属代码审查级保障。
3. **「？！」重合的 Android 实机复现**：桌面 Chromium 探针无法完全复现 Android WebView 的字体特性路由；修复以"删除特性后无压缩路径"为根除逻辑，配字体墨迹探针与输出锁定断言。建议下次真机装包后翻一页含连续标点的正文确认。
4. **EPUB 置灰停用的 UI 自动化点击验证**：面板内滚动查找受限（TapControl 滚动次数不足以越过窄屏大字体下的字体卡），该行为由 `gatedChange` no-op（单行实现）保证。
5. **页面内文案**：`ResetDialog` 明细行与合并横幅文案为功能性描述（由计划 §4.8 表格推导），计划 §4.10 未逐字列出，如需调整以你的措辞为准。

## 兼容性说明（发布时记录到版本说明）

- 旧 JSON（含已删字段/CURL）解码安全收敛；`reader_config_book_%` 遗留键启动时一次性清理。
- `windowedScroll` 配置字段移除：滚动模式一律启用 3 章滑动窗口（此前默认即如此）。
- 阅读面板不再提供「界面外观」应用级入口（统一收纳到设置页「应用设置」）；面板首页新增日夜模式快捷行与主题色板。

模拟器 `fix5-chrome` 验收后已关闭（`adb emu kill`）。全程未提交、未推送、未制作签名包。
