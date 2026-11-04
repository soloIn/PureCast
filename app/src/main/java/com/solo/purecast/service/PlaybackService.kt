package com.solo.purecast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.solo.purecast.R
import com.solo.purecast.util.Logger

/**
 * 💡 PureCast 核心播放前台服务
 * 管理 UPnP 接收器生命周期、USB 独占传输、Wi-Fi 锁与媒体通知保活。
 */
class PlaybackService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        Logger.service.i(TAG, "PlaybackService onCreate: 初始化前台服务...")
        createNotificationChannel()
        startForegroundWithTypes()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Logger.service.i(TAG, "PlaybackService onStartCommand, action: ${intent?.action}")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_notification_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithTypes() {
        val notification = buildNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
            Logger.service.i(TAG, "成功启动前台服务, 类型: mediaPlayback | connectedDevice")
        } catch (e: Exception) {
            Logger.service.e(TAG, "startForeground 失败!", e)
        }
    }

    override fun onDestroy() {
        Logger.service.i(TAG, "PlaybackService onDestroy: 释放资源...")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "purecast_playback_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
