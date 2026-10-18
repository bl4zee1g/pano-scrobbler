package com.arn.scrobble.discordrpc

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal object DiscordSuperProperties {
    private const val CLIENT_VERSION = "314.13 - Stable"
    private const val CLIENT_BUILD_NUMBER = 314013
    private const val RELEASE_CHANNEL = "googleRelease"

    const val USER_AGENT = "Discord-Android/$CLIENT_BUILD_NUMBER;RNA"

    // regenerated per process, nothing is persisted
    private val vendorId = UUID.randomUUID().toString()
    private val clientUuid = UUID.randomUUID().toString()

    val base64: String by lazy {
        val props = JSONObject().apply {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", Build.DEVICE)
            put("system_locale", Locale.getDefault().toString())
            put("client_version", CLIENT_VERSION)
            put("release_channel", RELEASE_CHANNEL)
            put("device_vendor_id", vendorId)
            put("client_uuid", clientUuid)
            put("client_launch_id", UUID.randomUUID().toString())
            put("os_version", Build.VERSION.RELEASE)
            put("os_sdk_version", Build.VERSION.SDK_INT.toString())
            put("client_build_number", CLIENT_BUILD_NUMBER)
            put("client_event_source", JSONObject.NULL)
            put("design_id", 0)
        }
        Base64.encodeToString(props.toString().toByteArray(), Base64.NO_WRAP)
    }
}
