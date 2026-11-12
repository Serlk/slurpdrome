package dev.slurpdrome.widget

import androidx.glance.LocalContext
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.imageLoader
import coil.request.ImageRequest
import dev.slurpdrome.R
import dev.slurpdrome.SlurpApp
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

/** What the widget shows; written by the playback service, so it's correct even with no UI. */
data class NowPlayingSnapshot(val song: SongEntity? = null, val isPlaying: Boolean = false)

object WidgetBridge {
    val state = MutableStateFlow(NowPlayingSnapshot())
    private var lastPush = 0L

    suspend fun publish(context: Context, snapshot: NowPlayingSnapshot) {
        if (snapshot == state.value && System.currentTimeMillis() - lastPush < 2000) return
        state.value = snapshot
        lastPush = System.currentTimeMillis()
        runCatching { PlayerWidget().updateAll(context) }
    }
}

class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetBridge.state.value
        val art = snap.song?.coverArt?.let { loadArt(context, it) }
        provideContent { GlanceTheme { Content(snap, art) } }
    }

    private suspend fun loadArt(context: Context, coverArt: String): Bitmap? {
        val c = (context.applicationContext as SlurpApp).container
        val url = c.sources.artUrl(coverArt, 300) ?: return null
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(300).allowHardware(false).build())
        return (result.drawable as? BitmapDrawable)?.bitmap
    }

    @Composable
    private fun Content(snap: NowPlayingSnapshot, art: Bitmap?) {
        val size = LocalSize.current
        val bg = ColorProvider(Color(0xEE14110F))
        val fg = ColorProvider(Color(0xFFEDE0DA))
        val dim = ColorProvider(Color(0xFFB8A99F))
        val song = snap.song
        val open = actionStartActivity<MainActivity>()
        Box(GlanceModifier.fillMaxSize().background(bg).cornerRadius(20.dp).padding(10.dp)) {
            when {
                size.width < MEDIUM.width -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                    Controls(snap.isPlaying, small = true)
                }
                size.height < LARGE.height -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Cover(art, 72, open)
                    Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(song?.title ?: "Slurpdrome", maxLines = 1, style = TextStyle(color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp), modifier = GlanceModifier.clickable(open))
                        Text(song?.artist ?: LocalContext.current.getString(R.string.widget_nothing_playing), maxLines = 1, style = TextStyle(color = dim, fontSize = 12.sp))
                        Spacer(GlanceModifier.height(4.dp))
                        Controls(snap.isPlaying, small = false)
                    }
                }
                else -> Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(art, (minOf(size.width.value, size.height.value) - 110).toInt().coerceAtLeast(80), open)
                    Spacer(GlanceModifier.height(8.dp))
                    Text(song?.title ?: "Slurpdrome", maxLines = 1, style = TextStyle(color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp))
                    Text(song?.artist ?: LocalContext.current.getString(R.string.widget_nothing_playing), maxLines = 1, style = TextStyle(color = dim, fontSize = 12.sp))
                    Spacer(GlanceModifier.height(4.dp))
                    Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                        Controls(snap.isPlaying, small = false)
                        if (song != null) {
                            Spacer(GlanceModifier.width(8.dp))
                            Button(if (song.starred) R.drawable.ic_w_favorite else R.drawable.ic_w_favorite_border, WidgetAction.FAVORITE)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Cover(art: Bitmap?, sizeDp: Int, open: androidx.glance.action.Action) {
        val mod = GlanceModifier.size(sizeDp.dp).cornerRadius(12.dp).clickable(open)
        if (art != null) Image(ImageProvider(art), null, mod, contentScale = ContentScale.Crop)
        else Box(mod.background(ColorProvider(Color(0xFF2A2523))), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_notification), null, GlanceModifier.size((sizeDp / 2).dp))
        }
    }

    @Composable
    private fun Controls(playing: Boolean, small: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(R.drawable.ic_w_prev, WidgetAction.PREV)
            Spacer(GlanceModifier.width(if (small) 4.dp else 8.dp))
            Button(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play, WidgetAction.TOGGLE, big = true)
            Spacer(GlanceModifier.width(if (small) 4.dp else 8.dp))
            Button(R.drawable.ic_w_next, WidgetAction.NEXT)
        }
    }

    @Composable
    private fun Button(icon: Int, action: String, big: Boolean = false) {
        Image(
            ImageProvider(icon), action,
            GlanceModifier.size(if (big) 44.dp else 36.dp).cornerRadius(22.dp)
                .background(ColorProvider(if (big) Color(0xFFFF8A57) else Color(0x22FFFFFF)))
                .padding(if (big) 10.dp else 8.dp)
                .clickable(actionRunCallback<WidgetAction>(actionParametersOf(WidgetAction.KEY to action))),
        )
    }

    companion object {
        private val SMALL = DpSize(110.dp, 48.dp)
        private val MEDIUM = DpSize(240.dp, 96.dp)
        private val LARGE = DpSize(240.dp, 240.dp)
    }
}

class WidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val c = (context.applicationContext as SlurpApp).container
        when (parameters[KEY]) {
            TOGGLE -> c.player.togglePlay()
            NEXT -> c.player.next()
            PREV -> c.player.previous()
            FAVORITE -> WidgetBridge.state.value.song?.let { c.library.toggleSongStar(it) }
        }
    }

    companion object {
        val KEY = ActionParameters.Key<String>("action")
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREV = "prev"
        const val FAVORITE = "favorite"
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}
