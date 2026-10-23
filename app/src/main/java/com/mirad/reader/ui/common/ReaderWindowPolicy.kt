package com.mirad.reader.ui.common

import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal data class ReaderWindowPolicy(val fullScreen: Boolean, val darkTheme: Boolean)
internal val LocalReaderWindowPolicy = staticCompositionLocalOf<ReaderWindowPolicy?> { null }

@Composable
internal fun ApplyReaderWindowPolicy(window: Window?, policy: ReaderWindowPolicy) {
    if (window != null) ReaderWindowPolicyEffect(window.decorView, policy, restoreAppearance = true) { window }
}

/** Must run inside Dialog content, where LocalView belongs to the dialog's window. */
@Composable
internal fun ApplyReaderDialogWindowPolicy() {
    val policy = LocalReaderWindowPolicy.current ?: return
    val view = LocalView.current
    ReaderWindowPolicyEffect(view, policy) {
        var parent = view.parent
        var window: Window? = null
        while (parent != null && window == null) {
            if (parent is DialogWindowProvider) window = parent.window
            parent = parent.parent
        }
        window
    }
}

@Composable
private fun ReaderWindowPolicyEffect(
    view: View, policy: ReaderWindowPolicy, restoreAppearance: Boolean = false, window: () -> Window?
) {
    DisposableEffect(view, policy) {
        var originalAppearance: Triple<Boolean, Boolean, Int>? = null
        fun applyPolicy() {
            val target = window() ?: return
            val controller = WindowInsetsControllerCompat(target, target.decorView)
            if (restoreAppearance && originalAppearance == null) {
                originalAppearance = Triple(controller.isAppearanceLightStatusBars,
                    controller.isAppearanceLightNavigationBars, controller.systemBarsBehavior)
            }
            controller.isAppearanceLightStatusBars = !policy.darkTheme
            controller.isAppearanceLightNavigationBars = !policy.darkTheme
            if (policy.fullScreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) applyPolicy() }
        var observer: ViewTreeObserver? = null
        fun unbind() {
            observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
            observer = null
        }
        fun bind() {
            unbind()
            observer = view.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focus) }
            applyPolicy()
        }
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = bind()
            override fun onViewDetachedFromWindow(v: View) = unbind()
        }
        view.addOnAttachStateChangeListener(attach)
        if (view.isAttachedToWindow) bind()
        onDispose {
            view.removeOnAttachStateChangeListener(attach)
            unbind()
            val target = window()
            val original = originalAppearance
            if (restoreAppearance && target != null && original != null) {
                WindowInsetsControllerCompat(target, target.decorView).apply {
                    isAppearanceLightStatusBars = original.first
                    isAppearanceLightNavigationBars = original.second
                    systemBarsBehavior = original.third
                }
            }
            // A disappearing dialog must never show bars on the underlying Activity.
        }
    }
}

/** Paint-only protection: scrolling text never changes viewport or reading position. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSystemBarProtection(background: Color) {
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(insets).background(background))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(insets).background(background))
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().windowInsetsStartWidth(insets).background(background))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().windowInsetsEndWidth(insets).background(background))
    }
}
