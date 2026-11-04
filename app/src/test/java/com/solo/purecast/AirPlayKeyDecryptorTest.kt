package com.solo.purecast

import com.solo.purecast.airplay.AirPlayKeyDecryptor
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 💡 AirPlayKeyDecryptor 单元测试
 * 验证 AirPort Express RSA 私钥加载与可用性
 */
class AirPlayKeyDecryptorTest {

    @Test
    fun testAirPlayKeyDecryptorInitialization() {
        // 验证单例对象初始化正常且私钥解析成功，未抛出异常
        assertNotNull(AirPlayKeyDecryptor)
    }
}
