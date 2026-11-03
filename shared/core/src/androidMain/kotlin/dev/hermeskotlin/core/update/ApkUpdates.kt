package dev.hermeskotlin.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import java.io.File
import java.security.MessageDigest

/**
 * Downloads the build the user's host serves and hands it to Android's own installer.
 *
 * Fetched with the signed-in client, so the APK route answers instead of a login page, and checked against the hash
 * the feed published: a truncated or swapped file is dropped rather than installed. Android asks once for "install
 * unknown apps" on the first try — after that an update is two taps.
 *
 * Here rather than in the UI module because that one has no HTTP client of its own.
 */
class ApkUpdates(private val context: Context, private val client: HttpClient) {

    /** Fetches, verifies and installs [update]. False when anything along the way failed or nothing took it. */
    suspend fun install(update: AppUpdate): Boolean {
        val url = update.downloadUrl ?: return false
        val apk = withContext(Dispatchers.IO) {
            // shortcut: the APK is held in memory while hashing (~30 MB here); stream it if it ever gets big.
            runCatching {
                // The host's feed carries the hash itself; a GitHub release publishes it in a file beside the APK.
                val expected = (update.sha256 ?: publishedSha(update.sha256Url))?.lowercase()
                    ?: return@runCatching null
                val bytes = client.get(url).body<ByteArray>()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                val actual = digest.joinToString("") { "%02x".format(it) }
                if (actual != expected) return@runCatching null
                File(context.cacheDir, "updates").apply { mkdirs() }.resolve("kyllene-$actual.apk")
                    .apply { writeBytes(bytes) }
            }.getOrNull()
        } ?: return false
        return openInstaller(apk)
    }

    /** The hash a release published beside its APK: `sha256sum` writes `<hash>  <file>`. */
    private suspend fun publishedSha(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            client.get(url).bodyAsText().trim().substringBefore(' ').takeIf { it.length == SHA_CHARS }?.lowercase()
        }.getOrNull()
    }

    /** The system installer's "replace this app?" screen. False when no installer took the intent. */
    private fun openInstaller(apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val SHA_CHARS = 64
    }
}

/** Koin definition: the app context and the signed-in client are both already in the graph. */
val apkUpdatesModule = module {
    single { ApkUpdates(get(), get()) }
}
