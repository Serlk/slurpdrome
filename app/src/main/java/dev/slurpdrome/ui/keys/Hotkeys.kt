package dev.slurpdrome.ui.keys

import androidx.annotation.StringRes
import dev.slurpdrome.R
import android.content.res.Configuration
import android.view.KeyEvent
import dev.slurpdrome.AppContainer
import dev.slurpdrome.data.prefs.KeypadMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Navigation requests from hotkeys, consumed by the Compose scaffold. */
enum class NavCommand { SEARCH, NOW_PLAYING, QUEUE, LYRICS, HOME, LIBRARY, PLAYLISTS, SETTINGS, HELP }

/** [keys] are literal key names (not translated) unless [keysRes] is set; [action] is a @StringRes id. */
data class HotkeyHelp(val keys: String, @StringRes val action: Int, @StringRes val keysRes: Int = 0)

/**
 * Global hotkeys. Handled in Activity.onKeyDown, i.e. only for key events no focused
 * component consumed — so typing in a text field never triggers them.
 */
class Hotkeys(private val c: AppContainer) {
    private val _nav = MutableSharedFlow<NavCommand>(extraBufferCapacity = 4)
    val nav: SharedFlow<NavCommand> = _nav

    fun keypadActive(config: Configuration): Boolean = when (c.settingsState.value.keypadMode) {
        KeypadMode.ON -> true
        KeypadMode.OFF -> false
        KeypadMode.AUTO -> config.keyboard == Configuration.KEYBOARD_12KEY
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent, config: Configuration): Boolean {
        if (event.repeatCount > 0 && keyCode !in REPEATABLE) return false
        val p = c.player
        val step = c.settingsState.value.seekStepSec * 1000L
        val keypad = keypadActive(config)

        if (keypad) {
            when (keyCode) {
                KeyEvent.KEYCODE_5 -> { p.togglePlay(); return true }
                KeyEvent.KEYCODE_4 -> { p.previous(); return true }
                KeyEvent.KEYCODE_6 -> { p.next(); return true }
                KeyEvent.KEYCODE_7 -> { p.seekBy(-step); return true }
                KeyEvent.KEYCODE_9 -> { p.seekBy(step); return true }
                KeyEvent.KEYCODE_0 -> { favoriteCurrent(); return true }
                KeyEvent.KEYCODE_STAR -> { p.toggleShuffle(); return true }
                KeyEvent.KEYCODE_POUND -> { p.cycleRepeat(); return true }
                KeyEvent.KEYCODE_1 -> { _nav.tryEmit(NavCommand.SEARCH); return true }
                KeyEvent.KEYCODE_2 -> { _nav.tryEmit(NavCommand.NOW_PLAYING); return true }
                KeyEvent.KEYCODE_3 -> { _nav.tryEmit(NavCommand.LYRICS); return true }
                KeyEvent.KEYCODE_8 -> { _nav.tryEmit(NavCommand.QUEUE); return true }
            }
        }

        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        if (alt && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_5) {
            rateCurrent(keyCode - KeyEvent.KEYCODE_0); return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> p.togglePlay()
            KeyEvent.KEYCODE_J -> p.seekBy(-step)
            KeyEvent.KEYCODE_L -> p.seekBy(step)
            KeyEvent.KEYCODE_N -> if (shift) p.previous() else p.next()
            KeyEvent.KEYCODE_P -> if (ctrl) _nav.tryEmit(NavCommand.PLAYLISTS) else p.previous()
            KeyEvent.KEYCODE_LEFT_BRACKET -> p.previous()
            KeyEvent.KEYCODE_RIGHT_BRACKET -> p.next()
            KeyEvent.KEYCODE_F -> favoriteCurrent()
            KeyEvent.KEYCODE_S -> p.toggleShuffle()
            KeyEvent.KEYCODE_R -> p.cycleRepeat()
            KeyEvent.KEYCODE_SLASH -> if (shift) _nav.tryEmit(NavCommand.HELP) else _nav.tryEmit(NavCommand.SEARCH)
            KeyEvent.KEYCODE_SEARCH -> _nav.tryEmit(NavCommand.SEARCH)
            KeyEvent.KEYCODE_Q -> _nav.tryEmit(NavCommand.QUEUE)
            KeyEvent.KEYCODE_Y -> _nav.tryEmit(NavCommand.LYRICS)
            KeyEvent.KEYCODE_V -> _nav.tryEmit(NavCommand.NOW_PLAYING)
            KeyEvent.KEYCODE_H -> _nav.tryEmit(NavCommand.HOME)
            KeyEvent.KEYCODE_B -> _nav.tryEmit(NavCommand.LIBRARY)
            KeyEvent.KEYCODE_COMMA -> if (ctrl) _nav.tryEmit(NavCommand.SETTINGS) else return false
            KeyEvent.KEYCODE_F1 -> _nav.tryEmit(NavCommand.HELP)
            else -> return false
        }
        return true
    }

    private fun favoriteCurrent() {
        c.player.state.value.current?.let { c.library.toggleSongStar(it) }
    }

    private fun rateCurrent(stars: Int) {
        c.player.state.value.current?.let { c.library.setRating(it.id, "song", stars) }
    }

    companion object {
        private val REPEATABLE = setOf(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_9)

        val keyboardHelp = listOf(
            HotkeyHelp("Space / K", R.string.hotkey_play_pause),
            HotkeyHelp("J / L", R.string.hotkey_seek),
            HotkeyHelp("N / P  ] / [", R.string.hotkey_next_prev_track, keysRes = R.string.hotkey_keys_next_prev),
            HotkeyHelp("F", R.string.hotkey_favorite),
            HotkeyHelp("Alt+0…5", R.string.hotkey_rate),
            HotkeyHelp("S / R", R.string.hotkey_shuffle_repeat),
            HotkeyHelp("/", R.string.hotkey_search),
            HotkeyHelp("V / Q / Y", R.string.hotkey_player_queue_lyrics),
            HotkeyHelp("H / B / Ctrl+P", R.string.hotkey_home_library_playlists),
            HotkeyHelp("Ctrl+,", R.string.hotkey_settings),
            HotkeyHelp("M / Menu", R.string.hotkey_item_actions),
            HotkeyHelp("? / F1", R.string.hotkey_this_help),
        )
        val keypadHelp = listOf(
            HotkeyHelp("5", R.string.hotkey_play_pause),
            HotkeyHelp("4 / 6", R.string.hotkey_prev_next),
            HotkeyHelp("7 / 9", R.string.hotkey_seek),
            HotkeyHelp("0", R.string.hotkey_favorite),
            HotkeyHelp("* / #", R.string.hotkey_shuffle_repeat),
            HotkeyHelp("1", R.string.hotkey_search),
            HotkeyHelp("2 / 8", R.string.hotkey_player_queue),
            HotkeyHelp("3", R.string.hotkey_lyrics),
            HotkeyHelp("Menu", R.string.hotkey_item_actions),
        )
    }
}
