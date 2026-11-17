package com.ngstudio.paperclash2.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.ngstudio.paperclash2.BuildConfig
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.data.Settings
import com.ngstudio.paperclash2.engine.level.Worlds
import com.ngstudio.paperclash2.game.Skins
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.Screen
import com.ngstudio.paperclash2.ui.art.PlaneIcon
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.GameDialog
import com.ngstudio.paperclash2.ui.components.GameSlider
import com.ngstudio.paperclash2.ui.components.GameToggle
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.PaperBackground
import com.ngstudio.paperclash2.ui.components.PaperCard
import com.ngstudio.paperclash2.ui.components.RoundButton
import com.ngstudio.paperclash2.ui.components.Segmented
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.splash.NameField
import com.ngstudio.paperclash2.ui.splash.OnboardingScreen
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type

@Composable
private fun TopBar(state: AppState, title: String) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(R.drawable.ic_back, { state.back() }, sound = Sfx.UI_BACK, contentDescription = "Back")
        Spacer(Modifier.width(10.dp))
        OutlinedText(title, Type.title.copy(color = Color.White), outline = PC.Ink, width = 9f)
    }
}

@Composable
fun SettingsScreen(state: AppState) {
    val save by state.save.state.collectAsState()
    val s = save.settings
    val fx = LocalFx.current
    val context = LocalContext.current
    var confirmReset by remember { mutableIntStateOf(0) }
    var debug by remember { mutableStateOf(false) }
    fun set(f: (Settings) -> Settings) = state.save.update { it.copy(settings = f(it.settings)) }
    BackHandler { state.back() }
    PaperBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, "SETTINGS")
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 14.dp)) {
                Section("Sound & feel", PC.Sky) {
                    ToggleRow(R.drawable.ic_sound_on, "Sound effects", s.sound) { v -> set { it.copy(sound = v) } }
                    ToggleRow(R.drawable.ic_music_on, "Music", s.music) { v -> set { it.copy(music = v) } }
                    ToggleRow(R.drawable.ic_vibrate_on, "Vibration", s.vibration) { v -> set { it.copy(vibration = v) } }
                }
                Section("Graphics", PC.Grape) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_graphics), null, Modifier.size(34.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Quality", style = Type.label)
                    }
                    VSpace(6.dp)
                    Segmented(listOf("Battery", "Balanced", "High", "Auto"), s.quality, { v -> set { it.copy(quality = v) } }, Modifier.fillMaxWidth())
                    Text("Battery saver also turns off anti-aliasing after a restart.", style = Type.small.copy(fontSize = 11.sp))
                    ToggleRow(R.drawable.ic_motion, "Reduce motion", s.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }
                    ToggleRow(R.drawable.ic_palette, "Colour-assist patterns", s.colorAssist) { v -> set { it.copy(colorAssist = v) } }
                }
                Section("Controls", PC.Mint) {
                    Segmented(listOf("Floating stick", "Fixed stick"), s.controls, { v -> set { it.copy(controls = v) } }, Modifier.fillMaxWidth())
                    VSpace(8.dp)
                    Text("Steering sensitivity", style = Type.label)
                    GameSlider(s.sensitivity, { v -> set { it.copy(sensitivity = v) } }, 0.6f..1.6f)
                    ToggleRow(R.drawable.ic_hand, "Left-handed buttons", s.leftHanded) { v -> set { it.copy(leftHanded = v) } }
                }
                Section("Game", PC.Coral) {
                    LinkRow(R.drawable.ic_profile, "Profile & nickname") { state.go(Screen.Profile) }
                    LinkRow(R.drawable.ic_help, "How to play") { state.go(Screen.HowToPlay) }
                    LinkRow(R.drawable.ic_rate, "Rate Paper Clash 2") {
                        open(context, "market://details?id=${context.packageName}", "https://play.google.com/store/apps/details?id=${context.packageName}")
                    }
                    LinkRow(R.drawable.ic_share, "Share with friends") {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Fly, draw and fold the world into your colour in Paper Clash 2! https://play.google.com/store/apps/details?id=${context.packageName}")
                        }
                        context.startActivity(Intent.createChooser(i, "Share Paper Clash 2"))
                    }
                }
                Section("Legal", PC.Kraft) {
                    LinkRow(R.drawable.ic_privacy, "Privacy Policy") { state.go(Screen.Legal("privacy")) }
                    LinkRow(R.drawable.ic_terms, "Terms of Use") { state.go(Screen.Legal("terms")) }
                }
                Section("Progress", PC.Pink) {
                    LinkRow(R.drawable.ic_reset, "Reset progress") { confirmReset = 1 }
                    if (BuildConfig.DEBUG) LinkRow(R.drawable.ic_controls, "Developer panel (debug only)") { debug = true }
                }
                Text("Paper Clash 2 · version ${BuildConfig.VERSION_NAME} · NG Studio", style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
                VSpace(30.dp)
            }
        }
    }
    if (confirmReset > 0) {
        GameDialog(onDismiss = { confirmReset = 0 }, tape = PC.Coral) {
            Text(if (confirmReset == 1) "Reset all progress?" else "Are you really sure?", style = Type.heading, modifier = Modifier.align(Alignment.CenterHorizontally))
            VSpace(8.dp)
            Text(
                if (confirmReset == 1) "Levels, stars, coins, planes and stickers will be wiped. Your settings stay." else "This can't be undone.",
                style = Type.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            VSpace(16.dp)
            Row {
                GameButton("Keep", { confirmReset = 0 }, Modifier.weight(1f), color = PC.Mint, dark = PC.MintDark)
                Spacer(Modifier.width(10.dp))
                GameButton("Reset", {
                    if (confirmReset == 1) confirmReset = 2 else {
                        state.app.save.reset()
                        confirmReset = 0
                        fx.play(Sfx.UI_BACK)
                        state.toast = "Progress reset"
                    }
                }, Modifier.weight(1f))
            }
        }
    }
    if (BuildConfig.DEBUG && debug) DebugPanel(state) { debug = false }
}

private fun open(context: android.content.Context, primary: String, fallback: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(primary)))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun Section(title: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    VSpace(10.dp)
    PaperCard(Modifier.fillMaxWidth(), tape = color) {
        Text(title, style = Type.heading.copy(fontSize = 18.sp))
        VSpace(6.dp)
        content()
    }
}

@Composable
private fun ToggleRow(icon: Int, label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = Type.label, modifier = Modifier.weight(1f))
        GameToggle(value, onChange)
    }
}

@Composable
private fun LinkRow(icon: Int, label: String, onClick: () -> Unit) {
    val fx = LocalFx.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { fx.play(Sfx.UI_TAP); onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(icon), null, Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = Type.label, modifier = Modifier.weight(1f))
        Image(painterResource(R.drawable.ic_next), null, Modifier.size(24.dp))
    }
}

// ---------------------------------------------------------------------- profile

@Composable
fun ProfileScreen(state: AppState) {
    val save by state.save.state.collectAsState()
    var name by remember { mutableStateOf(save.nickname) }
    BackHandler {
        state.save.update { it.copy(nickname = name.trim().ifBlank { it.nickname }) }
        state.back()
    }
    PaperBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, "PROFILE")
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PaperCard(Modifier.fillMaxWidth(), tape = PC.Sky) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PlaneIcon(Skins.get(save.skin), 120.dp)
                        Text(Skins.get(save.skin).name, style = Type.small)
                        VSpace(10.dp)
                        NameField(name) { name = it }
                        VSpace(8.dp)
                        GameButton("SAVE NAME", {
                            state.save.update { it.copy(nickname = name.trim().ifBlank { it.nickname }) }
                            state.toast = "Name saved"
                        }, Modifier.fillMaxWidth(0.7f), height = 46.dp, color = PC.Sky, dark = PC.SkyDark)
                    }
                }
                VSpace(12.dp)
                val st = save.stats
                PaperCard(Modifier.fillMaxWidth(), tape = PC.Sun) {
                    Text("Stats", style = Type.heading)
                    VSpace(6.dp)
                    StatRow(R.drawable.ic_trophy, "Levels won", st.levelsWon.toString())
                    StatRow(R.drawable.ic_star, "Stars", "${Progress.totalStars(save)} / ${Worlds.TOTAL_LEVELS * 3}")
                    StatRow(R.drawable.ic_nav_levels, "Furthest level", Progress.currentLevel(save).toString())
                    StatRow(R.drawable.ic_scissors, "Ribbons snipped", st.snips.toString())
                    StatRow(R.drawable.ic_mode_claim, "Loops closed", st.captures.toString())
                    StatRow(R.drawable.ic_target, "Best land claim", String.format("%.1f%%", st.bestPercent))
                    StatRow(R.drawable.ic_pop, "Best combo", "x${st.bestCombo}")
                    StatRow(R.drawable.ic_mode_boss, "Bosses beaten", st.bossesBeaten.toString())
                    StatRow(R.drawable.ic_sticker, "Stickers", "${save.album.size}")
                    StatRow(R.drawable.ic_clock, "Time played", "${st.playSeconds / 3600}h ${(st.playSeconds / 60) % 60}m")
                }
                VSpace(40.dp)
            }
        }
    }
}

@Composable
private fun StatRow(icon: Int, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = Type.body, modifier = Modifier.weight(1f))
        Text(value, style = Type.number)
    }
}

// ---------------------------------------------------------------------- legal

/** Privacy / Terms: our themed HTML from assets/legal, offline, in a WebView on its own screen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LegalScreen(state: AppState, page: String) {
    BackHandler { state.back() }
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(320)
        ready = true
    }
    Box(Modifier.fillMaxSize().background(PC.Cream)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, if (page == "terms") "TERMS OF USE" else "PRIVACY")
            if (!ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Unfolding the page...", style = Type.label)
                }
            } else AndroidView(
                factory = { ctx ->
                    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
                    WebView(ctx).apply {
                        setBackgroundColor(AColor.parseColor("#FFF6E6"))
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                loader.shouldInterceptRequest(request.url)

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                if (url.host == "appassets.androidplatform.net") return false
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url))
                                } catch (_: Exception) {
                                }
                                return true
                            }
                        }
                        loadUrl("https://appassets.androidplatform.net/assets/legal/$page.html")
                    }
                },
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            )
        }
    }
}

// ---------------------------------------------------------------------- how to play

@Composable
fun HowToPlayScreen(state: AppState) {
    BackHandler { state.back() }
    Box(Modifier.fillMaxSize()) {
        OnboardingScreen(state, replay = true)
        Box(Modifier.statusBarsPadding().padding(12.dp)) {
            RoundButton(R.drawable.ic_close, { state.back() }, sound = Sfx.UI_BACK, contentDescription = "Close")
        }
    }
}

// ---------------------------------------------------------------------- debug

@Composable
private fun DebugPanel(state: AppState, onClose: () -> Unit) {
    var jump by remember { mutableStateOf("100") }
    GameDialog(onDismiss = onClose, tape = PC.Grape) {
        Text("Developer panel", style = Type.heading)
        Text("Debug builds only.", style = Type.small)
        VSpace(10.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton("+5000 coins", { state.save.update { it.copy(coins = it.coins + 5000) } }, Modifier.fillMaxWidth(), height = 44.dp, color = PC.Sun, dark = PC.SunDark)
            GameButton("Unlock all 5000 levels", { state.save.update { it.copy(maxUnlocked = Worlds.TOTAL_LEVELS) } }, Modifier.fillMaxWidth(), height = 44.dp, color = PC.Sky, dark = PC.SkyDark)
            Row(verticalAlignment = Alignment.CenterVertically) {
                NameField(jump, numeric = true) { jump = it.filter { c -> c.isDigit() }.take(4) }
            }
            GameButton("Play level $jump", {
                val n = jump.toIntOrNull()?.coerceIn(1, Worlds.TOTAL_LEVELS) ?: 1
                onClose()
                state.save.update { Progress.unlockByAd(it, n) }
                state.openLevel(n)
            }, Modifier.fillMaxWidth(), height = 44.dp, color = PC.Mint, dark = PC.MintDark)
            GameButton("Reset daily gift & spin", { state.save.update { it.copy(daily = it.daily.copy(lastClaimDay = -1), spinDay = -1) } }, Modifier.fillMaxWidth(), height = 44.dp, color = PC.Pink, dark = PC.PinkDark)
            GameButton("Toggle FPS counter", { state.save.update { it.copy(settings = it.settings.copy(showFps = !it.settings.showFps)) } }, Modifier.fillMaxWidth(), height = 44.dp, color = PC.Grape, dark = PC.GrapeDark)
            GameButton("Replay onboarding", { onClose(); state.save.update { it.copy(onboarded = false) }; state.reset(Screen.Onboarding) }, Modifier.fillMaxWidth(), height = 44.dp, color = PC.Coral, dark = PC.CoralDark)
        }
    }
}
