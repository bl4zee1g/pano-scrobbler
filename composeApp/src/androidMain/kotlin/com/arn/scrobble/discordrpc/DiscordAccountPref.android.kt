package com.arn.scrobble.discordrpc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import co.touchlab.kermit.Logger
import com.arn.scrobble.pref.TextPref
import com.arn.scrobble.utils.AndroidStuff.toast
import com.arn.scrobble.utils.PlatformStuff
import com.arn.scrobble.utils.Stuff
import com.arn.scrobble.utils.Stuff.collectAsStateWithInitialValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.discord_login
import pano_scrobbler.composeapp.generated.resources.discord_login_failed
import pano_scrobbler.composeapp.generated.resources.discord_login_summary
import pano_scrobbler.composeapp.generated.resources.discord_logout
import pano_scrobbler.composeapp.generated.resources.discord_logged_in_as

@Composable
actual fun DiscordAccountPref() {
    val auth by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.discordAuth }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val loginFailed = stringResource(Res.string.discord_login_failed)

    val loggedIn = auth != null

    TextPref(
        text = stringResource(if (loggedIn) Res.string.discord_logout else Res.string.discord_login),
        summary = if (loggedIn)
            stringResource(Res.string.discord_logged_in_as, auth?.username ?: "?")
        else
            stringResource(Res.string.discord_login_summary),
        onClick = {
            // not the composition scope: the activity can be recreated while the browser is open
            Stuff.appScope.launch {
                if (loggedIn) {
                    DiscordAuth.logout()
                } else {
                    try {
                        DiscordAuth.login(appContext)
                        Logger.i { "Discord login succeeded" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(e) { "Discord login failed" }
                        withContext(Dispatchers.Main) {
                            appContext.toast(loginFailed + ": " + e.message, Toast.LENGTH_LONG)
                        }
                    }
                }
            }
        }
    )
}

@Composable
actual fun rememberDiscordLoggedIn(): Boolean {
    val auth by PlatformStuff.mainPrefs.data.collectAsStateWithInitialValue { it.discordAuth }
    return auth != null
}
