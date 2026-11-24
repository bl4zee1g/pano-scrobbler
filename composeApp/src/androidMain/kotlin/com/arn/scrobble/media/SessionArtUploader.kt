package com.arn.scrobble.media

import android.graphics.Bitmap
import com.arn.scrobble.discordrpc.UguuUploader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Uploads a media session's cover bitmap to uguu.se and caches the resulting URL.
 * The cache key is the hash of the downscaled JPEG, so a track from the same album (same cover)
 * reuses the earlier upload instead of uploading again.
 */
object SessionArtUploader {
    private const val MAX_SIDE = 512
    private const val JPEG_QUALITY = 90

    // uguu.se expires uploads after 3 hours, keep the cache well under that
    private const val TTL_MS = 30 * 60 * 1000L
    private const val MAX_CACHED = 16

    private val lock = Mutex()

    // jpeg contentHashCode -> (url, uploadedAt)
    private val uploaded = LinkedHashMap<Int, Pair<String, Long>>()

    /** Returns a URL for [bitmap], uploading it if it is not cached. Null if the upload failed. */
    suspend fun urlFor(bitmap: Bitmap): String? {
        val jpeg = bitmap.toUploadJpeg()
        val key = jpeg.contentHashCode()

        // one upload at a time, so concurrent calls for the same cover wait for the first one
        return lock.withLock {
            val now = System.currentTimeMillis()

            uploaded[key]?.let { (url, at) ->
                if (now - at < TTL_MS)
                    return@withLock url
                uploaded.remove(key)
            }

            val url = UguuUploader.upload(jpeg) ?: return@withLock null

            uploaded[key] = url to now
            while (uploaded.size > MAX_CACHED)
                uploaded.remove(uploaded.keys.first())

            url
        }
    }

    /** Center-crops to a square and scales it to at most [MAX_SIDE] px. Never upscales. */
    private fun Bitmap.toUploadJpeg(): ByteArray {
        // bitmaps from media sessions can be hardware-backed, which cannot be compressed
        val src = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val target = min(side, MAX_SIDE)
        val scaled = if (target == side) square else Bitmap.createScaledBitmap(square, target, target, true)

        return ByteArrayOutputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            it.toByteArray()
        }
    }
}
