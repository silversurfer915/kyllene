package dev.hermeskotlin.core.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
private const val MAX_HOPS = 5

/**
 * The response at [url], following its redirects.
 *
 * The shared client refuses to follow them — the dashboard answers some calls with a redirect to `/login`, and
 * following that would turn a 401 into a login page — but GitHub hands every release download to another host
 * (`objects.githubusercontent.com`) with a 302 before it sends the bytes. Only `https://` hops are taken.
 */
internal suspend fun HttpClient.getFollowing(url: String): HttpResponse {
    var response = get(url)
    repeat(MAX_HOPS) {
        if (response.status.value !in REDIRECT_STATUSES) return response
        val next = response.headers[HttpHeaders.Location]?.takeIf { it.startsWith("https://") } ?: return response
        response = get(next)
    }
    return response
}
