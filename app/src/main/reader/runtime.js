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

    /** 共用正文字符流规则；回调返回 false 时停在当前节点，避免定位扫描后续正文。 */
    function walkText(root, visit) {
        var count = 0;
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
            var separator = block !== lastBlock && count > 0;
            if (block !== lastBlock) {
                if (separator) count++;
                lastBlock = block;
            }
            if (visit(node, value, count, separator) === false) return count;
            count += value.length;
        }
        return count;
    }

    function buildTextModel(root) {
        var nodes = [];
        var text = '';
        walkText(root || activeRoot(), function (node, value, start, separator) {
            if (separator) text += '\n';
            nodes.push({node: node, start: start, len: value.length});
            text += value;
        });
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
