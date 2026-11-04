package com.solo.purecast.upnp

import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 💡 PureCast GENA (General Event Notification Architecture) 事件通知器
 * 负责维护 DLNA 控制端 (如 iPhone, airupnp, BubbleUPnP) 的订阅生命周期，
 * 并在电视端遥控器触发播放/暂停/切歌时，向所有订阅端毫秒级广播状态变更。
 */
class GenaEventNotifier(private val scope: CoroutineScope) {

    data class Subscriber(
        val sid: String,
        val callbackUrl: String,
        var expirationEpochMs: Long,
        val sequenceNumber: AtomicLong = AtomicLong(0)
    )

    private val subscribers = ConcurrentHashMap<String, Subscriber>()

    /**
     * 注册新的 GENA 订阅者
     */
    fun subscribe(callbackUrl: String, timeoutSeconds: Int = 1800): String {
        val sid = "uuid:" + UUID.randomUUID().toString()
        val expiration = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        val cleanCallback = callbackUrl.trim().removeSurrounding("<", ">")
        subscribers[sid] = Subscriber(sid, cleanCallback, expiration)
        Logger.upnp.i(TAG, "新增 GENA 订阅者: sid=$sid, callback=$cleanCallback, 超时=${timeoutSeconds}s")
        return sid
    }

    /**
     * 续订已有订阅
     */
    fun renew(sid: String, timeoutSeconds: Int = 1800): Boolean {
        val sub = subscribers[sid] ?: return false
        sub.expirationEpochMs = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        Logger.upnp.i(TAG, "续订 GENA 订阅: sid=$sid, 新超时=${timeoutSeconds}s")
        return true
    }

    /**
     * 取消订阅
     */
    fun unsubscribe(sid: String): Boolean {
        val removed = subscribers.remove(sid) != null
        Logger.upnp.i(TAG, "移除 GENA 订阅: sid=$sid (success=$removed)")
        return removed
    }

    /**
     * 广播播放状态变化 (PLAYING, PAUSED_PLAYBACK, STOPPED)
     */
    fun notifyTransportState(state: String) {
        val xmlEvent = buildLastChangeEventXml(state)
        val now = System.currentTimeMillis()

        // 清理过期订阅
        subscribers.entries.removeIf { it.value.expirationEpochMs < now }

        for (sub in subscribers.values) {
            val seq = sub.sequenceNumber.getAndIncrement()
            scope.launch(Dispatchers.IO) {
                sendNotify(sub.callbackUrl, sub.sid, seq, xmlEvent)
            }
        }
    }

    private fun sendNotify(callbackUrl: String, sid: String, seq: Long, bodyXml: String) {
        try {
            val url = URL(callbackUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "NOTIFY"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("CONTENT-TYPE", "text/xml; charset=\"utf-8\"")
                setRequestProperty("NT", "upnp:event")
                setRequestProperty("NTS", "upnp:propchange")
                setRequestProperty("SID", sid)
                setRequestProperty("SEQ", seq.toString())
                setRequestProperty("Connection", "close")
            }

            conn.outputStream.use { os ->
                os.write(bodyXml.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            Logger.upnp.d(TAG, "发送 GENA NOTIFY 至 $callbackUrl 结果: $code")
            conn.disconnect()
        } catch (e: Exception) {
            Logger.upnp.w(TAG, "发送 GENA NOTIFY 失败 ($callbackUrl): ${e.message}")
        }
    }

    private fun buildLastChangeEventXml(state: String): String {
        val escapedEvent = """&lt;Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"&gt;&lt;InstanceID val="0"&gt;&lt;TransportState val="$state"/&gt;&lt;TransportStatus val="OK"/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;"""
        return """<?xml version="1.0" encoding="utf-8"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
  <e:property>
    <LastChange>$escapedEvent</LastChange>
  </e:property>
</e:propertyset>""".trimIndent()
    }

    companion object {
        private const val TAG = "GenaEventNotifier"
    }
}
