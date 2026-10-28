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
