package com.mirad.reader.preview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.inspector.WindowInspector
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.ui.common.GlassBackdropState
import com.mirad.reader.ui.common.glassSnapshotDimensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.math.abs

/** Native, memory-fixture probe. PixelCopy is an independent reference, never a production backdrop. */
internal suspend fun runBackdropProbe(
    activity: Activity,
    view: ReaderWebView,
    source: GlassBackdropState,
    windowed: Boolean,
    fullScreen: Boolean,
    setChrome: (Boolean) -> Unit,
    setSettings: (Boolean) -> Unit,
    setDialog: (Boolean) -> Unit
) {
    val mode = if (windowed) "windowed" else "single"
    var passed = 0
    fun verify(name: String, condition: Boolean, detail: String = "") {
        check(condition) { "$name $detail" }
        passed++
        Log.i("GlassProbe", "PASS $mode $name $detail")
    }
    try {
        awaitCondition { source.image != null }
        delay(700)
        val idle = source.captureAttempts
        delay(500)
        verify("idle-does-not-sample", source.captureAttempts == idle, "attempts=$idle")

        view.js("""
            (() => {
              const m=document.createElement('div');m.id='glass-probe';
              m.style.cssText='position:fixed !important;left:'+innerWidth*.38+'px;top:'+innerHeight*.45+'px;width:'+innerWidth*.24+'px;height:'+innerHeight*.1+'px;background:rgb(24,112,192);z-index:2147483647;overflow:hidden';
              document.body.appendChild(m);return true;
            })()
        """.trimIndent())
        val blue = Color.rgb(24, 112, 192)
        delay(200)
        Log.i("GlassProbe", "marker=" + view.js("JSON.stringify(document.getElementById('glass-probe').getBoundingClientRect().toJSON())") + " attempts=${source.captureAttempts} published=${source.publishedCaptures}")
        awaitCondition { source.centerMatches(blue) }
        verify("first-paint-matches-window", source.matchesWindowCenter(activity, view))

        val beforeSmallScroll = source.captureAttempts
        view.js("window.scrollBy(0, 3);true")
        awaitCondition { source.captureAttempts > beforeSmallScroll }
        verify("sub-line-scroll-invalidates", source.matchesWindowCenter(activity, view))

        view.js("window.scrollTo(0, innerHeight*2.2);true")
        awaitCondition { view.scrollY > view.height }
        delay(150)
        awaitCondition { source.centerMatches(blue) }
        val reference = windowFrame(activity, view)
        val legacy = legacyFrame(view)
        try {
            val expected = reference[reference.width / 2, reference.height / 2]
            val oldPixel = legacy[legacy.width / 2, legacy.height / 2]
            verify("scrolled-snapshot-matches-window", source.centerMatches(expected), "scrollY=${view.scrollY}")
            verify("legacy-scroll-capture-reproduces-blank", colorDistance(oldPixel, expected) > 20)
        } finally { reference.recycle(); legacy.recycle() }

        if (windowed) {
            view.js("window.scrollTo(0, document.querySelector('section[data-chapter=\"1\"]').getBoundingClientRect().top+scrollY+100);true")
            awaitCondition { view.js("window.__probeChapter") == "1" }
            delay(180)
            verify("cross-chapter-current-viewport", source.matchesWindowCenter(activity, view))
        }

        val startAttempts = source.captureAttempts
        val startPublished = source.publishedCaptures
        val started = System.nanoTime()
        view.js("""
            (() => {window.__glassAnimate=true;let i=0;
              const paint=()=>{if(!window.__glassAnimate)return;
                document.getElementById('glass-probe').style.background='rgb('+(32+(i++*17)%190)+',100,180)';
                requestAnimationFrame(paint)};requestAnimationFrame(paint);return true;})()
        """.trimIndent())
        delay(700)
        val elapsed = System.nanoTime() - started
        val attemptDelta = source.captureAttempts - startAttempts
        verify("continuous-paint-does-not-starve", source.publishedCaptures - startPublished >= 3,
            "published=${source.publishedCaptures - startPublished}")
        verify("capture-rate-bounded", attemptDelta <= elapsed / 33_333_334L + 2, "attempts=$attemptDelta")
        view.js("window.__glassAnimate=false;document.getElementById('glass-probe').style.background='rgb(24,112,192)';true")
        // PixelCopy reads the committed Window buffer, which can trail WebView's software draw.
        awaitCondition { source.centerMatches(blue) && source.matchesWindowCenter(activity, view) }
        verify("final-frame-after-animation", true)

        val imagePublications = source.publishedCaptures
        view.js("""
            (()=>{const m=document.getElementById('glass-probe');const img=document.createElement('img');
              img.style.cssText='width:100%;height:100% !important;display:block;margin:0';m.appendChild(img);
              setTimeout(()=>{img.src='data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="rgb(192,40,60)"/></svg>')},250);return true;})()
        """.trimIndent())
        awaitCondition { source.centerMatches(Color.rgb(192, 40, 60)) && source.matchesWindowCenter(activity, view) }
        verify("delayed-image-with-constant-revision", source.publishedCaptures > imagePublications)

        view.js("""
            (()=>{const m=document.getElementById('glass-probe');m.innerHTML='阅读';
              m.style.cssText+=';font:52px monospace;line-height:'+innerHeight*.1+'px;text-align:center;color:black;background:rgb(240,240,240)';return true;})()
        """.trimIndent())
        delay(300)
        val beforeFont = source.fingerprint()
        val fontPublications = source.publishedCaptures
        view.js("""
            (()=>{window.__probeFontLoaded=false;
              setTimeout(()=>{const f=new FontFace('GlassProbeFont','url(https://appassets.androidplatform.net/assets/fonts/lxgw-wenkai-regular.woff2)');
                f.load().then(font=>{document.fonts.add(font);document.getElementById('glass-probe').style.setProperty('font-family','GlassProbeFont','important');window.__probeFontLoaded=true;})
                .catch(e=>{window.__probeFontError=String(e)})},250);return true;})()
        """.trimIndent())
        awaitCondition(6000) { view.js("window.__probeFontLoaded") == "true" }
        awaitCondition { source.publishedCaptures > fontPublications && source.fingerprint() != beforeFont }
        verify("delayed-font-with-constant-revision", true)

        val dimensions = view.width to view.height
        val scroll = view.scrollY
        val position = view.js("JSON.parse(MiradReader.getState()).offset")
        setSettings(true)
        delay(900)
        verify("sheet-inherits-system-bars", windowsMatchPolicy(fullScreen))
        verify("sheet-keeps-viewport", dimensions == (view.width to view.height))
        verify("sheet-keeps-reading-position", abs(view.scrollY - scroll) <= 1 && view.js("JSON.parse(MiradReader.getState()).offset") == position)
        setSettings(false)
        delay(350)
        verify("sheet-close-keeps-viewport", dimensions == (view.width to view.height))
        setDialog(true)
        delay(500)
        verify("dialog-inherits-system-bars", windowsMatchPolicy(fullScreen))
        verify("dialog-keeps-viewport-and-position", dimensions == (view.width to view.height) && abs(view.scrollY - scroll) <= 1)
        setDialog(false)
        delay(250)
        verify("dialog-close-keeps-reading-position", view.js("JSON.parse(MiradReader.getState()).offset") == position)

        setChrome(false)
        awaitCondition { source.image == null }
        val hiddenAttempts = source.captureAttempts
        view.js("document.getElementById('glass-probe').innerHTML='';document.getElementById('glass-probe').style.background='red';window.scrollBy(0,15);true")
        delay(500)
        verify("hidden-change-does-not-capture", source.captureAttempts == hiddenAttempts)
        setChrome(true)
        awaitCondition { source.centerMatches(Color.RED) && source.matchesWindowCenter(activity, view) }
        verify("reopen-refreshes-current-frame", true)
        view.js("document.getElementById('glass-probe').remove();true")
        var legacyNanos = 0L
        var correctedNanos = 0L
        repeat(10) {
            var start = System.nanoTime()
            legacyFrame(view).recycle()
            legacyNanos += System.nanoTime() - start
            start = System.nanoTime()
            legacyFrame(view, true).recycle()
            correctedNanos += System.nanoTime() - start
        }
        Log.i("GlassProbe", "draw-cost samples=10 legacyMs=${legacyNanos / 1_000_000.0} correctedMs=${correctedNanos / 1_000_000.0}")
        Log.i("GlassProbe", "DONE $mode passed=$passed attempts=${source.captureAttempts} published=${source.publishedCaptures} failures=${source.captureFailures} captureMs=${source.captureTimeNanos/1_000_000.0}")
    } catch (timeout: TimeoutCancellationException) {
        Log.e("GlassProbe", "FAIL $mode after=$passed timeout", timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.e("GlassProbe", "FAIL $mode after=$passed ${error.message}", error)
    }
}

private fun windowsMatchPolicy(fullScreen: Boolean): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 29) return false
    val windows = WindowInspector.getGlobalWindowViews().filter { it.isShown }
    return windows.size >= 2 && windows.all { root ->
        val insets = root.rootWindowInsets ?: return@all false
        val bars = WindowInsetsCompat.toWindowInsetsCompat(insets, root)
        bars.isVisible(WindowInsetsCompat.Type.statusBars()) != fullScreen &&
            bars.isVisible(WindowInsetsCompat.Type.navigationBars()) != fullScreen
    }
}

private suspend fun ReaderWebView.js(script: String): String = withTimeout(3000) {
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
    }
}

private suspend fun awaitCondition(timeout: Long = 6000, predicate: suspend () -> Boolean) {
    withTimeout(timeout) { while (!predicate()) delay(20) }
}

private fun GlassBackdropState.centerMatches(expected: Int): Boolean {
    val bitmap = image?.asAndroidBitmap() ?: return false
    return colorDistance(bitmap[bitmap.width / 2, bitmap.height / 2], expected) <= 6
}

private fun colorDistance(a: Int, b: Int): Int = maxOf(
    abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

private fun GlassBackdropState.fingerprint(): Long {
    val bitmap = image?.asAndroidBitmap() ?: return 0
    var hash = 7L
    for (y in bitmap.height * 45 / 100 until bitmap.height * 55 / 100 step 2) {
        for (x in bitmap.width * 38 / 100 until bitmap.width * 62 / 100 step 2) hash = hash * 31 + bitmap[x, y]
    }
    return hash
}

private suspend fun GlassBackdropState.matchesWindowCenter(activity: Activity, view: ReaderWebView): Boolean {
    val reference = windowFrame(activity, view)
    return try { centerMatches(reference[reference.width / 2, reference.height / 2]) } finally { reference.recycle() }
}

private suspend fun windowFrame(activity: Activity, view: ReaderWebView): Bitmap {
    val dimensions = checkNotNull(glassSnapshotDimensions(view.width, view.height))
    val bitmap = createBitmap(dimensions.first, dimensions.second)
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    val result = withTimeout(3000) {
        suspendCancellableCoroutine { continuation ->
            PixelCopy.request(activity.window, bounds, bitmap, { code ->
                if (continuation.isActive) continuation.resume(code)
            }, Handler(Looper.getMainLooper()))
        }
    }
    check(result == PixelCopy.SUCCESS) { "PixelCopy reference failed: $result" }
    return bitmap
}

private fun legacyFrame(view: ReaderWebView, compensateScroll: Boolean = false): Bitmap {
    val dimensions = checkNotNull(glassSnapshotDimensions(view.width, view.height))
    return createBitmap(dimensions.first, dimensions.second).also { bitmap ->
        view.withoutBackdropInvalidation {
            val canvas = Canvas(bitmap)
            canvas.scale(bitmap.width.toFloat() / view.width, bitmap.height.toFloat() / view.height)
            if (compensateScroll) view.drawVisibleBackdrop(canvas) else view.draw(canvas)
        }
    }
}
