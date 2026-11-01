# 共享玻璃边缘、线条背景与目录快速滚动实施记录

日期：2026-11-01（Asia/Shanghai）。分支：`codex/liquid-glass-edges-backgrounds-20261101`。执行依据：[实施计划](../plans/liquid-glass-edge-plan.md)。

## 范围与基线

面向使用日／夜外观的阅读者，玻璃部分复用现有顶栏、应用底栏及胶囊、阅读工具栏、菜单和面板，保持其布局、点击区域、配置写入链、正文排版与阅读位置。目标是正常比例下能辨认的曲面边缘及局部背景弯折，四张线条背景在现有两个设置入口可选。用户随后追加目录侧边可拖动滚动条，只移动目录列表，点击章节后才跳正文。

接手时 HEAD 为 `d199a1d`，工作区已有 18 个已跟踪文件改动及背景等未跟踪文件。初始工作区已经含 4.6dp 最大位移、9.5dp 影响带、两层散射、上下栏透色修复及四张背景注册。本轮没有把这些改动回退到 HEAD；`app/build.gradle.kts` 的 27／1.3.1 也是在先改动，本轮没有递增版本。

本机证据根：`build/verification/liquid-glass-edge-20261101-121224/`。其中：

- `initial-status.txt`、`initial-head.txt`、`initial-tracked.patch`、`initial-files/` 和 `initial-file-hashes.json` 固定接手状态。
- `baseline/` 从接手工作区构建 Debug／Preview，保存独立副本和 SHA-256。
- `baseline-optics/` 只比接手状态增加相同的预览图案命令，生产材质与 `baseline/` 相同；用于与最终包做确定性斜纹比较。

原始计划中 2.1dp／6dp 的描述属于更早历史，不能冒充此次“修改前”包。基线构建分别记录在 `baseline-build.log` 和 `baseline-optics-build.log`。

## 实现

- `GlassEdgeTexture` 的直边使用恒定单位外法线，圆角通过椭圆法线的环形渐变取光。光向固定在左上方 `(-.4472136, -.8944272)`，直边和圆角切点颜色一致。高光、柔光的描边外半宽被轮廓裁掉，因此将绘制宽度乘二，使实际可见宽度与 profile 一致。暗轮廓仍是原来的发丝宽度，背光侧使用窄暗部，不加整圈白框。
- 几何路径和 Brush 按真实轮廓、主题与清晰度缓存，不依赖传感器、时钟或持续刷新。全部光影仍在原材质绘制内，先于前景；没有新增 GPU 采样通道或全屏复制。
- 折射保持工作区已有的位移与短边限制。窄核／缓尾曲线写成 `1 - 1.86t + .72t² + .14t³`，同一组系数传给 AGSL；在影响带内沿值和一阶导数均归零。沿轮廓法线向内取样，映射留在影响带内，最小导数约 .07，不反向折叠；轮廓外权重为零，中心采样位置不变。
- `edge(blur(content))` 中折射读取的输入也需要完整模糊范围，采样留白改为 `ceil(2 × blurRadiusPx + maxDisplacementPx + 1)`；无折射只留模糊范围，100 档仍保留零模糊的独立折射输入。
- 预览增加 `pattern` 命令（`stripes/light/dark/none`），将确定性图案放在真实正文容器上，随正文滚动。独立 `refraction` 开关只控制背景弯折，保留同样的光影；原 `edge` 开关仍同时控制边缘和折射。两个开关及图案均不进入产品 UI 或持久化配置。

上下栏 `liquidBarOpacity` 的 0／60／100 日间值 `.46/.1408/.08`、夜间值 `.52/.1672/.10` 保持；面板仍走 `liquidPanelOpacity`。清晰度 0 为最朦胧、60 为默认、100 为零模糊，开关独立。没有新增整栏底色下限、局部文字保护色块或面板模糊封顶。

API 33+ 的背景弯折继续由已有 RuntimeShader 执行；API 31–32 只有模糊与几何光影。任意 Path 保持稳定的渐变降级。实现依据为 Android 官方 [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect) 的效果链与 API 门槛、[AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl) 的 RenderNode 输入，以及苹果 [Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/) 的透镜弯折和几何高光方向；没有复制平台私有实现。

## 背景素材

四张原图与 `artifacts/backgrounds/line-art-20261101/checks.json` 的 SHA-256 全部相符。产品 JPEG 均实际解码成功，为 768×1152；保留原图，不重绘、不删除中央线条。详细哈希、字节数和解码记录见证据根 `background-assets.json`，素材构图预览为 `background-assets-preview.png`。

| 注册 key | 显示名 | 分组／建议主题 | JPEG 字节数 |
| --- | --- | --- | ---: |
| `line-leaf-day` | 行叶·昼 | 日间／PAPER | 27,528 |
| `line-reed-day` | 疏苇·昼 | 日间／GREEN | 38,027 |
| `line-leaf-night` | 行叶·夜 | 夜间／DARK | 33,348 |
| `line-reed-night` | 疏苇·夜 | 夜间／DARK | 34,990 |

四张合计 133,893 字节；这是 JPEG 文件总量，不是实测 APK 增量。接入只追加可选项，旧背景和默认值保持。素材 README 已区分该批中央线条构图与旧批边角构图。

## 最终版本与验证范围

用户追加需求为“目录侧边可拖动滚动条：只移动目录列表，点击章节后才跳转正文”。实现仅操纵目录的 `LazyListState`，按搜索后的列表定位，首末端直达，内容可完整显示时隐藏；不调用阅读定位、不新增偏好。48dp 触区与 4dp 视觉条使用现有主题色。长目录夹具通过 `tocChapters` 参数提供，默认仍为原有 6 章。

600 章实测发现半展开面板的完整布局超出屏幕，原先按照完整高度映射只能到约 200 章。已把目录列表和滚动条约束到 `boundsInWindow` 得到的实际可见高度，外层面板和阅读 viewport 不变；频繁滚动值仅在绘制、语义与手势阶段读取。该修正的最终 Preview 为 `final5/app-preview.apk`，SHA-256 `ef28d8d2f932dcfb4df00ae7adbe79b59e182071d9109365bb0a3bf54fa42305`。目录首中末定点复测已通过：600章目录中点到第331–335章附近，末端可见第596–600章，随后可拖回首章；全过程正文章节、loads、scrollY与字符位置不变。Back正确关闭模态，夜间目录显示正常。搜索多结果可继续拖动、单结果/空结果/2章短目录隐藏滚动条均通过；点击筛选后的第600章才令 currentChapter=599、loads 增加。目录专项结果为 PASS，见 `toc-target-final5-new/`、`toc-remaining-final5-new/result.txt`，拖动录像为 `toc-remaining-final5-new/toc-drag.mp4`。旧失败样本保留，没有冒充通过。

### 源码与 APK 一致性

执行中另一个代理曾改写同一工作目录，并将目录切回 main。用户协调其停止后，本轮恢复了 `codex/liquid-glass-edges-backgrounds-20261101` 与轮廓法线实现；另一版本保存在 `external-edits/`，没有删除。包内核查证明中间 F3E 包只含向内折射，未含法线光影，因此不作为最终边缘验收。`artifact-code-audit-before-final3.json` 保留了分类。

`final3/` 是首次同时包含法线光影与向内折射的完整验证包：Preview SHA-256 `9297cdb84df97bc3433b054c7aca7fe0862b64378da42af7ef7e8643fbd796b4`。构建前后 main/preview 输入未漂移；`final3/code-audit.json` 从 DEX 核对两项实际代码存在。后续增加目录条及修正其可见范围，没有改动玻璃渲染模块，最终验收按各记录中的真实包哈希区分，未冒充同一二进制。

### 静态检查

最终目录实现有 4 项范围／端点／过滤索引／单调性单测。最终修正包 JVM 为 79 suites、577 项、0 失败／错误／跳过，Debug／Preview 构建成功。内置背景文件用例实际运行，未被 assume 跳过。最终可见范围修正后的完整输出见 `final5-preview-build.log`、`final5-validation.log` 与 `static-final5/summary.json`；已核对退出码0及完整结果。最终 lint 为0 Error、48 Warning、6 Hint；新增的频繁重组警告已消除。Debug SHA-256 为 `38d554b13ab9203a35148e52d783bf9cece2036628fb848e8f1eb7f7f6561be5`，Preview 为上述 EF28 包。

玻璃组合包的检查记录在 `static-final3/`：573 项 JVM 全通过；lint 0 Error、48 Warning、6 Hint。目录初版 `static-final4/` 为 577 项通过，新增的一条 `FrequentlyChangingValue` 警告已针对绘制阶段读取修正；最终 warning 数以 final5 实际输出为准，不声称 lint 全绿。

最终包再次核对 15 张 JPEG 的包内路径与源码哈希，其中四张新图完整打包。详细记录见 `background-assets.json` 与 `static-final5/packaged-backgrounds.json`。没有构建 release 或发行签名包。

### 玻璃视觉与像素证据

最终玻璃原图位于 `reader-final3/`、`rim-final3/`、`optics-day-final3/`、`warm-shell-final3/`，固定 API 37、1080×2400、密度 440，同一夹具及滚动位置。普通页使用 warm/scrolled 条件，避免把最初未就绪的 4 秒截图作为透色验收；外层旧 Compose 背景统计不能代表内层原生材质。

主代理实看原图和同坐标裁图，日夜默认 60 档的直边、圆角和面板光影可见，未见角部断缝或额外多圈边框。边缘折射关闭时保留相同光影，60 与 100 档均有独立对照；100 档实际 blur=0，能观察斜纹／文字在边界内的局部弯折。最右侧窄彩条在向内采样后消失。

| 同场景区域 | 最终玻璃包与基线实际比较 |
| --- | --- |
| 日夜正文参考区 880×1200 | 逐像素相同 |
| 日夜工具栏中心 | 最大通道差 1 |
| 日夜返回／书签区域 | 逐像素相同 |
| 日间顶部／底部边缘 | 平均绝对通道差约 6.51／3.41 |
| 夜间顶部／底部边缘 | 平均绝对通道差约 17.42／2.64 |

全屏前后图、原坐标裁图及原始度量位于 `comparisons/final3-reader-{day,night}-60-before-after*`。夜间 100 档独立折射对照的中心／前景最大差 1，正文参考区完全相同；差异集中在边缘，见 `comparisons/final3-refraction-night-100*`。

### 原生回归

| 套件 | 实际结果 | 证据目录 |
| --- | --- | --- |
| core | 63 项通过，0 失败 | `suite-final3-core/` |
| materials（当前契约） | 58 项通过，0 失败 | `suite-final3-materials-current-contract/` |
| fallback | 12 项通过，0 失败 | `suite-final3-fallback/` |
| lifecycle | 22 项通过，0 失败 | `suite-final3-lifecycle/` |
| layout | 23 项通过，0 失败 | `suite-final3-layout/` |

合计 178 项检查。覆盖模态、输入法、面板拖动、位置／viewport 保持、日夜各清晰度、开关、图片、后台停止和返回恢复等。相关状态记录 CPU display captures=0，分析尺寸不超过64、至多一项并行、动态采样间隔至少500ms，静止／隐藏后停止。各套件均绑定上述完整玻璃包哈希；目录新增部分另作专项。

原 materials 因旧脚本要求 100 档面板 .92／.88 不透明度而失败，原结果保存在 `suite-final3-materials/`。在先源码及用户约束均要求 .08／.10 薄底和零模糊，已最小修正 tracked 脚本并在 fresh 目录重跑完整套件，未修改产品语义来满足旧断言。100 档正文穿透、对比度下降是既定语义，不将它描述为改善或新增缺陷。

### 背景流程与版式

- `background-ui-v2/` 与 `background-ui-reader-v2/` 分别记录应用设置、阅读设置两个入口，四张新增图均通过真实 UI 缩略图点击并加载到正文；日夜分组及 URI 正确。该验证包的背景注册、资源与保存链和最终源码一致。
- `background-ui-cont2-v2/` 记录两组选择、不透明度、离开重进、HOME/返回与 reopen-store 的记忆；后者是新 repository 读取保留的 memory DAO，不能当成业务进程重启耐久性。
- 实际初始 100% 不透明度和真实滑块中间 55% 均有状态与图；端点触摸自动化两次未命中100%，失败尝试保留，没有冒充端点操作通过。
- `background-smoke-final3/` 再核对完整玻璃包的四图实际加载及夜间55%；`background-36sp-final3/` 是 config.fontSizeSp=36 的日夜真实正文大字图。系统 font_scale=1.6 的样例只证明原生控件大字。
- 320dp 窄屏、横屏的 cover 裁切和所选资源已检查，正文中央细线保留、无拉伸接缝。原始基线及预览可见系统栏模式均有页眉／状态栏、底部元信息与正文的既有重叠；本轮不改 insets／正文布局，不宣称整页无重叠。

首次沉浸系统提示遮住的图片已标无效，按 UI 树关闭提示后 fresh 目录重拍，未用遮挡图作验收。

### 动态与性能

导航的真实按住／拖动／释放在 `navigation-final3/`：中途 dragging=true、progress≈.896，释放后 progress=1、selected=Search，内层材质2项就绪、当前fallback=0、CPU display captures=0。主代理查看了原图及 `comparisons/final3-navigation-filmstrip.png`，未见停留残影；各页复用书架夹具，仅证明导航运动，不能证明其他入口业务。

最终目录专项为25项通过。`panel-dynamic-final5-new/panel.mp4` 录得真实手柄拖动与关闭，4项状态检查通过；`lifecycle-dynamic-final5-new/` 的玻璃关开、日夜切换、HOME/返回有7项状态检查通过，恢复后为2个就绪材质、当前fallback=0，阅读位置保持、CPU显示捕获为0。主代理抽帧查看 `comparisons/final5-{toc,panel,switch,home}-filmstrip.png`，未见持续残影、自采样或前景变形；低帧模拟器样本不能排除所有瞬态或证明流畅度。

原20秒“滚动＋目录开合”录像因实际动作时序未对齐，保留并标 `INVALID_COMPARISON`，不能用其帧数差判断回归。最终在第二轮同一实例按同一脚本，先确认真实滚动且动画活跃，再 reset gfxinfo、采样活跃窗口，起止状态门均通过：

| 包 | 设备窗口／录像 | gfxinfo帧／janky | p50／p95 | 主机CPU采前／采后 |
| --- | --- | --- | --- | --- |
| 基线 `61DB…146F1` | 约21s／19.29s | 401／358 | 113／150ms | 77%／76% |
| 最终 `EF28…42305` | 20s／18.84s | 456／397 | 97／150ms | 71%／91% |

原始录像、framestats、gfxinfo、活跃状态及主机负载记录在 `controlled-baseline-new/`、`controlled-final5-new/`。两次窗口长度、实际滚动轨迹及负载仍不完全相同，且均为高负载模拟器，**只作有限诊断，不据此认定性能改善、无回归或真机达标**。起止 animationRunning 为true，显式停止后为false。

### 环境与收尾边界

仅使用本任务新建的项目隔离实例，私有 ADB 5049、console5580；SDK 在 `G:/Android/Sdk`。当前本地只安装 API37 镜像，API31–32、26–30真实系统运行标 NOT_RUN，强制fallback不冒充旧系统。业务进程重启耐久性及真机性能均未运行，未操作用户手机。

两轮 RunId `liquid-edge-20261101-121224`、`liquid-edge-20261101-toc-final5` 均通过 keeper 的 finally 调用原 driver Stop。主代理分别回读确认3项PID为0、CleanupStatus=Removed、AVD不存在，5049／5580／5581无监听；第二轮独立核查为 `final-cleanup-independent.json`。截图、视频、日志、原始失败和中间APK均保留，不删除历史证据。未提交、推送、打tag或发布。

本轮新增差异相对接手工作区的补丁为 `this-task.patch`，原工作区补丁与文件快照单独保留。当前源码快照与SHA清单为 `final5-source/`、`final5-source-hashes.json`，终检为 `final-repository-state.json`（326项源码／测试文件零漂移、当前分支正确、暂存区为空）。
