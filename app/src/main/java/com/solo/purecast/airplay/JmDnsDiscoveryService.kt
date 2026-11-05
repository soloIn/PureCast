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
            // 💡 严格遵循 RFC 1034 / RFC 6762 规范：mDNS 主机名严禁包含空格等非法字符！
            // 传入纯小写字母与连字符构造合法的主机名 (如 purecast-1485540bcbc4)，SRV 记录的 Target 即为合规的 purecast-1485540bcbc4.local.
            // 解决 iOS 的 mDNSResponder 将带空格的主机名判定为 Malformed DNS Label 导致 iPhone 直接搜不到设备的致命 Bug
            val hostName = "purecast-${macAddressFormatted.lowercase()}"
            Logger.service.i(TAG, "初始化 JmDNS 实例: IP=${localIp.hostAddress}, MAC=$macAddressFormatted, HostName=$hostName, FriendlyName=$friendlyName")

            jmdns = JmDNS.create(localIp, hostName)

            // 💡 彻底注销旧残留的 _airplay._tcp 服务，杜绝 iOS/macOS 误入 AirPlay 2 配对通道
            try {
                val staleAirPlayInfo = ServiceInfo.create(AIRPLAY_SERVICE_TYPE, friendlyName, raopPort, 0, 0, emptyMap<String, String>())
                jmdns?.unregisterService(staleAirPlayInfo)
            } catch (e: Exception) {
                // ignore
            }

            // 💡 严格遵循经典 AirTunes / AirPort Express 纯音频规范 (与实测通过的 AirReceiver 100% 对齐)
            // 严禁注册 _airplay._tcp，iOS / macOS 音乐 App 仅凭借 _raop._tcp 即可直接发现并显示为无线音频音箱
            // am 使用 "AirRecever3,1" 避开 Apple 对官方硬件指纹与固件配对的严格校验，解决 macOS 报“不兼容”问题
            val raopInstanceName = "${macAddressFormatted}@$friendlyName"
            val raopProps = mapOf(
                "txtvers" to "1",
                "ch" to "2",
                "cn" to "0,1",
                "et" to "0,1",
                "sv" to "false",
                "da" to "true",
                "sr" to "44100",
                "ss" to "16",
                "pw" to "false",
                "vn" to "3",
                "tp" to "UDP",
                "md" to "0,1,2",
                "vs" to "105.1",
                "am" to "AirRecever3,1",
                "sf" to "0x4",
                "fv" to "76400.10",
                "ek" to "1"
            )

            raopServiceInfo = ServiceInfo.create(
                RAOP_SERVICE_TYPE,
                raopInstanceName,
                raopPort,
                0,
                0,
                raopProps
            )

            jmdns?.registerService(raopServiceInfo)

            isBroadcasting = true
            Logger.service.i(TAG, "AirPlay 1 (AirTunes / RAOP) 纯音频广播已成功注册: Instance=$raopInstanceName, Target=$hostName.local.:$raopPort, am=AirRecever3,1")
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
     * 💡 优先绑定物理网卡 wlan (WiFi) 或 eth (以太网)，主动跳过 usb0/rndis 等虚拟/共享接口
     */
    fun getLocalIpv4Address(): InetAddress? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sortedInterfaces = interfaces.sortedWith(compareByDescending { intf ->
                when {
                    intf.name.startsWith("wlan") -> 3
                    intf.name.startsWith("eth") -> 2
                    !intf.name.startsWith("usb") -> 1
                    else -> 0
                }
            })
            for (intf in sortedInterfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (!host.startsWith("127.") && !host.startsWith("169.254.")) {
                            return addr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.service.e(TAG, "获取本地 IPv4 地址失败", e)
        }
        return null
    }

    /**
     * 获取格式化的 MAC 地址（12 位大写无分隔符，如 1485540BCBC4）
     * 💡 优先从 Linux sysfs 直接读取真实物理网卡 MAC（避开 Android 11+ NetworkInterface.getHardwareAddress() 隐私屏蔽）
     */
    fun getMacAddressFormatted(): String {
        // 1. 优先尝试从 Linux sysfs 直接读取真机真实物理网卡 MAC
        val sysPaths = listOf(
            "/sys/class/net/wlan0/address",
            "/sys/class/net/eth0/address"
        )
        for (path in sysPaths) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    val raw = file.readText().trim()
                    val cleaned = raw.replace(":", "").replace("-", "").uppercase()
                    if (cleaned.length == 12 && cleaned.matches(Regex("^[0-9A-F]{12}$"))) {
                        Logger.service.i(TAG, "通过 sysfs [$path] 成功获取真实物理 MAC: $cleaned")
                        return cleaned
                    }
                }
            } catch (e: Exception) {
                Logger.service.w(TAG, "读取 $path 异常: ${e.message}")
            }
        }

        // 2. 尝试从 NetworkInterface 读取
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sortedInterfaces = interfaces.sortedWith(compareByDescending { intf ->
                when {
                    intf.name.startsWith("wlan") -> 3
                    intf.name.startsWith("eth") -> 2
                    else -> 1
                }
            })
            for (intf in sortedInterfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val mac = intf.hardwareAddress ?: continue
                if (mac.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (b in mac) {
                        sb.append(String.format("%02X", b))
                    }
                    if (sb.length == 12) {
                        Logger.service.i(TAG, "通过 NetworkInterface [${intf.name}] 成功获取 MAC: $sb")
                        return sb.toString()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.service.w(TAG, "读取 NetworkInterface MAC 异常: ${e.message}")
        }

        // 3. 兜底策略：回退至当前真机真实物理 MAC 默认值
        Logger.service.w(TAG, "无法动态获取物理 MAC，回退至基线物理 MAC")
        return "1485540BCBC4"
    }

    private fun formatMacWithColons(mac: String): String {
        return if (mac.length == 12) {
            mac.chunked(2).joinToString(":")
        } else {
            mac
        }
    }
}
