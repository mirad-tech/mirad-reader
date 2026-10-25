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
        anim: 'SLIDE',      // NONE | SLIDE | COVER
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
    var themeAnchor = null;

    function content() {
        return document.getElementById('mirad-content') || document.body;
    }

    function viewportWidth() {
        return Math.max(1, Math.round(document.documentElement.clientWidth || window.innerWidth));
    }

    function viewportHeight() {
        return Math.max(1, Math.round(document.documentElement.clientHeight || window.innerHeight));
    }

    // Paint-only clipping keeps scrolling text out of physical cutouts. The background
    // layers and viewport/page/anchor geometry stay unchanged; ordinary screens are not clipped.
    function updateContentClip() {
        var c = content();
        var clipTop = Math.max(0, CFG.clipTop || 0);
        var clipBottom = Math.max(0, CFG.clipBottom || 0);
        if (CFG.mode !== 'SCROLL' || (!clipTop && !clipBottom)) {
            c.style.clipPath = 'none';
            return;
        }
        var rect = c.getBoundingClientRect();
        var top = Math.max(0, clipTop - rect.top);
        var bottom = Math.max(0, rect.bottom - viewportHeight() + clipBottom);
        c.style.clipPath = 'inset(' + top + 'px 0px ' + bottom + 'px 0px)';
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
        if (location.pathname.indexOf('/chapter/') >= 0) {
            return location.pathname.replace(/\/chapter\/\d+$/, '/fragment/' + index) + location.search;
        }
        return '/fragment/' + index;
    }

    /** 首次进入窗口模式：把初始章内容包成 <section>，保持结构统一。 */
    function wrapInitialSection() {
        if (!windowed()) return;
        var c = container();
        // 已有 section 时不动 win.active：多章窗口的初始 fixture/恢复场景里，
        // 活动章由 refreshActive 按滚动位置选出，这里指到第一个 section 反而会选错。
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

    /**
     * 退出窗口模式：只保留活动章并把内容解包回容器（单章形态），
     * 容器章号对齐活动章。运行中把滚动/排版切回单章路径时必须走这里，
     * 否则残留的 section 与窗口状态会让单章引擎读到错误的章号。
     */
    function collapseWindowToActive() {
        var c = container();
        var active = win.active;
        if (!c || !active || active.parentNode !== c) {
            win.active = null;
            return;
        }
        var idx = sectionIndex(active);
        var list = c.querySelectorAll('section.mirad-chapter');
        for (var i = 0; i < list.length; i++) {
            if (list[i] !== active && list[i].parentNode) list[i].parentNode.removeChild(list[i]);
        }
        while (active.firstChild) c.insertBefore(active.firstChild, active);
        c.removeChild(active);
        c.setAttribute('data-chapter', String(idx >= 0 ? idx : 0));
        win.active = null;
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

    function loadedRange() {
        var c = container();
        var list = c ? c.querySelectorAll('section.mirad-chapter') : [];
        if (!list || list.length === 0) return null;
        var min = Infinity, max = -Infinity;
        for (var i = 0; i < list.length; i++) {
            var idx = sectionIndex(list[i]);
            if (idx >= 0) {
                if (idx < min) min = idx;
                if (idx > max) max = idx;
            }
        }
        if (min === Infinity) return null;
        return { min: min, max: max };
    }

    /** 窗口固定 3 章（活动章前后各 1）；裁上方时按视口相对坐标补偿滚动，避免视口跳动与回顶。 */
    /** 窗口裁剪：上方裁远端并补偿滚动，下方确保保留至少 2.5 屏高，坚决不在裁剪内递归补章。 */
    function trimWindow() {
        if (!windowed() || !win.active) return;
        var c = container();
        var list = Array.prototype.slice.call(c.querySelectorAll('section.mirad-chapter'));
        if (list.length <= 3) return;
        var activeIdx = sectionIndex(win.active);
        var vh = viewportHeight();
        var retainedThemeAnchor = reusableThemeAnchor();

        // 记录活动章在删除前相对于视口顶部的精确坐标
        var anchorTopBefore = win.active.getBoundingClientRect().top;
        var removedAny = false;

        // 1. 处理上方章节：仅当章节在活动章上方两章以上 (idx < activeIdx - 1) 时裁剪
        for (var i = 0; i < list.length; i++) {
            var el = list[i];
            var idx = sectionIndex(el);
            if (idx < activeIdx - 1) {
                el.parentNode.removeChild(el);
                removedAny = true;
            }
        }

        // 2. 处理下方章节：从最末尾向内检查，必须保证删除后剩余总高度 >= 2.5 屏高才允许删
        var remainingList = Array.prototype.slice.call(c.querySelectorAll('section.mirad-chapter'));
        for (var j = remainingList.length - 1; j >= 0; j--) {
            var bottomEl = remainingList[j];
            var bIdx = sectionIndex(bottomEl);
            if (bIdx > activeIdx + 1) {
                var elHeight = bottomEl.getBoundingClientRect().height || 0;
                if (documentHeight() - elHeight >= vh * 2.5) {
                    bottomEl.parentNode.removeChild(bottomEl);
                    removedAny = true;
                } else {
                    // 下方高度已到临界值，停止向内裁剪以保护视口填满
                    break;
                }
            }
        }

        if (removedAny && win.active && win.active.parentNode) {
            // 删除上方节点后，测量活动章的新视口相对位置并补偿差值，确保视口正文绝对稳定
            var anchorTopAfter = win.active.getBoundingClientRect().top;
            var drift = anchorTopAfter - anchorTopBefore;
            if (Math.abs(drift) > 0.5) {
                window.scrollBy(0, drift);
            }
        }
        // Removing earlier chapters changes document scrollY, not the reader's
        // logical position. Only retain an anchor that was current before trim.
        if (removedAny) rememberThemePosition(retainedThemeAnchor);
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
        if (!windowed()) return;
        var range = loadedRange();
        var active = win.active || sectionAt(window.scrollY || 0);
        var base = active ? sectionIndex(active) : -1;
        if (base < 0) base = parseInt(container().getAttribute('data-chapter'), 10);
        if (isNaN(base) || base < 0) base = 0;

        // 关键：以当前 DOM 中已挂载章节的最大/最小边界向外扩展，而不是以 active 为基准
        var idx;
        if (dir > 0) {
            idx = range ? (range.max + 1) : (base + 1);
        } else {
            idx = range ? (range.min - 1) : (base - 1);
        }

        if (idx < 0 || idx > lastChapterIndex()) return;
        if (container().querySelector('section.mirad-chapter[data-chapter="' + idx + '"]')) return;
        if (win.loading) return;
        win.loading = true;
        fetch(fragmentUrlOf(idx))
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                win.loading = false;
                // fetch 在途时用户可能已切回单章/翻页模式：迟到的补章插进单章容器
                // 会让正文显示两章、页数翻倍、偏移全部错位，必须丢弃。
                if (!windowed()) return;
                if (!html) return;
                var doc = new DOMParser().parseFromString(html, 'text/html');
                var sec = doc.querySelector('section.mirad-chapter');
                if (!sec) return;
                var before = documentHeight();
                var retainedThemeAnchor = dir < 0 ? reusableThemeAnchor() : null;
                insertSection(sec, idx, dir < 0);
                if (dir < 0) {
                    // 前插把内容整体下移，等量补偿 scrollY 才能保持在原处
                    var after = documentHeight();
                    window.scrollTo(0, Math.max(0, (window.scrollY || 0) + (after - before)));
                    rememberThemePosition(retainedThemeAnchor);
                }
                scheduleTrim();
                requestVisualSignal();
                // 连续短章保护：若插入后总高度仍不足两屏且尚未到达书末，持续向前推进加载下一章
                if (documentHeight() < viewportHeight() * 2) {
                    var curRange = loadedRange();
                    if (curRange && curRange.max < lastChapterIndex()) {
                        ensureSection(+1);
                    }
                }
            })
            .catch(function () { win.loading = false; requestVisualSignal(); });
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
            // 窗口模式：页数按活动章计算（页号也是章内值，两者同坐标系）；
            // 用整份窗口文档高度会出现"第 2/9 页"这类分子分母错位的页码。
            state.pages = (windowed() && win.active)
                ? Math.max(1, Math.ceil((win.active.getBoundingClientRect().height || h) / h))
                : scrollPageInfo().pages;
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
        updateContentClip();
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
        return (state.colW || viewportWidth()) + state.gap;
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
    function precisePos(offset, characterCenter) {
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
                ? (rect.left - rootRect.left + (c.scrollLeft || 0) + (characterCenter ? rect.width / 2 : 0))
                : (rect.top - rootRect.top);
        } catch (e) {
            return null;
        }
    }

    function pageFromOffset(offset) {
        var point = nearestPoint(offset);
        if (!point) return 0;
        // 段落级 points 只给到段落起点，用精确测量覆盖它（测不出来时退回段落起点）
        var precise = precisePos(offset, true);
        var pos = (precise === null || precise === undefined) ? point.pos : precise;
        if (CFG.mode !== 'PAGED') return pos;
        // A character in the right half still belongs to its current column.
        // Use its center for fractional glyph overhang at a zero-gap boundary;
        // offsetAtPage uses the same coordinate when finding the first character.
        return Math.max(0, Math.floor(pos / step()));
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
            var pos = precisePos(mid, CFG.mode === 'PAGED');
            if (pos === null) return null;
            if (pos >= targetPos - (CFG.mode === 'PAGED' ? 0 : 2)) {
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
        // precisePos/points are relative to the active chapter, not the document.
        // Earlier mounted chapters and the content's top margin are not text offsets.
        var root = activeRoot();
        var target = CFG.mode === 'PAGED' ? page * step()
            : Math.max(0, root ? -root.getBoundingClientRect().top : 0);
        var points = state.points;
        var before = null;
        var after = null;
        for (var i = 0; i < points.length; i++) {
            if (points[i].pos >= target - (CFG.mode === 'PAGED' ? 0 : 2)) {
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
        var marks = document.querySelectorAll('mark.mirad-hl, mark.mirad-search, span.mirad-tts, mark.mirad-tts');
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

    function applySectionSegments(sec, ranges) {
        if (!sec || !ranges || !ranges.length) return;
        var nodes = [];
        var count = 0;
        var lastB = null;
        var walker = document.createTreeWalker(sec, NodeFilter.SHOW_TEXT, null);
        var cur;
        while ((cur = walker.nextNode())) {
            var v = cur.nodeValue;
            if (!v) continue;
            var b = cur.parentElement;
            while (b && b !== sec && !isBlock(b)) b = b.parentElement;
            if (b !== lastB) {
                if (count > 0) count++;
                lastB = b;
            }
            nodes.push({node: cur, start: count, len: v.length});
            count += v.length;
        }
        var segments = [];
        for (var r = 0; r < ranges.length; r++) {
            var range = ranges[r];
            var from = Math.max(0, range.start);
            var to = Math.min(count, range.end);
            if (to <= from) continue;
            for (var i = 0; i < nodes.length; i++) {
                var entry = nodes[i];
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
        applySegments(segments);
    }

    /** 渲染全部划线（支持指定章节或批量按章节渲染）。 */
    function renderHighlights(highlights, targetChapter) {
        if (typeof targetChapter === 'number' && targetChapter >= 0) {
            var sec = container().querySelector('section.mirad-chapter[data-chapter="' + targetChapter + '"]');
            if (sec) {
                var oldMarks = sec.querySelectorAll('mark.mirad-hl');
                for (var i = 0; i < oldMarks.length; i++) {
                    var m = oldMarks[i];
                    var p = m.parentNode;
                    if (!p) continue;
                    while (m.firstChild) p.insertBefore(m.firstChild, m);
                    p.removeChild(m);
                }
                sec.normalize();
                if (highlights && highlights.length) {
                    var ranges = highlights.map(function (h) {
                        return {start: h.start, end: h.end, cls: h.note ? 'mirad-hl mirad-note' : 'mirad-hl'};
                    });
                    applySectionSegments(sec, ranges);
                }
                buildTextModel();
                return state.textLength;
            }
        }
        clearMarks();
        if (!highlights || !highlights.length) return state.textLength;

        var hasChapter = highlights.some(function (h) { return typeof h.chapter === 'number'; });
        if (hasChapter && windowed()) {
            var grouped = {};
            for (var j = 0; j < highlights.length; j++) {
                var item = highlights[j];
                var ch = typeof item.chapter === 'number' ? item.chapter : currentChapterIndex();
                if (!grouped[ch]) grouped[ch] = [];
                grouped[ch].push(item);
            }
            for (var chKey in grouped) {
                var targetSec = container().querySelector('section.mirad-chapter[data-chapter="' + chKey + '"]');
                if (targetSec) {
                    var rList = grouped[chKey].map(function (h) {
                        return {start: h.start, end: h.end, cls: h.note ? 'mirad-hl mirad-note' : 'mirad-hl'};
                    });
                    applySectionSegments(targetSec, rList);
                }
            }
            buildTextModel();
            return state.textLength;
        }

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

    // Semantic content changes drive bounded readability analysis. Native draw callbacks can be
    // caused by rendering the same WebView into another hardware target, so they are not a signal.
    var visualSignalsInstalled = false;
    var visualSignalTimer = null;
    var visualSignalDirty = false;
    var visualSignalSuspended = false;
    var visualSignalLastAt = -Infinity;
    var visualSignalState = '';
    var visualBackgroundUrl = '';
    var visualBackgroundImage = null;

    function visualAnimationsRunning() {
        if (!document.getAnimations) return false;
        var animations = document.getAnimations();
        for (var i = 0; i < animations.length; i++) {
            if (animations[i].playState === 'running' && animations[i].playbackRate !== 0) return true;
        }
        return false;
    }

    function stopVisualSignalTimer() {
        if (visualSignalTimer !== null) window.clearTimeout(visualSignalTimer);
        visualSignalTimer = null;
    }

    function flushVisualSignal() {
        visualSignalTimer = null;
        if (document.hidden || visualSignalSuspended || !state.ready) return;
        var animated = visualAnimationsRunning();
        if (!visualSignalDirty && !animated) return;
        watchVisualBackground();
        visualSignalDirty = false;
        visualSignalLastAt = nowMs();
        var b = bridge();
        if (b && b.onVisualChanged) {
            try { b.onVisualChanged(); } catch (e) { }
        }
        // A timer exists only for a pending event or a currently running CSS animation/transition.
        if (animated) visualSignalTimer = window.setTimeout(flushVisualSignal, 500);
    }

    function requestVisualSignal() {
        visualSignalDirty = true;
        if (!visualSignalsInstalled || document.hidden || visualSignalSuspended || !state.ready) return;
        if (visualSignalTimer === null) {
            visualSignalTimer = window.setTimeout(flushVisualSignal,
                Math.max(0, 500 - (nowMs() - visualSignalLastAt)));
        }
    }

    function watchVisualBackground() {
        var bg = document.getElementById('mirad-bg');
        var value = bg ? getComputedStyle(bg).backgroundImage : '';
        if (value === visualBackgroundUrl) return;
        visualBackgroundUrl = value;
        var match = /^url\(["']?(.*?)["']?\)$/.exec(value);
        if (!match) { visualBackgroundImage = null; return; }
        // CSS background images do not emit a DOM load event. Observe completion of this same
        // existing resource without inserting an element or changing the reading document.
        var image = new Image();
        visualBackgroundImage = image;
        image.onload = image.onerror = function () {
            if (visualBackgroundImage === image) requestVisualSignal();
        };
        image.src = match[1];
    }

    function installVisualSignals() {
        if (visualSignalsInstalled) { requestVisualSignal(); return; }
        visualSignalsInstalled = true;
        if (window.MutationObserver) {
            new MutationObserver(function (records) {
                var changed = records.some(function (record) {
                    if (record.type === 'attributes') {
                        return record.oldValue !== record.target.getAttribute(record.attributeName);
                    }
                    if (record.type === 'characterData') return record.oldValue !== record.target.data;
                    return record.addedNodes.length > 0 || record.removedNodes.length > 0;
                });
                if (changed) requestVisualSignal();
            }).observe(document.documentElement, {
                subtree: true, childList: true, attributes: true, attributeOldValue: true,
                characterData: true, characterDataOldValue: true
            });
        }
        document.addEventListener('scroll', requestVisualSignal, true);
        document.addEventListener('selectionchange', requestVisualSignal);
        document.addEventListener('load', requestVisualSignal, true);
        document.addEventListener('error', requestVisualSignal, true);
        ['animationstart', 'animationiteration', 'animationend', 'animationcancel',
            'transitionrun', 'transitionstart', 'transitionend', 'transitioncancel'].forEach(function (name) {
            document.addEventListener(name, requestVisualSignal, true);
        });
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) stopVisualSignalTimer();
            else requestVisualSignal();
        });
        window.addEventListener('pagehide', function () { visualSignalSuspended = true; stopVisualSignalTimer(); });
        window.addEventListener('pageshow', function () { visualSignalSuspended = false; requestVisualSignal(); });
        if (document.fonts) {
            document.fonts.addEventListener('loadingdone', requestVisualSignal);
            document.fonts.addEventListener('loadingerror', requestVisualSignal);
            document.fonts.ready.then(requestVisualSignal);
        }
        watchVisualBackground();
        requestVisualSignal();
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
        var visualState = [page, state.pages, offset, state.textLength, CFG.mode, window.scrollY || 0].join(':');
        if (visualState !== visualSignalState) { visualSignalState = visualState; requestVisualSignal(); }
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
                // page 与 pages 同源且 page <= pages-1：这里传 state.pages 会让原生短暂显示"第 N+1/N 页"。
                b.onState(Math.max(0, state.pages - 1), state.pages, state.textLength, state.textLength, window.scrollY || 0);
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
        updateContentClip();
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

    /** 当前章的索引：窗口模式取活动 section，否则取 #mirad-content 的 data-chapter。 */
    function currentChapterIndex() {
        if (windowed() && win.active) {
            var activeIdx = sectionIndex(win.active);
            if (activeIdx >= 0) return activeIdx;
        }
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

    function textOfSection(sec) {
        if (!sec || sec === activeRoot()) return state.text;
        var walker = document.createTreeWalker(sec, NodeFilter.SHOW_TEXT, null);
        var cur, t = '', lastB = null;
        while ((cur = walker.nextNode())) {
            var v = cur.nodeValue;
            if (!v) continue;
            var b = cur.parentElement;
            while (b && b !== sec && !isBlock(b)) b = b.parentElement;
            if (b !== lastB) {
                if (t.length > 0) t += '\n';
                lastB = b;
            }
            t += v;
        }
        return t;
    }

    function offsetWithinSection(sec, node, offset) {
        if (!sec) return -1;
        if (sec === activeRoot()) return offsetOfPoint(node, offset);
        var target = node.nodeType === 3 ? node : null;
        var walker = document.createTreeWalker(sec, NodeFilter.SHOW_TEXT, null);
        var cur, count = 0, lastB = null;
        while ((cur = walker.nextNode())) {
            var v = cur.nodeValue;
            if (!v) continue;
            var b = cur.parentElement;
            while (b && b !== sec && !isBlock(b)) b = b.parentElement;
            if (b !== lastB) {
                if (count > 0) count++;
                lastB = b;
            }
            if (cur === target || cur === node) {
                if (offset > v.length) return -1;
                return count + Math.max(0, offset);
            }
            count += v.length;
        }
        return -1;
    }

    // 选区上报：原生选区工具条动作（划线/笔记）需要精确区间与章节归属
    document.addEventListener('selectionchange', function () {
        var b = bridge();
        if (!b) return;
        var sel = window.getSelection();
        if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
            try {
                if (b.onSelection) b.onSelection(-1, -1, '', -1);
            } catch (e) {}
            return;
        }
        var range = sel.getRangeAt(0);
        var root = content();
        if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return;

        function findSection(n) {
            var el = (n && n.nodeType === 1) ? n : (n ? n.parentElement : null);
            while (el && el !== root && !el.classList.contains('mirad-chapter')) {
                el = el.parentElement;
            }
            return (el && el.classList.contains('mirad-chapter')) ? el : null;
        }

        var startSec = findSection(range.startContainer);
        var endSec = findSection(range.endContainer);

        // 跨章选区策略：不允许跨章划线/笔记，视为无效选区，清理原生选区
        if (startSec && endSec && startSec !== endSec) {
            try {
                if (b.onSelection) b.onSelection(-1, -1, '', -1);
            } catch (e) {}
            return;
        }

        var targetSec = startSec || endSec || activeRoot();
        var targetCh = sectionIndex(targetSec);
        if (targetCh < 0) targetCh = currentChapterIndex();

        var start = offsetWithinSection(targetSec, range.startContainer, range.startOffset);
        var end = offsetWithinSection(targetSec, range.endContainer, range.endOffset);
        if (start < 0 || end < 0 || end <= start) {
            try {
                if (b.onSelection) b.onSelection(-1, -1, '', -1);
            } catch (e) {}
            return;
        }
        var fullText = textOfSection(targetSec);
        var text = (end <= fullText.length) ? fullText.substring(start, end) : range.toString();
        try {
            if (b.onSelection) b.onSelection(start, end, text, targetCh);
        } catch (e) {}
    });

    function offsetOfPoint(node, offset) {
        var target = node.nodeType === 3 ? node : null;
        for (var i = 0; i < state.nodes.length; i++) {
            var entry = state.nodes[i];
            if (entry.node === target || entry.node === node) {
                if (offset > entry.len) return -1;
                return entry.start + Math.max(0, offset);
            }
        }
        // 容器节点：取第一个子文本节点
        if (node.nodeType === 1) {
            for (var j = 0; j < state.nodes.length; j++) {
                if (node.contains(state.nodes[j].node)) return state.nodes[j].start;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- CFI 与锚点快照

    function isMiradMark(node) {
        if (!node || node.nodeType !== 1) return false;
        var tag = node.tagName.toLowerCase();
        var cls = node.className || '';
        return (tag === 'mark' && cls.indexOf('mirad-') >= 0) || (tag === 'span' && cls.indexOf('mirad-tts') >= 0);
    }

    /**
     * 获取父元素的逻辑子节点列表：将阅读器插入的 mark 包装展开平铺，
     * 相邻文本节点合并为统一的逻辑文本流，保证划线操作不破坏 CFI 树结构。
     */
    function getLogicalChildren(parent) {
        var result = [];
        var currentTextGroup = null;

        function collectTextNodes(n) {
            if (n.nodeType === 3) {
                if (!currentTextGroup) {
                    currentTextGroup = { type: 'text', textNodes: [], totalLen: 0 };
                    result.push(currentTextGroup);
                }
                var len = (n.nodeValue || '').length;
                currentTextGroup.textNodes.push({ node: n, len: len });
                currentTextGroup.totalLen += len;
            } else if (n.nodeType === 1 && isMiradMark(n)) {
                for (var j = 0; j < n.childNodes.length; j++) {
                    collectTextNodes(n.childNodes[j]);
                }
            }
        }

        var children = parent.childNodes || [];
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (child.nodeType === 3 || (child.nodeType === 1 && isMiradMark(child))) {
                collectTextNodes(child);
            } else if (child.nodeType === 1) {
                currentTextGroup = null;
                result.push({ type: 'element', element: child });
            }
        }
        return result;
    }

    function cfiFromNodeOffset(node, offset) {
        if (!node) return null;
        var root = activeRoot();
        var path = [];
        var curr = node;

        // 若当前节点在 mark 内部，向上追溯到非 mark 的真实父元素
        while (curr && curr !== root && isMiradMark(curr)) {
            curr = curr.parentNode;
        }

        var accumulatedOffset = offset || 0;

        while (curr && curr !== root && curr.parentNode) {
            var parent = curr.parentNode;
            while (parent && parent !== root && isMiradMark(parent)) {
                parent = parent.parentNode;
            }
            if (!parent) break;

            var logical = getLogicalChildren(parent);
            var stepIndex = -1;

            if (curr.nodeType === 3 || isMiradMark(curr)) {
                // 找到包含 curr 文本节点的逻辑文本组
                for (var i = 0; i < logical.length; i++) {
                    var item = logical[i];
                    if (item.type === 'text') {
                        var found = false;
                        var prefixLen = 0;
                        for (var t = 0; t < item.textNodes.length; t++) {
                            if (item.textNodes[t].node === curr) {
                                found = true;
                                accumulatedOffset += prefixLen;
                                break;
                            }
                            prefixLen += item.textNodes[t].len;
                        }
                        if (found) {
                            stepIndex = i;
                            break;
                        }
                    }
                }
            } else {
                for (var k = 0; k < logical.length; k++) {
                    if (logical[k].type === 'element' && logical[k].element === curr) {
                        stepIndex = k;
                        break;
                    }
                }
            }

            if (stepIndex < 0) stepIndex = 0;

            // 遵循标准 CFI 惯例：元素子节点偶数步长，文本子节点奇数步长
            var itemType = (stepIndex < logical.length) ? logical[stepIndex].type : 'text';
            var cfiStep = (itemType === 'element') ? ((stepIndex + 1) * 2) : (stepIndex * 2 + 1);
            path.unshift(cfiStep);
            curr = parent;
        }

        return '/4/' + path.join('/') + ':' + accumulatedOffset;
    }

    function resolveCfi(cfi) {
        if (!cfi || typeof cfi !== 'string') return null;
        // 严格正则验证：必须以 /4/ 开头，路径必须为正整数且由 / 分隔，最后紧跟冒号与非负整数 offset
        var match = cfi.match(/^\/4\/(\d+(?:\/\d+)*):(\d+)$/);
        if (!match) return null;

        var stepsStr = match[1];
        var offsetStr = match[2];
        var targetOffset = parseInt(offsetStr, 10);
        if (isNaN(targetOffset) || targetOffset < 0) return null;

        var stepStrs = stepsStr.split('/');
        var steps = [];
        for (var s = 0; s < stepStrs.length; s++) {
            var stepNum = parseInt(stepStrs[s], 10);
            if (isNaN(stepNum) || stepNum < 1) return null;
            steps.push(stepNum);
        }

        var curr = activeRoot();
        for (var i = 0; i < steps.length; i++) {
            if (!curr) return null;
            var logical = getLogicalChildren(curr);
            var step = steps[i];
            var isElem = (step % 2 === 0);
            var targetIdx = isElem ? (step / 2 - 1) : Math.floor(step / 2);

            if (targetIdx < 0 || targetIdx >= logical.length) return null;
            var targetItem = logical[targetIdx];

            // 步长奇偶性与节点类型必须严格对应：偶数步必须是 element，奇数步必须是 text
            if (isElem && targetItem.type !== 'element') return null;
            if (!isElem && targetItem.type !== 'text') return null;

            if (i === steps.length - 1) {
                // 最终定位目标
                if (targetItem.type === 'text') {
                    // 只有文本步长（奇数步）对应文本节点，允许非负偏移量
                    if (targetOffset > targetItem.totalLen) return null; // 越界拒绝，杜绝截断假阳性
                    var remaining = targetOffset;
                    for (var t = 0; t < targetItem.textNodes.length; t++) {
                        var tn = targetItem.textNodes[t];
                        if (remaining <= tn.len) {
                            return { node: tn.node, offset: remaining };
                        }
                        remaining -= tn.len;
                    }
                    if (targetItem.textNodes.length > 0) {
                        var lastTn = targetItem.textNodes[targetItem.textNodes.length - 1];
                        return { node: lastTn.node, offset: lastTn.len };
                    }
                    return null;
                } else {
                    // 元素节点定位时，offset 必须为 0
                    if (targetOffset !== 0) return null;
                    return { node: targetItem.element, offset: 0 };
                }
            } else {
                // 非终止步必须是 element；文本节点作为叶子节点，绝不允许作为中间路径继续穿透
                if (targetItem.type !== 'element') return null;
                curr = targetItem.element;
            }
        }
        return null;
    }

    /**
     * 基于 TextQuote 上下文快照自愈定位（选区自愈）。
     * 在 DOM 发生结构变化、CFI 失效或章节修订时，通过前导、高亮词与后继文本模糊重定位。
     * 全文扫描所有候选位置，结合前后文最长公共子串及基准偏移距离加权消歧，杜绝盲目取首项。
     */
    function healWithTextQuote(quote, animate) {
        if (!quote) return -1;
        var hay = state.text || '';
        if (!hay) return -1;

        var before = quote.before || '';
        var highlight = quote.highlight || '';
        var after = quote.after || '';
        var targetOffset = (typeof quote.offset === 'number' && !isNaN(quote.offset)) ? quote.offset : 0;

        if (!highlight) {
            // 没有高亮词，若提供了 targetOffset 则直接使用
            if (typeof quote.offset === 'number' && quote.offset >= 0 && quote.offset <= hay.length) {
                window.MiradReader.gotoOffset(quote.offset, !!animate);
                return quote.offset;
            }
            return -1;
        }

        // 收集所有匹配项
        var candidates = [];
        var startPos = 0;
        while (startPos <= hay.length - highlight.length) {
            var foundPos = hay.indexOf(highlight, startPos);
            if (foundPos < 0) break;
            candidates.push(foundPos);
            startPos = foundPos + 1;
        }

        // 容错：去除首尾空白后再试
        if (candidates.length === 0) {
            var trimmed = highlight.replace(/^\s+|\s+$/g, '');
            if (trimmed.length > 0 && trimmed !== highlight) {
                startPos = 0;
                while (startPos <= hay.length - trimmed.length) {
                    var fPos = hay.indexOf(trimmed, startPos);
                    if (fPos < 0) break;
                    candidates.push(fPos);
                    startPos = fPos + 1;
                }
            }
        }

        if (candidates.length === 0) return -1;

        // 如果只有 1 个候选，直接命中
        if (candidates.length === 1) {
            var onlyPos = candidates[0];
            window.MiradReader.gotoOffset(onlyPos, !!animate);
            return onlyPos;
        }

        // 多个候选时进行多维评分消歧
        var bestPos = candidates[0];
        var bestScore = -Infinity;

        for (var i = 0; i < candidates.length; i++) {
            var pos = candidates[i];
            var score = 0;

            // 1. 前缀上下文打分（比较前缀后缀公共长度）
            if (before.length > 0) {
                var maxBeforeCheck = Math.min(before.length, pos, 30);
                var actualBefore = hay.substring(pos - maxBeforeCheck, pos);
                var expectBefore = before.slice(-maxBeforeCheck);
                var commonBefore = 0;
                for (var b = 1; b <= maxBeforeCheck; b++) {
                    if (actualBefore.slice(-b) === expectBefore.slice(-b)) {
                        commonBefore = b;
                    } else {
                        break;
                    }
                }
                score += commonBefore * 30;
                if (commonBefore === expectBefore.length) score += 500;
            }

            // 2. 后缀上下文打分（比较后缀前缀公共长度）
            if (after.length > 0) {
                var posAfter = pos + highlight.length;
                var maxAfterCheck = Math.min(after.length, hay.length - posAfter, 30);
                var actualAfter = hay.substring(posAfter, posAfter + maxAfterCheck);
                var expectAfter = after.slice(0, maxAfterCheck);
                var commonAfter = 0;
                for (var a = 1; a <= maxAfterCheck; a++) {
                    if (actualAfter.slice(0, a) === expectAfter.slice(0, a)) {
                        commonAfter = a;
                    } else {
                        break;
                    }
                }
                score += commonAfter * 30;
                if (commonAfter === expectAfter.length) score += 500;
            }

            // 3. 距离 targetOffset 越近得分越高（每接近 10 字符得 1 分）
            var dist = Math.abs(pos - targetOffset);
            score -= (dist / 10);

            if (score > bestScore) {
                bestScore = score;
                bestPos = pos;
            }
        }

        window.MiradReader.gotoOffset(bestPos, !!animate);
        return bestPos;
    }

    function getAnchorSnapshot(offset) {
        offset = Math.max(0, Math.min(offset | 0, state.textLength));
        var pt = locate(offset);
        var cfi = pt ? cfiFromNodeOffset(pt.node, pt.offset) : null;
        var beforeStart = Math.max(0, offset - 16);
        var afterEnd = Math.min(state.textLength, offset + 16);
        return {
            offset: offset,
            cfi: cfi,
            before: state.text.substring(beforeStart, offset),
            highlight: state.text.substring(offset, Math.min(state.textLength, offset + 1)),
            after: state.text.substring(offset + 1, afterEnd)
        };
    }

    // ---------------------------------------------------------------- 对外 API

    var pendingRestore = -1;

    function reusableThemeAnchor() {
        var x = content().scrollLeft || 0;
        var y = window.scrollY || 0;
        return themeAnchor && themeAnchor.root === activeRoot() && themeAnchor.chapter === chapterKey() &&
            themeAnchor.mode === CFG.mode && themeAnchor.x === x && themeAnchor.y === y &&
            (CFG.mode !== 'PAGED' || themeAnchor.page === state.page) ? themeAnchor : null;
    }

    function rememberThemePosition(anchor) {
        if (!anchor || anchor !== themeAnchor) return;
        anchor.x = content().scrollLeft || 0;
        anchor.y = window.scrollY || 0;
        anchor.page = state.page;
    }

    function captureThemeAnchor() {
        // Consecutive slider/resource updates keep the original character, not
        // the new page/line start produced by each intermediate layout.
        var retained = reusableThemeAnchor();
        if (retained) return retained;
        var root = activeRoot();
        var offset = offsetAtPage(state.page);
        var pos = precisePos(offset);
        return {
            root: root, chapter: chapterKey(), mode: CFG.mode, offset: offset,
            top: CFG.mode === 'SCROLL' && pos !== null ? root.getBoundingClientRect().top + pos : 0
        };
    }

    function restoreThemeAnchor(anchor) {
        var sameRootAndMode = anchor.root === activeRoot() && anchor.mode === CFG.mode;
        var pos = sameRootAndMode && CFG.mode === 'SCROLL' ? precisePos(anchor.offset) : null;
        if (pos !== null) {
            cancelScrollMotion();
            window.scrollTo(0, Math.max(0, sectionTop(activeRoot()) + pos - anchor.top));
            var relativeTop = (window.scrollY || 0) - (windowed() ? sectionTop(activeRoot()) : 0);
            state.page = Math.floor(Math.max(0, relativeTop) / viewportHeight());
            reportState();
        } else {
            window.MiradReader.gotoOffset(anchor.offset, false);
        }
        themeAnchor = sameRootAndMode ? anchor : null;
        rememberThemePosition(themeAnchor);
    }

    window.MiradReader = {
        /** 首次初始化（页面加载完成后调用）。 */
        init: function (config) {
            themeAnchor = null;
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
            installVisualSignals();
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
            var windowedBefore = prevMode === 'SCROLL' && prevWindowed === true;
            Object.assign(CFG, config || {});
            if (prevMode !== CFG.mode || prevWindowed !== CFG.windowedScroll) {
                // 模式变化：旧模式的自动下滑/定位动画不能继续写滚动位置。
                stopAutoScrollInternal();
                cancelScrollMotion();
                cancelPageMotion(false);
            }
            // 窗口/单章引擎对 DOM 形态的假设不同（section 包裹 vs 裸文本），
            // 翻转时必须同步重建结构，否则窗口引擎会在裸文本上补章、单章引擎会读到残留 section。
            if (windowedBefore !== windowed()) {
                if (windowed()) wrapInitialSection();
                else collapseWindowToActive();
            }
            buildInitialModelAndLayout();
            window.MiradReader.gotoOffset(anchor, false);
        },

        /**
         * 只替换主题样式（字号/行距/段距/配色等），跳过重建文本模型。
         * 拖动字号、行距滑杆时走这条快路径，否则每帧重建整章 DOM 文本模型会明显卡顿。
         */
        setThemeCssOnly: function (css, config) {
            var anchor = captureThemeAnchor();
            var prevMode = CFG.mode;
            var prevWindowed = CFG.windowedScroll;
            var windowedBefore = prevMode === 'SCROLL' && prevWindowed === true;
            Object.assign(CFG, config || {});
            var windowedFlipped = windowedBefore !== windowed();
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
            if (windowedFlipped) {
                // 运行中在窗口/单章形态之间切换（如翻页方式改为上下滚动）：
                // 先重建 DOM 形态与文本模型，再按原锚点恢复阅读位置。
                if (windowed()) wrapInitialSection();
                else collapseWindowToActive();
                buildInitialModelAndLayout();
                restoreThemeAnchor(anchor);
                updateContentClip();
                return state.pages;
            }
            applyLayout();
            restoreThemeAnchor(anchor);
            updateContentClip();
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
            var anchor = keepOffset !== false ? captureThemeAnchor() : null;
            if (!anchor) themeAnchor = null;
            buildInitialModelAndLayout();
            if (anchor) restoreThemeAnchor(anchor);
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
            // 接章分支只属于单章模式：窗口模式下 advanceChapter/retreatChapter 自身直接返回 false，
            // 这里若提前 return true 会把窗口边界的翻屏动作整个吞掉（点下 1/3 或音量键无响应）。
            if (!windowed()) {
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

        isAtBookEnd: function () {
            if (!windowed()) {
                var idx = currentChapterIndex();
                return idx >= lastChapterIndex();
            }
            var sections = container().querySelectorAll('section.mirad-chapter');
            if (!sections.length) return currentChapterIndex() >= lastChapterIndex();
            var maxIdx = -1;
            for (var i = 0; i < sections.length; i++) {
                var idx = sectionIndex(sections[i]);
                if (idx > maxIdx) maxIdx = idx;
            }
            if (maxIdx < lastChapterIndex()) return false;
            var scrollY = window.scrollY || 0;
            var docH = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight);
            var winH = window.innerHeight || 0;
            return (scrollY + winH >= docH - 4);
        },

        /** 自动滚动到章末后由原生调用：进入下一章（预取优先，失败走原生兜底）。 */
        advanceChapterForAuto: function () {
            if (CFG.mode !== 'SCROLL' || !state.ready) return 'FAILED';
            if (windowed()) {
                if (this.isAtBookEnd()) {
                    notifyBottomOnce();
                    return 'BOOK_END';
                }
                var sections = container().querySelectorAll('section.mirad-chapter');
                var maxIdx = -1;
                for (var i = 0; i < sections.length; i++) {
                    var idx = sectionIndex(sections[i]);
                    if (idx > maxIdx) maxIdx = idx;
                }
                var activeIdx = currentChapterIndex();
                // 若 DOM 中已挂载活动章之后的章节，且当前下方有可滚空间
                if (maxIdx > activeIdx && (documentHeight() > (window.scrollY || 0) + viewportHeight() + 10)) {
                    return 'READY';
                }
                if (maxIdx < lastChapterIndex()) {
                    if (!win.loading) ensureSection(+1);
                    return 'LOADING';
                }
                if (this.isAtBookEnd()) {
                    notifyBottomOnce();
                    return 'BOOK_END';
                }
                return 'FAILED';
            }
            var idx = currentChapterIndex();
            if (idx < 0) return 'FAILED';
            if (idx >= lastChapterIndex()) {
                notifyBottomOnce();
                return 'BOOK_END';
            }
            var started = advanceChapter();
            return started ? 'ADVANCING' : 'FAILED';
        },

        gotoPage: function (page, animate) {
            themeAnchor = null;
            var target = Math.min(Math.max(page | 0, 0), state.pages - 1);
            var changed = target !== state.page;
            state.page = target;
            applyPosition(!!animate);
            return changed;
        },

        gotoOffset: function (offset, animate) {
            themeAnchor = null;
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
                var documentY = sectionTop(activeRoot()) + page;
                if (animate) animateScrollTop(Math.max(0, documentY - 8));
                else window.scrollTo(0, Math.max(0, documentY - 8));
                var relativeTop = (window.scrollY || 0) - (windowed() ? sectionTop(activeRoot()) : 0);
                state.page = Math.floor(Math.max(0, relativeTop) / viewportHeight());
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

        renderHighlights: function (highlights, targetChapter) {
            renderHighlights(highlights || [], targetChapter);
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
        },

        getCfi: function (offset) {
            var pt = locate(offset !== undefined ? offset : offsetAtPage(state.page));
            return pt ? cfiFromNodeOffset(pt.node, pt.offset) : null;
        },

        resolveCfiOffset: function (cfi) {
            var target = resolveCfi(cfi);
            if (!target) return -1;
            return offsetOfPoint(target.node, target.offset);
        },

        resolveTextQuoteOffset: function (quote) {
            return healWithTextQuote(quote, false);
        },

        gotoCfi: function (cfi, animate, textQuote) {
            var target = resolveCfi(cfi);
            if (target) {
                var off = offsetOfPoint(target.node, target.offset);
                if (off >= 0) {
                    window.MiradReader.gotoOffset(off, !!animate);
                    return true;
                }
            }
            // CFI 定位失败或 DOM 被修改拆分时：尝试利用 TextQuote 模糊自愈恢复
            if (textQuote) {
                var healed = healWithTextQuote(textQuote, !!animate);
                if (healed >= 0) return true;
            }
            return false;
        },

        gotoLocator: function (locator, animate) {
            if (!locator) return false;
            if (locator.cfi) {
                var ok = window.MiradReader.gotoCfi(locator.cfi, animate, locator.text);
                if (ok) return true;
            }
            if (locator.text) {
                var healed = healWithTextQuote(locator.text, !!animate);
                if (healed >= 0) return true;
            }
            if (typeof locator.charOffset === 'number' && locator.charOffset >= 0) {
                window.MiradReader.gotoOffset(locator.charOffset, !!animate);
                return true;
            }
            return false;
        },

        getSnapshot: function (offset) {
            return getAnchorSnapshot(offset !== undefined ? offset : offsetAtPage(state.page));
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
        requestVisualSignal();
    });
})();
