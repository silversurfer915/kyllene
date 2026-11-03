package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed the host serves (`conduit_push`'s /update), and what the app makes of it. */
class HostUpdatesTest {

    private val installed = "b".repeat(64)
    private val served = "a".repeat(64)

    private fun feed(
        available: Boolean = true,
        code: Int = 100,
        sha: String = served,
        name: String = "0.1.0-dev",
    ) = """{"ok":true,"available":$available,"version_code":$code,"version_name":"$name","size":30211962,"sha256":"$sha",
        "path":"api/plugins/conduit_push/apk"}"""

    private suspend fun updater(body: String): HostUpdates {
        val gateways = GatewayRepository(InMemoryKeyValueStore())
        gateways.save(SavedGateway(url = "http://10.0.0.5:9119"))
        return HostUpdates(
            createHttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            gateways,
        )
    }

    @Test
    fun aDifferentBuildOnTheHostIsAnUpdate() = runTest {
        val update = updater(feed()).check(installedCode = 100, installedSha = installed)
        assertNotNull(update)
        assertTrue(update.fromServer)
        assertEquals("0.1.0-dev", update.version)
        assertEquals("http://10.0.0.5:9119/api/plugins/conduit_push/apk", update.downloadUrl)
        assertEquals(served, update.sha256)
    }

    @Test
    fun theBuildAlreadyInstalledIsNotOfferedAgain() = runTest {
        // A local build keeps version 0.1.0 across rebuilds, so the hash is what tells them apart.
        assertNull(updater(feed(sha = served)).check(installedCode = 100, installedSha = served))
    }

    @Test
    fun anOlderBuildIsNeverOffered() = runTest {
        assertNull(updater(feed(code = 99)).check(installedCode = 100, installedSha = installed))
    }

    @Test
    fun noBuildOnTheHostIsNoUpdate() = runTest {
        assertNull(updater("""{"ok":true,"available":false,"reason":"no build is being served"}""").check(100, installed))
    }

    @Test
    fun aFeedWithoutAHashIsIgnored() = runTest {
        assertNull(updater(feed(sha = "")).check(100, installed))
    }
}
