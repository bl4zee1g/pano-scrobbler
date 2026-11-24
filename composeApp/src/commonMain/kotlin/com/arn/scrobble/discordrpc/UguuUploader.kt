package com.arn.scrobble.discordrpc

import co.touchlab.kermit.Logger
import com.arn.scrobble.api.Requesters
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Uploads Discord album art to uguu.se. Files expire after 3 hours, which is enough
 * because Discord fetches the image once and caches it itself.
 */
object UguuUploader {
    private const val UPLOAD_TIMEOUT_MS = 20_000L

    /** Returns a direct https URL to the uploaded JPEG, or null if the upload failed. */
    suspend fun upload(jpeg: ByteArray): String? {
        val url = try {
            withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { uploadTo(jpeg) }
        } catch (e: Exception) {
            Logger.w { "art upload to uguu.se failed: ${e.message}" }
            null
        }
        if (url == null)
            Logger.w { "art upload to uguu.se returned no url" }
        return url?.takeIf { it.startsWith("https://") }
    }

    private suspend fun uploadTo(jpeg: ByteArray): String? {
        val response = Requesters.genericKtorClient.submitFormWithBinaryData(
            url = "https://uguu.se/upload.php",
            formData = formData {
                append("files[]", jpeg, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"art.jpg\"")
                })
            },
        )
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["files"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("url")?.jsonPrimitive?.content
    }
}
