package dev.hermeskotlin.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.update.AppUpdate
import dev.hermeskotlin.core.update.HostUpdates
import dev.hermeskotlin.core.update.UpdateChecker
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** A newer release, if one was found, and a way to stop offering it. */
/**
 * What the app is being offered. [recheck] asks again now, for the button in Settings: the app used to look only as it
 * opened, so a build published while it was running needed a restart to be seen.
 */
class UpdateOffer(
    update: AppUpdate?,
    onDismiss: () -> Unit = {},
    recheck: suspend () -> Unit = {},
) {
    var onDismiss: () -> Unit = onDismiss
    var recheck: suspend () -> Unit = recheck
    private val state = mutableStateOf(update)

    /** What is being offered now, recheck included: a snapshot would leave Settings reading the first answer for ever. */
    val update: AppUpdate? get() = state.value

    fun set(update: AppUpdate?) {
        state.value = update
    }
}

val LocalUpdateOffer = compositionLocalOf { UpdateOffer(null) {} }

/**
 * A desktop's way to install a release itself: fetch the installer and run it. False when it couldn't, and the
 * banner then opens the download in the browser instead.
 */
fun interface UpdateInstaller {
    suspend fun install(update: AppUpdate): Boolean
}

/** Set where the app can install a build itself (a phone's APK, a desktop's installer); null opens the download. */
val LocalUpdateInstaller = staticCompositionLocalOf<UpdateInstaller?> { null }

/**
 * Checks once per launch for a newer build: the one the user's own host serves (a phone takes that as an APK it can
 * install itself), then a newer release of [repo] than [version] (both come from the build; a local build has no
 * repo and never asks GitHub). Nothing is checked when [enabled] is off in Settings.
 */
@Composable
fun rememberUpdateOffer(
    repo: String?,
    version: String?,
    enabled: Boolean,
    versionCode: Int = 0,
): UpdateOffer {
    val checker = koinInject<UpdateChecker>()
    val host = koinInject<HostUpdates>()
    val scope = rememberCoroutineScope()
    // One offer for the app's life: the same instance the banner and Settings both read, so a recheck shows in both.
    val offer = remember(enabled, repo, version, versionCode) { UpdateOffer(null) }
    // One check, used by the app opening and by Settings asking again.
    suspend fun look() {
        offer.set(when {
            !enabled -> null
            else -> {
                // GitHub first: it answers from anywhere, where the host is only reachable on the user's tailnet.
                val release = if (!repo.isNullOrBlank() && version != null) checker.check(repo, version) else null
                // A desktop passes no version code and has no APK to install: only a phone asks its host.
                release ?: if (versionCode > 0) host.check(versionCode) else null
            }
        })
    }
    LaunchedEffect(repo, version, enabled, versionCode) { look() }
    offer.recheck = { look() }
    offer.onDismiss = {
        val dismissed = offer.update
        if (dismissed != null) {
            offer.set(null)
            scope.launch { checker.dismiss(dismissed.version) }
        }
    }
    return offer
}

/**
 * A card offering the new release: download the APK, install it (a desktop), or open the release page; or not now.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val offer = LocalUpdateOffer.current
    val update = offer.update ?: return
    val uriHandler = LocalUriHandler.current
    val installer = LocalUpdateInstaller.current?.takeIf { update.downloadUrl != null }
    val scope = rememberCoroutineScope()
    var installing by remember(update) { mutableStateOf(false) }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val tint = Theme[colors][accent]
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            .background(tint.copy(alpha = 0.08f), shape)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (update.fromServer) "A newer build is on your server" else "Version ${update.version} is out",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][text],
                )
                Text(
                    "Install it over this one; your sign-in and settings stay.",
                    style = Theme[typography][caption],
                    color = Theme[colors][textSecondary],
                )
            }
            UnstyledButton(onClick = offer.onDismiss, modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusSmall]))) {
                UnstyledIcon(Lucide.X, contentDescription = "Not now", tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
        }
        Button(
            when {
                installing -> "Downloading…"
                installer != null -> "Install"
                update.downloadUrl != null -> "Download"
                else -> "Open release"
            },
            onClick = {
                if (installer == null) {
                    uriHandler.openUri(update.downloadUrl ?: update.pageUrl)
                } else if (!installing) {
                    installing = true
                    scope.launch {
                        if (!installer.install(update)) uriHandler.openUri(update.downloadUrl ?: update.pageUrl)
                        installing = false
                    }
                }
            },
            loading = installing,
            size = ButtonSize.Small,
            leadingIcon = Lucide.Download,
        )
    }
}
