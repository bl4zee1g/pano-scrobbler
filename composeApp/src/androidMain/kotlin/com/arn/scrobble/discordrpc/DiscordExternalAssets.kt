package com.arn.scrobble.discordrpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves image urls to `mp:external/...` references that the gateway accepts as asset keys.
 */
internal object DiscordExternalAssets {
    private const val API = "https://discord.com/api/v9/applications/%s/external-assets"
    private const val CACHE_MAX_SIZE = 128

    private val cache = ConcurrentHashMap<String, String>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolve(imageUrl: String, appId: String, bearerToken: String): String? {
        if (imageUrl.isBlank()) return null
        if (imageUrl.startsWith("mp:")) return imageUrl
        cache[imageUrl]?.let { return it }

        return try {
            withContext(Dispatchers.IO) {
                val body = JSONObject().put("urls", JSONArray().put(imageUrl)).toString()
                val request = Request.Builder()
                    .url(API.format(appId))
                    .header("Authorization", bearerToken)
                    .header("User-Agent", DiscordSuperProperties.USER_AGENT)
                    .header("X-Super-Properties", DiscordSuperProperties.base64)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful || text.isBlank()) {
                        discordLog.w { "external-assets: HTTP ${response.code}: ${text.take(200)}" }
                        return@withContext null
                    }
                    val path = JSONArray(text).optJSONObject(0)
                        ?.optString("external_asset_path")
                        ?.takeIf { it.isNotEmpty() }
                    if (path == null) {
                        discordLog.w { "external-assets: no path in response: ${text.take(200)}" }
                        return@withContext null
                    }
                    discordLog.i { "external-assets: $imageUrl -> $path" }

                    "mp:$path".also {
                        if (cache.size >= CACHE_MAX_SIZE) cache.clear()
                        cache[imageUrl] = it
                    }
                }
            }
        } catch (e: Exception) {
            discordLog.w(e) { "external-assets failed" }
            null
        }
    }
}
