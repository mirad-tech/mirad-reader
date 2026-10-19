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

    // 程序滚动（点按翻屏 / 定位动画 / 自动下滑）共用一套可取消机制。
    // 任何新手势、新定位或模式变化都要先取消旧动画，避免两个循环同时写 scrollTop。
    var pageMotion = {active: false, token: 0, raf: 0, target: 0};
    var scrollMotion = {token: 0, raf: 0};

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
        // 只有显式开启才进入窗口引擎；缺省/旧调用方一律走稳定的单章路径。
        return CFG.mode === 'SCROLL' && CFG.windowedScroll === true;
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

    /** 真正的滚动容器（body/html 谁在滚以浏览器为准）。 */
    function scrollEl() {
        return document.scrollingElement || document.documentElement;
    }

    /**
     * 当前滚动页码信息：pages 与 page 用**同一份**高度与视口，且 page 一定被 clamp。
     *
     * 旧实现的 pages 把 padTop/padBottom 又加在 scrollHeight 上（这两段留白其实已经在
     * scrollHeight 里了），算出的页数偏小；page 又由 scrollY/h 独立算、从不 clamp，
     * 于是换章/图片展开后会出现「第 2/1 页」这种 page > pages 的显示。
     */
    function scrollPageInfo() {
        var el = scrollEl();
        var h = viewportHeight();
        var total = Math.max(1, el ? el.scrollHeight : viewportHeight());
        var pages = Math.max(1, Math.ceil(total / h));
        var page = Math.min(pages - 1, Math.max(0, Math.floor((window.scrollY || 0) / h)));
        return { pages: pages, page: page };
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
            state.pages = scrollPageInfo().pages;
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

    /**
     * 在字符区间 [lo, hi) 内二分，找出"流内位置 >= targetPos"的最小字符偏移。
     * 逐点用 [precisePos] 测量（它本身是节点二分 + 一次 Range 测量），因此不需要扫描所有字符。
     */
    function charAtFlowPos(targetPos, lo, hi) {
        if (lo >= hi) return null;
        var low = lo;
        var high = hi - 1;
        var best = -1;
        while (low <= high) {
            var mid = (low + high) >> 1;
            var pos = precisePos(mid);
            if (pos === null) return null;
            if (pos >= targetPos - 2) {
                best = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return best < 0 ? hi : best;
    }

    /**
     * 当前页（分页）或当前滚动位置（滚动）对应的**实际可见字符**偏移。
     *
     * 旧实现：在段落起点表里找第一个 pos >= 目标位置的条目并直接返回它 —— 当当前段落跨页、
     * 后面还有段落时，返回的其实是**尚未读到的后一段**，于是"记下锚点再恢复"会往后跳好几页。
     * 现在段落表只用于把查找区间收窄到 [上一段起点, 下一段起点)，真正的答案由 Range 测量二分给出。
     */
    function offsetAtPage(page) {
        var target = CFG.mode === 'PAGED' ? page * step() : (window.scrollY || 0);
        var points = state.points;
        var before = null;
        var after = null;
        for (var i = 0; i < points.length; i++) {
            if (points[i].pos >= target - 2) {
                after = points[i];
                break;
            }
            before = points[i];
        }
        var lo = before ? before.start : 0;
        var hi = after ? after.start : state.textLength;
        if (hi <= lo) hi = Math.min(state.textLength, lo + 1);
        var exact = charAtFlowPos(target, lo, hi);
        if (exact !== null) return exact;
        // 测量不可用时退回段落表（至少不会退到章末或章首）
        return after ? after.start : (before ? before.start : 0);
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
        // 定位是新的位置意图：先结束旧的滚动动画，保证同一时刻只有一个写入者。
        cancelScrollMotion();
        if (CFG.mode === 'PAGED') {
            var x = state.page * step();
            if (animate && CFG.anim === 'SLIDE') {
                animateScrollLeft(c, x);
            } else {
                c.scrollLeft = x;
            }
        } else {
            // 用 clamp 后的页号算 scrollTop，避免未 clamp 的 page 把视图滚到文档之外
            var idx = Math.max(0, Math.min(state.page, state.pages - 1));
            var y = idx * viewportHeight();
            if (animate && CFG.anim === 'SLIDE') {
                animateScrollTop(y);
            } else {
                window.scrollTo(0, y);
            }
        }
        reportState();
    }

    /** 取消水平翻页动画。snap=true 时直接落到目标列，避免停在两页之间。 */
    function cancelPageMotion(snap) {
        if (pageMotion.raf) {
            window.cancelAnimationFrame(pageMotion.raf);
            pageMotion.raf = 0;
        }
        pageMotion.token++;
        if (pageMotion.active && snap) {
            var c = content();
            if (c && CFG.mode === 'PAGED') c.scrollLeft = pageMotion.target;
        }
        pageMotion.active = false;
    }

    /** 取消纵向定位/翻屏/自动下滑动画。不改变当前位置，交给新的手势或新的目标接管。 */
    function cancelScrollMotion() {
        scrollMotion.token++;
        if (scrollMotion.raf) {
            window.cancelAnimationFrame(scrollMotion.raf);
            scrollMotion.raf = 0;
        }
    }

    function animateScrollLeft(el, target) {
        cancelPageMotion(false);
        var token = pageMotion.token;
        pageMotion.active = true;
        pageMotion.target = target;
        var start = el.scrollLeft;
        var delta = target - start;
        if (Math.abs(delta) < 1) {
            pageMotion.active = false;
            el.scrollLeft = target;
            return;
        }
        var duration = Math.min(320, 140 + Math.abs(delta) * 0.35);
        var startTime = null;
        function frame(ts) {
            if (token !== pageMotion.token) return;
            if (startTime === null) startTime = ts;
            var t = Math.min(1, (ts - startTime) / duration);
            var eased = 1 - Math.pow(1 - t, 3);
            el.scrollLeft = start + delta * eased;
            if (t < 1) {
                pageMotion.raf = window.requestAnimationFrame(frame);
            } else {
                pageMotion.active = false;
                pageMotion.raf = 0;
                reportState();
            }
        }
        pageMotion.raf = window.requestAnimationFrame(frame);
    }

    function animateScrollTop(target) {
        cancelScrollMotion();
        var token = scrollMotion.token;
        var start = window.scrollY || 0;
        var duration = 260;
        var startTime = null;
        function frame(ts) {
            if (token !== scrollMotion.token) return;
            if (startTime === null) startTime = ts;
            var t = Math.min(1, (ts - startTime) / duration);
            var eased = 1 - Math.pow(1 - t, 3);
            window.scrollTo(0, start + (target - start) * eased);
            if (t < 1) {
                scrollMotion.raf = window.requestAnimationFrame(frame);
            } else {
                scrollMotion.raf = 0;
                reportState();
            }
        }
        scrollMotion.raf = window.requestAnimationFrame(frame);
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
        // 上报前统一收口：滚动模式下页码/页数同源重算，其余模式至少保证 page <= pages-1。
        // 这一层是"永远不把 page > pages 报给原生"的最后保证。
        if (CFG.mode === 'SCROLL' && !windowed()) {
            var info = scrollPageInfo();
            state.pages = info.pages;
            state.page = info.page;
        } else if (state.pages > 0) {
            state.page = Math.max(0, Math.min(state.page, state.pages - 1));
        }
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
    var lastScrollReportAt = 0;
    /** 已经为哪一章发过"到底"信号。按章记录，切章自动失效，且改字号重排不会误触发。 */
    var bottomSentFor = null;

    // 用户手指/惯性滚动状态：只在这段时间内推迟资源重排（图片/字体展开），
    // 避免拖动过程中被"恢复到旧锚点"打断。
    var userScroll = {touching: false, driven: false, idleTimer: null};

    function nowMs() {
        return (window.performance && window.performance.now) ? window.performance.now() : Date.now();
    }

    function isUserScrolling() {
        return userScroll.touching || userScroll.driven;
    }

    /** 滚动事件延续"用户正在滚"的判定；惯性滚动也会持续续期。 */
    function markUserScroll() {
        userScroll.driven = true;
        if (userScroll.idleTimer) window.clearTimeout(userScroll.idleTimer);
        userScroll.idleTimer = window.setTimeout(function () {
            userScroll.idleTimer = null;
            userScroll.driven = false;
            flushDeferredRelayout();
        }, 180);
    }

    // ---------------------------------------------------------------- 自动下滑
    //
    // 单一 requestAnimationFrame 循环：按真实时间差累计位移并保留小数余量；
    // 长帧最多按 50ms 计算，切后台回来不会突然跳过一段。
    // 只在启动/暂停/调速/结束时与原生同步，不逐帧调用 JS Bridge。

    var autoScroll = {active: false, seconds: 30, pxPerMs: 0, raf: 0, lastTs: 0, acc: 0, endSentFor: null};

    function readableHeight() {
        return Math.max(1, viewportHeight() - (CFG.padTop || 0) - (CFG.padBottom || 0));
    }

    function normalizeAutoSeconds(seconds) {
        var value = Math.round(Number(seconds) || 0);
        if (!value) value = 30;
        return Math.max(5, Math.min(120, value));
    }

    function autoScrollSpeed(seconds) {
        // 一屏 = 扣除正文上下安全留白后的可读高度。
        return readableHeight() / (normalizeAutoSeconds(seconds) * 1000);
    }

    function stopAutoScrollInternal() {
        autoScroll.active = false;
        if (autoScroll.raf) {
            window.cancelAnimationFrame(autoScroll.raf);
            autoScroll.raf = 0;
        }
        autoScroll.lastTs = 0;
        autoScroll.acc = 0;
    }

    function notifyAutoScrollEnd() {
        stopAutoScrollInternal();
        var idx = currentChapterIndex();
        if (autoScroll.endSentFor === idx) return;
        autoScroll.endSentFor = idx;
        var b = bridge();
        if (b && b.onAutoScrollEnd) {
            try {
                b.onAutoScrollEnd(idx);
            } catch (e) {
            }
        }
    }

    function autoScrollFrame(ts) {
        autoScroll.raf = 0;
        if (!autoScroll.active || CFG.mode !== 'SCROLL') return;
        // 短章或已经停在章末：立刻通知原生进入"停留 → 接下一章"。
        if (atBottom(2)) {
            notifyAutoScrollEnd();
            return;
        }
        if (!autoScroll.lastTs) autoScroll.lastTs = ts;
        var dt = Math.min(50, Math.max(0, ts - autoScroll.lastTs));
        autoScroll.lastTs = ts;
        if (dt > 0) {
            autoScroll.acc += autoScroll.pxPerMs * dt;
            var delta = Math.floor(autoScroll.acc);
            if (delta > 0) {
                autoScroll.acc -= delta;
                window.scrollTo(0, (window.scrollY || 0) + delta);
            }
        }
        if (atBottom(2)) {
            notifyAutoScrollEnd();
            return;
        }
        autoScroll.raf = window.requestAnimationFrame(autoScrollFrame);
    }

    function setAutoScrollInternal(active, secondsPerScreen) {
        autoScroll.seconds = normalizeAutoSeconds(secondsPerScreen);
        autoScroll.pxPerMs = autoScrollSpeed(autoScroll.seconds);
        if (!active || CFG.mode !== 'SCROLL' || !state.ready) {
            stopAutoScrollInternal();
            autoScroll.endSentFor = null;
            return;
        }
        // 已在运行：只更新速度。保证任何时刻只有一个 rAF 循环。
        if (autoScroll.active) return;
        cancelScrollMotion();
        cancelPageMotion(true);
        autoScroll.active = true;
        autoScroll.lastTs = 0;
        autoScroll.acc = 0;
        autoScroll.endSentFor = null;
        autoScroll.raf = window.requestAnimationFrame(autoScrollFrame);
    }

    function chapterKey() {
        var c = activeRoot();
        if (!c || !c.getAttribute) return '';
        return c.getAttribute('data-chapter') || '';
    }

    /** 是否已经滚到本章底部（留 8px 容差，避免小数像素永远差一点）。 */
    function atBottom(slack) {
        var tol = (typeof slack === 'number') ? slack : 8;
        var top = window.scrollY || 0;
        var view = viewportHeight();
        var bodyHeight = document.body ? document.body.scrollHeight : 0;
        var total = Math.max(document.documentElement.scrollHeight, bodyHeight);
        return total > 0 && top + view >= total - tol;
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

    /**
     * 滚动位置回报：最多每 200ms 一次；滚动停止后再补报一次**最终位置**。
     * 不依赖"页号变了"才回报（长页内滚动同样会有进度变化），也不在这里重建文本模型。
     */
    function reportScrollProgress(force) {
        if (CFG.mode !== 'SCROLL') return;
        var top = window.scrollY || 0;
        var view = viewportHeight();
        if (windowed()) {
            // 窗口模式：先补章、再确认视口落在哪一章，页号一律按活动章计算
            ensureWindows();
            refreshActive();
            var relTop = Math.max(0, top - (win.active ? sectionTop(win.active) : 0));
            var winPage = Math.floor(relTop / view);
            if (winPage === lastReportedPage && !force) return;
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
        var info = scrollPageInfo();
        state.pages = info.pages;
        state.page = info.page;
        lastReportedPage = state.page;
        reportState();
    }

    function onScroll() {
        if (CFG.mode !== 'SCROLL') return;
        // 触摸期间/惯性期间续期，资源重排会推迟到滚动稳定之后。
        if (userScroll.touching || userScroll.driven) markUserScroll();
        var now = nowMs();
        if (now - lastScrollReportAt >= 200) {
            lastScrollReportAt = now;
            reportScrollProgress(false);
        }
        if (scrollTimer) window.clearTimeout(scrollTimer);
        scrollTimer = window.setTimeout(function () {
            scrollTimer = null;
            lastScrollReportAt = nowMs();
            // 最终位置即使和上次同页也要补报：进度、书签判断都依赖它。
            reportScrollProgress(true);
            if (!isUserScrolling()) flushDeferredRelayout();
        }, 160);
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
    // ---------------------------------------------------------------- 换章资源同步
    //
    // 换章时除了正文，还要同步：书籍样式表/内联样式、原书 html/body 呈现属性、
    // 并重新监听新章的图片与字体（它们可能晚于布局才展开）。
    // resourceGen 是换章代次：旧章的资源回调不得影响新章的布局、标题或定位。

    var resourceGen = 0;
    var appBodyBase = '';     // 应用自己写在 body 上的类（如 mirad-txt），换章时保留
    var appHtmlBase = '';
    var relayoutScheduled = false;
    var deferredRelayoutGen = null;

    /**
     * 合并同一帧内的多个资源事件，避免重复全量重排。
     * 手动拖动或惯性期间只登记、不执行：滚动稳定后再处理，避免重排把阅读锚点拉回旧位置。
     */
    function scheduleRelayout(gen) {
        if (gen !== undefined && gen !== resourceGen) return;
        if (isUserScrolling()) {
            deferredRelayoutGen = (gen === undefined) ? resourceGen : gen;
            return;
        }
        scheduleRelayoutNow(gen);
    }

    function scheduleRelayoutNow(gen) {
        if (gen !== undefined && gen !== resourceGen) return;
        if (relayoutScheduled) return;
        relayoutScheduled = true;
        var run = function () {
            relayoutScheduled = false;
            if (gen !== undefined && gen !== resourceGen) return;
            if (!state.ready) return;
            // 调度期间用户又开始拖动：继续推迟，不能在拖动中恢复旧锚点。
            if (isUserScrolling()) {
                deferredRelayoutGen = (gen === undefined) ? resourceGen : gen;
                return;
            }
            window.MiradReader.relayout(true);
            // 向后换章且用户还没动过：图片展开后仍应停在新的章末
            if (state.pendingEndAnchor) {
                window.scrollTo(0, document.documentElement.scrollHeight);
                state.page = Math.max(0, Math.floor((window.scrollY || 0) / viewportHeight()));
                state.pendingEndAnchor = false;
            }
            reportState();
        };
        if (window.requestAnimationFrame) requestAnimationFrame(run);
        else window.setTimeout(run, 16);
    }

    /** 用户滚动稳定后，补做被推迟的资源重排（代次已过期则丢弃）。 */
    function flushDeferredRelayout() {
        if (deferredRelayoutGen === null) return;
        var gen = deferredRelayoutGen;
        deferredRelayoutGen = null;
        if (gen !== resourceGen) return;
        scheduleRelayoutNow(gen);
    }

    /** 保留应用类，同时套用书籍类（同名去重）。 */
    function mergeBookClasses(base, extra) {
        var result = (base || '').split(/\s+/).filter(function (c) { return c; });
        (extra || '').split(/\s+/).forEach(function (c) {
            if (c && result.indexOf(c) < 0) result.push(c);
        });
        return result.join(' ');
    }

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
        var resolvedBase = incomingBase ? incomingBase.getAttribute('href') : null;

        // 1) 目标章的书籍样式（应用注入的 #mirad-theme 不复制，它是当前主题）
        var bookNodes = [];
        if (parsed.head) {
            var candidates = parsed.head.querySelectorAll('link[rel~="stylesheet"], style');
            for (var c1 = 0; c1 < candidates.length; c1++) {
                if (candidates[c1].id === 'mirad-theme') continue;
                bookNodes.push(candidates[c1]);
            }
        }
        var bookBodyClass = parsed.body ? (parsed.body.getAttribute('class') || '') : '';
        var bookBodyStyle = parsed.body ? parsed.body.getAttribute('style') : null;
        var bookHtmlClass = parsed.documentElement ? (parsed.documentElement.getAttribute('class') || '') : '';
        var bookDir = parsed.documentElement ? parsed.documentElement.getAttribute('dir') : null;
        var bookLang = parsed.documentElement ? parsed.documentElement.getAttribute('lang') : null;

        var gen = ++resourceGen;

        // 2) base：目标章没有 base 就退回文档地址，避免继续沿用上一章的相对路径
        if (resolvedBase) {
            if (!liveBase) {
                liveBase = document.createElement('base');
                document.head.appendChild(liveBase);
            }
            liveBase.setAttribute('href', resolvedBase);
        } else if (liveBase && liveBase.parentNode) {
            liveBase.parentNode.removeChild(liveBase);
        }

        // 3) 移除上一章的书籍样式（#mirad-theme 属于应用，不动）
        var stale = document.querySelectorAll('link[data-mirad-book="1"], style[data-mirad-book="1"]');
        for (var s1 = 0; s1 < stale.length; s1++) {
            if (stale[s1].parentNode) stale[s1].parentNode.removeChild(stale[s1]);
        }

        // 4) 装上目标章样式，等它们加载完成或明确失败（有超时，不无限等待）
        var styleReady = [];
        for (var n1 = 0; n1 < bookNodes.length; n1++) {
            var clone = bookNodes[n1].cloneNode(true);
            clone.setAttribute('data-mirad-book', '1');
            if (clone.tagName === 'LINK') {
                var href = clone.getAttribute('href');
                if (href) {
                    try {
                        clone.setAttribute('href', new URL(href, resolvedBase || document.baseURI).href);
                    } catch (e) {
                    }
                }
                styleReady.push(new Promise(function (resolve) {
                    var done = false;
                    var finishStyle = function () {
                        if (!done) {
                            done = true;
                            resolve();
                        }
                    };
                    clone.addEventListener('load', finishStyle);
                    clone.addEventListener('error', finishStyle);
                    window.setTimeout(finishStyle, 1500);
                }));
            }
            document.head.appendChild(clone);
        }

        // 5) 原书 html/body 呈现属性：目标章没有就清除，避免继承上一章的特殊样式
        document.body.className = mergeBookClasses(appBodyBase, bookBodyClass);
        if (bookBodyStyle) document.body.setAttribute('style', bookBodyStyle);
        else document.body.removeAttribute('style');
        document.documentElement.className = mergeBookClasses(appHtmlBase, bookHtmlClass);
        if (bookDir) document.documentElement.setAttribute('dir', bookDir);
        else document.documentElement.removeAttribute('dir');
        if (bookLang) document.documentElement.setAttribute('lang', bookLang);
        else document.documentElement.removeAttribute('lang');

        // 6) 换正文（innerHTML 不会执行书内 <script>，不引入新的脚本执行能力）
        live.innerHTML = incoming.innerHTML;
        for (var a1 = 0; a1 < incoming.attributes.length; a1++) {
            var attr = incoming.attributes[a1];
            if (attr && attr.name) live.setAttribute(attr.name, attr.value);
        }
        state.ready = false;
        bottomSentFor = null;
        lastReportedPage = -1;
        state.pendingEndAnchor = !!toBottom;
        // 先让新章内容立刻出现在屏幕上（滚动到顶部），再重建模型/布局。
        if (!toBottom) window.scrollTo(0, 0);
        var finish = function () {
            if (gen !== resourceGen) return;   // 更快的换章已经接管
            // 关键：applyLayout() 会按 state.page 恢复滚动位置。原地换章时这个值还是
            // 上一章的页号，不清零就会把新章滚到"后面"去。
            state.page = 0;
            buildInitialModelAndLayout();
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
            // 新章的图片/字体可能晚于布局才展开：重新监听并重排（按代次丢弃旧回调）
            watchResources(gen);
        };
        var start = function () {
            if (window.requestAnimationFrame) {
                requestAnimationFrame(function () { requestAnimationFrame(finish); });
            } else {
                finish();
            }
        };
        if (styleReady.length && window.Promise) {
            Promise.all(styleReady).then(start);
        } else {
            start();
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

    // 触摸手势：
    //  - 手指按下立即暂停自动下滑、取消程序滚动动画（同一次手势随后仍可正常拖动）
    //  - 手动跨章只在**手势开始时已位于边界 8px 内**、且继续向外滑动至少 48px 才执行
    //  - 拖动/惯性期间由 scheduleRelayout 推迟资源重排，滚动稳定后再补做
    var gesture = {startY: null, startAtTop: false, startAtBottom: false};

    document.addEventListener('touchstart', function (e) {
        var y = (e.touches && e.touches.length) ? e.touches[0].clientY : null;
        gesture.startY = y;
        gesture.startAtTop = CFG.mode === 'SCROLL' && nearTop(8);
        gesture.startAtBottom = CFG.mode === 'SCROLL' && nearBottom(8);
        userScroll.touching = true;
        if (userScroll.idleTimer) {
            window.clearTimeout(userScroll.idleTimer);
            userScroll.idleTimer = null;
        }
        // 新手指手势优先：先停掉自动下滑和所有程序滚动，避免两个写入者抢位置。
        stopAutoScrollInternal();
        cancelScrollMotion();
        cancelPageMotion(true);
        // 用户开始操作后，取消"保持章末"的意图，改以当前锚点为准
        state.pendingEndAnchor = false;
    }, {passive: true});

    document.addEventListener('touchend', function (e) {
        userScroll.touching = false;
        if (CFG.mode === 'SCROLL') markUserScroll();
        if (CFG.mode !== 'SCROLL' || gesture.startY === null) {
            gesture.startY = null;
            return;
        }
        var endY = (e.changedTouches && e.changedTouches.length) ? e.changedTouches[0].clientY : null;
        var dy = (endY === null) ? 0 : (endY - gesture.startY);
        var startedAtTop = gesture.startAtTop;
        var startedAtBottom = gesture.startAtBottom;
        gesture.startY = null;
        gesture.startAtTop = false;
        gesture.startAtBottom = false;
        // 短章一屏内同时是章首和章末：按滑动方向决定上一章/下一章。
        // 每次手势最多换一章（只执行一个分支）。
        if (dy <= -48 && startedAtBottom) {
            advanceChapter();
        } else if (dy >= 48 && startedAtTop) {
            retreatChapter();
        }
    }, {passive: true});

    document.addEventListener('touchcancel', function () {
        userScroll.touching = false;
        gesture.startY = null;
        gesture.startAtTop = false;
        gesture.startAtBottom = false;
        if (CFG.mode === 'SCROLL') markUserScroll();
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
            // 记录应用自己写在 html/body 上的类，换章同步书籍属性时不能把它们覆盖掉
            appBodyBase = (document.body && document.body.className) || '';
            appHtmlBase = (document.documentElement && document.documentElement.className) || '';
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
        },

        /** 只更新样式/模式，不重新加载章节；尽量保持当前字符位置。 */
        applyConfig: function (config) {
            var anchor = offsetAtPage(state.page);
            var prevMode = CFG.mode;
            var prevWindowed = CFG.windowedScroll;
            Object.assign(CFG, config || {});
            if (prevMode !== CFG.mode || prevWindowed !== CFG.windowedScroll) {
                // 模式变化：旧模式的自动下滑/定位动画不能继续写滚动位置。
                stopAutoScrollInternal();
                cancelScrollMotion();
                cancelPageMotion(false);
            }
            buildInitialModelAndLayout();
            window.MiradReader.gotoOffset(anchor, false);
        },

        /**
         * 只替换主题样式（字号/行距/段距/配色等），跳过重建文本模型。
         * 拖动字号、行距滑杆时走这条快路径，否则每帧重建整章 DOM 文本模型会明显卡顿。
         */
        setThemeCssOnly: function (css, config) {
            var anchor = offsetAtPage(state.page);
            var prevMode = CFG.mode;
            var prevWindowed = CFG.windowedScroll;
            Object.assign(CFG, config || {});
            if (prevMode !== CFG.mode || prevWindowed !== CFG.windowedScroll) {
                stopAutoScrollInternal();
                cancelScrollMotion();
                cancelPageMotion(false);
            }
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
                mode: CFG.mode,
                autoScroll: autoScroll.active,
                scrollY: window.scrollY || 0
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

        /**
         * 自动阅读：启动/停止/调速。只在状态切换时由原生调用，**不逐帧过桥**。
         * secondsPerScreen 是「秒/屏」；一屏按扣除正文上下安全留白后的可读高度计算。
         */
        setAutoScroll: function (active, secondsPerScreen) {
            setAutoScrollInternal(!!active, secondsPerScreen);
            return autoScroll.active;
        },

        /** 用户手势或模式变化时取消所有程序滚动，避免旧动画继续写位置。 */
        cancelProgrammaticScroll: function () {
            cancelScrollMotion();
            cancelPageMotion(true);
            stopAutoScrollInternal();
            return true;
        },

        /** 自动滚动到章末后由原生调用：进入下一章（预取优先，失败走原生兜底）。 */
        advanceChapterForAuto: function () {
            if (CFG.mode !== 'SCROLL' || !state.ready) return false;
            var idx = currentChapterIndex();
            if (idx < 0) return false;
            if (idx >= lastChapterIndex()) {
                notifyBottomOnce();
                return false;
            }
            return advanceChapter();
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
                cancelScrollMotion();
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
    function watchResources(gen) {
        var pending = 0;
        var finish = function () {
            pending--;
            if (pending <= 0) scheduleRelayout(gen);
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
                scheduleRelayout(gen);
            });
        }
    }

    window.addEventListener('resize', function () {
        window.MiradReader.relayout(true);
    });
})();
