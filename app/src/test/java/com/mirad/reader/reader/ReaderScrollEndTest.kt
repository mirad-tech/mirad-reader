package com.mirad.reader.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable

class ReaderScrollEndTest {
    @Test fun dragContinuesToBookEndAfterPauseWithFingerDown() = withReader {
        dragNearEnd()
        advanceTime(181)
        assertEquals("[]", completionEvents())

        touch("touchmove", 20)
        scrollTo(BOOK_BOTTOM)
        assertEquals(EXPECTED_END, completionEvents())
    }

    @Test fun inertiaCanReachBookEndAfterOriginalReleaseTimeout() = withReader {
        dragNearEnd()
        touch("touchend", 140)
        advanceTime(100)
        scrollTo(540)
        advanceTime(100)
        assertEquals("[]", completionEvents())

        scrollTo(BOOK_BOTTOM)
        assertEquals(EXPECTED_END, completionEvents())
    }

    @Test fun endedAndCancelledGesturesExpireWhenScrollingStops() {
        for (event in listOf("touchend", "touchcancel")) {
            withReader {
                dragNearEnd()
                touch(event, 140)
                advanceTime(181)
                scrollTo(BOOK_BOTTOM)
                assertEquals(event, "[]", completionEvents())
            }
        }
    }

    @Test fun initializingAtBookEndDoesNotCompleteIt() = withReader(bookHeight = VIEWPORT_HEIGHT) {
        scrollTo(0)
        advanceTime(181)
        assertEquals("[]", completionEvents())
    }

    @Test fun scrollingToBookEndWithoutAGestureDoesNotCompleteIt() = withReader {
        scrollTo(BOOK_BOTTOM)
        advanceTime(181)
        assertEquals("[]", completionEvents())
    }

    private fun withReader(bookHeight: Int = BOOK_HEIGHT, exercise: ScrollFixture.() -> Unit) {
        ContextFactory().call { context ->
            context.setInterpretedMode(true)
            context.languageVersion = Context.VERSION_ES6
            val scope = context.initSafeStandardObjects()
            context.evaluateString(scope, browserHost(bookHeight), "scroll-test-host.js", 1, null)
            context.evaluateString(scope, readerSource, "reader.js", 1, null)
            ScrollFixture(context, scope).apply {
                evaluate("window.MiradReader.init({mode: 'SCROLL', chapterCount: 1}, null, $DOCUMENT_EPOCH, false);")
                exercise()
            }
        }
    }

    private class ScrollFixture(private val context: Context, private val scope: Scriptable) {
        fun evaluate(script: String): Any? =
            context.evaluateString(scope, script, "scroll-test-events.js", 1, null)

        fun completionEvents(): String = Context.toString(evaluate("JSON.stringify(readToEndEvents)"))

        fun touch(type: String, y: Int) {
            val point = "{clientX: 30, clientY: $y}"
            val event = when (type) {
                "touchend" -> "{changedTouches: [$point]}"
                "touchcancel" -> "{}"
                else -> "{touches: [$point]}"
            }
            evaluate("dispatch('$type', $event);")
        }

        fun scrollTo(y: Int) {
            evaluate("window.scrollTo(0, $y); dispatch('scroll', {});")
        }

        fun advanceTime(ms: Int) {
            evaluate("advanceClockBy($ms);")
        }

        fun dragNearEnd() {
            scrollTo(360)
            touch("touchstart", 260)
            touch("touchmove", 140)
            scrollTo(480)
        }
    }

    companion object {
        private const val VIEWPORT_HEIGHT = 300
        private const val BOOK_HEIGHT = 900
        private const val BOOK_BOTTOM = BOOK_HEIGHT - VIEWPORT_HEIGHT
        private const val DOCUMENT_EPOCH = 7
        private const val EXPECTED_END = "[[0,$DOCUMENT_EPOCH,0]]"

        private val readerSource: String by lazy {
            val directory = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { it.resolve("app/src/main/reader") }
                .first { it.resolve("reader.js").isFile }
            fun read(name: String) = directory.resolve(name).readText(Charsets.UTF_8).replace("\r\n", "\n")
            // 与正式打包使用相同的入口及模块顺序，从工作区读取脚本。
            Regex("(?m)^    // @include (\\S+)\n").replace(read("reader.js")) { read(it.groupValues[1]) }
        }

        // 只模拟脚本需要的宿主能力，书末判定和事件处理全部由生产脚本执行。
        private fun browserHost(bookHeight: Int) = """
            var clock = 0;
            var nextTimerId = 0;
            var timers = [];
            var listeners = Object.create(null);
            var readToEndEvents = [];
            var NodeFilter = {SHOW_TEXT: 4};
            var contentElement = {
                style: {},
                getAttribute: function (name) { return name === 'data-chapter' ? '0' : null; },
                querySelectorAll: function () { return []; },
                getBoundingClientRect: function () {
                    return {top: -window.scrollY, bottom: $bookHeight - window.scrollY};
                }
            };
            var backgroundElement = {};
            var document = {
                documentElement: {clientWidth: 390, clientHeight: $VIEWPORT_HEIGHT, scrollHeight: $bookHeight, style: {}},
                body: {scrollHeight: $bookHeight, style: {}},
                createTreeWalker: function () { return {nextNode: function () { return null; }}; },
                getElementById: function (id) {
                    if (id === 'mirad-content') return contentElement;
                    if (id === 'mirad-bg') return backgroundElement;
                    return null;
                },
                addEventListener: function (type, listener) {
                    (listeners[type] || (listeners[type] = [])).push(listener);
                }
            };
            var window = {
                scrollY: 0,
                scrollTo: function (x, y) {
                    this.scrollY = Math.max(0, Math.min(y, $bookHeight - $VIEWPORT_HEIGHT));
                },
                getSelection: function () { return null; },
                addEventListener: function () {},
                performance: {now: function () { return clock; }},
                setTimeout: function (callback, delay) {
                    var timer = {id: ++nextTimerId, due: clock + delay, callback: callback};
                    timers.push(timer);
                    timers.sort(function (a, b) { return a.due - b.due || a.id - b.id; });
                    return timer.id;
                },
                clearTimeout: function (id) {
                    timers = timers.filter(function (timer) { return timer.id !== id; });
                }
            };
            var MiradBridge = {
                onReady: function () {},
                onState: function () {},
                onReadToEnd: function (chapter, epoch, coverTurnId) {
                    readToEndEvents.push([chapter, epoch, coverTurnId]);
                }
            };
            function getComputedStyle() { return {backgroundImage: 'none'}; }
            function dispatch(type, event) {
                listeners[type].forEach(function (listener) { listener(event); });
            }
            function advanceClockBy(ms) {
                var target = clock + ms;
                while (timers.length && timers[0].due <= target) {
                    var timer = timers.shift();
                    clock = timer.due;
                    timer.callback();
                }
                clock = target;
            }
        """.trimIndent()
    }
}
