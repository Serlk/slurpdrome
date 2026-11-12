package dev.slurpdrome.ui.components

import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import dev.slurpdrome.ui.LocalContainer

/** Standard Cast route picker; hidden on devices without Google Play Services. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val available by c.castAvailable.collectAsState()
    if (!available) return
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_DayNight)).also {
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it) }
            }
        },
        modifier = modifier.size(48.dp).focusRing(androidx.compose.foundation.shape.CircleShape),
    )
}
