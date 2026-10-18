package com.arn.scrobble.discordrpc

import com.arn.scrobble.media.PlayingTrackNotifyEvent
import kotlinx.coroutines.flow.Flow

internal expect object DiscordRpcPlatform {
    val playingTrackStates: Flow<Map<String, PlayingTrackNotifyEvent.PlayingTrackState>>

    /** @return true if the activity was applied */
    suspend fun update(activity: DiscordActivity): Boolean
}
