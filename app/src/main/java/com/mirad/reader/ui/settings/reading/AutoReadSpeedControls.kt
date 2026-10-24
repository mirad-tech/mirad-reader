package com.mirad.reader.ui.settings.reading

import androidx.compose.runtime.Composable
import com.mirad.reader.core.model.AutoReadSpeed
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.LabeledSlider
import kotlin.math.roundToInt

/**
 * 自动阅读速度滑块：自动阅读弹层与「翻页与交互」页共用，
 * 按当前阅读方式显示「翻页间隔」或「下滑速度」。
 */
@Composable
fun AutoReadSpeedControls(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
    val speed = AutoReadSpeed.forConfig(config)
    val range = AutoReadSpeed.MIN_SECONDS.toFloat()..AutoReadSpeed.MAX_SECONDS.toFloat()
    val steps = AutoReadSpeed.MAX_SECONDS - AutoReadSpeed.MIN_SECONDS - 1
    if (config.pageMode == PageMode.SCROLL) {
        LabeledSlider(
            label = "下滑速度",
            value = speed.toFloat(),
            valueText = "$speed 秒/屏",
            range = range,
            steps = steps,
            onChange = { value ->
                onChange { it.copy(autoScrollSecondsPerScreen = AutoReadSpeed.clamp(value.roundToInt())) }
            }
        )
    } else {
        LabeledSlider(
            label = "翻页间隔",
            value = speed.toFloat(),
            valueText = "$speed 秒/页",
            range = range,
            steps = steps,
            onChange = { value ->
                onChange { it.copy(autoPageSeconds = AutoReadSpeed.clamp(value.roundToInt())) }
            }
        )
    }
}
