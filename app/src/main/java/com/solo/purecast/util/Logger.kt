package com.solo.purecast.util

import android.util.Log

/**
 * 💡 PureCast 全局统一分层日志工具类
 * 规范格式与领域前缀，便于在 ADB Logcat 调试与生产运维中快速过滤与定位问题。
 */
object Logger {

    class Category(private val categoryName: String) {
        private val logTag = "PureCast:$categoryName"
        // 💡 Android setprop 禁止属性名包含冒号 ':'，门控属性名使用无冒号形式（如 adb shell setprop log.tag.PureCastUPnP VERBOSE）
        private val propTag = "PureCast$categoryName"

        inline fun v(tag: String, messageProvider: () -> String) {
            if (isVerboseEnabled()) {
                Log.v(logTagForInline(), "[$tag] ${messageProvider()}")
            }
        }

        @PublishedApi
        internal fun isVerboseEnabled(): Boolean = Log.isLoggable(propTag, Log.VERBOSE)

        @PublishedApi
        internal fun logTagForInline(): String = logTag

        fun d(tag: String, message: String) {
            Log.d(logTag, "[$tag] $message")
        }

        fun i(tag: String, message: String) {
            Log.i(logTag, "[$tag] $message")
        }

        fun w(tag: String, message: String, throwable: Throwable? = null) {
            if (throwable != null) {
                Log.w(logTag, "[$tag] $message", throwable)
            } else {
                Log.w(logTag, "[$tag] $message")
            }
        }

        fun e(tag: String, message: String, throwable: Throwable? = null) {
            if (throwable != null) {
                Log.e(logTag, "[$tag] $message", throwable)
            } else {
                Log.e(logTag, "[$tag] $message")
            }
        }
    }

    val usb = Category("USB")
    val audio = Category("Audio")
    val upnp = Category("UPnP")
    val airplay = Category("AirPlay")
    val service = Category("Service")
    val ui = Category("UI")
    val lyrics = Category("Lyrics")
    val hotkey = Category("Hotkey")
}
