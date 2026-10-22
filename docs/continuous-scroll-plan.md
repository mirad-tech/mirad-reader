> 历史记录：本文保留形成时的方案或交付结论，不代表当前版本的验收状态。当前构建与验证入口见 [开发文档](DEVELOPMENT.md)。

# 滚动模式「连续接章」实施计划

> 状态：待评审（未开始实现）
> 范围：仅 Android 阅读器「上下滚动」模式
> 估算：7–8 人日（TXT 最小闭环约 4.5 人日）

## 1. 背景与现状

上下滚动模式目前在章末仍然是**整章替换**：滚到底继续上滑时，`swapInNextChapter()` 把 `#mirad-content` 的 `innerHTML` 换成下一章，然后 `scrollTo(0, 0)`。

- 换内容：`app/src/main/assets/reader/reader.js` 的 `swapInNextChapter()`（约 753 行）
- 触发入口：`advanceChapter()` / `retreatChapter()`（约 826 / 892 行），由章末上滑手势、`scrollStep` 调用
- 结果：上一章结尾瞬间消失，视口直接出现下一章开头，观感是「刷新一下、开头替换现在的内容」

现有偏移模型是「章内字符偏移」：JS 把单章 DOM 抽成一条字符流，原生保存 `chapterIndex + charOffset`，进度、书签、划线、TTS、搜索共用这套偏移。这是本次改造最重要的兼容约束。

## 2. 目标与非目标

### 目标

1. 滚动到章末时，下一章正文**出现在当前内容下方**，自然继续滚动，不替换、不回到章首。
2. 向上回滚时能看到上一章结尾，不触发整章重载。
3. 章标题、全书百分比、章内页码、书签标志在跨章时正确切换。
4. 进度、划线、笔记、TTS、搜索在窗口文档下保持正确。
5. 窗口有界（默认 3 章），内存与重排成本可控。

### 非目标

- 不做整本书一个无限长页面。
- 不修改「左右翻页」模式（章边界仍走重载）。
- 不重构分页引擎；分页模式的分页/翻页动画逻辑保持不变。
- 不引入在线资源或网络请求。

## 3. 设计决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 生效模式 | 仅 SCROLL | 连续滚动才有「长页」需求；分页模式改造成本最高、收益最小 |
| 窗口大小 | 3 章（前 1 / 当前 / 后 1） | 覆盖回滚与预读，同时限制 DOM 规模 |
| 窗口触发 | 距顶/距底 2 屏预取，活动章稳定后裁剪 | 追加在视口外，视觉零跳动 |
| EPUB 统一排版 | 启用窗口 | 资源可绝对化，样式由 `#mirad-content` 强制接管 |
| EPUB 保留原书样式 | **不启用**，回退旧替换逻辑 | 每章自带 CSS 会互相污染，v1 不做 CSS 作用域隔离 |
| 章末上滑手势 | 删除 | 自然滚动已能接章，保留会造成重复触发 |
| 兼容开关 | `windowedScroll`（默认开） | 出问题可即时回退旧路径，不涉及数据迁移 |
| 偏移语义 | 数据库/桥/原生状态全部保持「章内偏移」 | 不破坏进度、标注、TTS、搜索 |

## 4. 目标架构

### 4.1 DOM 结构

```html
<div id="mirad-content">
  <section class="mirad-chapter" data-chapter="4"> …上一章… </section>
  <section class="mirad-chapter" data-chapter="5"> …当前章… </section>
  <section class="mirad-chapter" data-chapter="6"> …下一章… </section>
</div>
```

- 初始章也包成 `<section>`，保持结构统一。
- 现有 CSS 选择器 `#mirad-content p` 等为后代选择器，包裹后仍然生效。
- 章节之间用 section 的 margin 形成视觉分隔，标题仍由每章自己的 `<h2>` 呈现。

### 4.2 偏移模型

维护 `sections = [{ index, base, length, el }]`：

```
globalOffset = section.base + localOffset
localOffset  = globalOffset - section.base
```

- 每个 section **独立**建文本模型，保证 `sectionLocalText == 单章模式下的 state.text`。
- 全局文本仅用于窗口内的选区/查找，不再作为持久化语义。
- 原生侧继续只认 `chapterIndex + charOffset`。

### 4.3 不变式（实现与测试的断言）

1. 任一 section 的局部文本与该章单独加载时的文本逐字符相同。
2. `0 ≤ localOffset ≤ section.length`，且 `chapterIndex` 始终对应视口顶部所在 section。
3. 任何窗口结构变化后，视口顶部字符位置不变，除非用户主动滚动。
4. 裁剪后窗口仍包含活动章及其前后各 1 章（书首/书末除外）。
5. 原生 `chapterIndex + offset` 永远指向当前可见位置，不指向窗口首章。

## 5. 接口改动

### 5.1 资源接口

新增章节片段 URL：

```
https://mirad.local/book/<id>/fragment/<i>
```

- 只返回章节正文 `<section>`，不含 `<head>`、主题样式、`reader.js`。
- TXT：标题行处理与现有 `ChapterHtmlBuilder.build` 一致。
- EPUB：包裹原始 XHTML body 内容，并把 `img/src`、`srcset`、`link href`、内联 `url()` 改写为绝对 `asset` URL，替代文档级 `<base>`。
- 现有 `/chapter/<i>` 保持不变，供左右翻页模式与首屏使用。

### 5.2 JS API

| API | 说明 |
|---|---|
| `MiradReader.init(config)` | 初始化，包装初始章、建立偏移基址 |
| `MiradReader.gotoChapterOffset(chapter, offset, animate)` | 窗口内直接滚动；窗口外请求原生重载 |
| `MiradReader.getChapterText(index)` | 返回指定章的局部文本（TTS/搜索用） |
| `MiradReader.markTts(chapter, start, end)` | 章内偏移 → 全局渲染 |
| `MiradReader.markSearch(chapter, start, end)` | 同上 |
| `MiradReader.getWindow()` | 调试：返回窗口范围与各章基址 |
| `MiradReader.applyConfig(config)` | 保持现状，窗口结构不重建 |

### 5.3 桥接回调

```kotlin
onState(chapter: Int, page: Int, pages: Int, offset: Int, textLength: Int)
onChapterChanged(index: Int)
onWindowChanged(first: Int, last: Int)
onRequestChapter(index: Int, offset: Int)   // 窗口外跳转 / 换章失败兜底
```

- `page/pages/offset/textLength` 全部是**章内值**。
- `chapter` 直接随状态上报，避免 `onChapterChanged` 与 `onState` 的时序竞态。
- 原生在 `chapter` 变化时更新标题、重新查询标注、刷新书签标志；`offset` 变化时更新百分比并防抖保存。

## 6. 分阶段任务

### P0 · 保留旧路径 + 开关（0.5 天）

- 增加 `windowedScroll` 开关（构建常量或 `ReaderConfig` 字段，默认 `true`）。
- 关闭时完整保留现有 `swapInNextChapter` 行为。
- 建立基线：当前章末切换的截图、进度、高亮、TTS、搜索用例。

**验收**：开关关闭时行为与 1.0.7 一致；开关能通过配置/构建切换。

### P1 · 章节片段接口 + URL 绝对化（1 天）

- `ReaderResourceProvider` 增加 `fragment` 路由。
- 抽取 `ChapterHtmlBuilder` 正文生成逻辑，新增 `buildSection()` / `wrapBodySection()`。
- EPUB 相对 URL 改写（`src`、`srcset`、`href`、内联样式 `url()`）。
- 单测：TXT 标题行剔除、EPUB 包裹、`../` 路径、`srcset`、缺失资源。

**验收**：片段 URL 可独立渲染完整正文与插图；原 `/chapter/<i>` 无回归。

### P2 · JS 窗口引擎（2 天）

- `init` 时包装初始章，建立 `sections` 与基址。
- 文本模型按 section 构建并拼接，记录 `base/length`。
- 实现 `ensureNext()` / `ensurePrev()` / `trimWindow()`：
  - 追加：距底 2 屏触发，追加在视口下方；
  - 前插：距顶 2 屏触发，`scrollTop += 插入高度`；
  - 裁剪：活动章稳定 800ms 后移除窗口外章节，裁上方时补偿滚动。
- 位置汇报改为章内值；活动章变化时更新章号。
- 删除滚动模式下的 `advanceChapter` / `retreatChapter` 与章末上滑手势。
- 跳转：窗口内直接滚动，窗口外 `onRequestChapter`。

**验收**：TXT 连续滚 10 章无替换、无跳动；回滚连续看到上一章；标题与百分比在边界切换正确。

### P3 · 原生状态对接（1 天）

- `ReaderJsBridge` 扩展 `onState` 与新增回调。
- `ReaderSession` 处理章号变化、进度保存、书签标志、恢复定位。
- 页眉页码改为章内页；全书百分比沿用 `ProgressMath`。
- 启动仍以目标章为中心建立窗口并定位到章内偏移。

**验收**：杀进程恢复精确；换字号/行距不丢位置；书签标志正确。

### P4 · 标注跨章（0.5 天）

- `onWindowChanged` 后原生按窗口范围取高亮，带 `chapter` 字段下发。
- JS 按 `chapter` 映射到全局偏移；切章不再清除邻章划线。

**验收**：窗口内两章同时有划线时滚动不错位、不消失；新增/删除划线即时生效。

### P5 · TTS / 搜索 / EPUB（1 天）

- TTS 按章取文本与偏移；自动下一章时窗口内直接滚动，不重载。
- 搜索命中窗口内直接精确定位，窗口外重载并居中。
- EPUB 统一排版模式启用窗口，验证插图、字体、CSS。

**验收**：朗读跨章连续、当前句高亮跟随；搜索结果跳转准确；EPUB 图片正常。

### P6 · 回归与打磨（1.5 天）

- 执行第 7 节回归矩阵。
- 记录 append / trim / 改字号耗时；必要时把 `buildPoints` 改为增量更新。
- 更新 README 的阅读器说明与「尚未验证」清单。

**验收**：矩阵全部通过；长书连续滚动无 OOM、无明显掉帧。

## 7. 测试计划

### 7.1 单元测试（JVM）

- 片段构建：TXT 标题剔除、空章、卷节点、EPUB body 包裹。
- URL 改写：相对路径、`../`、绝对路径、`srcset`、内联 `url()`、无 `base` 兜底。
- 偏移换算（若在 Kotlin 侧实现）：`global ↔ local` 边界值与越界钳制。
- 现有 89 个测试全部保持通过。

### 7.2 手动回归矩阵

| 场景 | 关注点 |
|---|---|
| 连续滚动 10 章 | 无替换、无跳动、标题/百分比正确 |
| 回滚到上一章 | 上一章仍在窗口内，定位准确 |
| 长段落跨章 | 偏移不漂移、页码正确 |
| 单屏短章 / 空章 / 卷节点 | 窗口仍可用，不出现空白窗口 |
| 改字号 / 行距 / 字体 / 主题 | 位置保持，重排可接受 |
| 杀进程 / 切后台 / 旋转 | 精确恢复 |
| 目录跳转 / 进度条拖动 | 窗口重建或窗口内跳转正确 |
| 书签 / 划线 / 笔记 | 偏移正确，标记不丢 |
| 全文搜索 | 窗口内/外跳转都准确 |
| TTS | 跨章连续、当前句跟随、自动下一章 |
| EPUB 统一排版 | 插图、CSS、字体正常 |
| EPUB 保留原书样式 | 按设计回退旧行为 |

### 7.3 性能基准

- 3 章窗口的 `append`、`trim`、`applyLayout` 耗时（目标：单次 < 100ms，中端机）。
- 拖动字号时的帧感（对比单章基线）。
- 连续滚动 100 章的内存曲线（目标：稳定在 3 章规模）。

## 8. 风险与回退

| 风险 | 等级 | 应对 |
|---|---|---|
| EPUB 相对资源改写遗漏 | 高 | 片段接口统一绝对化；不支持的内联 `url()` 记录日志；原书样式模式直接回退 |
| 偏移错位导致进度/划线漂移 | 高 | 不变式断言 + 单测 + `getWindow()` 调试输出；开关可回退 |
| 裁剪/前插造成滚动跳动 | 中 | 按像素补偿；只在活动章稳定后裁剪 |
| 长文档重排卡顿 | 中 | 窗口固定 3 章；追加增量更新；必要时只测量视口附近 |
| TTS/搜索边缘场景 | 中 | 按章取文本与偏移；窗口外一律回退重载 |
| `onState` 时序竞态 | 中 | 章号随状态一起上报，不依赖单独回调 |

**回退策略**：设置 `windowedScroll=false` 即恢复 1.0.7 行为；进度仍是「章 + 章内偏移」，无需数据迁移。

## 9. 提交拆分

1. `feat(reader): 章节片段接口与 EPUB 资源绝对化`
2. `feat(reader): 滚动模式窗口文档与偏移基址`
3. `feat(reader): 连续接章的原生状态对接`
4. `feat(reader): 窗口内高亮与窗口范围同步`
5. `feat(reader): TTS/搜索窗口化`
6. `test: 片段构建与 URL 改写单测 + 回归记录`

每个提交都应能独立编译并通过现有测试；P0 的开关保证任意阶段可回退。

## 10. 交付验收清单

- [ ] 滚动模式章末自然接章，无内容替换、无回顶
- [ ] 回滚可看到上一章结尾
- [ ] 章标题、章内页码、全书百分比正确
- [ ] 进度恢复、书签、划线、笔记、搜索、TTS 全部正确
- [ ] EPUB 统一排版插图正常；原书样式回退符合设计
- [ ] 左右翻页模式无行为变化
- [ ] 89 个现有单测 + 新增单测全部通过
- [ ] README 与回归记录更新

## 11. 时间线

| 阶段 | 内容 | 人日 |
|---|---|---|
| P0 | 开关与基线 | 0.5 |
| P1 | 片段接口与 URL 改写 | 1 |
| P2 | JS 窗口引擎 | 2 |
| P3 | 原生对接 | 1 |
| P4 | 标注跨章 | 0.5 |
| P5 | TTS/搜索/EPUB | 1 |
| P6 | 回归与打磨 | 1.5 |
| 合计 | | 7.5 |

**最小可体验版本**：P0–P3（TXT + 统一排版），约 4.5 人日。
