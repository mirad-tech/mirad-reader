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
            // WebView 的实际 CSS 宽度可能含小数，clientWidth 却取整；auto 会多出不足1px，
            // scrollWidth 再取整便产生并不存在的下一列。容器和分页必须使用同一列宽。
            c.style.width = geo.colW + 'px';
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
