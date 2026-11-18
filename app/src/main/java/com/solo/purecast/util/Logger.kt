package com.solo.purecast.util

import android.util.Log

/**
 * 💡 PureCast 全局统一分层日志工具类
 * 规范格式与领域前缀，便于在 ADB Logcat 调试与生产运维中快速过滤与定位问题。
 */
object Logger {

    class Category(private val categoryName: String) {
        fun d(tag: String, message: String) {
            Log.d("PureCast:$categoryName", "[$tag] $message")
        }

        fun i(tag: String, message: String) {
            Log.i("PureCast:$categoryName", "[$tag] $message")
        }

        fun w(tag: String, message: String, throwable: Throwable? = null) {
            if (throwable != null) {
                Log.w("PureCast:$categoryName", "[$tag] $message", throwable)
            } else {
                Log.w("PureCast:$categoryName", "[$tag] $message")
            }
        }

        fun e(tag: String, message: String, throwable: Throwable? = null) {
            if (throwable != null) {
                Log.e("PureCast:$categoryName", "[$tag] $message", throwable)
            } else {
                Log.e("PureCast:$categoryName", "[$tag] $message")
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
}
