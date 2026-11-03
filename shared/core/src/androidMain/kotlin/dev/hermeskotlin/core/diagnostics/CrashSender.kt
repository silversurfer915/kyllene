package dev.hermeskotlin.core.diagnostics

import dev.hermeskotlin.core.gateway.GatewayRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.dsl.module
import java.io.File

/**
 * Sends the crashes the platform side saved, on the first start after they happened.
 *
 * Lives here rather than in the Android module because the gateway client and its credentials do: the Android
 * side only knows how to write a file, this knows where it goes.
 */
class CrashSender(
    private val client: HttpClient,
    private val gateways: GatewayRepository,
    private val scope: CoroutineScope,
) {

    /** Sends everything in [dir], deleting what the host accepted. Returns how many landed. */
    suspend fun sendAll(dir: File): Int {
        if (!dir.isDirectory) return 0
        val gateway = gateways.current() ?: return 0
        var delivered = 0
        for (file in dir.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
            ?.sortedBy { it.name }.orEmpty()) {
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            if (CrashReports.send(client, gateway.gatewayUrl, body)) {
                runCatching { file.delete() }
                delivered++
            }
        }
        return delivered
    }

    /** Fire-and-forget for startup: a failure leaves the files for the next run. */
    fun start(dir: File) {
        scope.launch { runCatching { sendAll(dir) } }
    }
}

/** Koin definition: next to the class, so the platform module never has to name the HTTP client it needs. */
val crashModule = module {
    single { CrashSender(get(), get(), get()) }
}
