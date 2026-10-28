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
