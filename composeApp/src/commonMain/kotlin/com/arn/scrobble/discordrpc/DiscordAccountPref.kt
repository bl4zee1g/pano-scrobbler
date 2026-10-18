package com.arn.scrobble.discordrpc

import androidx.compose.runtime.Composable

/** The Discord login, which is only needed on Android. */
@Composable
expect fun DiscordAccountPref()

/** Always true when no login is needed. */
@Composable
expect fun rememberDiscordLoggedIn(): Boolean
