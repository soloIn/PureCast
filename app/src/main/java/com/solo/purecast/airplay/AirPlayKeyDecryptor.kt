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

    // 💡 AirPort Express 经典 RSA-2048 私钥 (PKCS#1 转 PKCS#8 标准 DER 编码 Base64)
    private const val AIRPORT_PRIVATE_KEY_PKCS8_BASE64 =
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDn10TyoeJ4i2wfVaCOtwVE" +
        "qPp5RaqL5sYs5fUcvdTcaEL+PRCD3S7ewb/UJS3ALm85i98OYUjqhIVeLkQtptYmZPZ0ofME" +
        "kpreT2iT7y325xGox3oNkcStoreQMIuUNEpIq/qQOqfDhTA92k4xfOIL8AyPdn+VRVfUbtZI" +
        "cIBYp/XM1LV4u+qv5ugSNe4E6K2dn9sPM8etM5nPQN7DS6jDF///6wb40Ird5AlXGpxon+8Q" +
        "cohV3Yz7movvXIlD7ztfqhXd5pi+3fNZlgPrPm9hNyu2KPZVn1maeL9QBoeqf0l2wFYtQSlW" +
        "+JieGKY1W9gVl4JeD8h1ND7HghF2Jc2/mER7AgMBAAECggEBAOXwDHL1d9YEuaTOQSKqhLAX" +
        "Q+yZWs/Mf0qyfAsYf5BmW+NZ3xJZgY3u7XnTse+EXk3d2smhVTc7XicNjhMVABouUn1UzfkA" +
        "CldovJjURGs3u70Asp3YtTBiEzsqbnf07jJQVnEQTaAg+xwSwDmW2nE6BQYJjtvt7Pk20Pqc" +
        "vVkpq7DtoxWZUCSYlNy4/FaaiS0XeAMkorbDFm40ZwkTS4VAQbhncGtY/vKg25Ird2KLaOaW" +
        "k8evQ78qc9C3Mjd6C6F7RPBR6b95hJ3LMzJXHzCnCTPC1gvexHmTSj2spAu28vN8Cp0HEG6t" +
        "yLNpoD8vQciACY6K3UYkDaxtzFMU82ECgYEA9+C/Wh5nGDFqi2IJwxcURARZ+XOFZhOxeuFQ" +
        "i7PmMWRrf0YtL31kQSuEt2vCPysMNWJFUl2qQ6n3MW+VgAezTGH3aOLUTtX/Kycrd+wyveSS" +
        "kpIo+ueOd0yg9169adWSAnmPEW42DGQ4sy4b2LncHjIy8NMJGIg8xD743aIsNpECgYEA72//" +
        "+ZTx5WRBqgA1/RmgyNbwI3jHBYDZxIQgeR30B8WR+26/yjIsMIbdkB/S+uGuu2St9rt5/4BR" +
        "vr0M2CCriYdABgGncv6TkMrMmsq47Sv5HRhtj2lkPX7+D11W33V3otB16lQT/JjY8/kI2gWa" +
        "N52kscw48V1WCoPMMXFTyEsCgYEA0OuvvEAldoGMdXAjNDhOj2lvgE16oOd2TlB7t9Pf78fW" +
        "eMZoLT+tcTRBvurnJKCewJvcO8BwnJEz1Ins4qUbQUxJ0kPksfRc8ikBU3CClccfkwMmDT0o" +
        "d6HSRer5ADq+IUGLa3LfjQ2iecR91/ng9fhkZL9dpzVQr6kuQEH7NECAYB/QBjcfeopLaUwQ" +
        "jhvMQWgd4rcbz3mkNordinBVmD7fV0Zhov17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nv" +
        "LAdfC/PYPKzTLa1pGSwomSNYJcB9HNMlmhkGzc1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgND" +
        "aUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gILAuE4Pu13aKiJnfft7hIjbK+5kyb3T" +
        "ysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ2gG0N5hvJpzwwhbhXqFKA4zaaS" +
        "rw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY="

    private var cachedPrivateKey: PrivateKey? = null

    init {
        try {
            val keyBytes = Base64.getDecoder().decode(AIRPORT_PRIVATE_KEY_PKCS8_BASE64.replace("\n", "").trim())
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            cachedPrivateKey = keyFactory.generatePrivate(keySpec)
            Logger.audio.i(TAG, "AirPort Express RSA 私钥初始化成功")
        } catch (e: Exception) {
            Logger.audio.e(TAG, "AirPort Express RSA 私钥解析失败", e)
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
