package com.mirad.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mirad.reader.core.model.ReaderConfig

internal fun shouldRefreshReaderAppearance(
    applied: ReaderConfig,
    desired: ReaderConfig,
    appliedDark: Boolean,
    desiredDark: Boolean,
    resumed: Boolean
): Boolean = resumed || applied != desired || appliedDark != desiredDark

/** Refresh the resolved application appearance, including explicit day/night choices. */
@Composable
internal fun ReaderAppearanceEffect(
    config: ReaderConfig,
    isDark: Boolean,
    ready: Boolean,
    onRefresh: (ReaderConfig, Boolean, Boolean) -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(onRefresh)
    val currentConfig by rememberUpdatedState(config)
    val currentDark by rememberUpdatedState(isDark)
    val currentReady by rememberUpdatedState(ready)
    LaunchedEffect(config, isDark, ready) {
        if (ready) refresh(config, isDark, false)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentReady) {
                refresh(currentConfig, currentDark, true)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
