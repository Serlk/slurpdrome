package dev.slurpdrome.ui.components

import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.slurpdrome.R
import dev.slurpdrome.ui.LocalContainer
import kotlinx.coroutines.launch

/** "Play on…": this phone, Chromecast, or a DLNA/UPnP renderer — one picker for all outputs. */
@Composable
fun OutputButton() {
    val c = LocalContainer.current
    val dlna by c.dlna.active.collectAsState()
    val casting by c.castActive.collectAsState()
    var open by remember { mutableStateOf(false) }
    val icon = when { dlna != null -> Icons.Default.SpeakerGroup; casting -> Icons.Default.CastConnected; else -> Icons.Default.Cast }
    FIconButton(
        icon, stringResource(R.string.output_title),
        tint = if (dlna != null || casting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) { open = true }
    if (open) OutputDialog { open = false }
}

@Composable
private fun OutputDialog(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by c.dlna.devices.collectAsState()
    val scanning by c.dlna.scanning.collectAsState()
    val active by c.dlna.active.collectAsState()
    val castAvailable by c.castAvailable.collectAsState()
    val casting by c.castActive.collectAsState()
    LaunchedEffect(Unit) { c.dlna.scan() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.output_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutputRow(Icons.Default.PhoneAndroid, stringResource(R.string.output_this_phone), null, selected = active == null && !casting) {
                    c.dlna.active.value = null
                    if (casting) endCast(ctx)
                    onDismiss()
                }
                if (castAvailable) OutputRow(
                    if (casting) Icons.Default.CastConnected else Icons.Default.Cast, "Chromecast",
                    stringResource(if (casting) R.string.output_chromecast_connected else R.string.output_chromecast_pick),
                    selected = casting,
                ) {
                    if (casting) endCast(ctx) else pickCast(ctx)
                    onDismiss()
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DLNA / UPnP", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { scope.launch { c.dlna.scan() } }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.output_rescan)) }
                }
                devices.forEach { r ->
                    OutputRow(Icons.Default.Speaker, r.name, r.model, selected = active?.udn == r.udn) {
                        c.dlna.active.value = r
                        onDismiss()
                    }
                }
                when {
                    devices.isEmpty() && scanning -> Text(stringResource(R.string.output_searching), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    devices.isEmpty() -> Text(stringResource(R.string.output_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    else -> Text(stringResource(R.string.output_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun OutputRow(icon: ImageVector, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().dpadClickable(RoundedCornerShape(12.dp), onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

private fun pickCast(ctx: android.content.Context): Unit {
    val selector = runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx).mergedSelector }.getOrNull() ?: return
    val dialog = androidx.mediarouter.app.MediaRouteChooserDialog(ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog))
    dialog.routeSelector = selector
    dialog.show()
}

private fun endCast(ctx: android.content.Context) {
    runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx).sessionManager.endCurrentSession(true) }
}
