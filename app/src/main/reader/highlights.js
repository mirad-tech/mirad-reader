    // ---------------------------------------------------------------- 标注渲染

    function unwrapMarks(root, selector) {
        var marks = root.querySelectorAll(selector);
        for (var i = 0; i < marks.length; i++) {
            var mark = marks[i];
            var parent = mark.parentNode;
            if (!parent) continue;
            while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
            parent.removeChild(mark);
        }
    }

    function clearMarks() {
        unwrapMarks(document, 'mark.mirad-hl, mark.mirad-search, span.mirad-tts, mark.mirad-tts');
        var c = content();
        if (c) c.normalize();
        buildTextModel();
    }

    /** 计算若干字符区间涉及的文本节点片段。 */
    function collectSegments(ranges, nodes, textLength) {
        var segments = [];
        for (var r = 0; r < ranges.length; r++) {
            var range = ranges[r];
            var from = Math.max(0, range.start);
            var to = Math.min(textLength, range.end);
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
        var count = walkText(sec, function (node, value, start) {
            nodes.push({node: node, start: start, len: value.length});
        });
        applySegments(collectSegments(ranges, nodes, count));
    }

    /** 渲染全部划线（支持指定章节或批量按章节渲染）。 */
    function renderHighlights(highlights, targetChapter) {
        if (typeof targetChapter === 'number' && targetChapter >= 0) {
            var sec = container().querySelector('section.mirad-chapter[data-chapter="' + targetChapter + '"]');
            if (sec) {
                unwrapMarks(sec, 'mark.mirad-hl');
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
        applySegments(collectSegments(ranges, state.nodes, state.textLength));
        buildTextModel();
        return state.textLength;
    }

    function renderSingleMark(start, end, cls) {
        unwrapMarks(document, 'mark.' + cls + ', span.' + cls);
        buildTextModel();
        if (end <= start) return;
        applySegments(collectSegments([{start: start, end: end, cls: cls}], state.nodes, state.textLength));
        buildTextModel();
    }
