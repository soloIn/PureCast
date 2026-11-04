package com.solo.purecast

import com.solo.purecast.airplay.JmDnsDiscoveryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.jmdns.ServiceInfo

/**
 * 💡 JmDnsDiscoveryService 单元测试
 * 验证 AirPlay 1 与 RAOP 广播的服务类型、端口规范、TXT 记录属性及实例命名格式
 */
class JmDnsDiscoveryServiceTest {

    @Test
    fun testAirPlayAndRaopServicePropsCreation() {
        val friendlyName = "PureCast TV"
        val mac = "4A3B2C1D0E1F"
        val airplayPort = 7000
        val raopPort = 5000

        // 验证 _airplay._tcp.local. 服务属性 (纯音频 AirPort4,107, features=0x7)
        val airplayProps = mapOf(
            "deviceid" to "4A:3B:2C:1D:0E:1F",
            "features" to "0x7",
            "flags" to "0x4",
            "model" to "AirPort4,107",
            "srcvers" to "220.68",
            "vv" to "2"
        )

        val airplayInfo = ServiceInfo.create(
            JmDnsDiscoveryService.AIRPLAY_SERVICE_TYPE,
            friendlyName,
            raopPort,
            0,
            0,
            airplayProps
        )

        assertEquals(JmDnsDiscoveryService.AIRPLAY_SERVICE_TYPE, airplayInfo.type)
        assertEquals(friendlyName, airplayInfo.name)
        assertEquals(5000, airplayInfo.port)
        assertEquals("AirPort4,107", airplayInfo.getPropertyString("model"))
        assertEquals("0x7", airplayInfo.getPropertyString("features"))

        // 验证 _raop._tcp.local. 服务属性与命名规范 (必须为 MAC@Name)
        val raopInstanceName = "${mac}@$friendlyName"
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
            "am" to "AirPort4,107",
            "sf" to "0x4",
            "fv" to "76400.10",
            "ek" to "1"
        )

        val raopInfo = ServiceInfo.create(
            JmDnsDiscoveryService.RAOP_SERVICE_TYPE,
            raopInstanceName,
            raopPort,
            0,
            0,
            raopProps
        )

        assertEquals(JmDnsDiscoveryService.RAOP_SERVICE_TYPE, raopInfo.type)
        assertEquals("4A3B2C1D0E1F@PureCast TV", raopInfo.name)
        assertEquals(5000, raopInfo.port)
        assertEquals("UDP", raopInfo.getPropertyString("tp"))
        assertEquals("AirPort4,107", raopInfo.getPropertyString("am"))
        assertEquals("44100", raopInfo.getPropertyString("sr"))
        assertEquals("16", raopInfo.getPropertyString("ss"))
        assertEquals("2", raopInfo.getPropertyString("ch"))
        assertEquals("0x4", raopInfo.getPropertyString("sf"))
        assertEquals("76400.10", raopInfo.getPropertyString("fv"))
        assertEquals("105.1", raopInfo.getPropertyString("vs"))
    }
}
