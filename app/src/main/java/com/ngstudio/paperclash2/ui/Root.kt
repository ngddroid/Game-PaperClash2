package com.ngstudio.paperclash2.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.ngstudio.paperclash2.MainActivity
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.ads.AdProvider
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.gl.GameSurfaceView
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.GameDialog
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.game.GameScreen
import com.ngstudio.paperclash2.ui.levels.LevelSheet
import com.ngstudio.paperclash2.ui.levels.UnlockDialog
import com.ngstudio.paperclash2.ui.main.MainScreen
import com.ngstudio.paperclash2.ui.quests.DailyGiftDialog
import com.ngstudio.paperclash2.ui.quests.SpinDialog
import com.ngstudio.paperclash2.ui.settings.HowToPlayScreen
import com.ngstudio.paperclash2.ui.settings.LegalScreen
import com.ngstudio.paperclash2.ui.settings.ProfileScreen
import com.ngstudio.paperclash2.ui.settings.SettingsScreen
import com.ngstudio.paperclash2.ui.splash.OnboardingScreen
import com.ngstudio.paperclash2.ui.splash.SplashScreen
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.PaperClashTheme
import com.ngstudio.paperclash2.ui.theme.Type
import kotlinx.coroutines.delay

@Composable
fun PaperClashRoot(state: AppState) {
    val fx = remember { Fx(state.app.audio, state.app.haptics) }
    val save by state.save.state.collectAsState()
    val context = LocalContext.current
    PaperClashTheme {
        CompositionLocalProvider(LocalFx provides fx) {
            Box(Modifier.fillMaxSize().background(PC.Cream)) {
                // The single GL surface lives behind every screen.
                AndroidView(
                    factory = { GameSurfaceView(it, state.controller, msaa = save.settings.quality != 0) },
                    modifier = Modifier.fillMaxSize(),
                )

                // Which GL scene and music fit the current screen.
                LaunchedEffect(state.screen, state.tab) {
                    val c = state.controller
                    when (val s = state.screen) {
                        is Screen.Main -> {
                            when (state.tab) {
                                Tab.HOME -> c.showShowcase(state.look(), Progress.currentLevel(state.data))
                                Tab.SHOP -> c.showPreview(state.look())
                                else -> c.showNothing()
                            }
                            state.app.audio.playMenu()
                        }
                        is Screen.Game -> c.startLevel(s.level, state.look(), s.boosters)
                        is Screen.Splash, is Screen.Onboarding -> {
                            c.showNothing()
                            state.app.audio.playMenu()
                        }
                        else -> c.showNothing()
                    }
                    (context as? MainActivity)?.setGameImmersive(state.screen is Screen.Game)
                }
                LaunchedEffect(save.settings) { state.applySettings(save) }

                // A full-screen dialog over the live Home showcase: hold the background still.
                val homeDialog = state.screen is Screen.Main && state.tab == Tab.HOME &&
                    (state.levelSheet != null || state.unlockFor != null || state.dailyOpen || state.spinOpen || state.exitAsk)
                LaunchedEffect(homeDialog) { state.controller.freezeBackground(homeDialog) }

                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = {
                        (fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.96f))
                            .togetherWith(fadeOut(tween(200)) + scaleOut(tween(220), targetScale = 1.02f))
                    },
                    contentKey = { it::class },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        is Screen.Splash -> SplashScreen {
                            state.reset(if (state.data.onboarded) Screen.Main else Screen.Onboarding)
                        }
                        is Screen.Onboarding -> OnboardingScreen(state)
                        is Screen.Main -> MainScreen(state)
                        is Screen.Game -> androidx.compose.runtime.key(screen.nonce) { GameScreen(state, screen) }
                        is Screen.Settings -> SettingsScreen(state)
                        is Screen.Legal -> LegalScreen(state, screen.page)
                        is Screen.Profile -> ProfileScreen(state)
                        is Screen.HowToPlay -> HowToPlayScreen(state)
                    }
                }

                // dialogs shared by several screens
                state.levelSheet?.let { LevelSheet(state, it) }
                state.unlockFor?.let { UnlockDialog(state, it) }
                if (state.dailyOpen) DailyGiftDialog(state)
                if (state.spinOpen) SpinDialog(state)
                if (state.exitAsk) ExitDialog(state)
                Toast(state)
                AdProvider.Overlay()

                if (state.screen is Screen.Main) {
                    BackHandler {
                        when {
                            state.levelSheet != null -> state.levelSheet = null
                            state.unlockFor != null -> state.unlockFor = null
                            state.dailyOpen -> state.dailyOpen = false
                            state.spinOpen -> state.spinOpen = false
                            state.tab != Tab.HOME -> state.tab = Tab.HOME
                            else -> state.exitAsk = !state.exitAsk
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitDialog(state: AppState) {
    val context = LocalContext.current
    GameDialog(onDismiss = { state.exitAsk = false }) {
        Text("Leaving already?", style = Type.heading, modifier = Modifier.align(Alignment.CenterHorizontally))
        VSpace(8.dp)
        Text("Your progress is saved. Come back soon to fold more shapes!", style = Type.body, modifier = Modifier.align(Alignment.CenterHorizontally))
        VSpace(18.dp)
        Row {
            GameButton("Stay", { state.exitAsk = false }, Modifier.weight(1f), color = PC.Mint, dark = PC.MintDark)
            Spacer(Modifier.width(12.dp))
            GameButton("Exit", { (context as? Activity)?.finish() }, Modifier.weight(1f), sound = Sfx.UI_BACK)
        }
    }
}

@Composable
private fun Toast(state: AppState) {
    val text = state.toast ?: return
    LaunchedEffect(text) {
        delay(2200)
        state.toast = null
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(PC.Ink.copy(alpha = 0.92f))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_info), null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = Type.label.copy(color = Color.White))
        }
    }
}
