package com.arn.scrobble.discordrpc

import android.app.Activity
import android.os.Bundle

/** Receives the OAuth redirect from the browser and hands it to [DiscordAuth]. */
class DiscordOAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { DiscordAuth.onRedirect(it) }
        finish()
    }
}
