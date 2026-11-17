package com.ngstudio.paperclash2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ngstudio.paperclash2.game.GameController
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.PaperClashRoot

class MainActivity : ComponentActivity() {
    private lateinit var controller: GameController
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val app = application as PaperClashApp
        controller = GameController(applicationContext, app.audio, app.haptics)
        state = AppState(app, controller)
        state.applySettings()
        setContent { PaperClashRoot(state) }
    }

    /** Full-screen while playing; system bars come back with a swipe. */
    fun setGameImmersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        controller.surface?.onResume()
        (application as PaperClashApp).audio.onResume()
    }

    override fun onPause() {
        controller.surface?.onPause()
        val app = application as PaperClashApp
        app.audio.onPause()
        app.save.flush()
        super.onPause()
    }
}
