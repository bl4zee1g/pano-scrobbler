package com.arn.scrobble.discordrpc

internal sealed interface ReconnectAction {
    data class Resume(val sessionId: String, val seq: Int) : ReconnectAction
    data object ReIdentify : ReconnectAction
    data object RefreshAndReIdentify : ReconnectAction
    data object SurfaceFatal : ReconnectAction
}

internal object DiscordReconnectStrategy {
    fun decide(
        closeCode: Int,
        hadSession: Boolean,
        seq: Int,
        sessionId: String?,
    ): ReconnectAction = when (closeCode) {
        4000 -> if (hadSession && sessionId != null && seq > 0) {
            ReconnectAction.Resume(sessionId, seq)
        } else {
            ReconnectAction.ReIdentify
        }

        4004 -> ReconnectAction.RefreshAndReIdentify
        4014 -> ReconnectAction.SurfaceFatal
        else -> ReconnectAction.ReIdentify
    }
}
