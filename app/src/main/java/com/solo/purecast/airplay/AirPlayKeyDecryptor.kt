package com.solo.purecast.airplay

import com.solo.purecast.util.Logger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 💡 AirPlayKeyDecryptor: 专用于 AirPlay 1 (RAOP) RTSP ANNOUNCE 阶段解密 rsaaeskey
 * 采用 Apple AirPort Express 经典 RSA 私钥 (PKCS#8 格式) 进行 RSA/ECB/OAEPWithSHA-1AndMGF1Padding 解密，
 * 提取 16 字节 AES-128 主会话密钥供 Native 层实时解密音频流。
 */
object AirPlayKeyDecryptor {

    private const val TAG = "AirPlayKeyDecryptor"

    // 💡 AirPort Express 经典 RSA-2048 私钥 (标准 PKCS#8 DER Base64，与 shairport / openairplay1 100% 对齐)
    private const val AIRPORT_PRIVATE_KEY_PKCS8_BASE64 =
        "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDn10TyouJ4i2wfVaCOtwVE" +
        "qPp5RaqL5sYs5fUcvdTcaEL+PRCD3S7ewb/UJS3ALm85i98OYUjqhIVeLkQtptYmZPZ0ofME" +
        "kpreT2iT7y325xGox3oNkcnZgIIuUNEpIq/qQOqfDhTA92k4xfOIL8AyPdn+VRVfUbtZIcIB" +
        "Yp/XM1LV4u+qv5ugSNe4E6K2dn9sPM8etM5nPQN7DS6jDF//6wb40Ird5AlXGpxon+8QcohV" +
        "3Yz7movvXIlD7ztfqhXd5pi+3fNZlgPrPm9hNyu2KPZVn1maeL9QBoeqf0l2wFYtQSlW+Jie" +
        "GKY1W9gVl4JeD8h1ND7HghF2Jc2/mER7AgMBAAECggEBAOXwDHL1d9YEuaTOQSKqhLAXQ+yZ" +
        "Ws/Mf0qyfAsYf5BmW+NZ3xJZgY3u7XnTse+EXk3d2smhVTc7XicNjhMVABouUn1UzfkACldo" +
        "vJjURGs3u70Asp3YtTBiEzsqbnf07jJQViKQTacg+xwSwDmW2nE6BQYJjtvt7Pk20PqcvVkp" +
        "q7Dto1eZUC+YlNy4/FaaiS0XeAMkorbDFm40ZwkTS4VAQbhncGtY/vKg25Ird2KLaOaWk8ev" +
        "Q78qc9C3Mjd6C6F7RPBR6b95hJ3LMzJXH9inCTPC1gvexHmTSj2spAu28vN8Cp0HEG6tyLNp" +
        "oD8vQciACY6K3UYkDaxozFNU82ECgYEA9+C/Wh5nGDGai2IJwxcURARZ+XOFZhOxeuFQi7Pm" +
        "MW5rf0YtL31kQSuEt2vCPysMNWJFUnmyQ6n3MW+VgAezTGH3aOLUTtX/KycoF+wys+STkpIo" +
        "+ueOd0yg9169adWSAnmPEW42DGQ4sy4b2LncHjIy8NMJGIg8xD743aIsNpECgYEA72//+ZTx" +
        "5WRBqgA1/RmgyNbwI3jHBYDZxIQgeR30B8WR+26/yjIsMIbdkB/S+uGuu2St9rt5/4BRvr0M" +
        "2CCriYdABgGnsv6TkMrMmsq47Sv5HRhtj2lkPX7+D11W33V3otA16lQT/JjY8/kI2gWaN52k" +
        "scw48V1WCoPMMXFTyEsCgYEA0OuvvEAluoGMdXAjNDhOj2lvgE16oOd2TlB7t9Pf78fWeMZo" +
        "LT+tcTRBvurnJKCewJvcO8BwnJEz1Ins4qUa3QUxJ0kPkobRc8ikBU3CCldcfkwMmDT0od6HS" +
        "Rej5ADq+IUGLbXLfjQ2iecR91/ng9fhkZL9dpzVQr6kuQEH7NECgYB/QBjcfeopLaUwQjhvM" +
        "QWgd4rcbz3mkNordMUFWYPt9XRmGi/Xt96AU8zA4gjwyKxib1l9PZnSzlGjezmuS36e8sB18" +
        "L89g8rNMtqWkZLCiZI1glwH0c0yWaGQbNzUmcthPiLJTLHqlxkGYJ3xsPSLBj8XNyA0NpSZt" +
        "f35cO9EDQKBgQCQTukg+UTvWq98lCCgD16bSAgsC4Tg+7XdoqImd9+3uEiNsr7mTJvdPKxm" +
        "+jIOdvcc4q8icru9dsq5TghKDEHZsHcdxjNAwazPWonaAbQ3mG8mnPDCFuFeoUoDjNppKvDr" +
        "bbAOeIArkyUgTS0gAoo/jLE0aOgPZBiOEEa6G+RYpg=="

    private var cachedPrivateKey: PrivateKey? = null

    init {
        try {
            val keyBytes = Base64.getDecoder().decode(AIRPORT_PRIVATE_KEY_PKCS8_BASE64.replace("\n", "").trim())
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            cachedPrivateKey = keyFactory.generatePrivate(keySpec)
            Logger.audio.i(TAG, "AirPort Express RSA 私钥初始化成功: algo=${cachedPrivateKey?.algorithm}")
        } catch (e: Exception) {
            Logger.audio.e(TAG, "AirPort Express RSA 私钥解析失败", e)
        }
    }

    /**
     * 判断私钥是否已成功加载
     */
    fun isPrivateKeyReady(): Boolean = cachedPrivateKey != null

    /**
     * 💡 解决 Apple-Challenge 挑战响应机制 (生成 Apple-Response)
     * 遵循 RFC 3447 9.2 PKCS#1 v1.5 Block Type 1 规范与 AirTunes RAOP 协议实现：
     * 1. 解码 Apple-Challenge (16 bytes)
     * 2. 追加本地连接的 IPv4 地址 (4 bytes) 与 真实网卡 MAC 地址 (6 bytes)
     * 3. 填充对齐至 32 字节 (若不足 32 字节末尾补 0x00)
     * 4. 使用 AirPort Express 私钥进行 PKCS#1 v1.5 加密签名 (Cipher ENCRYPT_MODE / RSA/ECB/PKCS1Padding)
     * 5. 编码为无填充符 (no padding '=') 的 Base64 字符串返回
     */
    fun solveAppleChallenge(
        challengeBase64: String,
        localIp: java.net.InetAddress,
        macBytes: ByteArray
    ): String? {
        val privateKey = cachedPrivateKey ?: run {
            Logger.audio.e(TAG, "RSA 私钥未初始化，无法响应 Apple-Challenge")
            return null
        }

        return try {
            // 1. Base64 解码 Challenge (处理末尾缺少 = padding 的情况)
            var cleanChallenge = challengeBase64.trim()
            val mod = cleanChallenge.length % 4
            if (mod != 0) {
                cleanChallenge += "=".repeat(4 - mod)
            }
            val challengeBytes = Base64.getDecoder().decode(cleanChallenge)

            // 2. 获取 IP 字节
            val ipBytes = localIp.address

            // 3. 构建待签名数据 (challenge + ip + mac，最小长度 32 字节)
            val minLen = 32
            val contentLen = challengeBytes.size + ipBytes.size + macBytes.size
            val bufferLen = maxOf(minLen, contentLen)
            val buffer = ByteArray(bufferLen)

            var offset = 0
            System.arraycopy(challengeBytes, 0, buffer, offset, challengeBytes.size)
            offset += challengeBytes.size

            System.arraycopy(ipBytes, 0, buffer, offset, ipBytes.size)
            offset += ipBytes.size

            System.arraycopy(macBytes, 0, buffer, offset, macBytes.size)
            offset += macBytes.size

            // 4. PKCS#1 v1.5 加密签名 (Java 标准 JCE 支持对 PrivateKey 使用 PKCS1Padding 加密)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, privateKey)
            val signedBytes = cipher.doFinal(buffer)

            // 5. 编码为 Base64，移除换行符与末尾 '=' 填充
            Base64.getEncoder().encodeToString(signedBytes)
                .replace("=", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim()
        } catch (e: Exception) {
            Logger.audio.e(TAG, "解决 Apple-Challenge 异常", e)
            null
        }
    }

    /**
     * 解密 RTSP ANNOUNCE 中的 rsaaeskey (Base64)
     * @param encryptedAesKeyBase64 Base64 编码的 128 字节 (1024-bit) 或 256 字节 (2048-bit) 加密密钥
     * @return 16 字节 AES 会话密钥，失败返回 null
     */
    fun decryptRsaAesKey(encryptedAesKeyBase64: String): ByteArray? {
        val privateKey = cachedPrivateKey ?: run {
            Logger.audio.e(TAG, "RSA 私钥未初始化，无法解密")
            return null
        }

        return try {
            val encryptedBytes = Base64.getDecoder().decode(encryptedAesKeyBase64.trim())
            
            // 💡 尝试 OAEP 填充，若失败则尝试标准 PKCS1Padding
            val decrypted = try {
                val cipherOaep = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
                cipherOaep.init(Cipher.DECRYPT_MODE, privateKey)
                cipherOaep.doFinal(encryptedBytes)
            } catch (e: Exception) {
                Logger.audio.w(TAG, "OAEP 解密失败，尝试 PKCS1Padding: ${e.message}")
                val cipherPkcs1 = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipherPkcs1.init(Cipher.DECRYPT_MODE, privateKey)
                cipherPkcs1.doFinal(encryptedBytes)
            }

            if (decrypted.size == 16) {
                decrypted
            } else if (decrypted.size > 16) {
                // 部分 iOS 版本返回末尾 16 字节
                decrypted.copyOfRange(decrypted.size - 16, decrypted.size)
            } else {
                Logger.audio.e(TAG, "解密出的 AES 密钥长度异常: ${decrypted.size} bytes")
                null
            }
        } catch (e: Exception) {
            Logger.audio.e(TAG, "解密 rsaaeskey 失败", e)
            null
        }
    }
}
