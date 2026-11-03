package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.sessions.SessionsApi

/** A chat the voice found: enough to name it out loud, and to read it if the user means it. */
data class ChatHit(
    val id: String,
    val title: String,
    /** The matching words from inside the chat, when the search found them there. */
    val snippet: String? = null,
)

/**
 * The user's other chats, as the voice needs them: what did we talk about, and what was said there.
 *
 * Read-only on purpose. Opening a chat moves the screen, which is the user's to do; the voice names the chat,
 * and they tap it.
 */
interface ChatLookup {
    suspend fun find(query: String, limit: Int = 3): List<ChatHit>
    suspend fun lastReply(sessionId: String): String?
}

/** [ChatLookup] over the gateway's own session search and transcript routes. */
class SessionsChatLookup(
    private val sessions: SessionsApi,
    private val url: () -> GatewayUrl?,
    private val profile: String?,
) : ChatLookup {

    override suspend fun find(query: String, limit: Int): List<ChatHit> {
        val gateway = url() ?: return emptyList()
        val result = sessions.search(gateway, query, limit, profile) as? ApiResult.Success ?: return emptyList()
        return result.value.map { summary ->
            ChatHit(
                id = summary.id,
                title = summary.title?.takeIf { it.isNotBlank() }
                    ?: summary.preview?.take(60)?.takeIf { it.isNotBlank() }
                    ?: "an untitled chat",
                snippet = summary.snippet?.takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun lastReply(sessionId: String): String? {
        val gateway = url() ?: return null
        val page = sessions.messages(gateway, sessionId, profile = profile) as? ApiResult.Success ?: return null
        return page.value.messages
            .lastOrNull { it.role == "assistant" && !it.isHidden }
            ?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
