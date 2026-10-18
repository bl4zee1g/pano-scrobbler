package com.arn.scrobble.discordrpc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.arn.scrobble.BuildKonfig
import com.arn.scrobble.pref.MainPrefs
import com.arn.scrobble.utils.PlatformStuff
import com.arn.scrobble.utils.Stuff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

sealed class DiscordAuthException(message: String) : Exception(message) {
    class UserCancelled(message: String = "User cancelled authorization") :
        DiscordAuthException(message)

    class InvalidGrant(message: String = "Invalid or expired grant") : DiscordAuthException(message)
    class StateMismatch : DiscordAuthException("OAuth state mismatch")
    class NoBrowser : DiscordAuthException("No browser available")
    class Network(message: String) : DiscordAuthException(message)
}

/**
 * OAuth2 authorization code flow with PKCE against the Discord application, using the
 * `sdk.social_layer_presence` scope. The redirect uri must be registered for the application
 * in the Discord developer portal and the application must be marked as a public client.
 */
object DiscordAuth {
    const val REDIRECT_URI = "panoscrobblerdiscord://oauth2/callback"
    private const val AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
    private const val TOKEN_URL = "https://discord.com/api/v10/oauth2/token"
    private const val ME_URL = "https://discord.com/api/v10/users/@me"
    private const val USERINFO_URL = "https://discord.com/api/v10/oauth2/userinfo"
    private const val SCOPES = "openid sdk.social_layer_presence"
    private const val AUTH_TIMEOUT_MS = 120_000L

    val clientId: String
        get() = BuildKonfig.DISCORD_ANDROID_CLIENT_ID.ifEmpty { Stuff.DISCORD_CLIENT_ID }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var pendingCode: CompletableDeferred<Pair<String, String>>? = null

    /** called from [DiscordOAuthActivity] */
    internal fun onRedirect(uri: Uri) {
        val deferred = pendingCode
        discordLog.i { "OAuth redirect received, pending=${deferred != null}, error=${uri.getQueryParameter("error")}" }
        if (deferred == null) return
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state").orEmpty()

        when {
            error != null -> deferred.completeExceptionally(
                DiscordAuthException.UserCancelled("Authorization denied: $error")
            )

            code == null -> deferred.completeExceptionally(
                DiscordAuthException.InvalidGrant("Missing authorization code")
            )

            else -> deferred.complete(code to state)
        }
    }

    fun cancelPending() {
        pendingCode?.completeExceptionally(DiscordAuthException.UserCancelled())
    }

    /** Opens the browser, waits for the redirect, exchanges the code, and persists the result. */
    suspend fun login(context: Context) {
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        )
        val state = randomUrlSafe(16)

        val deferred = CompletableDeferred<Pair<String, String>>()
        pendingCode = deferred

        try {
            val url = buildString {
                append(AUTHORIZE_URL)
                append("?client_id=").append(clientId)
                append("&response_type=code")
                append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
                append("&scope=").append(URLEncoder.encode(SCOPES, "UTF-8"))
                append("&state=").append(state)
                append("&code_challenge_method=S256")
                append("&code_challenge=").append(challenge)
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)

            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                throw DiscordAuthException.NoBrowser()
            }

            val (code, returnedState) = try {
                withTimeout(AUTH_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw DiscordAuthException.UserCancelled()
            }

            if (returnedState != state) throw DiscordAuthException.StateMismatch()
            discordLog.i { "got authorization code, exchanging" }

            val tokens = tokenRequest(
                "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            )
            val username = fetchUsername(tokens.accessToken)
            save(tokens.copy(username = username))
            discordLog.i { "login saved, username=$username" }
        } finally {
            pendingCode = null
        }
    }

    /** Refreshes and persists the tokens. Returns the new tokens. */
    suspend fun refresh(refreshToken: String): MainPrefs.DiscordAuthPrefs {
        val old = PlatformStuff.mainPrefs.data.map { it.discordAuth }.first()
        val tokens = tokenRequest("refresh_token", "refresh_token" to refreshToken)
            .let { it.copy(username = old?.username) }
        save(tokens)
        return tokens
    }

    suspend fun logout() {
        PlatformStuff.mainPrefs.updateData { it.copy(discordAuth = null) }
    }

    private suspend fun save(tokens: MainPrefs.DiscordAuthPrefs) {
        PlatformStuff.mainPrefs.updateData { it.copy(discordAuth = tokens) }
    }

    private suspend fun tokenRequest(
        grantType: String,
        vararg extra: Pair<String, String>,
    ): MainPrefs.DiscordAuthPrefs = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", grantType)
            .apply { extra.forEach { (k, v) -> add(k, v) } }
            .build()

        val request = Request.Builder().url(TOKEN_URL).post(form).build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw DiscordAuthException.Network(e.message ?: "network error")
        }

        response.use {
            val body = it.body.string()
            if (it.isSuccessful) {
                val json = JSONObject(body)
                MainPrefs.DiscordAuthPrefs(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    expiresAtSecs = System.currentTimeMillis() / 1000 + json.optLong("expires_in"),
                )
            } else {
                val error = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                if (it.code == 400 && error == "invalid_grant")
                    throw DiscordAuthException.InvalidGrant()
                throw DiscordAuthException.Network("HTTP ${it.code}: ${body.take(200)}")
            }
        }
    }

    private suspend fun fetchUsername(accessToken: String): String? = withContext(Dispatchers.IO) {
        // /users/@me needs the identify scope, /oauth2/userinfo works with openid
        for (url in listOf(ME_URL, USERINFO_URL)) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                val name = client.newCall(request).execute().use {
                    if (!it.isSuccessful) {
                        discordLog.w { "$url: HTTP ${it.code}" }
                        return@use null
                    }
                    val json = JSONObject(it.body.string())
                    listOf("global_name", "username", "preferred_username", "nickname", "name")
                        .firstNotNullOfOrNull { key ->
                            json.optString(key).takeIf { n -> n.isNotEmpty() && n != "null" }
                        }
                }
                if (name != null) return@withContext name
            } catch (e: Exception) {
                discordLog.w(e) { "fetchUsername failed for $url" }
            }
        }
        null
    }

    private fun randomUrlSafe(byteCount: Int): String =
        base64Url(ByteArray(byteCount).also { SecureRandom().nextBytes(it) })

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
