package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import androidx.compose.material3.Icon
import com.composables.icons.lucide.Phone
import dev.hermeskotlin.core.voice.CallTurn
import dev.hermeskotlin.core.voice.ChatCall
import dev.hermeskotlin.core.voice.VoiceCallHistory
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import org.koin.compose.koinInject

/**
 * The calls saved into this chat, one card each, newest last — the way another voice app shows them inside the
 * conversation rather than as a session of their own. Tapping one opens everything that was said on it.
 *
 * [reload] is anything that changes when a call ends; it re-reads the list, so a call just finished appears without
 * leaving the chat.
 */
@Composable
internal fun VoiceCallCards(chatId: String?, reload: Any? = Unit, modifier: Modifier = Modifier) {
    if (chatId == null) return
    val history = koinInject<VoiceCallHistory>()
    var calls by remember(chatId) { mutableStateOf<List<ChatCall>>(emptyList()) }
    // Turns are fetched for every call so each card can say how long it ran; a chat with many calls keeps the last few.
    var turns by remember(chatId) { mutableStateOf<Map<String, List<CallTurn>>>(emptyMap()) }
    var open by remember(chatId) { mutableStateOf<String?>(null) }
    LaunchedEffect(chatId, reload) {
        val found = history.calls(chatId)
        calls = found
        turns = found.takeLast(SHOWN_CALLS).associate { call -> call.callId to history.turns(chatId, call.callId) }
    }
    if (calls.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        calls.takeLast(SHOWN_CALLS).forEach { call ->
            val said = turns[call.callId].orEmpty()
            CallCard(
                length = duration(said),
                turns = said.size,
                onClick = { open = call.callId },
            )
        }
    }
    open?.let { callId ->
        val said = turns[callId].orEmpty()
        TranscriptSheet(
            lines = said.map { TranscriptLine(fromVoice = it.role != "user", text = it.text) },
            onClose = { open = null },
        )
    }
}

/** One call, as it reads inside the chat. */
@Composable
private fun CallCard(length: String?, turns: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface2])
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Voice call${length?.let { ", $it" } ?: ""}. Open the transcript." }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Lucide.Phone,
            contentDescription = null,
            tint = Theme[colors][accent],
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Voice call",
                style = Theme[typography][body].copy(fontSize = 15.sp, lineHeight = 20.sp),
                fontWeight = FontWeight.SemiBold,
                color = Theme[colors][textColor],
            )
            val detail = buildString {
                if (length != null) append(length)
                if (turns > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(if (turns == 1) "1 line" else "$turns lines")
                }
            }
            Text(
                if (detail.isEmpty()) "Open transcript" else "$detail · Open transcript",
                style = Theme[typography][caption].copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = Theme[colors][textTertiary],
            )
        }
    }
}

/** How long a call ran, from its first turn to its last, when the host dated them: "1m 36s". */
private fun duration(turns: List<CallTurn>): String? {
    val times = turns.mapNotNull { it.at }.sorted()
    if (times.size < 2) return null
    val seconds = (times.last() - times.first()).toLong().coerceAtLeast(0)
    if (seconds <= 0) return null
    val minutes = seconds / 60
    return when {
        minutes <= 0 -> "${seconds}s"
        seconds % 60 == 0L -> "${minutes}m"
        else -> "${minutes}m ${seconds % 60}s"
    }
}

/** A chat keeps its latest calls read; older ones are one tap away on the host, not in this row. */
private const val SHOWN_CALLS = 12
