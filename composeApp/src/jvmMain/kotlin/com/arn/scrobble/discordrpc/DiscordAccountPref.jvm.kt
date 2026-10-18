package com.arn.scrobble.discordrpc

import androidx.compose.runtime.Composable

@Composable
actual fun DiscordAccountPref() {
    // the desktop app talks to the local Discord client, no login needed
}

@Composable
actual fun rememberDiscordLoggedIn(): Boolean = true
