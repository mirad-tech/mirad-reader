# 玻璃边缘优化与日夜背景接入计划

日期：2026-11-01（Asia/Shanghai）。项目：`G:\mirad\mirad-server\book`，墨读 MiradReader。

状态：**玻璃边缘优化与背景素材接入均待实施**。本轮按 plan-handoff 核对当前代码、文档、已有截图和官方资料，并按用户要求准备日／夜极简线条背景素材。用户最新明确要求背景接入也写入计划，因此本轮只交付计划与素材，不修改玻璃材质、背景注册表或其他产品源码，不运行产品构建／原生测试。

## 1. 用户目标与验收范围

用户反馈：“现在只有磨砂玻璃效果，没有玻璃边缘效果”，希望参考苹果液态玻璃的边缘。目标是在现有透色和磨砂之上，让边界具有可辨认的透镜折射、连续的圆角反光和适度厚度感。

默认覆盖现有公共玻璃表面：公共顶栏、应用悬浮底栏及选中胶囊、阅读顶／底工具栏，以及复用同一材质的目录、设置、菜单和确认面板。保持布局、点击区域、业务交互、圆角体系与外观控制不变；不顺带重做页面、增加设置项或改变正文排版。

另有一项已纳入同一交接的需求：把本轮生成的 4 张极简植物线条背景加入现有阅读背景选择，日／夜各 2 张，线条可穿过中间。原图已经准备，产品接入按第 8 节实施；不把“中央必须完全空白”的旧素材构图规则带到这批图上。

| 完成标准 | 如何观察 |
| --- | --- |
| 默认清晰度 60 下边缘可辨认 | 日／夜主题、正常显示比例下，直边与圆角均有连续的明暗和折光层次；不能只有放大截图才能看到的细线 |
| API 33+ 有真实的局部背景弯折 | 彩色图块、斜线或书封经过边缘时，边界内出现随背景位置变化的压缩／偏移；中心保持原有磨砂，前景文字和图标位置不变 |
| 反光符合轮廓 | 高光沿圆角转向，有受光与背光差异；平直边、胶囊和大面板协调，无整圈等亮白框、黑色粗框、多圈阶梯、角部断缝或塑料浮雕感 |
| 清晰度语义保持 | 0 仍是最朦胧，60 仍是默认，100 仍零模糊但保留边缘；独立关闭玻璃后停止光学效果。Panel 在 0 档允许实心，因此该档不要求看到背景折射 |
| 动态与稳定性保持 | 滚动、底栏拖动、面板拖动／开合、玻璃关闭再开启、HOME 返回过程中边缘跟随当前内容与几何，无停留残影、跳边、闪烁、自采样或前景变形 |
| 兼容行为诚实 | API 31–32 保留模糊与几何高光，不声称有 RuntimeShader 折射；低版本、禁用或不可用时遵循现有稳定降级 |
| 4 张背景可实际使用 | 日／夜分组正确、缩略图与实际正文背景一致、切换和重启后各自记忆；中心细线保持可见但不压过正文，旧背景与默认选择保持 |

## 2. 参考方向与边界

已读取苹果官方 [Meet Liquid Glass（WWDC25）](https://developer.apple.com/videos/play/wwdc2025/219/) 的文字资料：01:29 起介绍透镜式光线弯折，06:00 起说明材质随尺寸与环境变化，10:31 起说明几何相关的高光与阴影。本任务从中提取“背景弯折、轮廓反光、层次”三项视觉特征，不承诺复制苹果私有渲染实现。

[Apple HIG：Materials](https://developer.apple.com/design/human-interface-guidelines/materials) 将该材质用于内容上方的控制和导航层。这里对应现有工具栏与面板，不把阅读正文、书架内容卡片全部玻璃化，也不引入陀螺仪响应、元素融合／变形、常驻流光或新增交互动效。

实现仍使用项目现有 Compose、RenderNode、RenderEffect 与 AGSL 能力，默认不引入依赖。Android 官方 [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect) 明确 `createChainEffect(outer, inner)` 按 `outer(inner(source))` 执行，`createRuntimeShaderEffect` 自 API 33 提供；[AGSL 使用说明](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl#using_runtimeshader_with_rendereffect) 解释了如何让 shader 读取 RenderNode 输入。只处理材质背景，继续把文字／图标放在效果之外。

## 3. 当前基线与已确认事实

本轮读取的分支为 `main`，HEAD 为 `d199a1d`；工作区 `app/build.gradle.kts` 是 versionCode 27 / versionName 1.3.1。**这不是干净的 HEAD 基线，也不代表已经发布。** 接手时重新核对状态，以届时工作区为准。

本轮开始时有 13 个已跟踪文件修改及 4 个未跟踪文件，涵盖上下栏透色修复、玻璃策略及测试、1.3.1 文档和无关的 `pelican-bicycle.html`。不得 reset、清理或覆盖这些工作；尤其不能为了制作“修改前”截图 checkout 回 HEAD，丢掉已经存在的透色修复。

以下行号对应本轮读取的工作区，后续定位优先用函数名。

| 事实源 | 已确认内容 |
| --- | --- |
| [MiradScaffold.kt](../../app/src/main/java/com/mirad/reader/ui/common/MiradScaffold.kt)，`GlassScene`，约 32–74 行；[PagerChrome.kt](../../app/src/main/java/com/mirad/reader/ui/common/PagerChrome.kt)，`PagerChromeHost`，约 75 行 | 普通页面与应用底栏也进入硬件背景链，不应先假定“只有阅读页有折射” |
| [ReaderHardwareScene.kt](../../app/src/main/java/com/mirad/reader/ui/reader/ReaderHardwareScene.kt)，约 91–96 行；[GlassOverlayHost.kt](../../app/src/main/java/com/mirad/reader/ui/common/GlassOverlayHost.kt)，约 114、193 行 | 阅读场景提供共享硬件背景，浮层继承它；保持内容层／控制层分离 |
| [GlassSurface.kt](../../app/src/main/java/com/mirad/reader/ui/common/GlassSurface.kt)，`glassBackground`，约 203–205、307–341 行 | 有硬件源且 API 31+ 时转入 `hardwareGlassBackground`；另一条 Compose 路径使用 BlurEffect 加公共边缘描边，没有同等的 RuntimeShader 折射 |
| [LiquidGlassMaterial.kt](../../app/src/main/java/com/mirad/reader/ui/common/LiquidGlassMaterial.kt)，`LiquidBackdropRenderer.draw`、`LiquidEdgeRefraction.effect`，约 147–183、208–266 行 | 已有边缘折射。API 33+、边缘开启且轮廓非 `Outline.Generic` 才启用；shader 按圆角距离场法线移动采样，位移权重为 `weight²`。当前效果链是 `edge(blur(content))`；零模糊时仍能单独折射 |
| [LiquidGlassPolicy.kt](../../app/src/main/java/com/mirad/reader/ui/common/LiquidGlassPolicy.kt)，约 87–109 行 | 当前最大位移 **2.1dp**，边缘影响带为 `min(6dp, 短边 × 12%)`；默认 60 档 Toolbar 模糊 8.5dp、Panel 11.9dp。位移和影响带不是用户锁死的验收数值 |
| [GlassEdgeProfile.kt](../../app/src/main/java/com/mirad/reader/ui/common/GlassEdgeProfile.kt)，约 6–48 行；[GlassEdgeTexture.kt](../../app/src/main/java/com/mirad/reader/ui/common/GlassEdgeTexture.kt)，约 26–68、140–175 行 | 当前只有 **1 层**散射带，带宽随清晰度从 3.2dp 到 1.8dp；高光描边宽 1dp 到 0.65dp。高光是按高度渐变的 Brush，没有随圆角法线变化的光照；描边居中且向轮廓内裁切，细线可见宽度约为设定宽度一半 |
| [GlassAppearance.kt](../../app/src/main/java/com/mirad/reader/ui/common/GlassAppearance.kt)，约 10–35 行 | 默认清晰度 60；开关与清晰度分离。`transparencyPercent` 是旧键兼容投影，不应拿它重新定义 UI 清晰度 |
| [HardwareGlassBackdrop.kt](../../app/src/main/java/com/mirad/reader/ui/common/HardwareGlassBackdrop.kt)，`recordMaterial`、诊断摘要，约 281–315 行 | 可记录 `refractionActive`、`baseOpacity`、`blurDp`、材质就绪等状态。状态可证明分支，但不能证明边缘肉眼可见 |
| [SettingsImmersionPreviewActivity.kt](../../app/src/preview/java/com/mirad/reader/preview/SettingsImmersionPreviewActivity.kt)，约 73、136、191、239 行 | 有 `hardware=true` 生产组件预览，以及 `edge` 开关；可做同包、同场景的边缘开／关对照，无须新增产品设置 |

主入口位置：[ScreenHeader.kt](../../app/src/main/java/com/mirad/reader/ui/common/ScreenHeader.kt) 的 `GlassSurface`、[AppNavigationBar.kt](../../app/src/main/java/com/mirad/reader/ui/AppNavigationBar.kt) 的底座与选中胶囊、[ReaderChrome.kt](../../app/src/main/java/com/mirad/reader/ui/reader/ReaderChrome.kt) 的上下工具栏。优先改共享材质，不在各入口复制一组边缘参数。

### 历史资料的使用限制

- [阅读玻璃说明](../reader-liquid-glass.md) 中“3.2dp 折射／13dp 或 20% 影响带／六层散射”等历史文字与当前源码不一致，不能直接照抄或恢复。当前事实以以上代码为准。
- [上下栏透色修复报告](../reports/glass-bars-white-tint-fix-20261030.md) 记录了取消 66%／78% 整栏底色下限、区分栏与面板的结果。这些改动已在当前源码核对，应保留。
- 本轮看过历史对比图 `build/verification/glass-bars-fix-20261030/zooms/compare-header.png` 与 `zooms/compare-bottom.png`：可看到透色增强，边界仍主要表现为细轮廓与阴影。它们只辅助理解既有视觉，不能证明用户当前安装包、当前运行状态或本次优化效果。
- 本轮确认存在 `build/verification/glass-bars-final-20261030/session.ps1`，可供接手者核对跨命令持有隔离实例的方式；不能直接复用它的旧 RunId 或运行中实例。本机忽略路径以纯文本记录。

## 4. 合理推断与待验证项

当前证据支持以下诊断方向，**尚不足以把任何一项定为此次反馈的唯一根因**：

1. 现有距离场折射很窄，位移较小，且输入已经模糊；默认档位下结构可能先被抹平，再发生不明显的偏移。需比较 60 与 100 档，结合图形背景和边缘开／关图确认。
2. 细高光在轮廓裁切后更窄，且只按高度渐变，可能难以表达弧面厚度；需看正常比例下的直边、四角和胶囊端部。
3. 某场景也可能进入降级、未就绪或不同材质路径。先记录 API、硬件画布、背景源、轮廓和实际 guard/fallback 状态，再决定是否修接入问题；不能只加大常量。

尚未知用户当前 APK 与设备分支，也没有本轮同场景的新截图、动态视频和性能结果。没有这些信息不阻碍准备计划；实施时先用项目隔离环境建立可复现基线，不要求操作用户手机。

## 5. 实施顺序

### 第一步：固定当前基线并验证问题落点

- 阅读当前 `AGENTS.md` 与 [隔离验证说明](../../tools/glass-preview/README.md)，重新检查 diff。记录本轮将改文件的原始状态，保留所有在先改动。
- 在修改玻璃渲染代码前，从当前工作区构建基线 Debug／Preview，固定 APK 路径和 SHA-256；后续构建不能覆盖唯一的基线副本，也不借历史 APK 冒充当前基线。若先完成第 8 节背景接入，玻璃前后对照必须都包含同一批背景。
- 在全新项目隔离实例里记录普通页与阅读页日／夜 60 档，另选代表性圆角控件检查 100 档；固定背景、滚动位置、尺寸、字体和系统设置，采集边缘开／关截图及诊断。
- 如果实际未进入预期硬件分支，先修复经证实的接入／恢复问题再评估质感。若分支正常，直接进入共享材质优化，不重构整个背景架构。

### 第二步：让共享边缘呈现连续曲面

- 以 `LiquidGlassMaterial.kt`、`LiquidGlassPolicy.kt`、`GlassEdgeProfile.kt`、`GlassEdgeTexture.kt` 为主要修改面；分别控制折射带、反光与暗部过渡，保持中央底色和原有模糊语义。
- 沿现有轮廓距离场构造连续的边缘厚度／位移曲线，让效果在窄边缘内变化并平滑归零至中心。小胶囊按短边限幅，圆角与直边连续，不能因调大位移引出边界拉丝、越界采样或内容整体漂移。
- 高光跟随轮廓方向形成受光面和背光面，结合轻微内侧暗部形成厚度；避免只把整圈白色描边加粗。可先用固定光向与现有几何／背景信息校准，不新增传感器、时钟驱动的流光或每帧 CPU 回读。
- 先在现有单材质层内验证效果。若已证实默认模糊会使折射消失，才评估让边缘窄带保留较清晰背景采样、与中央磨砂平滑混合；仅扩大位移或交换 effect 顺序都不是自动成立的解法。新增 GPU 通道必须有对比图和开销依据，不扩大为全屏额外复制。
- 任何采样位移、影响带或图层变化，连同 padding、裁切、坐标、圆角、缓存失效一起校核；清晰度 100 的零 blur 分支必须保留有效折射输入。前景始终最后绘制。
- 数值由正常比例截图与视频确定，不把历史 3.2dp／13dp 当目标，也不机械保留现有“薄到不明显”的常量。默认先满足 60 档，再检查 0／100 与小控件，避免只在 100 档好看。

### 第三步：覆盖公共入口及已有降级

- 在普通页面、阅读页面、公共浮层中复核共享效果；旧 Compose 路径保留一致的边缘绘制风格，未进入硬件源的入口按实际用途记录，不为本任务全盘迁移。
- 特别看底栏底座与选中胶囊的叠加，避免双层亮圈／暗圈。保留现有选中表达和交互，不借苹果参考重做导航结构。
- API 31–32 用现有模糊与几何光影表达边缘；API 26–30、禁用、省电／强制降级继续按项目策略运行。不得绕过 API 守卫或把降级截图当完整折射验收。
- 如需补测量能力，只放 preview／验证脚本。现有 `edge` 开关同时控制描边和折射，不能独立证明两者；若像素对照不能分清，增加仅用于预览的单独对照能力，不进入产品 UI 或持久化配置。

### 第四步：同步必要测试和文档

- 按实际变更更新 [GlassEdgeProfileTest.kt](../../app/src/test/java/com/mirad/reader/ui/common/GlassEdgeProfileTest.kt) 与 [LiquidGlassPolicyTest.kt](../../app/src/test/java/com/mirad/reader/ui/common/LiquidGlassPolicyTest.kt)。旧测试中的“永远极薄”精确数值可以随新视觉目标调整，但不能删除小控件限幅、合法透明度、零模糊采样留白等必要约束。
- 测试应验证新增曲线的边界内连续性、有限值、小控件限制和采样范围等真实风险；不要堆砌只重复常量的测试。Android shader 的编译和最终像素效果必须在隔离实例确认，JVM 通过不能代替。
- 更新 `docs/reader-liquid-glass.md` 中此次相关的现状说明，区分当前值和历史记录；不顺手归档无关文件或改版本号。

## 6. 必须保持的约束

- **底色不可回退**：上下栏 `liquidBarOpacity` 的 0 档日 .46／夜 .52、60 档日 .1408／夜 .1672、100 档日 .08／夜 .10 保持；面板仍走 `liquidPanelOpacity`。不恢复 `minimumTintAlpha`、整栏厚白底、局部文字保护色块或面板 80 档模糊封顶。
- **开关与数据不可变**：不新增偏好或修改迁移／写回链，不改 Room、阅读位置、分页、`windowedScroll`、前景命中和音量键复位。不以“更可读”为由悄悄增加 100 档模糊。
- **背景来源不可污染**：只采样内容层，不把玻璃及其前景采入自身；不把低分辨率分析图当显示背景，不引入 WebView `onDraw` 内容通知或持续空转的刷新循环。
- **本计划授权**：玻璃边缘与背景接入当前只做计划；本轮可生成并保存待接入图片，但不写入产品 assets、不修改注册表。后续收到执行指令后，可按计划做范围内最小代码修改、构建和非破坏性隔离验证。未授权提交、push、tag、release、签名发布包、部署或操作用户手机。
- **原生环境**：每轮新 RunId，先 Create 再 Start，独立 AVD 和私有 ADB 端口；不使用默认 5037、已有 AVD 或旧实例。默认端口占用就另选空闲端口，跨命令测试按项目方式由隐藏后台任务持有，外层 `finally` 调用 `-Action Stop`。保留截图、视频、日志和 `instance.json`，清理只由工具在核验归属后处理本轮临时实例。
- 如执行中需要电脑／浏览器界面操作，按用户全局规则委派专用 Computer Use 子代理并复用；本计划不自动创建代理或发起执行。

## 7. 验证与交付证据（后续执行者运行）

### 直接检查

在最终修改后运行，并查看真实失败数、跳过数及日志；若参数更新后再次改 shader 或 profile，重做受影响视觉检查，不沿用旧包结论。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assemblePreview --console=plain
python tools/check-doc-links.py
```

`lintDebug` 有历史告警，报告本次 error／warning／hint，不称“lint 全绿”。默认不改 `reader.js`；若确有必要改到它，额外运行 `npm --prefix tools/browser-checks test`。

当前维护的 GPU 预览入口是 `preview` 的 `SettingsImmersionPreviewActivity` + `hardware=true`；不要因为旧目录仍存在而启用退役的独立 `gpuPreview` 原型。按验证 README 使用 `Test-ReaderHardware.ps1` 的 `materials`、`fallback`、`lifecycle`；`core` 覆盖菜单、面板、拖动和音量键等共享交互。若修改几何／insets，补 `layout`；若触及 COVER 或快照生命周期，补 `Test-ReaderHardwareCover.ps1`。既有套件不直接证明边缘质量，必须同时完成下表。

### 视觉与动态对照

| 组 | 最小覆盖 | 必留证据 |
| --- | --- | --- |
| 产品默认观感 | 日／夜 60：公共顶栏、底栏及胶囊、阅读顶／底栏、一个大面板；确认菜单／确认框复用正常 | 当前工作区基线与最终包的全屏图及同坐标边缘裁图；正常比例先验收，放大图只解释细节 |
| 边缘光学 | 同一几何下，均匀明／暗底、彩色图块或斜线、真实封面／正文图片 | 最终包边缘开／关对照；背景结构经过边缘时的连续短视频，证明局部形变，不能只比较平均亮度 |
| 清晰度边界 | 代表性工具栏、小胶囊和面板的 0／60／100；100 单独检查零模糊折射；显式关闭→开启 | 带开关与档位的截图、状态 JSON；保留中心区域和前景参照，检查新效果集中在边缘 |
| 状态变化 | 内容滚动、底栏和面板拖动、开合、HOME 返回、换日夜 | 视频与状态／logcat，检查边缘坐标、当前内容、恢复及静止后停止工作 |
| API 分层 | 可用的 API 33+ 真正 shader 路径，API 31–32 无 shader 路径，低 API 降级 | 每项记录实际 API、镜像和结果；镜像缺失标 `NOT_RUN`／`BLOCKED`，高 API 强制降级不冒充旧系统实测 |

使用同一隔离环境、分辨率、密度、内容、滚动位置、清晰度和光暗主题比较前后；固定 APK SHA-256。优先复用生产组件夹具；若缺少能识别折射的背景，只增加 preview 内的确定性图块／斜线，仍调用生产材质，不能造一个独立“看起来正确”的演示 shader。

最低动态检查同时比较基线／最终包的帧耗时或 `gfxinfo` 摘要，保留同长度、同动作样本。检查 `cpuDisplayCaptures=0`、静止后的刷新／分析停止、无 shader 编译异常和无持续材质重建；模拟器数据只用于同环境回归，不宣称目标手机帧率达标，也不为补性能证据操作手机。

证据目录在执行时用 `Join-Path 'build/verification' ('liquid-glass-edge-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))` 生成，拒绝覆盖已有批次；记录基线／最终 APK、参数、截图、视频、结果 JSON 和清理状态。当前尚未创建该目录或产出玻璃边缘优化的新验证结果。

### 交付与结束标准

交付最小源码差异、实际通过／失败／未运行项、同场景前后图、边缘开／关图、动态视频、性能回归摘要及隔离实例 Stop 结果；说明最终参数为何能呈现目标边缘，以及低 API／降级仍有哪些差异。验证结果可集中在本计划末尾或一份对应实施记录中，不增加多份重复入口。

不得只以构建成功、单测通过、`refractionActive=true` 或几张选取过的放大图宣布完成。达到第 1 节的正常比例视觉与动态标准、必要回归完成、实例清理且证据留存后结束；缺失项目如实列出，不继续做需求外重构或发布。

## 8. 日／夜极简线条背景：素材已生成，接入待执行

### 已交付素材

本轮使用内置 `image_gen`，先生成两张日间原图，再以各自原图为编辑输入生成相应夜间版，保留枝叶构图、只调整明暗配色。四张都已看图并用本机图像解码器读取成功，均为 **1024 × 1536、RGB、不透明 PNG**；项目副本与工具原文件的 SHA-256 一致。没有把图片复制进产品 `app/src/main/assets/`。

本机交接目录：`G:\mirad\mirad-server\book\artifacts\backgrounds\line-art-20261101\`。该目录受 `/artifacts/` 忽略规则约束，**不会随 Git 克隆自动出现**；跨机器交接必须一并复制整个目录，并对照 `checks.json` 核验。缺少原图时明确报告素材缺失，不用占位图或自行重新生成的不同画面冒充这批素材。

| 原图文件 | 建议显示名 | 配色与构图 | 拟用 key／URI | 拟用产品素材 | isDark／suggestedTheme |
| --- | --- | --- | --- | --- | --- |
| `leaf-day.png` | 行叶·昼 | 暖纸色、单枝疏叶细线穿过中央 | `line-leaf-day`／`builtin:line-leaf-day` | `backgrounds/line-leaf-day.jpg` | false／`ThemeKey.PAPER` |
| `leaf-night.png` | 行叶·夜 | 深墨青、与日间对应的低对比叶线 | `line-leaf-night`／`builtin:line-leaf-night` | `backgrounds/line-leaf-night.jpg` | true／`ThemeKey.DARK` |
| `reed-day.png` | 疏苇·昼 | 淡雾绿、细长疏苇曲线经过中央 | `line-reed-day`／`builtin:line-reed-day` | `backgrounds/line-reed-day.jpg` | false／`ThemeKey.GREEN` |
| `reed-night.png` | 疏苇·夜 | 炭灰、与日间对应的低对比苇线 | `line-reed-night`／`builtin:line-reed-night` | `backgrounds/line-reed-night.jpg` | true／`ThemeKey.DARK` |

同目录 `prompts.json` 保存四张的完整生成／编辑提示词、配对关系、建议 key 与素材路径；`checks.json` 保存尺寸、编码、字节数、完整 SHA-256 和复制一致性。四张源图合计 6,233,720 字节；这是交接原图大小，不是最终 APK 增量。

### 已确认的现有接入链

- [BuiltinBackgrounds.kt](../../app/src/main/java/com/mirad/reader/core/model/BuiltinBackgrounds.kt) 是注册表：每项包含 key、label、assetName、isDark、suggestedTheme；现有背景使用 `builtin:` URI。
- [AppAppearanceState.kt](../../app/src/main/java/com/mirad/reader/ui/common/AppAppearanceState.kt) 的 `readingBackgroundChoices`（约 144 行）按 `isDark` 过滤；[ReaderStyleSections.kt](../../app/src/main/java/com/mirad/reader/ui/common/ReaderStyleSections.kt) 的 `ImageBackgroundControls`／`BackgroundPresetTile` 从注册表加载缩略图并更新 `backgroundImageUri`。预期不需要新入口或额外 UI 列表。
- [AppAppearance.kt](../../app/src/main/java/com/mirad/reader/core/model/AppAppearance.kt) 的 `ReadingStyle.normalized` 会清除与日／夜组不匹配的内置图片选择，分组必须准确。
- [ReaderSessionStyle.kt](../../app/src/main/java/com/mirad/reader/reader/ReaderSessionStyle.kt) 解析内置 URI；[ReaderResourceProvider.kt](../../app/src/main/java/com/mirad/reader/reader/ReaderResourceProvider.kt) 用 `bg` 路由流式返回 assets，已支持 JPEG／PNG／WebP。[ReaderCss.kt](../../app/src/main/java/com/mirad/reader/core/reader/ReaderCss.kt) 当前使用 `background-size: cover` 与居中定位，因此必须验证窄竖屏和横屏的真实裁切。

### 最小实施步骤

1. 先核验原图与 `checks.json`，再制作产品编码副本，保留交接 PNG。可按[既有素材说明](../../app/src/main/assets/backgrounds/README.txt)先尝试 768 × 1152 的 JPEG、quality 86、无色度抽样；这只是现有打包基准，细线断裂、暗色条带或阅读效果不合格时保留更高分辨率／编码质量。只做尺寸与编码适配，不另添纹理、重绘植物或把中央线条删掉。
2. 把最终四张 JPEG 放入 `app/src/main/assets/backgrounds/`，在 `BuiltinBackgrounds` 增加四个独立 key 与上述记录，检查 key／路径不重名。复用现有明暗分组与 `ReadingConfigEditor` 保存路径，不添加偏好、迁移、外部下载或运行时生成逻辑。
3. 只追加可选背景，不替换、删除旧素材，也不把当前用户的日／夜背景自动换成新图。更新素材 `README.txt`，记录这批来源、导出尺寸与格式。
4. 这项可先作为独立增量完成，再做玻璃边缘；两者交付和验证状态分别记录。新低对比背景可用于联合观感检查，但不能替代第 7 节用于观察折射的彩色图块／斜线测试背景。

### 接入后的验证与边界

- 先运行现有 `ReadingStyleTest`（含注册素材文件存在性）、`AppAppearanceUiPolicyTest`（日夜分组）、`ReaderAppearanceBindingTest`（阅读外观刷新），再纳入第 7 节最终 JVM／lint／构建。查看素材存在性用例是否实际运行，不能忽略其 `assumeTrue` 跳过。纯追加注册项不必为每个常量再写一套重复测试。
- 对最终编码副本检查实际可解码、尺寸、文件体积与 APK 内打包路径；不能只验证交接 PNG。检查现有日夜素材均仍存在且 URI 可解析。
- 仅在新建项目隔离模拟器中，从应用外观设置与阅读设置两个现有入口选择这四张图，确认每组只出现对应的 2 张新增图，缩略图、选中态及实际背景一致；检查 100% 和一个中间不透明度、切换日夜、离开阅读页再进入、后台／重启后的分别记忆。读写仍走原有共享编辑链。
- 保留默认字号及大字号下的正文截图，覆盖窄竖屏 `cover` 裁切和一个横屏状态；检查细线穿过中心时文字仍可辨认、边缘没有拉伸／接缝。线条穿过中间是用户允许且本批已采用的构图，不应机械套用旧素材“仅边角、中央留白”的限制。
- 本轮完成的只有素材生成、逐图查看、解码与复制校验；**尚未接入、未检查实际 APK／原生阅读效果**。若后续环境不足，分别标明素材接入、源码检查与原生验收的实际状态。
