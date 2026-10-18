package com.arn.scrobble.discordrpc

import org.json.JSONArray
import org.json.JSONObject

internal data class ActivityPayload(
    val name: String,
    val type: Int,
    val details: String?,
    val state: String?,
    val detailsUrl: String?,
    val largeImage: String?,
    val largeText: String?,
    val startMs: Long?,
    val endMs: Long?,
    // (label, url), at most 2
    val buttons: List<Pair<String, String>>,
)

internal object DiscordPresence {
    const val TYPE_LISTENING = 2

    fun buildPresenceUpdate(activities: List<ActivityPayload>): String {
        val d = JSONObject()
            .put("since", 0)
            .put("activities", JSONArray().apply { activities.forEach { put(toJson(it)) } })
            .put("status", "online")
            .put("afk", false)

        return JSONObject().put("op", DiscordGateway.PRESENCE_UPDATE).put("d", d).toString()
    }

    private fun toJson(a: ActivityPayload): JSONObject {
        val obj = JSONObject()
            .put("name", a.name)
            .put("type", a.type)
        a.details?.let { obj.put("details", it) }
        a.state?.let { obj.put("state", it) }
        a.detailsUrl?.let { obj.put("details_url", it) }

        if (a.startMs != null || a.endMs != null) {
            val timestamps = JSONObject()
            a.startMs?.let { timestamps.put("start", it) }
            a.endMs?.let { timestamps.put("end", it) }
            obj.put("timestamps", timestamps)
        }

        if (a.largeImage != null) {
            val assets = JSONObject().put("large_image", a.largeImage)
            a.largeText?.let { assets.put("large_text", it) }
            obj.put("assets", assets)
        }

        // In gateway op 3, buttons is string[] (labels only);
        // the urls go in metadata.button_urls
        if (a.buttons.isNotEmpty()) {
            obj.put("buttons", JSONArray(a.buttons.map { it.first }))
            obj.put(
                "metadata",
                JSONObject().put("button_urls", JSONArray(a.buttons.map { it.second }))
            )
        }

        return obj
    }
}
