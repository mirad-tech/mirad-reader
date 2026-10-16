package com.mirad.reader

import android.app.Application

/**
 * 应用入口。持有全局依赖容器与导入队列（不引入 Hilt/Koin，使用手动注入）。
 */
class MiradApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
