package app.orchard.tv.screentime

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppNames
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/**
 * What a child gets instead of the app once a limit is reached: which app, why it stopped, and the
 * one way on — the parent's PIN. It reads as an explanation, not a punishment: the parent decides
 * how much to give, from fifteen minutes up to the rest of the day, and picks that before the PIN.
 *
 * The minutes come first because that is what the phone page already does (an extension is a
 * timed exception with a countdown, not a secret "allow everything"): the gate asks *how much*,
 * then who may say yes. [onAskForPin] carries the chosen minutes, null for the rest of today.
 *
 * The PIN is checked with AerialSettings.pinMatches (PinMode.Verify), never Unlock: letting a child
 * watch longer must not also unlock the TV's settings. Turning the PIN into an exception is the
 * caller's job (ScreenTime.dayPass), because only it talks to the backend.
 *
 * With no PIN set on this TV there is nothing to check against, so the time buttons are left out
 * rather than shown as a keypad that can never be right.
 *
 * Controls are Pressable, which triggers on the release of a press that started on itself — the
 * same guard that keeps a long press from opening a menu and then firing its first row.
 */
@Composable
fun ScreenTimeGate(block: ScreenTime.Block, onAskForPin: (Int?) -> Unit, onDismiss: () -> Unit) {
    // Back closes the card, the same as its own button. Home's BackHandler is off while an
    // overlay is up, so unhandled, Back would quit Home and the guard would bring it back
    // through the boot input — the TV appears to switch source under the card.
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val hasPin = AerialSettings.pinSet
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val name = remember(block.pkg) { appLabel(context, block.pkg) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.Glass)
                .padding(28.dp),
        ) {
            BasicText(stringResource(R.string.st_gate_title, name), style = Type.Title)
            Spacer(Modifier.height(10.dp))
            BasicText(
                stringResource(reasonText(block.reason)),
                style = Type.Body.copy(color = Palette.SecondaryLabel),
            )
            Spacer(Modifier.height(20.dp))
            // A day lock sits ahead of every exception (§10), so extra minutes asked for here
            // would still be stopped — the card would offer a way out that does not open.
            val canAskForMore = hasPin && block.reason != ScreenTime.Reason.DayLock
            if (canAskForMore) {
                // How much first, then who says yes: the same timed exception the phone page makes,
                // just asked on the TV with four buttons instead of a keypad and a picker.
                BasicText(
                    stringResource(R.string.st_gate_add_hint),
                    style = Type.Body.copy(color = Palette.SecondaryLabel),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    GateButton(stringResource(R.string.st_gate_add_15), first, { onAskForPin(15) }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    GateButton(stringResource(R.string.st_gate_add_30), null, { onAskForPin(30) }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    GateButton(stringResource(R.string.st_gate_add_60), null, { onAskForPin(60) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                GateButton(stringResource(R.string.st_gate_add_day), null, { onAskForPin(null) })
                Spacer(Modifier.height(10.dp))
            }
            GateButton(stringResource(R.string.st_gate_close), if (canAskForMore) null else first, onDismiss)
        }
    }
}

@Composable
private fun GateButton(label: String, requester: FocusRequester?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(
        onClick = onClick,
        focusRequester = requester,
        modifier = modifier.fillMaxWidth().height(54.dp),
    ) { state ->
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(if (state.focused) Palette.Focus else Palette.FillStrong),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = Type.Body.copy(color = if (state.focused) Palette.OnFocus else Palette.Label),
            )
        }
    }
}

private fun reasonText(reason: ScreenTime.Reason): Int = when (reason) {
    ScreenTime.Reason.Blocked -> R.string.st_gate_reason_blocked
    ScreenTime.Reason.Window -> R.string.st_gate_reason_window
    ScreenTime.Reason.GroupLimit -> R.string.st_gate_reason_group
    ScreenTime.Reason.TvLimit -> R.string.st_gate_reason_tv
    // A whole-day lock (POLICY-DESIGN §10): the card only reads the reason, never the countdown,
    // so nothing here depends on leftMs; the PIN buttons still work — the parent's PIN on the TV
    // remains the parent's say-so.
    ScreenTime.Reason.DayLock -> R.string.st_gate_reason_daylock
    // The backend's fallback lock (FALLBACK-DESIGN §4): the TV's own stop did not reach the
    // viewer, so the parent console popped this one. Extra minutes still work — the PIN on the
    // TV remains the parent's say-so, fallback lock included.
    ScreenTime.Reason.Fallback -> R.string.st_gate_reason_fallback
}

/** The app's name as this household sees it: a rename wins, then the app's own label. */
private fun appLabel(context: Context, pkg: String): String =
    AppNames.of(pkg) ?: runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
