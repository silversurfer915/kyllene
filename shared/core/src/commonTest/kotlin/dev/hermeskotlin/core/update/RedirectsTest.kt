package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Following a release download to the host that actually holds the bytes. */
class RedirectsTest {

    @Test
    fun aDownloadHandedToAnotherHostIsFollowed() = runTest {
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += request.url.toString()
            if (request.url.host == "github.com") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/the.apk"))
            } else {
                respond("the bytes", HttpStatusCode.OK)
            }
        }
        val answer = createHttpClient(engine).getFollowing("https://github.com/o/r/releases/download/v1/the.apk")

        assertEquals("the bytes", answer.bodyAsText())
        assertEquals(
            listOf(
                "https://github.com/o/r/releases/download/v1/the.apk",
                "https://objects.githubusercontent.com/the.apk",
            ),
            asked,
        )
    }

    @Test
    fun aRedirectOutOfHttpsIsNotFollowed() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://elsewhere.test/the.apk"))
        }
        val answer = createHttpClient(engine).getFollowing("https://github.com/x")

        assertEquals(HttpStatusCode.Found, answer.status)
    }

    @Test
    fun aRedirectLoopStopsAndHandsBackTheLastAnswer() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://github.com/again"))
        }
        val answer = createHttpClient(engine).getFollowing("https://github.com/x")

        assertEquals(HttpStatusCode.Found, answer.status)
        assertEquals(6, calls) // the request itself, then five hops
    }
}
