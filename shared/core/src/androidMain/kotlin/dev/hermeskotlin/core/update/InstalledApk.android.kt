package dev.hermeskotlin.core.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest

/**
 * The hash of the APK this app runs from, so the host's feed can tell "the build I serve is already on the phone"
 * from "a newer one is". Read off the platform's own copy, which is the only file the app can hash.
 *
 * Null when there is nothing to read (a test or host-side run with no app context): the caller then offers the
 * update on the version code alone.
 */
internal actual suspend fun installedApkSha256(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val context = GlobalContext.get().get<Context>()
        val source = context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
        val digest = MessageDigest.getInstance("SHA-256")
        File(source).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
