package com.mirad.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mirad.reader.ui.AppRoot
import com.mirad.reader.ui.theme.MiradTheme

class MainActivity : ComponentActivity() {

    /**
     * 音量键翻页：阅读页注册了 handler 时消费按键（上=上一页/上滚、下=下一页/下滚），
     * 其他界面不拦截，音量键照常工作。
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val delta = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> -1
            else -> 0
        }
        if (delta != 0) {
            val handler = com.mirad.reader.reader.ReaderKeys.pager
            if (handler != null && handler(delta)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MiradApp).container
        setContent {
            val systemDark = isSystemInDarkTheme()
            MiradTheme(darkTheme = systemDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(container = container, systemDark = systemDark)
                }
            }
        }
    }
}
