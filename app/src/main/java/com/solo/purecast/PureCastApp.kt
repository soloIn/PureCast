package com.solo.purecast

import android.app.Application
import com.solo.purecast.util.Logger

/**
 * 💡 PureCast 全局 Application 类
 * 负责在进程启动首要阶段加载 Native 驱动核心动态库，并初始化全局运行时环境。
 */
class PureCastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.service.i(TAG, "PureCast 应用程序启动，准备初始化 Native 库与服务...")

        try {
            System.loadLibrary("purecast_usb")
            Logger.service.i(TAG, "libpurecast_usb.so 动态链接库加载成功")
        } catch (e: UnsatisfiedLinkError) {
            Logger.service.e(TAG, "libpurecast_usb.so 动态链接库加载失败!", e)
        }
    }

    companion object {
        private const val TAG = "PureCastApp"
        lateinit var instance: PureCastApp
            private set
    }
}
