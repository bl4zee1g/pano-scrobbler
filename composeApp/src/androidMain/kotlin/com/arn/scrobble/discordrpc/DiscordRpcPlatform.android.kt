package com.arn.scrobble.discordrpc

import com.arn.scrobble.media.PlayingTrackNotifyEvent
import com.arn.scrobble.utils.PanoNotifications
import kotlinx.coroutines.flow.Flow

internal actual object DiscordRpcPlatform {
    actual val playingTrackStates: Flow<Map<String, PlayingTrackNotifyEvent.PlayingTrackState>>
        get() = PanoNotifications.playingTrackStates

    actual suspend fun update(activity: DiscordActivity): Boolean =
        DiscordRpcManager.update(activity)
}
