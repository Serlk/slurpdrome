package dev.slurpdrome.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.slurpdrome.SlurpApp
import dev.slurpdrome.ui.keys.Hotkeys
import dev.slurpdrome.ui.nav.AppRoot
import dev.slurpdrome.ui.theme.SlurpTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as SlurpApp).container }
    private val hotkeys by lazy { Hotkeys(container) }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val settings by container.settingsState.collectAsStateWithLifecycle()
            SlurpTheme(settings) {
                CompositionLocalProvider(LocalContainer provides container) {
                    AppRoot(hotkeys)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.network.refresh()
    }

    /**
     * Hardware Enter (Titan/QWERTY, cyberdecks) ≠ D-pad centre for many Compose components.
     * Outside text fields we treat Enter as "activate", exactly like the centre key.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val enter = event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (enter && !dev.slurpdrome.ui.components.TextFocus.active) {
            return super.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
            )
        }
        return super.dispatchKeyEvent(event)
    }

    /** Only reached for keys no focused composable consumed — so text input is never hijacked. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (container.hasSources.value && hotkeys.onKeyDown(keyCode, event, resources.configuration)) return true
        return super.onKeyDown(keyCode, event)
    }
}
