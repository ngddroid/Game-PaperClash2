package com.ngstudio.paperclash2.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ngstudio.paperclash2.BuildConfig
import com.ngstudio.paperclash2.PaperClashApp
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.data.SaveData
import com.ngstudio.paperclash2.engine.game.Boosters
import com.ngstudio.paperclash2.game.GameController
import com.ngstudio.paperclash2.game.PlayerLook
import com.ngstudio.paperclash2.game.SceneSettings

sealed interface Screen {
    data object Splash : Screen
    data object Onboarding : Screen
    data object Main : Screen
    data class Game(val level: Int, val boosters: Boosters, val nonce: Long = System.nanoTime()) : Screen
    data object Settings : Screen
    data class Legal(val page: String) : Screen
    data object Profile : Screen
    data object HowToPlay : Screen
}

enum class Tab { SHOP, LEVELS, HOME, ALBUM, QUESTS }

/** Navigation and transient UI state (single activity, survives configuration changes). */
class AppState(val app: PaperClashApp, val controller: GameController) {
    var screen by mutableStateOf<Screen>(Screen.Splash)
        private set
    var tab by mutableStateOf(Tab.HOME)
    private val stack = ArrayList<Screen>()

    var levelSheet by mutableStateOf<Int?>(null)
    var unlockFor by mutableStateOf<Int?>(null)
    var dailyOpen by mutableStateOf(false)
    var spinOpen by mutableStateOf(false)
    var exitAsk by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var celebrate by mutableStateOf<String?>(null)

    val save get() = app.save
    val data: SaveData get() = app.save.value
    val isDebug = BuildConfig.DEBUG

    fun look(d: SaveData = data) = PlayerLook(d.nickname.ifBlank { "You" }, d.skin, d.ribbon)

    fun go(s: Screen) {
        if (s is Screen.Settings || s is Screen.Legal || s is Screen.Profile || s is Screen.HowToPlay) stack += screen
        screen = s
    }

    /** Replaces the screen without remembering the current one (main flow). */
    fun reset(s: Screen) {
        stack.clear()
        screen = s
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        screen = stack.removeAt(stack.size - 1)
        return true
    }

    fun play(level: Int, boosters: Boosters) {
        levelSheet = null
        unlockFor = null
        reset(Screen.Game(level, boosters))
    }

    fun openLevel(level: Int) {
        if (Progress.isUnlocked(data, level)) levelSheet = level else unlockFor = level
    }

    fun applySettings(d: SaveData = data) {
        val s = d.settings
        app.audio.sfxOn = s.sound
        app.audio.musicOn = s.music
        app.haptics.enabled = s.vibration
        controller.settings = SceneSettings(
            quality = if (s.quality == 3) 2 else s.quality,
            reduceMotion = s.reduceMotion,
            colorAssist = s.colorAssist,
            showFps = s.showFps,
        )
    }
}
