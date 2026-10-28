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
        var text = '';
        walkText(sec, function (node, value, start, separator) {
            if (separator) text += '\n';
            text += value;
        });
        return text;
    }

    function offsetWithinSection(sec, node, offset) {
        if (!sec) return -1;
        if (sec === activeRoot()) return offsetOfPoint(node, offset);
        var target = node.nodeType === 3 ? node : null;
        var result = -1;
        walkText(sec, function (cur, value, start) {
            if (cur === target || cur === node) {
                result = offset > value.length ? -1 : start + Math.max(0, offset);
                return false;
            }
        });
        return result;
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
