package dev.hermeskotlin.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import com.composeunstyled.theme.Theme
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import dev.hermeskotlin.core.voice.CallTurn
import dev.hermeskotlin.core.voice.VoiceCallHistory
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import org.koin.compose.koinInject

/** A call saved into the chat, with everything said on it, ready to be drawn where it happened. */
internal data class ShownCall(val callId: String, val at: Double?, val turns: List<CallTurn>)

/**
 * The chat's saved calls, each with its turns and the time it began — so the chat can put a card where the call was
 * placed instead of at the end. [at] is the first turn's time; a call the host never dated is left out, since a card
 * with no place would drift to whichever end.
 *
 * [reload] is anything that changes when a call ends; it re-reads, so a call just finished appears at once.
 */
@Composable
internal fun rememberShownCalls(chatId: String?, reload: Any? = Unit): List<ShownCall> {
    if (chatId == null) return emptyList()
    val history = koinInject<VoiceCallHistory>()
    var calls by remember(chatId) { mutableStateOf<List<ShownCall>>(emptyList()) }
    LaunchedEffect(chatId, reload) {
        // One card per call, however the host happens to list them.
        calls = history.calls(chatId).distinctBy { it.callId }.takeLast(SHOWN_CALLS).mapNotNull { call ->
            val turns = history.turns(chatId, call.callId)
            // An undated call still gets its card; it simply lands at the end of the chat.
            ShownCall(call.callId, turns.mapNotNull { it.at }.minOrNull(), turns)
        }
    }
    return calls
}

/** One call, as it reads inside the chat. */
@Composable
internal fun CallCard(call: ShownCall, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val length = duration(call.turns)
    val lines = call.turns.size
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
                if (lines > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(if (lines == 1) "1 line" else "$lines lines")
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

/** A call's words, over the chat until it's closed. */
@Composable
internal fun CallTranscript(call: ShownCall, onClose: () -> Unit) {
    TranscriptSheet(
        lines = call.turns.map { TranscriptLine(fromVoice = it.role != "user", text = it.text) },
        onClose = onClose,
    )
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

/** A chat keeps its latest calls read; older ones stay on the host. */
private const val SHOWN_CALLS = 12
