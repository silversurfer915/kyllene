package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.HermesJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The sha256 of the APK this app was installed from; null where there is no such file (a desktop). */
internal expect suspend fun installedApkSha256(): String?

/**
 * The build the user's own host serves: `conduit_push`'s `/update` feed (version, hash) and its `/apk` file.
 *
 * A local build carries no `releasesRepo`, so GitHub is never asked; this is how a rebuild on the host reaches the
 * phone as a button instead of a browser download. Nothing is offered when the host serves the very APK that is
 * installed — the hash decides that, so a rebuild of the same version still counts as an update.
 */
class HostUpdates(private val client: HttpClient, private val gateways: GatewayRepository) {

    /** The host's build when it differs from the installed one, else null. Never raises: a launch calls this. */
    suspend fun check(installedCode: Int): AppUpdate? = check(installedCode, installedApkSha256())

    /** [installedSha] is the installed APK's hash; null (a desktop, or no context) decides on the version alone. */
    suspend fun check(installedCode: Int, installedSha: String?): AppUpdate? {
        val gateway = gateways.current() ?: return null
        val base = gateway.gatewayUrl
        val feed = try {
            val response = client.get(base.resolve(FEED))
            if (!response.status.isSuccess()) return null
            HermesJson.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } ?: return null
        if (feed.text("available") != "true") return null
        // An older build is not an update: installing it would be a downgrade the user didn't ask for.
        if ((feed.text("version_code")?.toIntOrNull() ?: 0) < installedCode) return null
        val sha = feed.text("sha256")?.lowercase()?.takeIf { it.length == SHA_CHARS } ?: return null
        if (installedSha?.lowercase() == sha) return null
        val apk = base.resolve(APK)
        return AppUpdate(
            version = feed.text("version_name").orEmpty().ifBlank { "new build" },
            pageUrl = apk,
            downloadUrl = apk,
            sha256 = sha,
            fromServer = true,
        )
    }

    private companion object {
        const val FEED = "api/plugins/conduit_push/update"
        const val APK = "api/plugins/conduit_push/apk"
        const val SHA_CHARS = 64
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
