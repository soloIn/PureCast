package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 💡 歌词网络请求轻量客户端契约
 */
interface LyricsHttpClient {
    suspend fun get(url: String, timeoutMs: Int = 1500, headers: Map<String, String> = emptyMap()): HttpResponse

    data class HttpResponse(
        val statusCode: Int,
        val body: String
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299
    }
}

/**
 * 💡 基于原生 HttpURLConnection 的生产级轻量实现（零外部重量级框架开销）
 */
class DefaultLyricsHttpClient : LyricsHttpClient {

    override suspend fun get(url: String, timeoutMs: Int, headers: Map<String, String>): LyricsHttpClient.HttpResponse =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PureCast-TV/1.0 (Android TV)")
                    setRequestProperty("Accept", "application/json, text/xml, */*")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = if (stream != null) {
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                } else {
                    ""
                }

                LyricsHttpClient.HttpResponse(statusCode = code, body = body)
            } catch (e: Exception) {
                Logger.lyrics.w("LyricsHttpClient", "GET 请求失败: url=$url, error=${e.message}")
                LyricsHttpClient.HttpResponse(statusCode = -1, body = "")
            } finally {
                conn?.disconnect()
            }
        }
}
