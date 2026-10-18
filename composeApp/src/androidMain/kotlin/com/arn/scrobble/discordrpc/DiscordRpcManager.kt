package com.arn.scrobble.discordrpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.arn.scrobble.pref.MainPrefs
import com.arn.scrobble.utils.PlatformStuff
import java.util.concurrent.atomic.AtomicLong

/**
 * Sets the Discord presence for the logged in user through the gateway.
 * Lives in the scrobbler process, the login happens in the main process and is shared
 * via [MainPrefs.discordAuth].
 */
internal object DiscordRpcManager {
    private const val REFRESH_BEFORE_EXPIRY_SECS = 3600L
    private const val IMAGE_WAIT_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    private val gateway = DiscordGateway(
        appId = DiscordAuth.clientId,
        tokenProvider = { "Bearer " + currentAuth()?.accessToken.orEmpty() },
        externalScope = scope,
    )

    @Volatile
    private var started = false

    @Volatile
    private var ready = false

    @Volatile
    private var connecting = false

    @Volatile
    private var pending: DiscordActivity.Activity? = null

    @Volatile
    private var hasPresence = false

    private val activityCounter = AtomicLong(0L)

    @Volatile
    private var imageJob: Job? = null

    private suspend fun currentAuth() = PlatformStuff.mainPrefs.data.first().discordAuth

    @Synchronized
    private fun start() {
        if (started) return
        started = true

        scope.launch {
            gateway.events.collect { event ->
                when (event) {
                    is GatewayEvent.Ready, is GatewayEvent.Resumed -> {
                        ready = true
                        pending?.let { resend(it) }
                    }

                    is GatewayEvent.Disconnected -> {
                        ready = false
                        hasPresence = false
                    }

                    GatewayEvent.RefreshToken -> scope.launch { refreshAndReconnect() }
                }
            }
        }

        scope.launch {
            PlatformStuff.mainPrefs.data
                .map { it.discordAuth?.accessToken }
                .distinctUntilChanged()
                .collect { token ->
                    if (token == null)
                        disconnect()
                    else if (pending != null)
                        ensureConnected()
                }
        }
    }

    suspend fun update(activity: DiscordActivity): Boolean {
        start()
        return when (activity) {
            is DiscordActivity.Activity -> setActivity(activity)
            is DiscordActivity.Clear -> clear()
            is DiscordActivity.Stop -> {
                pending = null
                disconnect()
                true
            }
        }
    }

    private suspend fun setActivity(activity: DiscordActivity.Activity): Boolean {
        pending = activity

        // not logged in, nothing to do
        if (currentAuth() == null) return true

        if (!ready) {
            ensureConnected()
            return false
        }

        return try {
            send(activity)
            true
        } catch (e: Exception) {
            discordLog.w(e) { "setActivity failed" }
            false
        }
    }

    private fun resend(activity: DiscordActivity.Activity) {
        scope.launch {
            try {
                send(activity)
            } catch (e: Exception) {
                discordLog.w(e) { "resend failed" }
            }
        }
    }

    private suspend fun send(activity: DiscordActivity.Activity) {
        val id = activityCounter.incrementAndGet()
        imageJob?.cancel()

        val (startSecs, endSecs) = activity.timestampsSecs(System.currentTimeMillis())

        val payload = ActivityPayload(
            name = activity.name,
            type = DiscordPresence.TYPE_LISTENING,
            details = activity.details.takeIf { it.isNotBlank() },
            state = activity.state.takeIf { it.isNotBlank() },
            detailsUrl = activity.detailsUrl.ifEmpty { null },
            largeImage = null,
            largeText = activity.largeText.takeIf { it.isNotBlank() },
            startMs = startSecs * 1000,
            endMs = (endSecs * 1000).takeIf { it > 0 },
            buttons = listOf(
                activity.buttonText to activity.buttonUrl,
                activity.button2Text to activity.button2Url,
            ).filter { (text, url) -> text.isNotEmpty() && url.isNotEmpty() },
        )

        val token = "Bearer " + currentAuth()?.accessToken.orEmpty()
        val resolving = if (activity.artUrl.isNotEmpty())
            scope.async { DiscordExternalAssets.resolve(activity.artUrl, DiscordAuth.clientId, token) }
        else
            null

        discordLog.d { "send: artUrl='${activity.artUrl}'" }
        val quickImage = resolving?.let { withTimeoutOrNull(IMAGE_WAIT_MS) { it.await() } }

        // a newer activity may have arrived while waiting for the image
        if (id != activityCounter.get()) return

        gateway.send(DiscordPresence.buildPresenceUpdate(listOf(payload.copy(largeImage = quickImage))))
        hasPresence = true

        if (resolving != null && quickImage == null) {
            imageJob = scope.launch {
                val image = resolving.await() ?: return@launch
                if (id != activityCounter.get() || !ready) return@launch
                try {
                    gateway.send(
                        DiscordPresence.buildPresenceUpdate(listOf(payload.copy(largeImage = image)))
                    )
                } catch (e: IllegalStateException) {
                    discordLog.w(e) { "image update failed" }
                }
            }
        }
    }

    private fun clear(): Boolean {
        pending = null
        activityCounter.incrementAndGet()
        imageJob?.cancel()

        if (!ready || !hasPresence) return true

        return try {
            gateway.send(DiscordPresence.buildPresenceUpdate(emptyList()))
            hasPresence = false
            true
        } catch (e: Exception) {
            discordLog.w(e) { "clear failed" }
            false
        }
    }

    private fun ensureConnected() {
        if (gateway.isOpen || gateway.isReconnecting || connecting) return
        connecting = true
        scope.launch {
            try {
                connect()
            } catch (e: Exception) {
                discordLog.w(e) { "connect failed" }
            } finally {
                connecting = false
            }
        }
    }

    private suspend fun connect() {
        connectMutex.withLock {
            if (gateway.isOpen) return

            var auth = currentAuth() ?: return
            val now = System.currentTimeMillis() / 1000

            if (auth.refreshToken.isNotEmpty() &&
                auth.expiresAtSecs > 0 &&
                auth.expiresAtSecs - now < REFRESH_BEFORE_EXPIRY_SECS
            ) {
                try {
                    auth = DiscordAuth.refresh(auth.refreshToken)
                } catch (e: DiscordAuthException.InvalidGrant) {
                    DiscordAuth.logout()
                    return
                } catch (e: Exception) {
                    discordLog.w(e) { "proactive refresh failed, using the old token" }
                }
            }

            gateway.resetReconnectAttempts()
            gateway.connect()
            gateway.identify("Bearer " + auth.accessToken)
        }
    }

    private suspend fun refreshAndReconnect() {
        val refreshToken = currentAuth()?.refreshToken
        if (refreshToken.isNullOrEmpty()) {
            DiscordAuth.logout()
            return
        }

        try {
            DiscordAuth.refresh(refreshToken)
        } catch (e: DiscordAuthException.InvalidGrant) {
            DiscordAuth.logout()
            return
        } catch (e: Exception) {
            discordLog.w(e) { "token refresh failed" }
            return
        }

        ensureConnected()
    }

    private fun disconnect() {
        activityCounter.incrementAndGet()
        imageJob?.cancel()
        gateway.close(1000, "disconnect")
        ready = false
        hasPresence = false
    }
}
