# 主页与阅读页上下栏磨砂玻璃透色修复报告

报告日期：2026-10-30。基线提交：`d199a1d`（1.3.0 / versionCode 26）。本轮改动尚未提交、未推送、未发版。

**结论：白底来自两段"整栏底色下限"——五个调用点传入 66%（日）／78%（夜）的不透明度，材质层再用 `max()` 抬过模糊结果，与 GPU 采样、模糊、折射是否正常无关。取消这层下限并改为按角色的底色曲线后，真实 Debug 页面与生产阅读硬件场景里，封面／图片经过顶栏与底栏时能连续透出颜色与结构。文字可读性在浅色主题的验收样本中同时改善；深色主题叠在高亮封面时小字对比度仍有下降，属薄玻璃的固有取舍，单列在残余限制。**

## 1. 根因定位

两处绘制路径都对底色取了「曲线值与调用点下限的较大者」：

| 层 | 位置（改动前） | 行为 |
| --- | --- | --- |
| GPU 硬件材质 | `LiquidGlassMaterial.kt:48` | `max(liquidBaseOpacity(clarity), minimumTintAlpha)` |
| Compose 采样材质 | `GlassSurface.kt:280` | 同上 |

下限由五个调用点提供：

| 调用点 | 角色 | 改动前下限 |
| --- | --- | --- |
| `ScreenHeader.kt:40` | 书架／搜书／笔记／设置公共顶栏及二级页 | 日 .66／夜 .78 |
| `AppNavigationBar.kt:51 → 65` | 应用悬浮底栏底座 | 日 .66／夜 .78 |
| `AppNavigationBar.kt:119` | 底栏选中胶囊 | 日 .66／夜 .78 |
| `ReaderChrome.kt:100` | 阅读顶部工具栏 | 日 .66／夜 .78 |
| `ReaderChrome.kt:190` | 阅读底部工具栏 | 日 .66／夜 .78 |

在默认清晰度 60 下，公共曲线本该给出 22.7%（日）／24.8%（夜），却被抬到 66%／78%，于是一整块主题底覆盖在模糊结果上。清晰度 0 档另有一条独立原因：`liquidBaseOpacity(0) = 1.0`，即使没有下限也天然实心。

同场景实测（硬件阅读场景，`gpuActive=true`、`fallbackReason=none`、`refractionActive=true`）：改动前 `baseOpacity=0.66`、`blurDp=8.5`，即模糊与折射都在工作，看到的仍是白底——与"GPU 失效"无关。

## 2. 源码改动

### 2.1 取消调用点覆盖（结构性）

- 删除 `minimumTintAlpha` 参数：`GlassSurface`、`Modifier.glassBackground`、`Modifier.hardwareGlassBackground` 三层签名同时移除，五个调用点不再传入。材质层从此没有"局部抬高整栏底色"的入口，避免下次调整又只修到一处。
- 两处 `max(...)` 改为直接取公共角色策略。

### 2.2 按角色拆底色曲线（`LiquidGlassPolicy.kt`）

| 函数 | 用途 | 曲线 |
| --- | --- | --- |
| `liquidPanelOpacity`（原 `liquidBaseOpacity`，改名以体现角色） | 面板（目录、设置、消息、确认框） | 不变：`floor + (1-floor)·(1-p)²`，下限日 .08／夜 .10，0 档接近实心 |
| `liquidBarOpacity`（新增） | 上下栏、栏内小控件、选中胶囊 | `floor + (ceiling-floor)·(1-p)²`，floor 日 .08／夜 .10，ceiling 日 .46／夜 .52 |
| `liquidTintFloor` / `liquidBarCeiling`（新增） | 两个曲线共用的下限与栏上限常量 | 避免同一组数值在两条曲线里各写一遍 |
| `liquidSurfaceOpacity`（新增） | 按 `GlassStyle` 分派 | Panel → 面板曲线；Toolbar → 上下栏曲线 |
| `uniformGlassOpacity` | 开关／降级／曲线统一入口 | 增加 `style` 参数；关闭 1f、GPU 不可用 .94f 不变 |

设计取舍：100 档仍取与改动前相同的 8%／10% 薄底（这一档本来就该最透），只把中低档位的上限压下来；0 档保留最大模糊（17.5dp）与最厚底色（日 .46／夜 .52），仍能看见背后结构，与"关闭"（完全不透明）区分。

### 2.3 取消弹窗 80 档模糊封顶（按用户当轮要求）

`liquidBlurClarity(clarity, style)` 改为 `liquidBlurClarity(clarity)`，`GlassStyle.Panel` 不再折算到 80。弹窗与上下栏共用 0–100 语义，100 档同样零模糊；底色仍沿面板曲线在 80→100 继续减薄（8%／10%）。计划文档第 3 节的"80 只限制弹窗模糊档位"已在 `docs/plans/glass-bars-white-tint-fix-plan.md` 第 7 节标注作废。

### 2.4 不变量保持

- 未新增偏好键、未改 `app_appearance_v1`、未绕过 `SettingsRepository`／`ReadingConfigEditor`。
- 未改 Room、书源、字体、背景资源；未改 `reader.js`／`reader.css`／分页留白。
- 未改 `LocalGlassLegacy`、`LocalGlassForceFallback` 与 `.94` 降级分支。
- 内容层／控制层分离不变：玻璃仍只采样内容层。

## 2.5 评审后调整（同日自查）

对未提交 diff 做了一轮评审，按评审意见改了五处，均为可读性／可维护性，不改行为：

| 评审意见 | 处理 |
| --- | --- |
| `liquidBaseOpacity` 已不再是"基础"曲线，名字与实际角色不符 | 改名 `liquidPanelOpacity`，与 `liquidBarOpacity` 对称；文档与测试同步 |
| 下限与栏上限两组数值在两条曲线里各写一遍，改一处容易漏另一处 | 抽出 `LIQUID_TINT_FLOOR_*`／`LIQUID_BAR_CEILING_*` 常量与 `liquidTintFloor()`／`liquidBarCeiling()`，两条曲线共用 |
| 只有"小于旧下限"的断言，缺少对验收实测值的锁定，日后"稍微加厚点更稳"不会被测试挡住 | 新增 `barTintStaysLockedToTheAcceptedSamples`，锁定 0／60／100 两套主题的确切值（.46／.52、.1408／.1672、.08／.10），面板曲线同时锁定 .31（50 档）与 1f（0 档） |
| 底栏底座与选中胶囊各自采样并叠底色，代码里没说明这是有意设计 | 在胶囊处补注释：叠加是"选中抬升"，实测无双层白胶囊／暗圈；后续只调填充浓度，不再给材质加不透明度下限 |
| `uniformTintDependsOnlyOnClarityThemeAndAvailability`、`highTransparencyRemainsThinAndMonotonicallyDecreases` 两个测试名与现在测的内容不符 | 分别改为 `toolbarTintDependsOnlyOnClarityThemeAndAvailability`、`panelCurveStaysMonotonicAndThinAtHighClarity` |

评审后重新构建并用同一场景复核：纯改名与注释不改变渲染，`after` 截图与评审前逐像素一致（见第 4.6 节）。

## 3. 检查结果

| 项 | 结果 | 证据 |
| --- | --- | --- |
| `:app:testDebugUnitTest` | **PASS** | 566 项、0 失败（新增 4 项角色／开关／档位边界与数值锁定测试，改写 2 项与 80 档封顶、底栏下限相关的旧断言） |
| `:app:lintDebug` | **PASS（存量告警）** | 54 项：48 warning + 6 hint，0 error；改动文件中仅 `ReaderChrome.kt:374` 一条历史 `AutoboxingStateCreation` hint |
| `:app:assembleDebug` / `assemblePreview` | **PASS** | 见第 6 节哈希 |
| 原生视觉（真实 Debug 页面） | **PASS（含残余限制）** | 第 4 节 |
| 原生视觉（预览矩阵 21 例） | **PASS** | `build/verification/glass-bars-fix-20261030/{before,after}/captures/` |
| 底栏长按跟手拖动 | **PASS** | `recordings/after-debug-nav-drag.mp4` |
| 阅读位置恢复 | **PASS** | 硬件场景 offset=215／page 1/11，HOME 返回后不变 |
| 弹窗 100 档零模糊 | **PASS** | 第 4.4 节前后对比 |
| API 26–30 | **NOT_RUN** | 仅有 API 37 镜像 |

## 4. 原生证据

### 4.1 真实 Debug 页面（API 37，硬件玻璃路径）

书架导入 `星辰之下.epub`（含彩色渐变封面与内嵌彩色插图），滚动使封面经过上下栏；同一模拟器、同一场景参数，仅更换 APK。

| 区域（封面经过） | 改动前 | 改动后 |
| --- | --- | --- |
| 顶栏右侧平均色 | rgb(228, 211, 227)，彩度 21.8 | rgb(197, 151, 203)，彩度 **58.2** |
| 底栏中部 | 彩度 19.6 | 彩度 32.7 |

截图：`after/captures/after-debug-shelf-scrolled.png`、`before/captures/before-debug-shelf-scrolled.png`；前后并排拼图 `zooms/compare-header.png`、`zooms/compare-bottom.png`、`zooms/compare-dialog-c100.png`（左为改动前，右为改动后）。改动后顶栏内能连续看到封面紫／品红的过渡与封面之间的缝隙，标题与图标仍为清晰前景、不参与模糊。

阅读页（纸色底 + 彩色插图经过底栏）：`after-debug-reader-light-scrolled.png` 与 `before-debug-reader-light-scrolled.png` 对比，底栏由近乎均匀的米白变成能看到插图由青到品的渐变与模糊字迹。纸色底之上顶栏仍呈浅色——那是内容本身的颜色，不是底色遮挡。

### 4.2 生产阅读硬件场景（真实分支指标）

`SettingsImmersionPreviewActivity` + 生产 `ReaderHardwareScene`，同一 APK：

| 场景 | gpuActive | baseOpacity | blurDp | refraction | fallbackReason |
| --- | --- | --- | --- | --- | --- |
| 日间 60（改动后） | true | 0.1408 | 8.5 | true | none |
| 夜间 60（改动后） | true | 0.1672 | 8.5 | true | none |
| 日间 0（改动后） | true | 0.46 | 17.5 | true | none |
| 日间 100（改动后） | true | 0.08 | 0 | true | none |
| 日间 60（改动前） | true | 0.66 | 8.5 | true | none |

状态 JSON 与截图在 `hardware/`。可见改动前后都在 GPU 分支上，差值只来自底色覆盖。

### 4.3 矩阵覆盖

`before`／`after` 各 21 例：书架与阅读 × 日／夜 × 清晰度 0／60／100，另加玻璃关闭、强制降级、弹窗日／夜、弹窗 100 档、阅读设置页与 100 档。`captures.json` 记录每例的场景参数与该例 `glassStatsDump` 日志。

- 玻璃关闭：1f 不透明稳定表面，`gpuActive=false`。
- 强制降级：`.94` 实心底，按分支标注，不算玻璃视觉通过。
- 100 档：`blurDp=0`，底色 8%／10%，边缘与折射保留。

### 4.4 弹窗 80 档封顶取消

`before/captures/before-dialog-light-c100.png`（背景仍被模糊）对比 `after/captures/after-dialog-light-c100.png`（背景清晰可辨，仅 8% 薄底）。这正是用户当轮要求的语义。

### 4.5 交互与位置

- 底栏长按 700ms 后连续拖动：选中态跟手跨过四个入口（中途截图 `after-debug-nav-drag.png` 位于"设置"），松手吸附，栏高不增长；16 秒录屏 `recordings/after-debug-nav-drag.mp4`。
- 阅读位置：硬件场景滚动到 offset=215（page 1/11）后按 HOME、再切回，`state-deep.json` 与 `state-resumed.json` 的 `scrollY=900 / offset=215 / page=1/11` 完全一致；Debug 真机上滚动后退出、强停、重进，截图为同一画面（`after-debug-reader-*` 与本轮 `deep` 两图 SHA-256 相同）。

### 4.6 评审后复核

评审只做改名、抽常量、补注释与测试，理论上不改渲染。用同一脚本、同一模拟器重截三例与评审前逐像素比较：

| 场景 | 差异像素占比 | 区域分布 |
| --- | ---: | --- |
| `app-shell` 日间 60 | 0.058% | 全部在状态栏时钟区域；顶栏／正文／底栏 0 像素差异 |
| `reader` 日间 60 | 0.065% | 同上 |
| 弹窗 日间 100 | 0.079% | 同状态栏级别差异 |

硬件阅读场景复测：`gpuActive=true / baseOpacity=0.1408 / blurDp=8.5 / fallbackReason=none`，与评审前一致。截图与状态 JSON 在 `verify/`。

## 5. 残余限制

1. **深色主题叠在高亮封面时，小字对比度下降。** 同一场景测得顶栏标题 3.06:1（改动前 14.21:1）——标题为大号字仍可辨，副标题与小标签低于 4.5:1。这是"薄玻璃 + 固定主题前景"的固有代价：要把浅色字拉回 4.5:1 需要约 78% 的深色底，正是本轮被否定的白／黑板。若后续要同时满足通透与可读，正确做法是按采样亮度切换栏内前景色（动态前景），本轮未做，避免把材质修复扩大为页面重设计。用户已确认 100 档看不清文字可接受，此处的 3.06:1 是默认 60 档的实测值，供是否需要动态前景的决策参考。
2. **预览货架夹具的封面是浅色占位图**，单独看预览矩阵无法证明透色；透色证据以真实 EPUB 封面（4.1）与硬件场景（4.2）为准。
3. **阅读页顶栏在纸色正文上仍呈浅色**：背后内容本身就是主题纸色，不是底色遮挡；判断通透应看彩色插图经过底栏的对比。
4. **短样章页码停留在 0.0%**：`星辰之下` 单章内容较短，页码与百分比不变，非零位置改用硬件场景的 offset=215 证明。
5. **仅 API 37 模拟器（`-no-window`，gfxstream）**：API 26–30 的简化材质未实测，标 `NOT_RUN`；模拟器帧率不代表真机性能。

## 6. 交付物

| 类型 | 路径 | SHA-256 |
| --- | --- | --- |
| Debug APK | `artifacts/apk/debug/1.3.0/20261030-glassbars-white-tint/mirad-reader-1.3.0-debug.apk` | `5e2340bac178574587825dc4be266fca532c17b689c17a4955e714f1b58af027` |
| Preview APK | `artifacts/apk/preview/1.3.0/20261030-glassbars-white-tint/mirad-reader-1.3.0-preview.apk` | `fe03ef0e7e86178d031f433857eaca3f312c4aa40b8fa31a60c952fb0f21c521` |
| 改动前对照 Debug | `build/verification/glass-bars-fix-20261030/baseline/mirad-1.3.0-before-debug.apk` | `d2937c1d4c76fcf581506656ab393b5dc87b94c47600669ecf7f3a405323983b` |
| 改动前对照 Preview | `build/verification/glass-bars-fix-20261030/baseline/mirad-1.3.0-before-preview.apk` | `6f14f0ad7ab48861111cf6f95386cf8961b28c416873b5315ac8a5edf651a513` |
| Release APK（1.3.1 / versionCode 27） | `artifacts/apk/release/1.3.1/20261030-183603/mirad-reader-1.3.1-release.apk` | `41631cc5cd997f48b497ffc865ed768b30278f079e7e2531abf8d81220c20186` |
| 证据目录 | `build/verification/glass-bars-fix-20261030/`（`before/`、`after/`、`hardware/`、`recordings/`、`zooms/`、`capture-bars.ps1`） | — |
| 运行记录 | `build/verification/glass-native-preview/glass-bars-final-20261030/`（实例、日志、截图；AVD 已在 Stop 时清理） | — |

源码改动：`LiquidGlassPolicy.kt`、`LiquidGlassMaterial.kt`、`GlassSurface.kt`、`GlassPolicy.kt`、`ScreenHeader.kt`、`AppNavigationBar.kt`、`ReaderChrome.kt`（`app/src/main`），测试 `LiquidGlassPolicyTest.kt`、`GlassAppearanceTest.kt`（`app/src/test`）。
