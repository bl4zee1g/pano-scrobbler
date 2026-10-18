package com.arn.scrobble.discordrpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

internal sealed interface GatewayEvent {
    data class Ready(val sessionId: String) : GatewayEvent
    data class Resumed(val sessionId: String) : GatewayEvent
    data class Disconnected(val code: Int, val reason: String) : GatewayEvent
    data object RefreshToken : GatewayEvent
}

/**
 * Minimal Discord gateway client: HELLO, IDENTIFY/RESUME, heartbeats, reconnects and presence
 * updates (opcode 3).
 */
internal class DiscordGateway(
    private val appId: String,
    private val tokenProvider: suspend () -> String,
    private val externalScope: CoroutineScope,
) {

    private val _events = MutableSharedFlow<GatewayEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    @Volatile
    private var currentSeq = 0

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    var isOpen = false
        private set

    @Volatile
    var isReconnecting = false
        private set

    private val webSocketIdCounter = AtomicLong(0L)

    @Volatile
    private var activeWebSocketId = 0L

    @Volatile
    private var gatewayUrl = DEFAULT_GATEWAY_URL

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var heartbeatJob: Job? = null

    private val lastAckAtMs = AtomicLong(0L)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun connect() {
        val myId = webSocketIdCounter.incrementAndGet()
        activeWebSocketId = myId
        val openDeferred = CompletableDeferred<Unit>()
        val ws = httpClient.newWebSocket(
            Request.Builder().url(gatewayUrl).build(),
            createListener(openDeferred, myId)
        )
        webSocket = ws
        try {
            openDeferred.await()
        } catch (e: Throwable) {
            ws.cancel()
            webSocket = null
            throw e
        }
    }

    fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }

    fun close(code: Int = 1000, reason: String? = null) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val ws = webSocket
        webSocket = null
        isOpen = false
        isReconnecting = false
        activeWebSocketId = webSocketIdCounter.incrementAndGet()
        sessionId = null
        currentSeq = 0
        gatewayUrl = DEFAULT_GATEWAY_URL
        ws?.close(code, reason)
    }

    fun send(frameJson: String) {
        val ws = webSocket
        check(ws != null && isOpen) { "DiscordGateway: WebSocket is not open" }
        check(ws.send(frameJson)) { "DiscordGateway: WebSocket send returned false" }
    }

    fun identify(token: String) {
        val props = JSONObject()
            .put("os", "android")
            .put("browser", "Discord Android")
            .put("device", appId)
        val d = JSONObject()
            .put("token", token)
            .put("intents", 0)
            .put("properties", props)
            .put("compress", false)
        send(wrapOp(IDENTIFY, d))
    }

    private fun resume(sessionId: String, seq: Int, token: String) {
        val d = JSONObject()
            .put("token", token)
            .put("session_id", sessionId)
            .put("seq", seq)
        send(wrapOp(RESUME, d))
    }

    private fun heartbeat(seq: Int) {
        val root = JSONObject().put("op", HEARTBEAT)
        root.put("d", if (seq > 0) seq else JSONObject.NULL)
        send(root.toString())
    }

    private fun createListener(
        openDeferred: CompletableDeferred<Unit>,
        wsId: Long,
    ) = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (wsId != activeWebSocketId) return
            this@DiscordGateway.webSocket = webSocket
            isOpen = true
            lastAckAtMs.set(System.currentTimeMillis())
            openDeferred.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (wsId != activeWebSocketId) return
            externalScope.launch {
                try {
                    handleFrame(text)
                } catch (e: Throwable) {
                    discordLog.e(e) { "failed to handle frame" }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            externalScope.launch { handleClose(code, reason, wsId) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (wsId != activeWebSocketId) return
            discordLog.w(t) { "websocket failure, response=${response?.code}" }
            if (!openDeferred.isCompleted) {
                openDeferred.completeExceptionally(t)
            }
            externalScope.launch {
                val code = response?.code ?: 4000
                val retryAfter = response?.header("Retry-After")
                val reason = if (retryAfter != null)
                    "${t.message ?: "failure"};retry_after=$retryAfter"
                else
                    t.message ?: "failure"
                handleClose(code, reason, wsId)
            }
        }
    }

    private suspend fun handleFrame(text: String) {
        val json = JSONObject(text)
        val op = json.optInt("op", -1)
        val d = json.optJSONObject("d")
        val t = if (json.has("t") && !json.isNull("t")) json.optString("t") else null

        if (json.has("s") && !json.isNull("s")) {
            val seq = json.optInt("s", 0)
            if (seq > 0) currentSeq = seq
        }

        when (op) {
            HELLO -> startHeartbeat(d?.optLong("heartbeat_interval", DEFAULT_HEARTBEAT_MS) ?: DEFAULT_HEARTBEAT_MS)
            HEARTBEAT_ACK -> lastAckAtMs.set(System.currentTimeMillis())
            DISPATCH -> when (t) {
                "READY" -> {
                    val data = d ?: JSONObject()
                    sessionId = data.optString("session_id", "")
                    data.optString("resume_gateway_url", "").takeIf { it.isNotEmpty() }?.let {
                        gatewayUrl = "$it/?v=10&encoding=json"
                    }
                    reconnectAttempts = 0
                    isReconnecting = false
                    _events.emit(GatewayEvent.Ready(sessionId.orEmpty()))
                }

                "RESUMED" -> {
                    reconnectAttempts = 0
                    isReconnecting = false
                    _events.emit(GatewayEvent.Resumed(sessionId.orEmpty()))
                }
            }

            INVALID_SESSION -> {
                val resumable = (json.opt("d") as? Boolean) ?: false
                if (!resumable) sessionId = null
                webSocket?.close(4000, "invalid session")
            }

            HEARTBEAT -> heartbeat(currentSeq)
            RECONNECT -> webSocket?.close(4000, "reconnect requested")
        }
    }

    private suspend fun handleClose(code: Int, reason: String, closedWebSocketId: Long) {
        if (closedWebSocketId != activeWebSocketId) return
        if (!isOpen && webSocket == null) return

        isOpen = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket = null

        _events.emit(GatewayEvent.Disconnected(code, reason))

        if (code == 1000) {
            sessionId = null
            currentSeq = 0
            return
        }

        val action = DiscordReconnectStrategy.decide(code, sessionId != null, currentSeq, sessionId)
        discordLog.i { "closed code=$code, strategy=${action::class.simpleName}" }

        when (action) {
            is ReconnectAction.SurfaceFatal -> {
                sessionId = null
                currentSeq = 0
            }

            is ReconnectAction.RefreshAndReIdentify -> _events.emit(GatewayEvent.RefreshToken)

            is ReconnectAction.Resume,
            is ReconnectAction.ReIdentify -> {
                if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    isReconnecting = false
                    return
                }
                reconnectAttempts++
                isReconnecting = true
                val wait = if (code == 429)
                    parseRetryAfter(reason).coerceAtLeast(60_000L)
                else
                    reconnectDelayMs(reconnectAttempts)
                delay(wait)
                // a newer connection may have been started while waiting
                if (closedWebSocketId != activeWebSocketId) return
                performReconnect(action)
            }
        }
    }

    private suspend fun performReconnect(action: ReconnectAction) {
        try {
            connect()
        } catch (e: Throwable) {
            discordLog.w(e) { "reconnect failed" }
            isReconnecting = false
            return
        }

        try {
            val token = tokenProvider()
            when (action) {
                is ReconnectAction.Resume -> resume(action.sessionId, action.seq, token)
                is ReconnectAction.ReIdentify -> identify(token)
                else -> Unit
            }
        } catch (e: Throwable) {
            discordLog.w(e) { "resume/identify failed" }
            webSocket?.close(1011, "resume/identify failed")
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        val jittered = applyJitter(intervalMs, JITTER_RATIO)
        lastAckAtMs.set(System.currentTimeMillis())
        heartbeatJob = externalScope.launch {
            var lastSentAt = System.currentTimeMillis()
            while (isActive && isOpen) {
                delay(jittered)
                if (!isActive || !isOpen) break
                if (lastAckAtMs.get() < lastSentAt) {
                    webSocket?.close(4000, "heartbeat timeout")
                    break
                }
                lastSentAt = System.currentTimeMillis()
                try {
                    heartbeat(currentSeq)
                } catch (e: IllegalStateException) {
                    discordLog.w(e) { "heartbeat failed" }
                }
            }
        }
    }

    private fun wrapOp(op: Int, d: JSONObject) =
        JSONObject().put("op", op).put("d", d).toString()

    private fun reconnectDelayMs(attempt: Int): Long {
        val base = (RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1)))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        return applyJitter(base, 0.25)
    }

    private fun parseRetryAfter(reason: String): Long {
        val prefix = ";retry_after="
        val idx = reason.indexOf(prefix)
        if (idx < 0) return 60_000L
        val seconds = reason.substring(idx + prefix.length).trim()
            .substringBefore(';').substringBefore(',').toDoubleOrNull()
        return if (seconds != null) (seconds * 1000.0).toLong() else 60_000L
    }

    private fun applyJitter(intervalMs: Long, ratio: Double): Long {
        val delta = (intervalMs * ratio).toLong()
        if (delta <= 0L) return intervalMs
        val offset = abs(Random.nextLong(delta + 1))
        return intervalMs + if (Random.nextBoolean()) -offset else offset
    }

    companion object {
        private const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val DEFAULT_HEARTBEAT_MS = 41250L
        private const val JITTER_RATIO = 0.05
        private const val MAX_RECONNECT_ATTEMPTS = 7
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 64_000L

        private const val DISPATCH = 0
        private const val HEARTBEAT = 1
        private const val IDENTIFY = 2
        const val PRESENCE_UPDATE = 3
        private const val RESUME = 6
        private const val RECONNECT = 7
        private const val INVALID_SESSION = 9
        private const val HELLO = 10
        private const val HEARTBEAT_ACK = 11
    }
}
