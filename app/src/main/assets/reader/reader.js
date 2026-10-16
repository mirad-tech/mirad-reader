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

    function buildTextModel() {
        var root = content();
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
            state.pages = Math.max(1, Math.round((flowWidth + geo.gap) / (geo.colW + geo.gap)));
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
            state.pages = Math.max(1, Math.ceil((c.scrollHeight + geo.padTop + geo.padBottom) / h));
        }
        buildPoints();
        state.page = Math.min(state.page, state.pages - 1);
        applyPosition(false);
        reportMetrics();
    }

    /** 记录每个文本节点在分列流（或纵向滚动流）中的像素位置，用于偏移↔页互转。 */
    function buildPoints() {
        var c = content();
        var rootRect = c.getBoundingClientRect();
        var points = [];
        var paged = CFG.mode === 'PAGED';
        for (var i = 0; i < state.nodes.length; i++) {
            var entry = state.nodes[i];
            var el = entry.node.parentElement;
            if (!el) continue;
            var rect = el.getBoundingClientRect();
            // 容器与子元素的 rect 都包含滚动偏移，两者相减即"在流中的位置"，与 scrollLeft 无关。
            // （旧代码额外加了 scrollLeft，导致跳转后再重排时偏移↔页映射整体漂移。）
            var pos = paged ? (rect.left - rootRect.left) : (rect.top - rootRect.top);
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

    function pageFromOffset(offset) {
        if (CFG.mode !== 'PAGED') {
            var point = nearestPoint(offset);
            return point ? point.pos : 0;
        }
        var point = nearestPoint(offset);
        if (!point) return 0;
        return Math.max(0, Math.floor((point.pos + 1) / step()));
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
            return 0;
        }
        var target = page * step();
        var points2 = state.points;
        for (var i = 0; i < points2.length; i++) {
            if (points2[i].pos >= target - 2) return points2[i].start;
        }
        return Math.max(0, state.textLength - 1);
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
        var c = content();
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
        if (scrollTimer) window.clearTimeout(scrollTimer);
        scrollTimer = window.setTimeout(function () {
            if (atBottom()) {
                notifyBottomOnce();
                return;
            }
            var page = Math.floor((window.scrollY || 0) / viewportHeight());
            // 页号没变就不回报，滚动过程不打扰原生层（否则每帧穿透到 Compose 会顿）。
            if (page === lastReportedPage) return;
            lastReportedPage = page;
            state.page = page;
            reportState();
        }, 140);
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
        if (dy <= -24 && atBottom()) notifyBottomOnce();
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
        setThemeCssOnly: function (css) {
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
            // 已经在底部还继续往下翻 → 用户意图是继续读下一章
            if (direction >= 0 && atBottom()) {
                notifyBottomOnce();
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
                var point = nearestPoint(offset);
                if (point) {
                    if (animate) animateScrollTop(Math.max(0, point.pos - 8));
                    else window.scrollTo(0, Math.max(0, point.pos - 8));
                    state.page = Math.floor((window.scrollY || 0) / viewportHeight());
                    reportState();
                }
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
