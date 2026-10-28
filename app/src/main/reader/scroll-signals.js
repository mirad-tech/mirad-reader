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
