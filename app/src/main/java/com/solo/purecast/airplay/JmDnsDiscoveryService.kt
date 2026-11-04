package com.solo.purecast.airplay

import android.content.Context
import android.net.wifi.WifiManager
import com.solo.purecast.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * 💡 JmDnsDiscoveryService: 基于 jmdns 的 AirPlay 1 (AirTunes / RAOP) 零配置双服务广播管理器
 *
 * 核心技术策略：
 * 1. 废弃 Android 原生 NsdManager（避开 @ 字符转义崩溃与多属性 TXT 截断 Bug）。
 * 2. 必须且同时广播两个 Bonjour 服务：
 *    - `_airplay._tcp.local.` (端口 7000): 声明 AirPlay 1 特性标识 features、model 等，使 iOS 15~18 隔空投送列表稳定发现。
 *    - `<MAC>@<Name>._raop._tcp.local.` (端口 5000): 声明 RAOP 音频通道属性 (UDP、ALAC、44.1kHz/16-bit)。
 * 3. 严格托管 WifiManager.MulticastLock，确保电视在息屏与后台运行时组播流量畅通。
 */
class JmDnsDiscoveryService(private val context: Context) {

    companion object {
        private const val TAG = "JmDnsDiscovery"
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."
        const val RAOP_SERVICE_TYPE = "_raop._tcp.local."
        const val DEFAULT_AIRPLAY_PORT = 7000
        const val DEFAULT_RAOP_PORT = 5000
    }

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var airPlayServiceInfo: ServiceInfo? = null
    private var raopServiceInfo: ServiceInfo? = null

    @Volatile
    private var isBroadcasting = false

    /**
     * 获取当前是否正在广播
     */
    fun isRunning(): Boolean = isBroadcasting

    /**
     * 启动 AirPlay / RAOP 广播
     * @param friendlyName 电视在隔空投送列表中显示的友好名称（如 PureCast TV）
     * @param airPlayPort AirPlay 信令端口（默认 7000）
     * @param raopPort RAOP RTSP 端口（默认 5000）
     */
    suspend fun startBroadcast(
        friendlyName: String = "PureCast TV",
        airPlayPort: Int = DEFAULT_AIRPLAY_PORT,
        raopPort: Int = DEFAULT_RAOP_PORT
    ): Boolean = withContext(Dispatchers.IO) {
        if (isBroadcasting) {
            Logger.service.w(TAG, "JmDnsDiscoveryService 已处于运行状态，无需重复启动")
            return@withContext true
        }

        try {
            acquireMulticastLock()

            val localIp = getLocalIpv4Address()
            if (localIp == null) {
                Logger.service.e(TAG, "未获取到有效的局域网 IPv4 地址，广播启动失败")
                releaseMulticastLock()
                return@withContext false
            }

            val macAddressFormatted = getMacAddressFormatted()
            Logger.service.i(TAG, "初始化 JmDNS 实例: IP=${localIp.hostAddress}, MAC=$macAddressFormatted, Name=$friendlyName")

            jmdns = JmDNS.create(localIp, friendlyName)

            // 1. 构建 _airplay._tcp.local. 服务
            val airplayProps = mapOf(
                "deviceid" to formatMacWithColons(macAddressFormatted),
                "features" to "0x5A7FFFF7,0x1E",
                "flags" to "0x4",
                "model" to "AppleTV2,1",
                "pk" to "b07727d6f6cd5e08b58ede525ec3cdeaa252ad9f683fedc4d1fba8343fc95634",
                "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
                "srcvers" to "220.68",
                "vv" to "2"
            )

            airPlayServiceInfo = ServiceInfo.create(
                AIRPLAY_SERVICE_TYPE,
                friendlyName,
                airPlayPort,
                0,
                0,
                airplayProps
            )

            // 2. 构建 _raop._tcp.local. 服务 (服务名必须为: MAC@Name)
            val raopInstanceName = "${macAddressFormatted}@$friendlyName"
            val raopProps = mapOf(
                "ch" to "2",
                "cn" to "0,1",
                "da" to "true",
                "et" to "0,1",
                "ek" to "1",
                "md" to "0,1,2",
                "pw" to "false",
                "sr" to "44100",
                "ss" to "16",
                "sv" to "false",
                "sm" to "false",
                "tp" to "UDP",
                "txtvers" to "1",
                "vn" to "3",
                "vs" to "220.68"
            )

            raopServiceInfo = ServiceInfo.create(
                RAOP_SERVICE_TYPE,
                raopInstanceName,
                raopPort,
                0,
                0,
                raopProps
            )

            jmdns?.registerService(airPlayServiceInfo)
            jmdns?.registerService(raopServiceInfo)

            isBroadcasting = true
            Logger.service.i(TAG, "AirPlay 1 / RAOP 双服务已成功通过 JmDNS 广播在局域网中")
            true
        } catch (e: Exception) {
            Logger.service.e(TAG, "启动 JmDnsDiscoveryService 异常", e)
            stopBroadcast()
            false
        }
    }

    /**
     * 停止并注销广播服务
     */
    suspend fun stopBroadcast() = withContext(Dispatchers.IO) {
        if (!isBroadcasting && jmdns == null) return@withContext

        Logger.service.i(TAG, "正在停止 JmDNS 广播并注销 Bonjour 服务...")
        try {
            airPlayServiceInfo?.let { jmdns?.unregisterService(it) }
            raopServiceInfo?.let { jmdns?.unregisterService(it) }
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: Exception) {
            Logger.service.w(TAG, "注销 JmDNS 服务时发生异常: ${e.message}")
        } finally {
            jmdns = null
            airPlayServiceInfo = null
            raopServiceInfo = null
            isBroadcasting = false
            releaseMulticastLock()
            Logger.service.i(TAG, "JmDNS 广播已注销，MulticastLock 已释放")
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("PureCast_AirPlay_mDNS")?.apply {
                setReferenceCounted(true)
            }
        }
        if (multicastLock?.isHeld == false) {
            multicastLock?.acquire()
            Logger.service.i(TAG, "已获取 WifiManager.MulticastLock 组播锁")
        }
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
            Logger.service.i(TAG, "已释放 WifiManager.MulticastLock 组播锁")
        }
    }

    /**
     * 获取有效的本地局域网 IPv4 地址
     */
    fun getLocalIpv4Address(): InetAddress? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            Logger.service.e(TAG, "获取本地 IPv4 地址失败", e)
        }
        return null
    }

    /**
     * 获取格式化的 MAC 地址（12 位大写无分隔符，如 001122334455）
     */
    fun getMacAddressFormatted(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val mac = intf.hardwareAddress ?: continue
                if (mac.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (b in mac) {
                        sb.append(String.format("%02X", b))
                    }
                    if (sb.isNotEmpty()) {
                        return sb.toString()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.service.w(TAG, "读取物理 MAC 异常，回退至虚拟固定 MAC: ${e.message}")
        }
        return "4A3B2C1D0E1F"
    }

    private fun formatMacWithColons(mac: String): String {
        return if (mac.length == 12) {
            mac.chunked(2).joinToString(":")
        } else {
            mac
        }
    }
}
