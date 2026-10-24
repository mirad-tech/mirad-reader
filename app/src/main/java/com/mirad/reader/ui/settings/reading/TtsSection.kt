package com.mirad.reader.ui.settings.reading

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingNavRow
import com.mirad.reader.ui.common.SettingSwitchRow
import com.mirad.reader.ui.reader.openTtsSettings

/** 「朗读」分类页。 */
@Composable
fun TtsSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
    val context = LocalContext.current
    SettingCard(title = "朗读") {
        LabeledSlider(
            label = "语速",
            value = config.ttsSpeechRate,
            valueText = String.format("%.2f", config.ttsSpeechRate),
            range = 0.5f..2f,
            onChange = { value -> onChange { it.copy(ttsSpeechRate = value) } }
        )
        LabeledSlider(
            label = "音调",
            value = config.ttsPitch,
            valueText = String.format("%.2f", config.ttsPitch),
            range = 0.5f..2f,
            onChange = { value -> onChange { it.copy(ttsPitch = value) } }
        )
        SettingSwitchRow(
            label = "读完自动下一章",
            checked = config.ttsAutoNextChapter,
            onCheckedChange = { value -> onChange { it.copy(ttsAutoNextChapter = value) } }
        )
        // 朗读读不出声/读错音，多半是系统 TTS 引擎或中文语音没装/没选对。
        // 这里直接把你送到系统设置，不自己管引擎列表。
        SettingNavRow(
            label = "选择朗读语音 / 引擎",
            onClick = { openTtsSettings(context) }
        )
    }
}
