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
        // 规则服务使用独立 UID，没有应用私有文件权限，也不应初始化数据库或网络任务。
        if (android.os.Process.myUid() != applicationInfo.uid) return
        container = AppContainer(this)
    }
}
