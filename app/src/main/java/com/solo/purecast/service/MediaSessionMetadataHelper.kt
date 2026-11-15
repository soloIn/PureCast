package com.solo.purecast.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import com.solo.purecast.util.Logger
import com.solo.purecast.model.TrackMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 💡 MediaSessionMetadataHelper: 封装系统 MediaSession 元数据及专辑封面位图采样解码逻辑，
 * 防止封面位图超大引发 Binder 事务缓冲区溢出 (TransactionTooLargeException)，
 * 并缓存已解码位图避免歌名/进度刷新时频繁重解与界面闪烁。
 */
class MediaSessionMetadataHelper {

    private var lastCoverMetadataHash: Int = 0
    private var lastCoverBitmap: Bitmap? = null

    /**
     * 采样解码封面 ByteArray 为轻量级 Bitmap (512x512 RGB_565)
     */
    fun decodeSampledBitmapFromByteArray(
        bytes: ByteArray,
        reqWidth: Int = 512,
        reqHeight: Int = 512
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val (srcWidth, srcHeight) = options.outWidth to options.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return null

            var inSampleSize = 1
            while (srcWidth / inSampleSize > reqWidth || srcHeight / inSampleSize > reqHeight) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 💡 降低内存占用，杜绝 Binder 1MB 事务缓冲区溢出

            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            if (decoded.width > reqWidth || decoded.height > reqHeight) {
                val scaled = Bitmap.createScaledBitmap(decoded, reqWidth, reqHeight, true)
                if (scaled != decoded) {
                    decoded.recycle()
                }
                scaled
            } else {
                decoded
            }
        } catch (t: Throwable) {
            Logger.service.w(TAG, "解码 MediaSession 封面 Bitmap 异常: ${t.message}")
            null
        }
    }

    /**
     * 更新 MediaSession 元数据，并在封面存在时异步下采样解码投递
     */
    fun updateMediaSessionMetadata(
        session: MediaSession?,
        metadata: TrackMetadata,
        scope: CoroutineScope
    ) {
        if (session == null) return
        try {
            val baseBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, metadata.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, metadata.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, metadata.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, metadata.durationMs)

            val coverBytes = metadata.coverBytes
            val currentHash = metadata.coverHash

            // 💡 复用已解码的封面 Bitmap，避免无封面变更时被 baseBuilder 覆盖清空
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                if (currentHash == lastCoverMetadataHash && lastCoverBitmap != null) {
                    baseBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, lastCoverBitmap)
                    baseBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, lastCoverBitmap)
                }
            } else {
                lastCoverMetadataHash = 0
                lastCoverBitmap = null
            }

            // 先快速更新纯文本元数据（若已有缓存封面则一并携带）
            session.setMetadata(baseBuilder.build())

            // 💡 异步采样解码封面并同步至系统全局 MediaSession 卡片，限制 512x512 RGB_565
            if (coverBytes != null && coverBytes.isNotEmpty() && currentHash != lastCoverMetadataHash) {
                lastCoverMetadataHash = currentHash
                scope.launch(Dispatchers.IO) {
                    val bitmap = decodeSampledBitmapFromByteArray(coverBytes, reqWidth = 512, reqHeight = 512)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            try {
                                if (lastCoverMetadataHash == currentHash) {
                                    lastCoverBitmap = bitmap
                                    val fullBuilder = MediaMetadata.Builder()
                                        .putString(MediaMetadata.METADATA_KEY_TITLE, metadata.title)
                                        .putString(MediaMetadata.METADATA_KEY_ARTIST, metadata.artist)
                                        .putString(MediaMetadata.METADATA_KEY_ALBUM, metadata.album)
                                        .putLong(MediaMetadata.METADATA_KEY_DURATION, metadata.durationMs)
                                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                                        .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                                    session.setMetadata(fullBuilder.build())
                                }
                            } catch (t: Throwable) {
                                Logger.service.w(TAG, "设置 MediaSession 带封面元数据异常", t)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.service.w(TAG, "更新 MediaMetadata 异常", e)
        }
    }

    /**
     * 清理缓存的封面资源
     */
    fun clear() {
        lastCoverBitmap = null
        lastCoverMetadataHash = 0
    }

    companion object {
        private const val TAG = "MediaSessionMetadata"
    }
}
