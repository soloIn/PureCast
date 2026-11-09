package com.solo.purecast.util

import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * 💡 网络工具类：统一处理局域网物理网卡识别与有效 IPv4 地址检索
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * 获取有效的本地局域网 IPv4 地址
     * 💡 优先绑定物理网卡 wlan (WiFi) 或 eth (以太网)，严格过滤 169.254. 链路本地地址、usb0/rndis 及虚拟/代理接口
     */
    fun getLocalIpv4Address(): InetAddress? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sortedInterfaces = interfaces.sortedWith(compareByDescending { intf ->
                when {
                    intf.name.startsWith("wlan") -> 3
                    intf.name.startsWith("eth") -> 2
                    else -> 0
                }
            })
            for (intf in sortedInterfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                // 💡 严格排除投影仪内部主板 usb0、虚拟网卡 dummy、p2p、tun 等
                if (name.startsWith("usb") || name.startsWith("dummy") || name.startsWith("p2p") || name.startsWith("tun")) {
                    continue
                }
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (!host.startsWith("127.") && !host.startsWith("169.254.") && !host.startsWith("192.168.48.")) {
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
}
