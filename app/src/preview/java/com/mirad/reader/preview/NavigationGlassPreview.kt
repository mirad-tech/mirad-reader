package com.mirad.reader.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mirad.reader.ui.AppNavigationBar
import com.mirad.reader.ui.AppNavigationState
import com.mirad.reader.ui.rememberAppPagerMotion
import com.mirad.reader.ui.common.*
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** 仅验证导航材质的运动；使用生产分页、内容合成及胶囊，页面数据仍为已有内存夹具。 */
@Composable
internal fun NavigationGlassPreview(page: @Composable () -> Unit) {
    val navigation = remember { AppNavigationState() }
    val motion = rememberAppPagerMotion(navigation)
    val chrome = remember { PagerChrome() }
    val context = LocalContext.current
    BackHandler(motion.isDragging) { motion.cancelDrag() }
    PagerChromeHost(chrome, navigation.selectedTab.name, pageProgress = { motion.progress },
        onAvailability = { if (!it && motion.isDragging) motion.cancelDrag() },
        navigation = { available ->
            val source = LocalHardwareGlassBackdrop.current
            DisposableEffect(context, motion, source) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        File(requireNotNull(context.getExternalFilesDir(null)), "navigation-glass-state.json").writeText(
                            JSONObject().put("label", intent.getStringExtra("label"))
                                .put("progress", motion.progress).put("dragging", motion.isDragging)
                                .put("stretch", motion.dragStretch).put("selected", navigation.selectedTab.name)
                                .put("hardwareStats", if (Build.VERSION.SDK_INT >= 31) source?.statsSummary() else null)
                                .toString())
                    }
                }
                val filter = IntentFilter("com.mirad.reader.preview.ACTION_DUMP_NAVIGATION")
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receiver, filter)
                onDispose { context.unregisterReceiver(receiver) }
            }
            AppNavigationBar(navigation.selectedTab, motion, available) { navigation.select(it) }
        }
    ) {
        HorizontalPager(state = motion.pager, userScrollEnabled = false, beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()) { index ->
            val visible by remember(index, motion) { derivedStateOf { abs(index - motion.progress) < 1f } }
            CompositionLocalProvider(LocalPagerPageIndex provides index,
                LocalPagerPageVisible provides visible,
                LocalPagerPageActive provides (index == navigation.selectedTab.ordinal)) { page() }
        }
    }
}
