/*
 * 墨读阅读引擎（注入到 WebView 的脚本）。
 *
 * 职责：
 *  - 文本模型：把 DOM 抽成稳定的字符流，供进度定位、划线、朗读、搜索共用
 *  - 分页：CSS 多列 + 视口尺寸计算页数与页起点
 *  - 翻页/滚动、偏移定位
 *  - 标注渲染：划线 / 搜索命中 / TTS 当前句
 *  - 通过 MiradBridge 把状态与选区回报给原生层
 */
(function () {
    'use strict';

    var CFG = {
        mode: 'PAGED',      // PAGED | SCROLL
        gap: 24,
        anim: 'SLIDE',      // NONE | SLIDE | COVER | CURL
        pad: 20,            // 正文左右留白（CSS px，等于 dp）
        colMax: 0           // 单行最大宽度（CSS px），0 = 不限（版心宽度）
    };

    var state = {
        ready: false,
        page: 0,
        pages: 1,
        text: '',
        textLength: 0,
        nodes: [],          // {node, start, len}
        points: [],         // {start, pos} 按文档顺序，pos 为分列流中的像素位置
        pendingOffset: 0,
        colW: 0,            // 当前正文列宽（CSS px）
        gap: 24,            // 当前列间距
        inset: 0            // 居中偏移（左右各留这么多）
    };

    var BLOCK_TAGS = {
        P: 1, DIV: 1, LI: 1, DD: 1, DT: 1, BLOCKQUOTE: 1, SECTION: 1, ARTICLE: 1,
        H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, TR: 1, TD: 1, TH: 1,
        FIGCAPTION: 1, PRE: 1, BODY: 1
    };

    var animating = false;

    function content() {
        return document.getElementById('mirad-content') || document.body;
    }

    function viewportWidth() {
        return Math.max(1, Math.round(document.documentElement.clientWidth || window.innerWidth));
    }

    function viewportHeight() {
        return Math.max(1, Math.round(document.documentElement.clientHeight || window.innerHeight));
    }

    /**
     * 正文版心几何：留白 + 单行上限 → 列宽与居中偏移。
     * 窄屏只要留白即可，宽屏（横屏/折叠屏展开/平板）由 colMax 限制单行长度并居中。
     */
    function columnGeometry() {
        var vw = viewportWidth();
        var pad = Math.max(0, Math.round(CFG.pad || 0));
        var padTop = Math.max(0, Math.round(CFG.padTop || 0));
        var padBottom = Math.max(0, Math.round(CFG.padBottom || 0));
        var minCol = 12 * 16;   // 留白不能把正文挤没
        if (vw - pad * 2 < minCol) {
            pad = Math.max(0, Math.floor((vw - minCol) / 2));
        }
        var colW = vw - pad * 2;
        var max = Math.round(CFG.colMax || 0);
        if (max > 0 && colW > max) colW = max;
        colW = Math.max(1, colW);
        // 列间距必须不小于两侧留白总量，否则限宽居中后，右侧那一列会露出在留白区里
        // ——表现就是"两边都显示文字"。
        var gap = Math.max(pad * 2, vw - colW);
        return {
            pad: pad,
            colW: colW,
            inset: Math.max(0, Math.round((vw - colW) / 2)),
            gap: gap,
            padTop: padTop,
            padBottom: padBottom
        };
    }

    /**
     * 背景层：固定定位、铺满视口，与分列几何完全解耦。
     * 放在正文容器之下（z-index:0，正文 z-index:1），所以不论正文列多窄、滚到哪一页，
     * 背景图都恰好铺满整屏——这正是"文字居中但背景要全覆盖"的做法。
     */
    function ensureBackgroundLayers() {
        if (document.getElementById('mirad-bg')) return;
        var body = document.body;
        if (!body) return;
        var bg = document.createElement('div');
        bg.id = 'mirad-bg';
        var tint = document.createElement('div');
        tint.id = 'mirad-tint';
        body.insertBefore(tint, body.firstChild);
        body.insertBefore(bg, tint);
    }

    function isBlock(el) {
        return !!el && el.nodeType === 1 && BLOCK_TAGS[el.tagName] === 1;
    }

    // ---------------------------------------------------------------- 文本模型

    function buildTextModel(root) {
        root = root || activeRoot();
        var nodes = [];
        var text = '';
        var lastBlock = null;
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        var node;
        while ((node = walker.nextNode())) {
            var value = node.nodeValue;
            if (!value) continue;
            var block = node.parentElement;
            while (block && block !== root && !isBlock(block)) {
                block = block.parentElement;
            }
            if (block !== lastBlock) {
                if (text.length > 0) text += '\n';
                lastBlock = block;
            }
            nodes.push({node: node, start: text.length, len: value.length});
            text += value;
        }
        state.nodes = nodes;
        state.text = text;
        state.textLength = text.length;
    }

    /** 把字符偏移换算成 {node, offset}；clamp 到末尾。 */
    function locate(offset) {
        var nodes = state.nodes;
        if (!nodes.length) return null;
        var lo = 0, hi = nodes.length - 1, found = 0;
        while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            if (nodes[mid].start <= offset) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        var entry = nodes[found];
        var local = Math.min(Math.max(offset - entry.start, 0), entry.len);
        return {node: entry.node, offset: local};
    }

    // ---------------------------------------------------------------- 分页布局

    // ---------------------------------------------------------------- 窗口化连续滚动（P2）
    //
    // 只启用 SCROLL；windowedScroll 关闭时全部退化为原来的单章行为。
    // 偏移语义不变：每个 section 各自建文本模型，models/points 都是**章内**坐标，
    // 原生侧仍然只认 chapterIndex + charOffset。

    function windowed() {
        return CFG.mode === 'SCROLL' && CFG.windowedScroll !== false;
    }

    var win = { active: null, loading: false, trimTimer: null, switching: false };

    function container() {
        return document.getElementById('mirad-content') || document.body;
    }

    /** 测量与建模型的根：窗口模式取当前活动章，否则就是容器。 */
    function activeRoot() {
        return (windowed() && win.active) ? win.active : content();
    }

    function sectionIndex(el) {
        var n = parseInt(el && el.getAttribute ? el.getAttribute('data-chapter') : '', 10);
        return isNaN(n) ? -1 : n;
    }

    function sectionTop(el) {
        return el.getBoundingClientRect().top + (window.scrollY || 0);
    }

    /** 视口顶部所在的 section。 */
    function sectionAt(offset) {
        var list = container().querySelectorAll('section.mirad-chapter');
        var best = null;
        for (var i = 0; i < list.length; i++) {
            if (sectionTop(list[i]) <= offset + 2) best = list[i];
        }
        return best || list[0] || null;
    }

    function fragmentUrlOf(index) {
        return location.pathname.replace(/\/chapter\/\d+$/, '/fragment/' + index) + location.search;
    }

    /** 首次进入窗口模式：把初始章内容包成 <section>，保持结构统一。 */
    function wrapInitialSection() {
        if (!windowed()) return;
        var c = container();
        if (!c || c.querySelector('section.mirad-chapter')) return;
        var raw = c.getAttribute('data-chapter');
        var idx = parseInt(raw, 10);
        if (isNaN(idx)) idx = 0;
        try {
            console.log('MIRAD wrap raw=' + raw + ' idx=' + idx);
        } catch (e) {
        }
        var sec = document.createElement('section');
        sec.className = 'mirad-chapter';
        sec.setAttribute('data-chapter', String(idx));
        while (c.firstChild) sec.appendChild(c.firstChild);
        c.appendChild(sec);
        win.active = sec;
    }

    function insertSection(node, index, atTop) {
        if (!node || node.tagName !== 'SECTION') return;
        node.setAttribute('data-chapter', String(index));
        node.className = 'mirad-chapter';
        var c = container();
        if (atTop) c.insertBefore(node, c.firstChild); else c.appendChild(node);
    }

    function documentHeight() {
        return Math.max(document.documentElement.scrollHeight, container().scrollHeight);
    }

    /** 视口所在章变化：重建该章的文本模型与分页，并把章号告诉原生层。 */
    function refreshActive() {
        if (!windowed() || win.switching) return;
        var el = sectionAt(window.scrollY || 0);
        if (!el || el === win.active) return;
        win.switching = true;
        var idx = sectionIndex(el);
        // 记录视口在新章内部的相对位置，布局后按它恢复，避免被 applyPosition 重置到章首
        var relInSection = Math.max(0, (window.scrollY || 0) - sectionTop(el));
        win.active = el;
        state.ready = false;
        state.page = 0;
        buildTextModel(el);
        applyLayout();
        window.scrollTo(0, sectionTop(el) + relInSection);
        state.page = Math.floor(relInSection / viewportHeight());
        state.ready = true;
        var b = bridge();
        if (b && b.onChapterChanged) {
            try {
                b.onChapterChanged(idx);
            } catch (e) {
            }
        }
        reportState();
        win.switching = false;
        scheduleTrim();
    }

    /** 只在活动章稳定后裁剪，避免快速滚动时反复增删。 */
    function scheduleTrim() {
        if (win.trimTimer) window.clearTimeout(win.trimTimer);
        win.trimTimer = window.setTimeout(function () {
            win.trimTimer = null;
            trimWindow();
        }, 800);
    }

    /** 窗口固定 3 章（活动章前后各 1）；裁上方时补偿滚动，避免视口跳动。 */
    function trimWindow() {
        if (!windowed() || !win.active) return;
        var c = container();
        var list = Array.prototype.slice.call(c.querySelectorAll('section.mirad-chapter'));
        if (list.length <= 3) return;
        var activeIdx = sectionIndex(win.active);
        var removedAbove = 0;
        for (var i = 0; i < list.length; i++) {
            var el = list[i];
            var idx = sectionIndex(el);
            if (Math.abs(idx - activeIdx) > 1) {
                if (idx < activeIdx) removedAbove += el.getBoundingClientRect().height;
                el.parentNode.removeChild(el);
            }
        }
        if (removedAbove > 0) {
            window.scrollTo(0, Math.max(0, (window.scrollY || 0) - removedAbove));
        }
        // 裁剪后可能又不足两屏：补回来，避免"裁剪完就滚不动了"
        if (documentHeight() < viewportHeight() * 2) ensureWindows();
    }

    /** 距顶/距底 2 屏时补一章；追加在视口外，视觉零跳动。 */
    function ensureWindows() {
        if (!windowed() || win.loading) return;
        var vh = viewportHeight();
        var total = documentHeight();
        var top = window.scrollY || 0;
        if (top + vh * 2 >= total) ensureSection(+1);
        // 只在接近章首（半屏内）才前插：2 屏就前插会连着补好几章，观感是"内容忽然变多"
        if (top < vh * 0.5) ensureSection(-1);
    }

    function ensureSection(dir) {
        var active = win.active || sectionAt(window.scrollY || 0);
        if (!active) return;
        // 兜底：section 上的 data-chapter 没拿到时，用容器的章号（HTML 由原生注入）
        var base = sectionIndex(active);
        if (base < 0) base = parseInt(container().getAttribute('data-chapter'), 10);
        if (isNaN(base) || base < 0) return;
        var idx = base + dir;
        if (idx < 0 || idx > lastChapterIndex()) return;
        if (container().querySelector('section.mirad-chapter[data-chapter="' + idx + '"]')) return;
        if (win.loading) return;
        win.loading = true;
        fetch(fragmentUrlOf(idx))
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                win.loading = false;
                if (!html) return;
                var doc = new DOMParser().parseFromString(html, 'text/html');
                var sec = doc.querySelector('section.mirad-chapter');
                if (!sec) return;
                var before = documentHeight();
                insertSection(sec, idx, dir < 0);
                if (dir < 0) {
                    // 前插把内容整体下移，等量补偿 scrollY 才能保持在原处
                    var after = documentHeight();
                    window.scrollTo(0, Math.max(0, (window.scrollY || 0) + (after - before)));
                }
                scheduleTrim();
                // 短章（不足两屏）时没有滚动事件可触发补章，这里主动补到可滚动为止
                if (documentHeight() < viewportHeight() * 2) ensureWindows();
            })
            .catch(function () { win.loading = false; });
    }

    function applyLayout() {
        var c = content();
        if (!c) return;
        var w = viewportWidth();
        var h = viewportHeight();
        var geo = columnGeometry();
        state.colW = geo.colW;
        state.gap = geo.gap;
        state.inset = geo.inset;
        ensureBackgroundLayers();

        document.documentElement.style.height = h + 'px';
        document.body.style.height = CFG.mode === 'PAGED' ? h + 'px' : 'auto';

        if (CFG.mode === 'PAGED') {
            document.documentElement.style.overflow = 'hidden';
            document.body.style.overflow = 'hidden';
            // 用 margin 而不是 padding 做居中：
            // padding 会被算进 scrollWidth，导致最后一页的 scrollLeft 被浏览器夹住，
            // 视口就正好跨在两列之间——表现就是"两侧都显示文字"。
            c.style.paddingLeft = '0px';
            c.style.paddingRight = '0px';
            c.style.marginLeft = geo.inset + 'px';
            c.style.marginRight = geo.inset + 'px';
            c.style.marginTop = geo.padTop + 'px';
            c.style.width = 'auto';
            c.style.maxWidth = 'none';
            c.style.columnWidth = geo.colW + 'px';
            c.style.columnGap = geo.gap + 'px';
            c.style.columnFill = 'auto';
            c.style.minHeight = '0';
            c.style.height = Math.max(1, h - geo.padTop - geo.padBottom) + 'px';
            c.style.overflow = 'hidden';
            c.style.transform = 'none';
            var flowWidth = Math.max(c.scrollWidth, geo.colW);
            // 用 ceil 而不是 round：末列只要溢出一像素就说明还需要一页，
            // round 会把"最后只剩一点点"的那一列算没，导致末列不可达。
            state.pages = Math.max(1, Math.ceil((flowWidth + geo.gap) / (geo.colW + geo.gap)));
        } else {
            document.documentElement.style.overflow = 'auto';
            document.body.style.overflow = 'visible';
            c.style.paddingLeft = '0px';
            c.style.paddingRight = '0px';
            c.style.columnWidth = 'auto';
            c.style.columnGap = '0px';
            c.style.columnFill = 'auto';
            c.style.width = geo.colW + 'px';
            c.style.maxWidth = geo.colW + 'px';
            c.style.marginLeft = 'auto';
            c.style.marginRight = 'auto';
            c.style.marginTop = geo.padTop + 'px';
            c.style.marginBottom = geo.padBottom + 'px';
            c.style.height = 'auto';
            c.style.minHeight = '0';
            c.style.overflow = 'visible';
            c.style.transform = 'none';
            var measure = activeRoot();
            state.pages = Math.max(1, Math.ceil((((measure && measure.scrollHeight) || c.scrollHeight) + geo.padTop + geo.padBottom) / h));
        }
        var anchorOffset = -1;
        var anchorDelta = 0;
        if (windowed() && win.active && !win.switching) {
            // 重排后按"当前可见字符"恢复滚动，避免改字号/行距时跳位置
            anchorOffset = offsetAtPage(state.page);
            var beforePos = precisePos(anchorOffset);
            anchorDelta = (beforePos === null) ? 0 : ((window.scrollY || 0) - beforePos);
        }
        buildPoints();
        state.page = Math.min(state.page, state.pages - 1);
        if (windowed() && win.active && !win.switching) {
            var afterPos = precisePos(anchorOffset);
            if (afterPos !== null) window.scrollTo(0, Math.max(0, afterPos + anchorDelta));
            state.page = Math.floor(Math.max(0, (window.scrollY || 0) - sectionTop(win.active)) / viewportHeight());
            reportState();
        } else if (!win.switching) {
            // 换章过程中不要重定位：refreshActive 会在布局后按新章内的相对位置恢复滚动
            applyPosition(false);
        }
        reportMetrics();
    }

    /** 记录每个文本节点在分列流（或纵向滚动流）中的像素位置，用于偏移↔页互转。 */
    function buildPoints() {
        var c = activeRoot();
        var rootRect = c.getBoundingClientRect();
        var points = [];
        var paged = CFG.mode === 'PAGED';
        for (var i = 0; i < state.nodes.length; i++) {
            var entry = state.nodes[i];
            var el = entry.node.parentElement;
            if (!el) continue;
            var rect = el.getBoundingClientRect();
            // 容器自身的矩形不随内部滚动移动，子元素会移动，所以差值 = 流内位置 − 滚动位置。
            // 必须加回滚动量，才得到"与滚动无关"的流内位置（之前的注释把方向写反了）。
            var pos = paged
                ? (rect.left - rootRect.left + (c.scrollLeft || 0))
                : (rect.top - rootRect.top);
            if (points.length && points[points.length - 1].pos === pos) {
                continue;
            }
            points.push({start: entry.start, pos: Math.round(pos)});
        }
        state.points = points;
        return 0;
    }

    function step() {
        return (state.colW || viewportWidth()) + (state.gap || CFG.gap);
    }

    /** 二分找出包含该字符偏移的文本节点。 */
    function nodeAt(offset) {
        var nodes = state.nodes;
        if (!nodes.length) return null;
        var lo = 0, hi = nodes.length - 1, found = nodes[0];
        while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            if (nodes[mid].start <= offset) {
                found = nodes[mid];
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /**
     * 用 Range 量出某个字符偏移在流中的精确像素位置。
     *
     * 为什么需要它：buildPoints 按"父元素矩形"取位置，同一个 <p> 里的多个文本节点会被
     * 去重成一条，points 的实际粒度就是段落。段落一旦跨列/跨屏，段内任意偏移都会被
     * nearestPoint 归到段落起点 → 进度跳转、TTS 定位、搜索落点、划线跳转全落到段落开头。
     * 测量只发生在跳转时（滚动回报不走这里），代价可以接受。
     */
    function precisePos(offset) {
        var entry = nodeAt(offset);
        if (!entry || !entry.node) return null;
        var len = entry.len || 0;
        if (len <= 0) return null;
        var inNode = Math.max(0, Math.min(offset - entry.start, len - 1));
        var c = activeRoot();
        if (!c) return null;
        try {
            var range = document.createRange();
            range.setStart(entry.node, inNode);
            range.setEnd(entry.node, Math.min(inNode + 1, len));
            var rect = range.getBoundingClientRect();
            var rootRect = c.getBoundingClientRect();
            if (!rect || (rect.width === 0 && rect.height === 0)) return null;
            // 与 buildPoints 同理：加回滚动量，否则测量值随当前页漂移，
            // 会形成"算页 → 改滚动 → 再测量 → 又算出另一页"的反馈震荡。
            return CFG.mode === 'PAGED'
                ? (rect.left - rootRect.left + (c.scrollLeft || 0))
                : (rect.top - rootRect.top);
        } catch (e) {
            return null;
        }
    }

    function pageFromOffset(offset) {
        var point = nearestPoint(offset);
        if (!point) return 0;
        // 段落级 points 只给到段落起点，用精确测量覆盖它（测不出来时退回段落起点）
        var precise = precisePos(offset);
        var pos = (precise === null || precise === undefined) ? point.pos : precise;
        if (CFG.mode !== 'PAGED') return pos;
        // 与 offsetAtPage 的 `pos >= page*step - PAGE_EPS` 对称：用 round 时
        // 落在 [k*step - step/2, k*step + step/2) 都归 k，往返（锚点→页→锚点）不再漂移。
        // 旧实现是 floor((pos+1)/step)，与反向的 -2 容差不对称，每次重排都会退 1 页。
        return Math.max(0, Math.round(pos / step()));
    }

    function nearestPoint(offset) {
        var points = state.points;
        if (!points.length) return null;
        var lo = 0, hi = points.length - 1, found = points[0];
        while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            if (points[mid].start <= offset) {
                found = points[mid];
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    function offsetAtPage(page) {
        if (CFG.mode !== 'PAGED') {
            var y = window.scrollY || 0;
            var points = state.points;
            for (var j = 0; j < points.length; j++) {
                if (points[j].pos >= y - 2) return points[j].start;
            }
            // 滚动模式下 points 同样是段落级粒度：单段落章节的表里只有一个 pos=0 的条目，
            // "滚到 y>2"永远查不到点，旧实现直接 return 0 —— 于是每次重排都把阅读位置清回章首。
            // 与 PAGED 分支同一病因，同样用反向测量兜底。
            var measured = offsetAtPos(y);
            if (measured !== null) return measured;
            return points.length ? points[points.length - 1].start : 0;
        }
        var target = page * step();
        var points2 = state.points;
        for (var i = 0; i < points2.length; i++) {
            if (points2[i].pos >= target - 2) return points2[i].start;
        }
        // 没有任何点到达目标页：典型情况是"整章只有一个长段落"，points 只有段落起点。
        // 旧实现返回 textLength-1（末尾）——于是每一次重排把锚点回写时都会跳到本章末尾。
        // 这里改为跨全部文本节点反向测量出该页真正的起始字符偏移。
        var measuredPos = offsetAtPos(target);
        if (measuredPos !== null) return measuredPos;
        var last = points2.length ? points2[points2.length - 1] : null;
        return last ? last.start : 0;
    }

    /**
     * 跨全部文本节点二分，找出"流内位置 >= targetPos 的最小字符偏移"。
     *
     * 这是精确的反向测量，取代"从 points 查表"：points 是按父元素矩形去重的，
     * 同一位置上的多个节点（典型是章节标题与它后面的首个段落，pos 都是 0）只会留第一条，
     * 于是表里根本没有段落级以下的信息——长段落跨页时任何页号都查不到点。
     * 只在查表失败时调用，因此每次重排最多付一次二分代价。
     */
    function offsetAtPos(targetPos) {
        var nodes = state.nodes;
        if (!nodes || !nodes.length) return null;
        var list = [];
        for (var i = 0; i < nodes.length; i++) {
            if (nodes[i].len > 0) list.push(nodes[i]);
        }
        if (!list.length) return null;
        var c = content();
        if (!c) return null;
        var rootRect = c.getBoundingClientRect();
        var scroll = CFG.mode === 'PAGED' ? (c.scrollLeft || 0) : 0;

        function posOf(entry, inNode) {
            try {
                var r = document.createRange();
                r.setStart(entry.node, inNode);
                r.setEnd(entry.node, Math.min(inNode + 1, entry.len));
                var rect = r.getBoundingClientRect();
                if (!rect || (rect.width === 0 && rect.height === 0)) return null;
                return CFG.mode === 'PAGED'
                    ? (rect.left - rootRect.left + scroll)
                    : (rect.top - rootRect.top);
            } catch (e) {
                return null;
            }
        }

        // 1) 节点粒度：找第一个"起点位置 >= target"的节点
        var lo = 0;
        var hi = list.length - 1;
        var ansNode = -1;
        while (lo <= hi) {
            var mid = (lo + hi) >> 1;
            var p = posOf(list[mid], 0);
            if (p === null) return list[mid].start;
            if (p >= targetPos - 2) {
                ansNode = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (ansNode === 0) return 0;
        // ansNode === -1 表示没有任何节点的起点到达目标页 → 目标落在**最后一个节点**内部
        // （典型：整章一个长段落，标题与段落起点位置相同、都不到目标页）。
        // 早先这里写成 list[0]，于是跑进标题节点里搜，返回"标题末尾"这种荒谬值。
        var entry = ansNode > 0 ? list[ansNode - 1] : list[list.length - 1];
        var lo2 = 0;
        var hi2 = entry.len - 1;
        var ans = -1;
        while (lo2 <= hi2) {
            var m2 = (lo2 + hi2) >> 1;
            var p2 = posOf(entry, m2);
            if (p2 === null) return entry.start;
            if (p2 >= targetPos - 2) {
                ans = m2;
                hi2 = m2 - 1;
            } else {
                lo2 = m2 + 1;
            }
        }
        return ans < 0 ? entry.start + entry.len : entry.start + ans;
    }

    function applyPosition(animate) {
        var c = content();
        if (!c) return;
        if (CFG.mode === 'PAGED') {
            var x = state.page * step();
            if (animate && CFG.anim === 'SLIDE') {
                animateScrollLeft(c, x);
            } else {
                c.scrollLeft = x;
            }
        } else {
            var y = state.page * viewportHeight();
            if (animate && CFG.anim === 'SLIDE') {
                animateScrollTop(y);
            } else {
                window.scrollTo(0, y);
            }
        }
        reportState();
    }

    function animateScrollLeft(el, target) {
        if (animating) {
            el.scrollLeft = target;
            return;
        }
        animating = true;
        var start = el.scrollLeft;
        var delta = target - start;
        if (Math.abs(delta) < 1) {
            animating = false;
            el.scrollLeft = target;
            return;
        }
        var duration = Math.min(320, 140 + Math.abs(delta) * 0.35);
        var startTime = null;
        function frame(ts) {
            if (startTime === null) startTime = ts;
            var t = Math.min(1, (ts - startTime) / duration);
            var eased = 1 - Math.pow(1 - t, 3);
            el.scrollLeft = start + delta * eased;
            if (t < 1) {
                window.requestAnimationFrame(frame);
            } else {
                animating = false;
                reportState();
            }
        }
        window.requestAnimationFrame(frame);
    }

    function animateScrollTop(target) {
        var start = window.scrollY || 0;
        var duration = 260;
        var startTime = null;
        function frame(ts) {
            if (startTime === null) startTime = ts;
            var t = Math.min(1, (ts - startTime) / duration);
            var eased = 1 - Math.pow(1 - t, 3);
            window.scrollTo(0, start + (target - start) * eased);
            if (t < 1) window.requestAnimationFrame(frame);
            else reportState();
        }
        window.requestAnimationFrame(frame);
    }

    // ---------------------------------------------------------------- 标注渲染

    var BLOCK_FOR_MARK = 1;

    function clearMarks() {
        var marks = document.querySelectorAll('mark.mirad-hl, mark.mirad-search, span.mirad-tts');
        for (var i = 0; i < marks.length; i++) {
            var mark = marks[i];
            var parent = mark.parentNode;
            if (!parent) continue;
            while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
            parent.removeChild(mark);
        }
        var c = content();
        if (c) c.normalize();
        buildTextModel();
    }

    /** 计算若干字符区间涉及的文本节点片段。 */
    function collectSegments(ranges) {
        var segments = [];
        for (var r = 0; r < ranges.length; r++) {
            var range = ranges[r];
            var from = Math.max(0, range.start);
            var to = Math.min(state.textLength, range.end);
            if (to <= from) continue;
            for (var i = 0; i < state.nodes.length; i++) {
                var entry = state.nodes[i];
                var nodeStart = entry.start;
                var nodeEnd = entry.start + entry.len;
                if (nodeEnd <= from) continue;
                if (nodeStart >= to) break;
                var localFrom = Math.max(from, nodeStart) - nodeStart;
                var localTo = Math.min(to, nodeEnd) - nodeStart;
                if (localTo > localFrom) {
                    segments.push({
                        node: entry.node,
                        from: localFrom,
                        to: localTo,
                        cls: range.cls || 'mirad-hl'
                    });
                }
            }
        }
        return segments;
    }

    /** 把片段按节点分组、合并、并从后往前切分，避免下标失效。 */
    function applySegments(segments) {
        var byNode = [];
        var map = new Map();
        for (var i = 0; i < segments.length; i++) {
            var seg = segments[i];
            var list = map.get(seg.node);
            if (!list) {
                list = [];
                map.set(seg.node, list);
                byNode.push({node: seg.node, list: list});
            }
            list.push(seg);
        }
        for (var n = 0; n < byNode.length; n++) {
            var entry = byNode[n];
            var list2 = entry.list.sort(function (a, b) {
                return b.from - a.from;
            });
            var merged = [];
            for (var k = 0; k < list2.length; k++) {
                var seg2 = list2[k];
                if (merged.length && seg2.to >= merged[merged.length - 1].from) {
                    var last = merged[merged.length - 1];
                    last.from = Math.min(last.from, seg2.from);
                } else {
                    merged.push({from: seg2.from, to: seg2.to, cls: seg2.cls});
                }
            }
            for (var m = 0; m < merged.length; m++) {
                splitAndWrap(entry.node, merged[m].from, merged[m].to, merged[m].cls);
            }
        }
    }

    function splitAndWrap(node, from, to, cls) {
        var text = node.nodeValue;
        if (!text || from >= to || to > text.length) return;
        var target = node;
        if (to < target.nodeValue.length) {
            target.splitText(to);
        }
        if (from > 0) {
            target = target.splitText(from);
        }
        var mark = document.createElement('mark');
        mark.className = cls;
        target.parentNode.insertBefore(mark, target);
        mark.appendChild(target);
    }

    /** 渲染全部划线。 */
    function renderHighlights(highlights) {
        clearMarks();
        if (!highlights || !highlights.length) return state.textLength;
        var ranges = highlights.map(function (h) {
            return {start: h.start, end: h.end, cls: h.note ? 'mirad-hl mirad-note' : 'mirad-hl'};
        });
        applySegments(collectSegments(ranges));
        buildTextModel();
        return state.textLength;
    }

    function renderSingleMark(start, end, cls) {
        var marks = document.querySelectorAll('mark.' + cls + ', span.' + cls);
        for (var i = 0; i < marks.length; i++) {
            var mark = marks[i];
            var parent = mark.parentNode;
            if (!parent) continue;
            while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
            parent.removeChild(mark);
        }
        buildTextModel();
        if (end <= start) return;
        applySegments(collectSegments([{start: start, end: end, cls: cls}]));
        buildTextModel();
    }

    // ---------------------------------------------------------------- 状态回报

    function bridge() {
        return (typeof MiradBridge !== 'undefined') ? MiradBridge : null;
    }

    function reportState() {
        var b = bridge();
        if (!b) return;
        var page = state.page;
        var offset = offsetAtPage(page);
        try {
            b.onState(page, state.pages, offset, state.textLength, CFG.mode === 'PAGED' ? 0 : (window.scrollY || 0));
        } catch (e) {
        }
    }

    /** 把版心几何回报给原生层，供页眉页脚与正文列对齐。 */
    function reportMetrics() {
        var b = bridge();
        if (!b || !b.onMetrics) return;
        try {
            b.onMetrics(state.inset || 0, state.colW || 0);
        } catch (e) {
        }
    }

    var scrollTimer = null;
    var lastReportedPage = -1;
    /** 已经为哪一章发过"到底"信号。按章记录，切章自动失效，且改字号重排不会误触发。 */
    var bottomSentFor = null;

    function chapterKey() {
        var c = activeRoot();
        if (!c || !c.getAttribute) return '';
        return c.getAttribute('data-chapter') || '';
    }

    /** 是否已经滚到本章底部（留 8px 容差，避免小数像素永远差一点）。 */
    function atBottom() {
        var top = window.scrollY || 0;
        var view = viewportHeight();
        var bodyHeight = document.body ? document.body.scrollHeight : 0;
        var total = Math.max(document.documentElement.scrollHeight, bodyHeight);
        return total > 0 && top + view >= total - 8;
    }

    /**
     * 通知原生层"本章到底了，请接下一章"。每章只发一次。
     * 单屏短章根本没有滚动事件，所以除了 onScroll，触摸上滑与"再往下翻一屏"也会调它。
     */
    function notifyBottomOnce() {
        if (CFG.mode !== 'SCROLL' || !state.ready) return false;
        var key = chapterKey();
        if (bottomSentFor === key) return false;
        bottomSentFor = key;
        var b = bridge();
        if (b) {
            try {
                b.onState(state.pages, state.pages, state.textLength, state.textLength, window.scrollY || 0);
            } catch (e) {
            }
        }
        return true;
    }

    function onScroll() {
        if (CFG.mode !== 'SCROLL') return;
        try {
            console.log('MIRAD scroll y=' + Math.round(window.scrollY || 0) + ' win=' + windowed());
        } catch (e) {
        }
        if (scrollTimer) window.clearTimeout(scrollTimer);
        scrollTimer = window.setTimeout(function () {
            // 不再"滚到底就自动接章"：最后一段刚露出来时用户还没读完，
            // 自动跳会让人觉得"没看完就被切走"。接章改为到底后继续上滑（见 touchend / scrollStep）。
            var top = window.scrollY || 0;
            var view = viewportHeight();
            if (windowed()) {
                // 窗口模式：先补章、再确认视口落在哪一章，页号一律按活动章计算
                ensureWindows();
                refreshActive();
                var relTop = Math.max(0, top - (win.active ? sectionTop(win.active) : 0));
                var winPage = Math.floor(relTop / view);
                try {
                    console.log('MIRAD win y=' + Math.round(top) + ' rel=' + Math.round(relTop) +
                        ' page=' + winPage + ' pages=' + state.pages +
                        ' secs=' + container().querySelectorAll('section.mirad-chapter').length +
                        ' idx=' + sectionIndex(win.active) + ' docH=' + documentHeight() + ' vh=' + view);
                } catch (e) {
                }
                if (winPage === lastReportedPage) return;
                lastReportedPage = winPage;
                state.page = Math.min(winPage, state.pages - 1);
                reportState();
                return;
            }
            var total = Math.max(
                document.documentElement.scrollHeight,
                document.body ? document.body.scrollHeight : 0
            );
            // 还剩一屏半时就把下一章取到内存，真正换章时几乎零等待。
            if (total > 0 && top + view * 1.5 >= total) prefetchNextChapter();
            if (top < view) prefetchPreviousChapter();
            var page = Math.floor(top / view);
            // 页号没变就不回报，滚动过程不打扰原生层（否则每帧穿透到 Compose 会顿）。
            if (page === lastReportedPage) return;
            lastReportedPage = page;
            state.page = page;
            reportState();
        }, 140);
    }

    // ---------------------------------------------------------------- 滚动模式：无感接章

    var prefetchedFor = -1;
    var prefetchedHtml = null;
    var prefetching = false;

    /** 当前章的索引（来自 #mirad-content 的 data-chapter）。 */
    function currentChapterIndex() {
        var c = content();
        var raw = c && c.getAttribute ? c.getAttribute('data-chapter') : null;
        var n = parseInt(raw, 10);
        return isNaN(n) ? -1 : n;
    }

    function lastChapterIndex() {
        return (typeof CFG.chapterCount === 'number' && CFG.chapterCount > 0) ? CFG.chapterCount - 1 : 0;
    }

    function chapterUrlOf(index) {
        return location.pathname.replace(/\/chapter\/\d+$/, '/chapter/' + index) + location.search;
    }

    /** 提前把下一章 HTML 取到内存（同源，走本地拦截，不产生网络流量）。 */
    function prefetchNextChapter() {
        var idx = currentChapterIndex();
        if (idx < 0 || idx >= lastChapterIndex()) return;
        if (prefetchedFor === idx + 1 && prefetchedHtml) return;
        if (prefetching) return;
        prefetching = true;
        fetch(chapterUrlOf(idx + 1))
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                if (html) { prefetchedHtml = html; prefetchedFor = idx + 1; }
            })
            .catch(function () {})
            .then(function () { prefetching = false; });
    }

    /**
     * 原地换到下一章：替换 #mirad-content、同步 <base>（EPUB 的图片依赖它）、
     * 重建文本模型与布局、回到顶部，并把新章号告诉原生层。
     * 全程不重载文档，因此没有白屏与"卡一下"。
     */
    function swapInNextChapter(html, toBottom) {
        var live = content();
        if (!live) return false;
        var parsed;
        try {
            parsed = new DOMParser().parseFromString(html, 'text/html');
        } catch (e) {
            return false;
        }
        var incoming = parsed.querySelector('#mirad-content');
        if (!incoming) return false;
        var incomingBase = parsed.querySelector('base');
        var liveBase = document.querySelector('base');
        if (incomingBase) {
            if (!liveBase) {
                liveBase = document.createElement('base');
                document.head.appendChild(liveBase);
            }
            liveBase.setAttribute('href', incomingBase.getAttribute('href') || '');
        }
        live.innerHTML = incoming.innerHTML;
        for (var i = 0; i < incoming.attributes.length; i++) {
            var attr = incoming.attributes[i];
            if (attr && attr.name) live.setAttribute(attr.name, attr.value);
        }
        state.ready = false;
        bottomSentFor = null;
        lastReportedPage = -1;
        // 先让新章内容立刻出现在屏幕上（滚动到顶部），再重建模型/布局。
        // 旧实现把整段重建卡在"换内容"这一帧里，观感就是接章时顿一下。
        if (!toBottom) window.scrollTo(0, 0);
        var finish = function () {
            // 关键：applyLayout() 会按 state.page 恢复滚动位置。原地换章时这个值还是
            // 上一章的页号，不清零就会把新章滚到"后面"去（重载路径天然是 0，所以以前没暴露）。
            state.page = 0;
            buildInitialModelAndLayout();
            // 布局重建后才知道新章高度：往回翻时停在上一章末尾，向前翻则回到开头。
            window.scrollTo(0, toBottom ? document.documentElement.scrollHeight : 0);
            state.page = Math.floor((window.scrollY || 0) / viewportHeight());
            lastReportedPage = state.page;
            state.ready = true;
            var b = bridge();
            if (b) {
                try {
                    if (b.onChapterChanged) b.onChapterChanged(currentChapterIndex());
                } catch (e) {
                }
            }
            reportState();
        };
        if (window.requestAnimationFrame) {
            requestAnimationFrame(function () { requestAnimationFrame(finish); });
        } else {
            finish();
        }
        return true;
    }

    /** 原地换章失败时，显式请原生重载目标章——这是唯一的失败兜底入口。 */
    function requestChapterLoad(index) {
        var b = bridge();
        if (b && b.onRequestNextChapter) {
            try {
                b.onRequestNextChapter(index);
                return true;
            } catch (e) {
            }
        }
        notifyBottomOnce();
        return false;
    }

    /** 用户明确要继续读下一章时才走这里：优先原地换章，失败才请原生重载。 */
    function advanceChapter() {
        // 窗口模式下章与章首尾相连，自然滚动即可接章，不再需要"到底再上滑"
        if (windowed()) return false;
        if (CFG.mode !== 'SCROLL' || !state.ready) return false;
        var idx = currentChapterIndex();
        if (idx < 0) return false;
        if (idx >= lastChapterIndex()) {
            notifyBottomOnce();
            return false;
        }
        if (prefetchedFor === idx + 1 && prefetchedHtml) {
            var cached = prefetchedHtml;
            prefetchedHtml = null;
            prefetchedFor = -1;
            if (swapInNextChapter(cached)) {
                prefetchNextChapter();
                return true;
            }
        }
        fetch(chapterUrlOf(idx + 1))
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                if (!html || !swapInNextChapter(html)) requestChapterLoad(idx + 1);
                else prefetchNextChapter();
            })
            .catch(function () { requestChapterLoad(idx + 1); });
        return true;
    }

    /** 是否在章首（留 2px 容差）。 */
    function atTop() {
        return (window.scrollY || 0) <= 2;
    }

    /** 距章末还有多少像素（用于"一次滑动就接章"的容差判断）。 */
    function nearBottom(slack) {
        var top = window.scrollY || 0;
        var total = Math.max(
            document.documentElement.scrollHeight,
            document.body ? document.body.scrollHeight : 0
        );
        return total > 0 && top + viewportHeight() >= total - slack;
    }

    function nearTop(slack) {
        return (window.scrollY || 0) <= slack;
    }

    var prevPrefetchedFor = -1;
    var prevPrefetchedHtml = null;
    var prevPrefetching = false;

    function prefetchPreviousChapter() {
        var idx = currentChapterIndex();
        if (idx <= 0) return;
        if (prevPrefetchedFor === idx - 1 && prevPrefetchedHtml) return;
        if (prevPrefetching) return;
        prevPrefetching = true;
        fetch(chapterUrlOf(idx - 1))
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                if (html) { prevPrefetchedHtml = html; prevPrefetchedFor = idx - 1; }
            })
            .catch(function () {})
            .then(function () { prevPrefetching = false; });
    }

    /** 在章首继续下拉：回到上一章并停在它的末尾（连续阅读，不重载文档）。 */
    function retreatChapter() {
        // 窗口模式：向上滚动自然看到上一章，不再整章替换
        if (windowed()) return false;
        if (CFG.mode !== 'SCROLL' || !state.ready) return false;
        var idx = currentChapterIndex();
        if (idx <= 0) return false;
        if (prevPrefetchedFor === idx - 1 && prevPrefetchedHtml) {
            var cached = prevPrefetchedHtml;
            prevPrefetchedHtml = null;
            prevPrefetchedFor = -1;
            if (swapInNextChapter(cached, true)) {
                prefetchPreviousChapter();
                return true;
            }
        }
        fetch(chapterUrlOf(idx - 1))
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                if (!html || !swapInNextChapter(html, true)) requestChapterLoad(idx - 1);
                else prefetchPreviousChapter();
            })
            .catch(function () { requestChapterLoad(idx - 1); });
        return true;
    }

    // 单屏短章没有滚动事件：手指在底部继续上滑也算"要继续读下一章"。
    var swipeStartY = null;
    document.addEventListener('touchstart', function (e) {
        swipeStartY = (e.touches && e.touches.length) ? e.touches[0].clientY : null;
    }, {passive: true});
    document.addEventListener('touchend', function (e) {
        if (CFG.mode !== 'SCROLL' || swipeStartY === null) return;
        var endY = (e.changedTouches && e.changedTouches.length) ? e.changedTouches[0].clientY : null;
        var dy = (endY === null) ? 0 : (endY - swipeStartY);
        swipeStartY = null;
        // 容差放宽：手指抬起时惯性还没落定，严格 atBottom 会让人"再滑一次才进下一章"
        if (dy <= -48 && nearBottom(96)) advanceChapter();
        else if (dy >= 48 && nearTop(96)) retreatChapter();
    }, {passive: true});

    document.addEventListener('scroll', onScroll, true);

    // 选区上报：原生选区工具条动作（划线/笔记）需要精确区间
    document.addEventListener('selectionchange', function () {
        var b = bridge();
        if (!b) return;
        var sel = window.getSelection();
        if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
            return;
        }
        var range = sel.getRangeAt(0);
        var root = content();
        if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return;
        var start = offsetOfPoint(range.startContainer, range.startOffset);
        var end = offsetOfPoint(range.endContainer, range.endOffset);
        if (start < 0 || end < 0 || end <= start) return;
        var text = state.text.substring(start, end);
        try {
            b.onSelection(start, end, text);
        } catch (e) {
        }
    });

    function offsetOfPoint(node, offset) {
        var target = node.nodeType === 3 ? node : null;
        for (var i = 0; i < state.nodes.length; i++) {
            var entry = state.nodes[i];
            if (entry.node === target) return entry.start + Math.min(offset, entry.len);
            if (entry.node === node) return entry.start + Math.min(offset, entry.len);
        }
        // 容器节点：取第一个子文本节点
        if (node.nodeType === 1) {
            for (var j = 0; j < state.nodes.length; j++) {
                if (node.contains(state.nodes[j].node)) return state.nodes[j].start;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 对外 API

    var pendingRestore = -1;

    window.MiradReader = {
        /** 首次初始化（页面加载完成后调用）。 */
        init: function (config) {
            Object.assign(CFG, config || {});
            // 窗口模式先把初始章包成 <section>，再建模型（模型只覆盖活动章）
            wrapInitialSection();
            buildInitialModelAndLayout();
            state.ready = true;
            var b = bridge();
            if (b) {
                try {
                    b.onReady(state.pages, state.textLength);
                } catch (e) {
                }
            }
            if (pendingRestore >= 0) {
                var offset = pendingRestore;
                pendingRestore = -1;
                window.MiradReader.gotoOffset(offset, false);
            }
            reportState();
            watchResources();
            // 短章不足一屏时没有任何滚动事件：初始化后立刻补下一章，否则永远滚不动
            if (windowed()) {
                ensureWindows();
                window.setTimeout(function () {
                    if (windowed()) ensureWindows();
                }, 300);
            }
            try {
                console.log('MIRAD init mode=' + CFG.mode + ' windowed=' + windowed() +
                    ' docH=' + documentHeight() + ' vh=' + viewportHeight() +
                    ' secs=' + container().querySelectorAll('section.mirad-chapter').length +
                    ' nodes=' + state.nodes.length + ' pages=' + state.pages + ' page=' + state.page);
            } catch (e) {
            }
        },

        /** 只更新样式/模式，不重新加载章节；尽量保持当前字符位置。 */
        applyConfig: function (config) {
            var anchor = offsetAtPage(state.page);
            Object.assign(CFG, config || {});
            buildInitialModelAndLayout();
            window.MiradReader.gotoOffset(anchor, false);
        },

        /**
         * 只替换主题样式（字号/行距/段距/配色等），跳过重建文本模型。
         * 拖动字号、行距滑杆时走这条快路径，否则每帧重建整章 DOM 文本模型会明显卡顿。
         */
        setThemeCssOnly: function (css, config) {
            var anchor = offsetAtPage(state.page);
            Object.assign(CFG, config || {});
            var element = document.getElementById('mirad-theme');
            if (element) {
                element.textContent = css;
            } else {
                element = document.createElement('style');
                element.id = 'mirad-theme';
                element.textContent = css;
                document.head.appendChild(element);
            }
            applyLayout();
            window.MiradReader.gotoOffset(anchor, false);
            return state.pages;
        },

        /** 替换主题样式表（阅读中改字号/主题/背景时调用）。 */
        setThemeCss: function (css) {
            var anchor = offsetAtPage(state.page);
            var element = document.getElementById('mirad-theme');
            if (element) {
                element.textContent = css;
            } else {
                element = document.createElement('style');
                element.id = 'mirad-theme';
                element.textContent = css;
                document.head.appendChild(element);
            }
            buildInitialModelAndLayout();
            window.MiradReader.gotoOffset(anchor, false);
            return state.pages;
        },

        /** 重新测量（图片/字体加载完成后调用）。 */
        relayout: function (keepOffset) {
            var anchor = offsetAtPage(state.page);
            buildInitialModelAndLayout();
            if (keepOffset !== false) {
                window.MiradReader.gotoOffset(anchor, false);
            }
            return state.pages;
        },

        getState: function () {
            return JSON.stringify({
                ready: state.ready,
                page: state.page,
                pages: state.pages,
                offset: offsetAtPage(state.page),
                textLength: state.textLength,
                mode: CFG.mode
            });
        },

        getText: function () {
            return state.text;
        },

        nextPage: function () {
            return window.MiradReader.gotoPage(state.page + 1, true);
        },

        prevPage: function () {
            return window.MiradReader.gotoPage(state.page - 1, true);
        },

        /**
         * 上下滚动模式下的"翻一屏"：按 85% 屏高平滑滚动，而不是整页跳，
         * 这样点击左右两侧的体感与手指滑动一致。
         */
        scrollStep: function (direction) {
            var h = viewportHeight();
            var max = Math.max(0, document.documentElement.scrollHeight - h);
            var target = (window.scrollY || 0) + (direction >= 0 ? 1 : -1) * h * 0.85;
            // 已经在底部还继续往下翻 → 用户意图是继续读下一章（原地换章，不重载）
            if (direction >= 0 && atBottom()) {
                advanceChapter();
                return true;
            }
            // 已经在章首还继续往回翻 → 回到上一章末尾
            if (direction < 0 && atTop()) {
                retreatChapter();
                return true;
            }
            animateScrollTop(Math.max(0, Math.min(target, max)));
            return true;
        },

        gotoPage: function (page, animate) {
            var target = Math.min(Math.max(page | 0, 0), state.pages - 1);
            var changed = target !== state.page;
            state.page = target;
            applyPosition(!!animate);
            return changed;
        },

        gotoOffset: function (offset, animate) {
            if (!state.ready) {
                pendingRestore = offset;
                return false;
            }
            var page = pageFromOffset(offset);
            if (CFG.mode !== 'PAGED') {
                // 直接用 pageFromOffset 的返回值：滚动模式下它就是精确测量出的流内位置。
                // 旧实现退回 nearestPoint(offset).pos（段落起点），于是长段落里的跳转又落回段首——
                // 与 PAGED 分支修掉的是同一个病。
                if (animate) animateScrollTop(Math.max(0, page - 8));
                else window.scrollTo(0, Math.max(0, page - 8));
                state.page = Math.floor((window.scrollY || 0) / viewportHeight());
                reportState();
                return true;
            }
            state.page = Math.min(Math.max(page, 0), state.pages - 1);
            applyPosition(!!animate);
            return true;
        },

        /** 当前页的起始字符偏移。 */
        currentOffset: function () {
            return offsetAtPage(state.page);
        },

        renderHighlights: function (highlights) {
            renderHighlights(highlights || []);
            if (CFG.mode === 'PAGED') {
                applyLayout();
            }
            return state.textLength;
        },

        markSearch: function (start, end) {
            renderSingleMark(start, end, 'mirad-search');
            return true;
        },

        markTts: function (start, end) {
            renderSingleMark(start, end, 'mirad-tts');
            return true;
        },

        clearTts: function () {
            renderSingleMark(0, 0, 'mirad-tts');
            return true;
        },

        /**
         * 在 JS 文本模型中就近查找一段文本（用于跨端偏移不一致时精确定位），
         * 找到后可选地高亮并滚动过去。
         */
        findText: function (needle, approxOffset, mark) {
            if (!needle) return -1;
            var hay = state.text;
            var idx = hay.indexOf(needle, Math.max(0, approxOffset | 0));
            if (idx < 0) idx = hay.indexOf(needle);
            if (idx < 0) {
                var trimmed = needle.replace(/^\s+|\s+$/g, '');
                if (trimmed.length > 4) {
                    idx = hay.indexOf(trimmed);
                    if (idx >= 0) {
                        window.MiradReader.gotoOffset(idx, true);
                        if (mark) renderSingleMark(idx, idx + trimmed.length, 'mirad-search');
                        return idx;
                    }
                }
                return -1;
            }
            window.MiradReader.gotoOffset(idx, true);
            if (mark) {
                renderSingleMark(idx, idx + needle.length, 'mirad-search');
            }
            return idx;
        },

        isReady: function () {
            return state.ready;
        }
    };

    function buildInitialModelAndLayout() {
        buildTextModel();
        applyLayout();
    }

    /** 图片/字体加载完成后重新分页（保持当前字符位置）。 */
    function watchResources() {
        var pending = 0;
        var finish = function () {
            pending--;
            if (pending <= 0) {
                window.MiradReader.relayout(true);
            }
        };
        var imgs = document.images || [];
        for (var i = 0; i < imgs.length; i++) {
            if (!imgs[i].complete) {
                pending++;
                imgs[i].addEventListener('load', finish);
                imgs[i].addEventListener('error', finish);
            }
        }
        if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
            document.fonts.ready.then(function () {
                window.MiradReader.relayout(true);
            });
        }
    }

    window.addEventListener('resize', function () {
        window.MiradReader.relayout(true);
    });
})();
