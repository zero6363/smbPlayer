package com.smbplayer.app

import android.app.Application
import android.os.Process
import com.smbplayer.app.data.FavoriteRepository
import com.smbplayer.app.data.ServerRepository
import com.smbplayer.app.data.SettingsRepository
import com.smbplayer.app.util.AppLog
import com.smbplayer.app.util.logError
import com.smbplayer.app.util.logInfo

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServerRepository.init(this)
        SettingsRepository.init(this)
        FavoriteRepository.init(this)
        AppLog.init(this)
        installCrashHandler()
        logInfo("应用启动")
    }

    /**
     * 未捕获异常兜底：把崩溃堆栈写进 AppLog（同步落盘），
     * 这样即使闪退回首页，下次也能在「日志」页复制到完整堆栈。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logError("未捕获异常: ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    Process.killProcess(Process.myPid())
                    System.exit(1)
                }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
