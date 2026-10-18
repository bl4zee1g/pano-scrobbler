package com.arn.scrobble.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import com.arn.scrobble.discordrpc.DiscordRpcScreen
import com.arn.scrobble.onboarding.FixItDialog
import com.arn.scrobble.ui.navModal
import com.arn.scrobble.ui.navScrollableColumn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pano_scrobbler.composeapp.generated.resources.Res
import pano_scrobbler.composeapp.generated.resources.discord_rich_presence

actual fun EntryProviderScope<PanoRoute>.panoPlatformSpecificNavGraph(
    onSetTitle: (PanoRoute, String) -> Unit,
    navigate: (PanoRoute) -> Unit,
    goBack: () -> Unit,
) {
    @Composable
    fun onSetTitleRes(route: PanoRoute, resId: StringResource) {
        val title = stringResource(resId)

        LaunchedEffect(resId) {
            onSetTitle(route, title)
        }
    }

    modalEntry<PanoRoute.Modal.FixIt> { route ->

        FixItDialog(
            killedReason = route.killedReason,
            onNavigate = navigate,
            modifier = Modifier.navModal(),
        )
    }

    entry<PanoRoute.DiscordRpcSettings> { route ->
        onSetTitleRes(route, Res.string.discord_rich_presence)
        DiscordRpcScreen(
            modifier = Modifier.navScrollableColumn()
        )
    }
}
