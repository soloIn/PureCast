package com.solo.purecast.airplay

import android.content.Context
import android.net.wifi.WifiManager
import com.solo.purecast.util.Logger
import com.solo.purecast.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        const val DACP_SERVICE_TYPE = "_dacp._tcp.local."
        const val DEFAULT_AIRPLAY_PORT = 7000
        const val DEFAULT_RAOP_PORT = 5000

        /**
         * 获取有效的本地局域网 IPv4 地址 (代理至公共 NetworkUtils)
         */
        fun getLocalIpv4Address(): InetAddress? = NetworkUtils.getLocalIpv4Address()
    }

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var airPlayServiceInfo: ServiceInfo? = null
    private var raopServiceInfo: ServiceInfo? = null

    // 💡 DACP 动态端口发现监听器与目标凭证匹配
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dacpResolveJob: Job? = null
    private var dacpListener: javax.jmdns.ServiceListener? = null
    private var targetDacpId: String? = null
    private var targetClientIp: String? = null
    private var onDacpPortResolvedCallback: ((Int) -> Unit)? = null

    @Volatile
    private var isBroadcasting = false
    private var boundIp: InetAddress? = null

    /**
     * 获取当前是否正在广播
     */
    fun isRunning(): Boolean = isBroadcasting

    /**
     * 获取当前已绑定广播的 IP 地址
     */
    fun getCurrentBoundIp(): InetAddress? = boundIp

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

            // 💡 注册 DACP 服务监听器，实时捕获 iPhone / iPad 在局域网中广播的临时 DACP 端口
            setupDacpServiceListener()

            boundIp = localIp
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
            dacpListener?.let { jmdns?.removeServiceListener(DACP_SERVICE_TYPE, it) }
            dacpListener = null
            clearDacpTarget()
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
            boundIp = null
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
     * 获取有效的本地局域网 IPv4 地址 (代理到 Companion 静态实现)
     */
    fun getLocalIpv4Address(): InetAddress? = Companion.getLocalIpv4Address()

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

    /**
     * 💡 设置 DACP 服务监听器，实时捕获 iOS 设备注册的 _dacp._tcp 广播
     */
    private fun setupDacpServiceListener() {
        val listener = object : javax.jmdns.ServiceListener {
            override fun serviceAdded(event: javax.jmdns.ServiceEvent) {
                Logger.service.d(TAG, "发现 _dacp._tcp 服务加入: ${event.name}")
                event.dns.requestServiceInfo(event.type, event.name, 1500)
            }

            override fun serviceRemoved(event: javax.jmdns.ServiceEvent) {
                Logger.service.d(TAG, "_dacp._tcp 服务移除: ${event.name}")
            }

            override fun serviceResolved(event: javax.jmdns.ServiceEvent) {
                val info = event.info ?: return
                Logger.service.d(TAG, "已解析 _dacp._tcp 服务: Name=${info.name}, Port=${info.port}")
                inspectDacpServiceInfo(info)
            }
        }
        dacpListener = listener
        jmdns?.addServiceListener(DACP_SERVICE_TYPE, listener)
    }

    /**
     * 💡 注册 DACP 目标客户端信息，并启动端口匹配
     */
    fun resolveDacpPort(dacpId: String, clientIp: String, onPortResolved: (Int) -> Unit) {
        targetDacpId = dacpId.ifEmpty { null }
        targetClientIp = clientIp.ifEmpty { null }
        onDacpPortResolvedCallback = onPortResolved

        Logger.service.i(TAG, "注册 DACP 监听目标: dacpId=$dacpId, clientIp=$clientIp，启动动态匹配")

        // 异步检查并解析 JmDNS 当前已缓存的服务 (由 CoroutineScope 统一调度与取消)
        jmdns?.let { dns ->
            dacpResolveJob?.cancel()
            dacpResolveJob = serviceScope.launch {
                try {
                    val existingServices = dns.list(DACP_SERVICE_TYPE)
                    for (stub in existingServices) {
                        if (!isActive) break
                        val resolved = if (stub.port > 0) stub else dns.getServiceInfo(stub.type, stub.name, 1000)
                        if (resolved != null && inspectDacpServiceInfo(resolved)) {
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Logger.service.w(TAG, "遍历解析已缓存 DACP 服务异常: ${e.message}")
                }
            }
        }
    }

    /**
     * 💡 匹配 DACP 服务的 IP、DACP-ID (通过服务名或 DbId 属性)
     */
    private fun inspectDacpServiceInfo(info: javax.jmdns.ServiceInfo): Boolean {
        val port = info.port
        if (port <= 0) return false

        val targetIp = targetClientIp
        val targetId = targetDacpId

        val hostMatches = targetIp != null && (
            info.hostAddresses.any { it == targetIp } ||
            info.inetAddresses.any { it.hostAddress == targetIp } ||
            info.inet4Addresses.any { it.hostAddress == targetIp }
        )
        val nameMatches = !targetId.isNullOrEmpty() && info.name.contains(targetId, ignoreCase = true)
        val dbIdMatches = !targetId.isNullOrEmpty() && info.getPropertyString("DbId")?.equals(targetId, ignoreCase = true) == true

        if (hostMatches || nameMatches || dbIdMatches) {
            val ips = (info.hostAddresses.toList() + info.inet4Addresses.mapNotNull { it.hostAddress }).distinct().joinToString()
            Logger.service.i(TAG, "🎉 成功锁定 DACP 真实端口: $port (Name=${info.name}, IPs=[$ips])")
            onDacpPortResolvedCallback?.invoke(port)
            return true
        }
        return false
    }

    /**
     * 清理 DACP 匹配目标
     */
    fun clearDacpTarget() {
        dacpResolveJob?.cancel()
        dacpResolveJob = null
        targetDacpId = null
        targetClientIp = null
        onDacpPortResolvedCallback = null
    }
}
