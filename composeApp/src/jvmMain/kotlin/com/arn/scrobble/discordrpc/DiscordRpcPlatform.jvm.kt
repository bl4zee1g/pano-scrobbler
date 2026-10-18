package com.arn.scrobble.discordrpc

import com.arn.scrobble.PanoNativeComponents
import com.arn.scrobble.media.PlayingTrackNotifyEvent
import com.arn.scrobble.utils.PanoNotifications
import kotlinx.coroutines.flow.Flow

internal actual object DiscordRpcPlatform {
    actual val playingTrackStates: Flow<Map<String, PlayingTrackNotifyEvent.PlayingTrackState>>
        get() = PanoNotifications.playingTrackTrayInfo

    actual suspend fun update(activity: DiscordActivity): Boolean =
        when (activity) {
            is DiscordActivity.Activity -> {
                val (startTimeSecs, endTimeSecs) = activity.timestampsSecs(System.currentTimeMillis())

                PanoNativeComponents.updateDiscordActivity(
                    clientId = activity.discordClientId,
                    name = activity.name,
                    state = activity.state,
                    details = activity.details,
                    largeText = activity.largeText,
                    startTime = startTimeSecs,
                    endTime = endTimeSecs,
                    artUrl = activity.artUrl,
                    detailsUrl = activity.detailsUrl,
                    isPlaying = activity.isPlaying,
                    statusLine = activity.statusLine,
                    buttonText = activity.buttonText,
                    buttonUrl = activity.buttonUrl,
                )
            }

            is DiscordActivity.Clear -> PanoNativeComponents.clearDiscordActivity(false)

            is DiscordActivity.Stop -> PanoNativeComponents.clearDiscordActivity(true)
        }
}
