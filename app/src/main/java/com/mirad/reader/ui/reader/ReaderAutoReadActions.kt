package com.mirad.reader.ui.reader

/** 两个快捷入口和隔离预览共用同一启动顺序，参数设置不在此处弹出。 */
internal object ReaderAutoReadActions {
    fun start(ttsActive: Boolean, pauseTts: () -> Unit, closeControls: () -> Unit, startReading: () -> Unit) {
        if (ttsActive) pauseTts()
        closeControls()
        startReading()
    }
}
