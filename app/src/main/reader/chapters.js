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
