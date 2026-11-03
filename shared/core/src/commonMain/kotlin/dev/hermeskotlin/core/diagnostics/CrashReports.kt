package dev.hermeskotlin.core.diagnostics

import dev.hermeskotlin.core.gateway.GatewayUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Hands a crash report to the host, over the app's own client and credentials — the same ones a chat uses, so
 * there's no second way in.
 *
 * The body is written by the platform side, which owns the files; this only knows how to deliver one.
 */
object CrashReports {

    /** The dashboard's route for a crash report (the `conduit_push` plugin mounts it). */
    private const val ENDPOINT = "api/plugins/conduit_push/crash"

    /** True when the host took it, so the caller can drop its copy. False leaves the file for the next start. */
    suspend fun send(client: HttpClient, gateway: GatewayUrl, body: String): Boolean = runCatching {
        client.post(gateway.resolve(ENDPOINT)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status.isSuccess()
    }.getOrDefault(false)
}
