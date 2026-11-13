    // 读完信号只来自一次向前阅读动作的落点，和续读百分比、一般布局回报分开。
    var readingEnd = {documentEpoch: 0, intent: 0, pending: null, waitingForTail: null, touch: null};

    function newReadingIntent() {
        readingEnd.pending = null;
        readingEnd.waitingForTail = null;
        readingEnd.touch = null;
        return ++readingEnd.intent;
    }

    function visibleBookEndRoot() {
        if (!state.ready || !(CFG.chapterCount > 0)) return null;
        var last = lastChapterIndex();
        if (CFG.mode === 'PAGED') {
            return currentChapterIndex() === last && state.page === state.pages - 1 && !pageMotion.active
                ? activeRoot() : null;
        }
        if (!atBottom(2)) return null;
        // 短末章可能完整显示在视口下方，顶部还属于前一章。以挂载的末章和文档尾为准。
        if (windowed()) return container().querySelector('section.mirad-chapter[data-chapter="' + last + '"]');
        return currentChapterIndex() === last ? activeRoot() : null;
    }

    function publishReadingEnd() {
        if (readingEnd.pending === null) return;
        var root = visibleBookEndRoot();
        if (!root) {
            readingEnd.pending = null;
            return;
        }
        // 等待已经触发的资源重排，以免仍在加载的末章图片或字体暂时只占一屏。
        if (relayoutScheduled || deferredRelayoutGen !== null || (document.fonts && document.fonts.status === 'loading')) return;
        var images = root.querySelectorAll('img');
        for (var i = 0; i < images.length; i++) {
            if (!images[i].complete && images[i].loading !== 'lazy') return;
        }
        var coverTurnId = readingEnd.pending;
        readingEnd.pending = null;
        var b = bridge();
        if (b && b.onReadToEnd) b.onReadToEnd(lastChapterIndex(), readingEnd.documentEpoch, coverTurnId);
    }

    function confirmReadingEnd(intent, coverTurnId) {
        if (intent !== readingEnd.intent || readingEnd.documentEpoch <= 0 || !visibleBookEndRoot()) return false;
        readingEnd.waitingForTail = null;
        readingEnd.pending = coverTurnId || 0;
        publishReadingEnd();
        return true;
    }

    function checkForwardScrollEnd(intent) {
        if (intent !== readingEnd.intent || readingEnd.documentEpoch <= 0 || !state.ready || !atBottom(2)) return false;
        if (confirmReadingEnd(intent)) return true;
        // 连续窗口可能还在补末章，只延续这一次真实向前滚动留下的候选。
        if (windowed()) readingEnd.waitingForTail = intent;
        return false;
    }

    function checkAppendedBookEnd(chapterIndex) {
        if (chapterIndex !== lastChapterIndex() || readingEnd.waitingForTail === null) return;
        var intent = readingEnd.waitingForTail;
        readingEnd.waitingForTail = null;
        confirmReadingEnd(intent);
    }

    function startReadingEndTouch(point) {
        var intent = newReadingIntent();
        if (!point || readingEnd.documentEpoch <= 0 || CFG.mode !== 'SCROLL' || !state.ready) return;
        readingEnd.touch = {intent: intent, x: point.clientX, y: point.clientY, scrollY: window.scrollY || 0, forward: false};
    }

    function moveReadingEndTouch(point) {
        var touch = readingEnd.touch;
        if (!point || !touch) return;
        var dx = point.clientX - touch.x;
        var dy = point.clientY - touch.y;
        if (Math.max(Math.abs(dx), Math.abs(dy)) < 4) return;
        touch.x = point.clientX;
        touch.y = point.clientY;
        touch.forward = dy < 0 && -dy > Math.abs(dx);
        if (!touch.forward) readingEnd.waitingForTail = null;
        checkReadingEndTouchScroll();
    }

    function checkReadingEndTouchScroll() {
        var touch = readingEnd.touch;
        if (!touch || !touch.forward) return;
        var y = window.scrollY || 0;
        var moved = y > touch.scrollY;
        touch.scrollY = y;
        if (!moved) return;
        var selection = window.getSelection();
        if ((!selection || selection.isCollapsed) && checkForwardScrollEnd(touch.intent)) readingEnd.touch = null;
    }

    function syncReadingEndScrollPosition() {
        if (readingEnd.touch) readingEnd.touch.scrollY = window.scrollY || 0;
    }

    // 窗口补入的图片不在首次布局时的资源监听范围内。载入后只复核已有候选。
    function recheckWindowEndImage(event) {
        if (readingEnd.pending !== null && windowed() && event.target.tagName === 'IMG') {
            window.requestAnimationFrame(publishReadingEnd);
        }
    }
    document.addEventListener('load', recheckWindowEndImage, true);
    document.addEventListener('error', recheckWindowEndImage, true);
