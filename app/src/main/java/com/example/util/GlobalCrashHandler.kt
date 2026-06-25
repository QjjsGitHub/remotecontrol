package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.example.data.model.LogType
import com.example.data.repository.RemoteControlRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * 全局异常捕获器
 * 用于拦截所有未捕获异常，记录日志并重启应用
 */
@Singleton
class GlobalCrashHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RemoteControlRepository
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun init() {
        // 设置为系统默认处理器
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 1. 获取错误堆栈信息
        val stackTrace = Log.getStackTraceString(throwable)
        val threadName = thread.name

        // 2. 打印到 Logcat
        Log.e("GlobalCrashHandler", "检测到未捕获异常 [线程: $threadName]:\n$stackTrace")

        // 3. 尝试记录到我们的业务日志中
        try {
            repository.addClientLog("系统崩溃: ${throwable.localizedMessage}", LogType.ERROR)
        } catch (e: Exception) {
            // 防止记录日志本身也崩溃
        }

        // 4. 执行自定义的退出/重启逻辑
        handleCrash(throwable)
    }

    private fun handleCrash(throwable: Throwable) {
        // 5. 延迟一会确保日志写完，然后重启应用或退出
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("CRASH_MSG", throwable.toString())
        }
        
        context.startActivity(intent)

        // 杀掉当前崩溃的进程
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
