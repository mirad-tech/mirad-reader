# 阅读外观与交互修复 · 实施与模拟器验收报告

日期：2026-10-26。计划：`docs/plans/reading-appearance-interaction-fix-plan-2026-10-26.md`。
基线源码：`main @ 458f9c9` + 本轮开始前的未提交改动（快照见
`build/verification/appearance-fix-20261026-a/baseline-workspace.diff`）。

## 0. 结论摘要

| 计划项 | 状态 | 证据 |
| --- | --- | --- |
| 1 主题与背景选项互相覆盖 | **已修复，已验收** | 设置结构改为「样式组 → 背景来源 → 分支设置」；`Test-AppAppearance` 前后均 PASS，11 张截图；`settings-layout` 48 项通过 |
| 2 设置二级页滑动卡手 | **未复现，未改代码** | `layout` 套件 48 项通过；未观察到拖拽被抢；无证据不改动嵌套滚动 |
| 3 阅读顶栏间距 | **已修复，已验收** | 内边距 4→12dp、三组间 8dp、标题/章节 2dp、空副标题不占行；`layout` 在 1.0/1.3/1.6 字体倍率下通过 |
| 4 玻璃下文字闪烁 | **未复现；未改渲染链路** | 20 秒滚动录屏 + 逐帧亮度分析：静止段相邻帧亮度波动均值 0.011（无周期性明暗） |
| 5 磨砂开关 / 清晰度 / 边缘质感 | **已实现并验收** | 五档清晰度截图 + PSNR（0↔50 = 26.5dB，50↔100 = 21.0dB）；开关关闭后 GPU 材质与分析停止；边缘第三轮：竖向方向性高光 + 内侧衰减带（9.2dp@0% → 6.0dp@100%，随清晰度变化）+ 浅色「暗轮廓 × 白镜面」双组交叉 + 折射带加宽；六档实测峰值 34–113，无任何一档消失 |

构建与回归：单测 **434 项全绿**（新增 11 项玻璃语义/迁移回归）；lint **0 error / 43 warning / 6 hint**（与改动前一致，无新增）；`assembleDebug`、`assemblePreview` 成功；CSP 浏览器回归 9/9。

## 1. 主题与背景选项

### 原因

旧结构在同一个卡片里同时呈现「主题色预设」和「背景来源」，两套控件都表现成"当前生效"：
主题点击只改 `theme`，图片点击改 `backgroundType/imageUri`，而 `ReaderCss` 用
`1 - backgroundImageAlpha` 叠衬底。结果是"选了纯色却仍是图片""图片 100% 时配色看不见"这类
无法解释的混合状态。两个入口（阅读面板 / 应用设置）行为也不一致：快捷色块会顺带
`backgroundType = COLOR`，详细页不会。

### 修复位置

- `ui/common/ReaderStyleSections.kt`：`BackgroundSection` 重写为单一卡片三段式
  1. 样式组（日间/夜间）→ 2. 真实预览 → 3. 背景来源二选一 → 分支设置。
  - `ColorBackgroundControls`：配色预设 + 自定义色，点选同时固定 `backgroundType = COLOR`。
  - `ImageBackgroundControls`：内置图、本机图、不透明度；**图片模式下不再并排展示配色预设**，
    改为不透明度 < 100% 时才可展开的「衬底配色」，且该处配色只改主题、不切回纯色。
  - `BackgroundPreview`：按 `ReaderCss` 同口径合成（图片 + `1-alpha` 衬底 + 文字色条），
    图片走新增的 `BackgroundThumbs.loadUri`（LruCache，按 URI 缓存，不随重组重解码）。
- 两个入口本来共用 `BackgroundSection`（`ReaderSheets` 与 `ReadingSettingsHost`），状态变换同步生效。

### 验收

- `build/verification/appearance-fix-baseline/app-appearance`（基线，PASS，11 张）
  对比 `appearance-fix-after/app-appearance`（PASS，11 张）。
- `after/settings-layout`：48 项通过、0 失败，12 张截图，覆盖两个入口 × 1.0/1.3/1.6 字体倍率。
- 人工查看截图（如 `reader-materials-4/DAY-c0.png`）：日间样式 / 夜间样式 → 预览 → 纯色/图片 →
  配色预设，层级清楚；不再出现两套控件同时"正在生效"。

## 2. 设置二级页滑动

**未复现，未改代码。** 计划要求先区分手势分配与渲染停顿，再决定是否动 Standard Slider。
本轮在隔离模拟器上通过 `Test-ImmersionSettings -Suite layout` 覆盖两个入口与三档字体倍率，
未出现页面偏移不变、滑块误改或 Sheet 抢手的情况；代码层面 `glassBackdropSource()`
不含 `pointerInput`/`clickable` 拦截，`MiradSlider` 仍是 Material3 `Slider`（水平 `draggable`），
垂直拖动由父级 `verticalScroll` 接管。

因此**没有**为了"看起来修了"去改父层手势分发。该项目的关闭条件未满足（无复现证据），
保持现状并列入未验证项。

## 3. 阅读顶栏间距

### 原因

`ReaderTopBar` 外边距只有 4dp、行内水平 8dp，返回按钮、文字列、右侧按钮之间没有显式间距；
副标题用空字符串 `Text` 占位，即使没有章节名也多占一行并拉大视觉间距。

### 修复位置

`ui/reader/ReaderChrome.kt`：

- 行内边距 `8dp` → `12dp`（垂直 8dp 不变），`horizontalArrangement = spacedBy(8.dp)`；
- 文字列 `verticalArrangement = spacedBy(2.dp)`；
- `subtitle.isNotBlank()` 时才渲染副标题（空副标题不占行）；
- 标题/副标题保持 `maxLines = 1` + 省略；触摸目标仍是 48dp `IconButton`。

### 验收

`after/settings-layout` 在 320dp、1.0/1.3/1.6 字体倍率下 48 项通过（含阅读器顶栏与设置二级页），
未出现重叠或裁切；截图与断言一并保存在该目录。

## 4. 玻璃下文字闪烁

**未复现。** 按计划要求先取证再改：

- 录屏：`build/verification/appearance-fix-after/recordings/glass-scroll.mp4`（20 秒，213 帧，
  阅读页 + 顶栏/底栏 + 设置面板，DAY / 清晰度 50%）。
- 逐帧分析：裁出顶栏玻璃区域（1080×240 @ y=60），用 `signalstats` 取每帧平均亮度。
  - 相邻帧亮度跳变 > 2 的 40 帧，全部落在滑动瞬间（背景内容本身在移动）；
  - **近静止帧对的平均跳变 0.011**，没有周期性明暗、没有整块闪白/黑。
- 因此没有修改 `ReaderBackdropHost` / `HardwareGlassBackdrop` / `GlassSurface` 的录制与保护链，
  也没有为了"改善"去降模糊、冻结背景或停正文滚动（计划明确禁止）。

**未验证**：真机高对比图片背景下的持续拖动、跨章瞬时切换、延迟图片加载时的亮度稳定性，仍需
目标设备复核；本轮只有模拟器 + 内存夹具，不能替代。

## 5. 磨砂玻璃开关、清晰度与边缘质感

### 5.1 语义与模型

`ui/common/GlassPolicy.kt`：

```kotlin
data class GlassAppearance(val enabled: Boolean = true, val clarityPercent: Int = 60)
val transparencyPercent: Int get() = if (!enabled) 0 else clarityPercent.coerceAtLeast(1)
```

- `clarityPercent`：0 最朦胧、100 最清晰，**两端都不等于关闭**；
- `transparencyPercent` 降级为给旧 APK 的单调投影（关闭写 0、开启写 1..100），
  新记录是事实源，旧投影不反向覆盖；
- `GlassAppearanceJson` 版本化记录 + `fromLegacyPercent`：`0 → 关闭`（清晰度取校准默认 60），
  `>0 → 开启并把旧值当清晰度初值`；损坏记录回退默认、不重复迁移。

### 5.2 清晰度真正改变模糊

- Compose 路径 `computeGlassParams(..., clarityPercent)`：`blurRadius = 自适应值 × (1 + (50-clarity)/50 × 0.75)`，
  **clarity = 100 直接返回 0dp**，不复用"透明度"命名。
- GPU 路径 `liquidBlurDp(style, clarity)`：同一方向、同一端点半语义；`LiquidGlassMaterial`
  在 `hasBlur == false` 时**不创建 blur effect**（`createBlurEffect(0,0)` 会抛异常，必须走显式无 effect 分支），
  折射也随之为空。

### 5.3 持久化

`data/repo/SettingsRepository.kt`：

- 新键 `ui_glass_appearance_v1`（JSON），旧键 `ui_glass_transparency_percent` 仅作投影；
- 仅当新记录不存在时迁移一次；新记录损坏 → 默认值，不重新迁移；
- 沿用既有 `glassWrites` 互斥、400ms 防抖、`pendingGlass` 优先与失败重试；首次读取的迁移写入包
  `NonCancellable`。

### 5.4 设置界面

`ui/settings/AppearancePage.kt`：单个「磨砂玻璃」开关 + 「清晰度」滑条（关闭时禁用并标注"已关闭"），
副标题说明关闭后停用模糊/折射/分析；`LabeledSlider` 增加 `enabled` 透传。

### 5.5 边缘质感

**GPU 路径此前只有一块纯色描边，没有任何高光**（`drawOutline(outline, surface.copy(alpha = baseOpacity))`），
所以生产阅读页的边界看起来像"半透明色块"而不是玻璃。

第一版补的是 1.5dp 内沿明暗（alpha 0.10–0.22）。实测观感**仍然偏弱**：1080 宽的面板上只有约 4px，
且亮度不足以在真实背景上形成边界。第二轮按 iOS 26 Liquid Glass / Telegram 的读法重做：

- 抽出共用单元 `ui/common/GlassEdgeTexture.kt`：`DrawScope.drawGlassEdgeTexture(outline, surface, tintAlpha, topSheen)`，
  `LiquidGlassMaterial`（GPU）与 `GlassSurface`（Compose）两条路径调用同一份实现，消除原来的两份复制代码。
- **方向性高光**：一条**竖向**渐变代替一圈等亮度描边。等亮度会读成"白框"，竖向渐变才读成"受光的玻璃截面"。
  > 早期版本用对角渐变；在工具栏这种"宽而矮"的面板上，任何带 x 分量的方向都会退化成水平渐变，
  > 把左半边的四条边一起点亮 —— 实测确实出现了"白框"。改竖向与宽高比无关。
- **内侧衰减带**：同一条轮廓由外向内叠 6 层，半径 5.2dp → 1.2dp，强度 .03→.46 递增，
  累积成连续过渡。这是"厚度"的来源，也是第一版完全缺失的部分。全部裁在面板轮廓内，面板不会被撑大。
- **边界收边**：1dp 细线，浅色主题用暗线（.13）、深色主题用亮线（.20）。
  浅色面板上的白色高光等于白上叠白（实测日间几乎看不到边界），必须有这条线把面板和背景"切"开。
- **底部内阴影**：最后 12% 高度压一条暗带，让面板像"压在内容上"；避开正文区域不影响对比度。
- **透镜折射带加宽**：`LiquidEdgeRefraction` 的 `rim` 8dp→13dp、`strength` 1.5dp→2.6dp。
  边缘的"玻璃感"一半来自位移透镜，原来这组值过于保守。
- 渐变 Brush 按 (宽, 高, 明暗, 清晰度) 缓存 8 组：绘制阶段拿不到 `remember`，
  每帧新建十几个 Skia shader 不该留在滚动热路径上。

**边缘跟随清晰度**（第三轮）

固定宽度会让 0%（最糊）和 100%（无模糊）看着像两块不同的材质。同一块玻璃在不同雾度下，
边缘的物理成因本来就不同，所以宽度、锐度和亮/暗两组权重都随清晰度变化：

| | 0%（最朦胧） | 100%（无模糊） |
| --- | --- | --- |
| 衰减带总宽 | 9.2dp（宽而柔） | 6.0dp（窄而锐） |
| 收边线 | 1.5dp，alpha 高（轮廓实） | 0.9dp，alpha 低（轮廓让位） |
| 镜面权重 | 0.40（不透明板没有可反光的内表面） | 1.20（薄玻璃片的镜面） |
| 轮廓权重（浅色） | 1.30 | 0.55（**不归零**） |

浅色主题用两组渐变叠加：`轮廓`（暗，随磨砂增强）+ `镜面`（白，随清晰度增强）。
这不是可选装饰——实测发现两处"边缘消失"都出在单组渐变上：

1. 0% 清晰度 + 浅色：面板变成近乎不透明的亮板，白高光叠白板 = 不可见，
   边缘差分总量只剩 50% 档的 **7%**（35 vs 369）。加暗轮廓后升到 134。
2. 100% 清晰度 + 浅色：面板几乎全透明，白高光的底是浅色纸面，又是白叠白。
   所以轮廓在任何一档都保留一份（最低 0.55），不随清晰度归零。
- 新增 `LocalGlassEdgeTexture`（默认 true），仅用于预览的"边缘临时禁用"对照；生产恒为 true，
  不参与模糊、保护场、折射或布局计算。

**验收（模拟器 emulator-5580，预览包走生产 `ReaderHardwareScene` 组件）**

| 场景 | 边缘差分峰值 | 带长 | 边缘总量 |
| --- | --- | --- | --- |
| 夜间 0% | 112.5 | 18px ≈ 6.5dp | 759 |
| 夜间 50% | 84.0 | 22px ≈ 8.0dp | 686 |
| 夜间 100% | 69.5 | 18px ≈ 6.5dp | 475 |
| 日间 0% | 34.2 | 13px ≈ 4.7dp | 134 |
| 日间 50% | 41.6 | 15px ≈ 5.5dp | 295 |
| 日间 100% | 75.4 | 18px ≈ 6.5dp | 501 |

测量方法：同一进程内分别截"边缘开"与"边缘关"，按行取绝对差（x=420..660，避开圆角与图标）。
面板背景在两次截图之间完全不变，所以差分只反映边缘本身。三个清晰度档位都在可辨认区间，
没有哪一档消失；日间 0% 最低（不透明板只有轮廓），与物理预期一致。

同一批对照里还核过：夜间上沿逐行亮度 138 → 104 → 76 → 47 连续下降，无台阶、无断裂（4× 放大验证）；
同进程开/关的中心区域 PSNR = **inf**，说明没有向面板内铺任何蒙层。

截图与录屏：
`build/verification/appearance-fix-after/edge-v8/`（六档开/关成对截图、`day-3levels.png` / `night-3levels.png`
三档纵向对照、`prev-vs-now.png` 与上一版固定宽度的对比）、`edge-v4/`（第一轮）与
`edge-video2/edge2-zoom.mp4`（顶栏左上角放大 2 倍，218 帧，中途切边缘开关）。

> 视频顶部偶尔出现的"虚线"是 H.264 对 1px 亮线的压缩伪影；原图逐行亮度是连续的，不是绘制缺口。

### 5.6 验收

- `after/reader-materials-4`：**53 项 PASS**，日/夜各 5 档清晰度（0/25/50/75/100）与「玻璃关闭」截图齐全：
  - 100% 走无 blur 分支（`blurDp = 0`）；
  - 0/25/50/75 保持非零模糊半径；
  - 0% 仍运行材质（`gpuActive = true`），不是"关闭"；
  - 开关关闭后 `gpuActive = false` 且分析采样停止。
- 像素：`DAY-c0` ↔ `DAY-c50` PSNR 26.48dB，`DAY-c50` ↔ `DAY-c100` 21.02dB，
  `glass-off` ↔ `c50` 26.11dB —— 清晰度确实改变背景细节，不是改名的透明度滑条。
- **工具缺陷**：该套件 53 项全 PASS 后、在收尾清理阶段异常退出，未写出 `summary.json`；
  判定以上述日志与截图为准，已如实记录，未用"编译通过"替代。

## 6. 测试与回归

| 项目 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | **434 用例 / 0 失败 / 0 错误**（新增 `GlassAppearanceTest` 8 项 + 仓库迁移 3 项） |
| `:app:lintDebug` | 0 error / 43 warning / 6 hint（与改动前完全一致，无新增） |
| `:app:assembleDebug` / `:app:assemblePreview` | 成功 |
| `npm --prefix tools/browser-checks run test:csp` | 9/9（`reader.js` 未改动，未跑全量浏览器套件） |

新增回归覆盖：关闭时投影为 0 但保留清晰度、清晰度夹取与"开启时不得投影成 0"、旧值 0/正数/缺失/损坏的迁移、
JSON 往返、Compose 与 GPU 两条路径的单调性、100% 无模糊分支、关闭后不透明表面、
旧键不得反向覆盖新记录、关闭后重开仓库仍保留清晰度。

## 7. 证据索引

```
build/verification/appearance-fix-20261026-a/   基线源码快照（HEAD + git diff）
build/verification/appearance-fix-baseline/     基线 APK 哈希 0BE8B4DB…；app-appearance(11 张)、theme、reader-materials(14 张)
build/verification/appearance-fix-after/        修复后 APK 哈希 675B208C…；app-appearance(11 张)、settings-layout(12 张/48 项)、reader-materials-4(五档 + 关闭)
build/verification/appearance-fix-after/recordings/glass-scroll.mp4   20 秒滚动录屏（213 帧）
build/verification/appearance-fix-after/recordings/yavg.log           逐帧亮度原始数据
```

## 8. 明确未验证 / 未完成

1. **边缘质感的观感评价**——只做到"细边 + 上亮下暗 + 顶部窄高光"的实现与像素对照；
   是否达到目标审美需要真机主观确认。`createChainEffect` 的"先模糊再折射"顺序未改动：
   本轮在现有链路与描边上校准已足够看出边界，未引入双采样或额外 RenderEffect。
2. **设置页滑动**——未复现，未改动；真机上"卡手"若仍存在，需要按计划 B.1 用帧轨迹重新定位。
3. **闪烁**——模拟器未复现；真机高对比图片、跨章与延迟加载场景未验证。
4. **性能证据**——没有 Perfetto/FrameTimeline，没有三组 30 秒 ×3 的计时对照；`gfxinfo` 与套件帧统计
   仅为诊断数据，不构成显示帧验收。
5. **materials 套件 summary.json** 未生成（工具收尾异常），以日志 53 项 PASS 与截图为准。
6. **API 26–32 分支**：本机只有 API 37 镜像，低版本降级路径未实测。
7. 偏好迁移只由内存 DAO 回归覆盖，**没有磁盘进程重启实测**。
