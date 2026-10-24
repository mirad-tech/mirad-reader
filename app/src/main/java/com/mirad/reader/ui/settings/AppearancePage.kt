package com.mirad.reader.ui.settings

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.mirad.reader.ui.common.AppThemeModeSettings
import com.mirad.reader.ui.common.GlassAppearance
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.LocalAppAppearanceController
import com.mirad.reader.ui.common.LocalAppAppearanceSaveFailed
import com.mirad.reader.ui.common.LocalGlassAppearance
import com.mirad.reader.ui.common.LocalGlassAppearanceController
import com.mirad.reader.ui.common.LocalGlassSaveFailed
import com.mirad.reader.ui.common.MiradTextButton
import com.mirad.reader.ui.common.SettingCard

/** 「界面外观」应用级设置页：日夜模式与玻璃质感。 */
@Composable
fun AppearanceSettings() {
    val context = LocalContext.current
    val view = LocalView.current
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val isPowerSave = powerManager?.isPowerSaveMode == true
    val apiLevel = Build.VERSION.SDK_INT
    val isHardwareAccelerated = view.isHardwareAccelerated
    val isSimplified = apiLevel < 31 || isPowerSave || !isHardwareAccelerated
    val simplifiedReason = when {
        apiLevel < 31 -> "系统版本低于 Android 12"
        isPowerSave -> "系统开启了省电模式"
        !isHardwareAccelerated -> "硬件加速未启用"
        else -> ""
    }

    val appearance = LocalGlassAppearance.current
    val controller = LocalGlassAppearanceController.current
    val appSaveFailed = LocalAppAppearanceSaveFailed.current
    val appController = LocalAppAppearanceController.current
    val saveFailed = appSaveFailed || LocalGlassSaveFailed.current || controller.saveFailed
    var previewPercent by remember(appearance.transparencyPercent) {
        mutableFloatStateOf(appearance.transparencyPercent.toFloat())
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.commit()
        }
    }

    // 两个保存通道合并为一条横幅：重试时依次重试两个通道。
    if (saveFailed) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "设置保存失败，当前修改尚未写入本地存储。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                MiradTextButton(
                    onClick = {
                        appController?.retrySave()
                        controller.retrySave()
                    }
                ) {
                    Text("重试")
                }
            }
        }
    }

    AppThemeModeSettings()
    SettingCard(title = "玻璃质感") {
        if (isSimplified) {
            Text(
                text = "当前处于简化质感模式（$simplifiedReason），采用安全底色保护阅读体验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        LabeledSlider(
            label = "玻璃透明度",
            value = previewPercent,
            valueText = "${previewPercent.toInt()}%",
            range = 0f..100f,
            steps = 99,
            onChange = { value ->
                previewPercent = value
                controller.update(GlassAppearance(value.toInt()))
            },
            onCommit = {
                controller.commit()
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            MiradTextButton(
                onClick = {
                    controller.reset()
                    previewPercent = GlassAppearance.DEFAULT_PERCENT.toFloat()
                }
            ) {
                Text("恢复默认")
            }
        }
    }
}
