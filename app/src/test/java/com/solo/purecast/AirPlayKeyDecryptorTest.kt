package com.solo.purecast

import com.solo.purecast.airplay.AirPlayKeyDecryptor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

/**
 * 💡 AirPlayKeyDecryptor 单元测试
 * 验证 AirPort Express RSA 私钥加载与 Apple-Challenge 挑战求解
 */
class AirPlayKeyDecryptorTest {

    @Test
    fun testAirPlayKeyDecryptorInitialization() {
        assertTrue("私钥必须正确初始化", AirPlayKeyDecryptor.isPrivateKeyReady())
    }

    @Test
    fun testSolveAppleChallenge() {
        val fakeChallenge = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val localIp = InetAddress.getByName("192.168.2.179")
        val macBytes = byteArrayOf(0x14, 0x85.toByte(), 0x54, 0x0B, 0xCB.toByte(), 0xC4.toByte())

        val response = AirPlayKeyDecryptor.solveAppleChallenge(fakeChallenge, localIp, macBytes)
        assertNotNull("Apple-Response 不得为 null", response)
        assertTrue("Apple-Response 长度必须大于 100", (response?.length ?: 0) > 100)
        assertTrue("Apple-Response 不得包含 '=' 填充符", !response!!.contains("="))
        println("生成的 Apple-Response: $response")
    }
}
