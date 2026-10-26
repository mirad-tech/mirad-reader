# 阅读外观与交互修复计划

日期：2026-10-26。供下一位实施 agent 使用。本轮交付诊断、修复方案与验收要求，尚未修改产品代码，也尚未执行本计划的模拟器验收。

目标：解决主题与背景选项互相覆盖、设置二级页滑动卡手、阅读顶栏间距不足、玻璃下文字滑动闪烁，以及玻璃缺乏清晰度控制和边缘质感这五项问题。完成标准是实际操作、动态画面和性能记录共同通过，不能用编译成功或静态截图替代。

## 1. 当前基线与实施边界

- 仓库：`G:\mirad\mirad-server\book`；本次读取时 HEAD 为 `458f9c9cb388e6160e163e9789e5b2a15a952e9d`。
- 工作区已有其他任务的未提交修改，涉及导入、资源访问、TTS、`ReaderChrome.kt`、`ReaderScreen.kt`、测试与文档。接手先执行 `git status --short` 和相关 `git diff`，以最新文件为准，保留已有工作。尤其保留底栏“自动阅读”和更多菜单内朗读的现有调整。
- 仅处理本计划五项问题。无需全仓重构、升级依赖、修改 Room 表结构或扩展功能。优先复用现有 Compose、RenderNode、RenderEffect、AGSL 和预览工具。
- 遵守 `AGENTS.md`：不提交、推送、发版、打 tag 或制作发行签名包；不操作真实手机，不删除历史 APK。测试限自建隔离模拟器和独立预览包。
- 保持 `app_appearance_v1` 的日夜独立样式、旧阅读配置双写、`ReadingConfigEditor` 的共享锁、保存防抖与生命周期 flush；不得重置用户图片或阅读位置。
- 阅读内容层和控制层继续分离。玻璃不得采样自身，模糊与折射不得作用于按钮、标题和正文前景。保持音量键遮挡状态的释放复位。
- `reader.js` 的语义变化信号仍至多每 500ms 合并一次，不能用 WebView `onDraw` 制造事件，也不能为了玻璃修改正文 DOM 或阅读位置。该限频与 GPU 显示刷新是两回事。

## 2. 当前证据与尚待验证的原因

以下行号对应本次读取的工作区，实施前按函数名重新定位。

| 问题 | 当前源码证据 | 结论边界 |
| --- | --- | --- |
| 选颜色后又被图片盖住 | `ui/common/ReaderStyleSections.kt:247–351` 同时展示主题色和背景类型；主题点击只改 `theme`，图片点击改 `backgroundType/imageUri`。`core/reader/ReaderCss.kt:57–61` 使用 `1 - backgroundImageAlpha` 生成底色蒙层 | 已确认设置表达不清。图片不透明度 100% 时底色不可见；主题仍会影响文字色，不能概括为整个主题完全失效 |
| 两个入口行为不一致 | `ui/settings/reading/QuickAdjustSection.kt:95` 的快捷色块同时设置 `backgroundType = COLOR`，详细主题页仅改主题 | 已确认相似的选色操作有不同背景切换语义 |
| 设置页卡手 | `SettingsScreen.kt:180–186` 为 `glassBackdropSource + verticalScroll`；阅读设置在 `ReaderSheets.kt:311–319` 内滚动，外层 `ReaderOverlayHost.kt:307–345` 还有可拖动 BottomSheet | 可能涉及嵌套滚动、滑块起手区域或绘制开销，必须用手势与帧记录区分。`MiradProgress.kt:38` 已使用 Material3 Slider，不能未经复现就指责自定义 Slider 吞事件 |
| 顶栏变宽后仍挤 | `ReaderChrome.kt:94–123` 外边距只有 4dp、行内边距 8dp；返回按钮、文字列、右侧按钮之间没有显式间距，标题与章节文字也无间距 | 布局可直接调整，但最终尺寸须在窄屏、大字体、长标题上确认 |
| 滑动时玻璃下文字闪烁 | 阅读页走 `ReaderBackdropHost + HardwareGlassBackdrop + LiquidGlassMaterial`；普通设置页走 `GlassSurface.kt` 的共享玻璃路径，后者含快照读回与保护场更新 | 这是两条不同渲染路径。实际是掉帧、背景停跳、前景亮度变化还是降级帧交替，尚未模拟器复现，不能现在指定单一根因 |
| 滑条不能调清晰度 | `AppearancePage.kt:116–129` 只有“玻璃透明度”；`LiquidGlassPolicy.kt:17–25` 调的是底色 alpha，模糊固定为 Toolbar 10dp、Panel 14dp | 已确认透明度和模糊清晰度没有解耦 |
| 边缘不像玻璃 | `LiquidGlassMaterial.kt:179–237` 已有 API 33+ 边缘折射，边缘带为 8dp、位移为 1.5dp；当前 GPU 材质最后只合成底色和前景，没有独立边缘高光绘制 | 不能把“没有边缘感”简单写成“没有 shader”。需要改善可见结果，而非重复增加一套渲染架构 |

另一个必须核实的偏差：`docs/reader-liquid-glass.md` 描述了 GPU 局部前景保护，但当前 `liquidProtectionOpacity` 的生产源码调用搜索只找到定义，GPU 材质记录的 protection 值为 0。不能把旧文档描述当作正在运行的保护能力，也不能把该未接入函数认定为 GPU 闪烁原因。实施时核对当前调用链，单独验证文字可读性。

## 3. 修复后的交互约定

### 3.1 主题与背景只保留一个明确的背景来源

保留“日间样式 / 夜间样式”编辑分组，先选择“纯色背景 / 图片背景”，再显示当前分支的设置。

- **纯色背景**：显示现有配色预设与自定义颜色。点选后立即生效，并明确设置 `backgroundType = COLOR`；保持与阅读面板快捷色块一致。
- **图片背景**：显示当前图片预览、内置图片、本机图片选择和“图片不透明度”。普通纯色背景选项不再并排表现为同时生效。
- 现有图片混色能力予以保留：当图片不透明度小于 100% 时，可在按需展开的“衬底配色”中复用现有主题选择；这里修改的是衬底及其关联文字配色，不切回纯色。100% 时隐藏这组衬底选项，避免出现视觉上无效的底色操作。
- 图片模式沿用已保存的日/夜配色与文字色；不得为了让底色显现而强制降低图片不透明度，或静默更改全局日夜模式。对高对比度图片验证可读性，必要时使用稳定的最小保护。
- 切换背景类型保留此前颜色、图片 URI 和图片不透明度；切回时恢复上次选择。选择器取消不修改状态；图片失效应稳定回退并提供必要的恢复入口。
- 预览使用实际选中的图片、图片不透明度、底色与文字颜色，而非当前仅有的纯色条。图片异步解码并缓存，不能随每次重组/滑动重新解码。
- 两个设置入口共用相同组件和状态变换。夜间组编辑不污染日间组；当前没有启用的组继续用现有必要状态提示表达。

验收：任一可见背景选项都能解释当前效果；不存在“选中纯色但实际仍为图片”的含混状态。纯色→图片→纯色、日→夜→日、返回再进入，状态与真实阅读画面一致。

### 3.2 独立开关与清晰度滑条

用户可见控制收敛为：

| 控制 | 行为 |
| --- | --- |
| 磨砂玻璃 | 独立开关。关闭后使用稳定普通主题表面，停止玻璃专属模糊、折射和分析工作 |
| 清晰度 | 0–100%，从“朦胧”到“清晰”；越往右，背景纹理越清楚。关闭玻璃时禁用该滑条并保留上次值 |

清晰度 0% 表示最朦胧，100% 表示最清晰；两端都不承担关闭玻璃的含义。再次开启时恢复上次清晰度，不能因为关了一次就丢值。标题、按钮、图标始终清晰。

内部使用独立的 `enabled` 和 `clarityPercent`（名称可按现有惯例调整）。清晰度必须实际改变模糊半径，必要时协调较薄的底色；不能只给旧透明度滑条改名。工具栏和面板可保留不同曲线，但方向和端点语义一致。先沿用当前中档观感作为校准点，再用 0/25/50/75/100 五档对照确定范围，不把未经模拟器校准的半径写成最终标准。

底色保护以可读性为约束，避免通过大面积厚白/黑蒙层掩盖“清晰度无效”。边缘强度与清晰度分开计算，最清晰档仍能看出边界。若极限清晰度需零模糊，应明确走无 blur effect 分支，不能向要求有效半径的路径盲传 0。

数据兼容方案：沿用偏好表，在新版本化记录中保存开关和清晰度，不改 Room schema。仅在新记录不存在时从 `ui_glass_transparency_percent` 迁移：旧值 0 → 关闭，旧值大于 0 → 开启；有效旧百分比可作为清晰度初值，但须承认旧值控制遮罩、新值控制清晰度，视觉不保证严格相等。旧值 0 的清晰度采用经校准的默认值。新记录损坏时回退默认，不重复旧值迁移；缺字段、越界、异常值分别有确定回退。

为旧 APK 回退保留兼容键：关闭写 0，开启按明确且有测试的投影写 1–100；新版本始终以新记录为事实源，不能从这个投影反向覆盖新清晰度。新旧记录更新须避免部分成功与旧代次覆盖：沿用 repository 写入串行、防抖、pending 优先及失败重试，必要的成组写入放在事务与 `NonCancellable` 保护内；ON_STOP/onDispose 执行 flush。不得另起直接写 DAO 的 UI 通道。

## 4. 分阶段实施

### A. 先建立实际复现与可比较基线

先从当前工作区构建并固定基线预览 APK、SHA-256 与源码状态，随后才修改。所有前后对照固定设备、窗口、字体、书籍夹具、页面位置、日夜主题和手势序列。

对每个可滚动设置二级页，从空白区、标签/选项区、滑块轨道及滑块拇指处分别向上和向下拖动，记录页面偏移、滑块值、Sheet 位置。对“更多→阅读设置”和应用设置两个入口都测试。

阅读页分别在滚动和分页模式下，显示顶栏/底栏/设置面板，记录高对比文字和图片背景经过玻璃区域的动态画面。标记闪烁出现在背景字形、前景文字、边缘还是整块表面，以及是否伴随布局变化。

### B. 修复设置滑动的实际原因

1. 先判断输入问题：手指移动但页面偏移不变，滑块数值误变或 Sheet 被拖动，属于手势分配；页面偏移持续变化但画面停顿，则继续检查渲染。
2. 标准 Slider 优先保留。确有手势冲突时，修正触摸范围和方向判定；垂直意图交给页面，水平意图调数值，保留点按定位、键盘与无障碍。不要在父层无条件消费所有方向的事件。
3. 阅读设置面板保留一个正文纵向滚动容器。明确顶部/底部与 BottomSheet 的交接，拖柄继续可操作。`fillHeight = true` 已使当前 Sheet 外层避开一层滚动，不应仅见到多个 `verticalScroll` 就判断重复嵌套；检查实际路由和 Modifier 链。
4. 有真实掉帧时，记录 main/RenderThread/GPU 轨迹，定位玻璃录制、CPU 读回、图片解码、保护计算、重组或布局热区。按证据缩小范围、缓存或移出主线程。只有内容量确实导致问题时才把相关长列表改为惰性列表，不全局替换。
5. 应用设置和阅读器的玻璃分支分开处理；避免把阅读 GPU 的“分析快照不用于显示”误套到普通页面。

Compose 官方建议优先使用带手势及无障碍能力的现成组件；自定义方向手势需等待相应 touch slop 再接管，见 [手势说明](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)。

### C. 整理阅读顶栏内部间距

维持当前返回、标题/章节信息、书签、更多四组内容以及现有外部宽度。主要调整内部布局，不再单纯加高或加宽背景。

- 按钮触摸目标至少 48dp；返回按钮与文字列、文字列与右侧操作组建议留 8–12dp，按现有 spacing token 取值。
- 标题与章节文字建议增加 2–4dp 垂直间距；文字列占剩余宽度，两个文本分别单行省略。空副标题不占一行。
- 内边距建议以 12–16dp 为初始值，在 320dp 和 1.6 字体倍率下确认可用后定稿；不得靠缩小字体或缩小触摸目标腾空间。
- 保持安全区、横屏刘海、RTL、状态栏行为；返回、书签、更多菜单都必须真实可点击，不能与正文点按翻页穿透。

### D. 解决动态闪烁，再调整玻璃材质

先增加只存在于预览/调试的必要记录，关联内容更新、显示层录制、分析结果代次、材质可用状态、fallback 原因和布局坐标。现有 `recordGuard` 等诊断可复用，禁止逐帧生产日志。

根据证据修复，避免预设结论：

- **内容停跳或旧帧**：检查 WebView 子层失效是否传到内容 RenderNode，采样坐标是否一致，旧尺寸/旧代次是否被丢弃，材质缓存是否在生命周期切换后恢复。不得把低频分析图当作显示源。
- **整块忽明忽暗**：检查 `contentReady`、坐标/节点可用性与 fallback 交替。区分真正不可用与瞬时状态，不可用时提供稳定过渡；尺寸、主题或场景已变化时不能保留无效旧画面冒充稳定。
- **文字/底色亮度脉动**：确认是普通 `GlassSurface` 的保护场变化还是 GPU 路径；仅对实际在用的机制做迟滞/平滑/局部稳定化。参数更新不得逐帧触发大范围重组、bitmap 创建或 effect 重建。
- **拖动重影或边缘跳跃**：检查屏幕坐标与窗口坐标、padding 和裁切同步，避免错帧或非整数位移的突变。
- **前景本身变糊**：修正绘制分层。前景始终最后独立绘制；不能对整个包含文字的父 View 直接套 blur/refraction。

保留内容语义事件的限频。GPU 显示应跟随原生内容失效；降低模糊、把采样改成无限循环、停止正文滚动或冻结整个玻璃背景均不能作为修复。

### E. 清晰度与边缘质感

接入第 3.2 节的新语义，同时覆盖阅读 GPU 路径、普通 Compose 玻璃路径和降级路径；不得让同一清晰度在设置页与阅读页呈现反向含义。

在现有材质上逐项增加/校准：较薄的底色、局部边缘折射、窄幅方向性高光、轻微内沿明暗变化。保持面板中心稳定清晰，不能用一圈粗白边或整块发白代替玻璃质感，也不要求新增高成本色散动画。

当前 `createChainEffect(refraction, blur)` 的语义为先模糊，再对该结果折射；这可能让细节本就不足的边缘更不明显，属于视觉待验证项。先在现有曲线与描边上校准，仍不足时才评估仅在边缘带混合较清晰的同源内容，比较新增采样开销，不能先扩展成双全屏渲染。链路顺序依据 [RenderEffect API](https://developer.android.com/reference/android/graphics/RenderEffect)；效果作用范围参考 [AGSL 与 RenderEffect](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)。

Telegram 仅作为“中心通透、边界可辨、动态稳定”的视觉方向，不承诺复刻其私有实现或未经提供的具体页面。以本项目相同背景的前后截图和录屏评价实际结果。

API 31–32 保留 blur 与轻量边缘绘制，API 33+ 使用现有折射能力；API 26–30、硬件不可用、省电模式使用稳定且可读的降级表面。必要时简短标明降级，不能显示一个假装有效的清晰度控制。用户偏好与设备是否可用分离保存，设备降级不能把用户开关永久写成关闭。

## 5. 必须执行的 Android 模拟器验收

### 5.1 环境与证据约束

- 先读 `tools/glass-preview/README.md` 和驱动参数，使用该仓库的独立 AVD、私有 ADB 与 `com.mirad.reader.preview`。每个会话使用新的 RunId，每个套件使用新的证据目录。
- 使用 API 33+ 且实际硬件渲染可用的模拟器验证完整材质。仓库驱动文档目前使用 API 37，执行前检查实际镜像与运行状态，不能仅凭 SDK 版本认定 GPU 生效。
- 优先复用生产 `SettingsContent`、`ReaderSettingsPanel`、`ReaderHardwareScene` 和 WebView assets 的集成预览。独立 shader demo 或 `gpuPreview` 只能辅助诊断，不能替代生产组件验收。
- 预览继续遵守内存数据夹具、无业务初始化的隔离边界。当前内存 DAO 重开测试不等于真实进程重启持久化；保存顺序、失败重试与迁移由 repository 回归覆盖，若没有磁盘重启实测，报告必须注明该缺口。
- 不操作默认 ADB 下的真实手机。启动后台进程应隐藏窗口并及时返回；结束时用驱动 Stop 终止本轮模拟器及私有 ADB，确认相应端口释放，保留证据。

### 5.2 验收矩阵

| 项目 | 必测场景 | 通过条件 |
| --- | --- | --- |
| 背景互斥与记忆 | 两个设置入口；日/夜；预设/自定义纯色；内置/本机图片；图片不透明度 100% 与较低值；取消选择与图片失效；返回重进 | 选项、预览、阅读正文一致；隐藏分支不误导；切回恢复原值，日夜不串组 |
| 设置页起手 | 每个可滚动二级页，空白/标签/选项/滑块轨道/拇指处，各向上和向下至少 5 次；再做水平调值、轻斜拖与边界拖动 | 垂直滚动可重复触发，无误改数值；水平调值正常；Sheet 无中途抢手或正文穿透；本来不可滚动的短页标为不适用 |
| 顶栏 | 320dp、常规手机、横屏；字体倍率 1.0/1.3/1.6；长书名、长章节名、空副标题；日夜与 RTL | 间距清楚，无重叠、裁切或触摸区冲突；正确省略；所有操作可用 |
| 开关与滑条 | 开→关→开至少 5 次；0/25/50/75/100 五档；拖动后立即返回、切后台再回来 | 关闭彻底停用材质且保留值；清晰度方向单调，两端不等于开关；恢复无旧值闪回；保存失败可恢复 |
| 动态稳定 | 高对比文字、纯色与图片背景；慢拖、快甩、停止；顶/底栏与设置面板；滚动与分页；跨章、延迟图片加载 | 前景始终清楚；无周期性亮暗、旧帧跳回、整块闪白/黑、持续背景冻结或边缘漂移 |
| 边缘质感 | 同一画面比较关闭、新材质五档、边缘效果临时禁用；日夜分别截图，前景空白区域取样 | 边缘局部可辨，中心不被加厚蒙层；清晰度实际改变背景细节；无强白框、锯齿或漏出裁切 |
| 生命周期和降级 | HOME/恢复、旋转、面板反复开关、省电与强制 fallback；COVER 若相关层有改动则追加专测 | 无崩溃/ANR、节点丢失、后台持续工作、阅读位置漂移或音量键永久被拦 |

必须保存并实际查看至少：背景状态截图组、顶栏布局截图组、玻璃五档日夜对照、设置纵滑录屏、正文经过玻璃的录屏。动态录屏每段至少 20 秒，正常速度观察后，对可疑时段逐帧核对；静止截图不能证明不闪烁。

旧脚本里的“0% 关闭、100→0→100”和“透明度”查找规则必须随新语义更新为真实开关操作与清晰度矩阵。测试应操作新控件并核对状态与像素，不能只改内部 intent 值、放宽阈值或改断言使旧脚本通过。

### 5.3 性能证据

在同一模拟器、同一构建类型和同一集成页面上比较：修改前的玻璃、修改后的关闭玻璃、修改后的开启玻璃。先预热，每种模式运行相同的 30 秒操作序列三次，测试期间固定主题和清晰度。录屏和正式计时尽量分开，避免编码开销污染对照。

记录实际显示刷新率、总帧数、janky frames、帧时长 P50/P95/P99、最长帧，并保留 Perfetto/System Trace 的 main、RenderThread 和 FrameTimeline。`gfxinfo framestats` 可补充，但不能用平均 FPS、`contentRecords` 或分析采样次数代替显示帧证据。方法参考 [Android UI 卡顿诊断](https://developer.android.com/studio/profile/jank-detection)。

验收规则：手势阻塞与闪烁复现序列应消失；同场景计时不出现超出基线三轮波动范围的持续退化。若仍掉帧，报告明确指出瓶颈、前后数据和是否解决，不以“模拟器慢”直接通过。没有可用的 FrameTimeline 时保留替代记录并将正式帧性能标为未验证。模拟器结果不能承诺真机 120Hz 或一帧延迟。

### 5.4 构建与回归命令

以下是实施后的验证命令，本计划编写时未执行。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assemblePreview --console=plain
```

记录实际测试数、失败数、退出码和 lint 新增问题；历史 warning 与本次新增问题分开，不能声称“lint 全绿”。若修改 `reader.js`，执行完整浏览器回归；若修改 `ReaderCss`，至少执行使用生产 CSS 的相关浏览器验证，禁止手抄一份 CSS 冒充生产实现。

```powershell
npm --prefix tools/browser-checks test
```

模拟器命令模板如下。运行前填写唯一 RunId 与全新证据目录；固定 APK 后不得在套件中途重新构建覆盖它。

```powershell
$taskRunId = 'appearance-fix-<unique-id>'
$taskApk = (Resolve-Path 'app/build/outputs/apk/preview/app-preview.apk').Path
$taskEvidence = Join-Path (Get-Location) 'build/verification/appearance-fix-<unique-id>'
Get-FileHash -LiteralPath $taskApk -Algorithm SHA256
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Create -RunId $taskRunId
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Start -RunId $taskRunId
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Status -RunId $taskRunId
```

确认 `BootCompleted = 1` 后，再执行安装与套件。Status 是有限检查，不阻塞轮询等待；启动失败先读日志。

```powershell
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Install -RunId $taskRunId -Apk $taskApk
& './tools/glass-preview/Test-AppAppearance.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/app-appearance"
& './tools/glass-preview/Test-ImmersionSettings.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/settings" -Suite settings
& './tools/glass-preview/Test-ImmersionSettings.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/theme" -Suite theme
& './tools/glass-preview/Test-ImmersionSettings.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/settings-layout" -Suite layout
& './tools/glass-preview/Test-ReaderHardware.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/reader-core" -Suite core
& './tools/glass-preview/Test-ReaderHardware.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/reader-materials" -Suite materials
& './tools/glass-preview/Test-ReaderHardware.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/reader-layout" -Suite layout
& './tools/glass-preview/Test-ReaderHardware.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/reader-lifecycle" -Suite lifecycle
& './tools/glass-preview/Test-ReaderHardware.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/reader-fallback" -Suite fallback
& './tools/glass-preview/Test-ReaderMaterialPixels.ps1' -RunId $taskRunId -Apk $taskApk -OutputDirectory "$taskEvidence/material-pixels"
& './tools/glass-preview/Invoke-GlassPreview.ps1' -Action Stop -RunId $taskRunId
```

上述已有套件是回归基础，仍需补上第 5.2 节的定向手势、录屏、五档像素及新偏好测试。只执行已有套件不能宣称新增需求已验收。若改动分页快照/内容层，再运行 `Test-ReaderHardwareCover.ps1`。

API 31–32 和 API 26–30 在有相应本机镜像时分别运行实际分支检查。API 37 的强制 fallback 只能证明降级逻辑，不能写成旧系统实测；缺镜像应列明缺口，不擅自下载或操作其他既有设备。

## 6. 实施交付物与结束条件

1. 范围内最小代码改动：共享主题背景 UI、顶栏布局、已定位的手势/渲染原因、玻璃偏好与两条材质路径；必要的定向回归和预览夹具一起更新。
2. 新增或更新 `ReadingStyle/AppAppearance`、`GlassPolicy/LiquidGlassPolicy`、`SettingsRepository` 的相关回归，覆盖背景状态转换、日夜隔离、开关记忆、清晰度单调、迁移、坏数据、快速修改/flush 与保存失败。避免只测试常量或复制实现。
3. `build/verification/appearance-fix-<unique-id>/` 下保存基线与新 APK 哈希、环境、日志、真实控件树、截图、录屏、像素比较及性能数据；不入 Git。
4. 在 `docs/reports/` 写一份实施与模拟器验收报告：每项给出原因、修复位置、前后证据、用例结果和未验证项。同步修正与本次变更直接相关的玻璃使用文档，避免继续描述不存在的保护能力。
5. 五项问题全部有本次证据，相关构建/回归通过，模拟器已停止，才可报告完成。模拟器无法启动、无法安装、无法复现或动态验收没跑时，应明确交付“代码已改/某项未验证”，不能宣称 UI、卡顿或闪烁已修复。
