package com.ngstudio.paperclash2.ui.game

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ngstudio.paperclash2.R
import com.ngstudio.paperclash2.ads.Placements
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.data.LevelReward
import com.ngstudio.paperclash2.data.Progress
import com.ngstudio.paperclash2.engine.game.Boosters
import com.ngstudio.paperclash2.engine.game.EndReason
import com.ngstudio.paperclash2.engine.game.SessionResult
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.level.Worlds
import com.ngstudio.paperclash2.engine.shape.ShapeLibrary
import com.ngstudio.paperclash2.engine.world.Cause
import com.ngstudio.paperclash2.game.GameEvent
import com.ngstudio.paperclash2.ui.AppState
import com.ngstudio.paperclash2.ui.LocalFx
import com.ngstudio.paperclash2.ui.Screen
import com.ngstudio.paperclash2.ui.art.ShapeThumb
import com.ngstudio.paperclash2.ui.components.ConfettiRain
import com.ngstudio.paperclash2.ui.components.GameButton
import com.ngstudio.paperclash2.ui.components.GameDialog
import com.ngstudio.paperclash2.ui.components.GameToggle
import com.ngstudio.paperclash2.ui.components.OutlinedText
import com.ngstudio.paperclash2.ui.components.PaperCard
import com.ngstudio.paperclash2.ui.components.ProgressStrip
import com.ngstudio.paperclash2.ui.components.RibbonBanner
import com.ngstudio.paperclash2.ui.components.StarRow
import com.ngstudio.paperclash2.ui.components.VSpace
import com.ngstudio.paperclash2.ui.theme.PC
import com.ngstudio.paperclash2.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResultInfo(val level: Int, val result: SessionResult, val reward: LevelReward, val shapeId: String, val map: Bitmap?)

@Composable
fun PauseDialog(state: AppState, onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit) {
    val save by state.save.state.collectAsState()
    GameDialog(onDismiss = onResume, tape = PC.Sky) {
        OutlinedText("PAUSED", Type.title.copy(color = Color.White), Modifier.align(Alignment.CenterHorizontally), outline = PC.Ink, width = 9f)
        VSpace(12.dp)
        listOf(
            Triple(R.drawable.ic_sound_on, "Sound", save.settings.sound),
            Triple(R.drawable.ic_music_on, "Music", save.settings.music),
            Triple(R.drawable.ic_vibrate_on, "Vibration", save.settings.vibration),
        ).forEachIndexed { i, (icon, label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(icon), null, Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = Type.label, modifier = Modifier.weight(1f))
                GameToggle(value, { v ->
                    state.save.update {
                        it.copy(settings = when (i) {
                            0 -> it.settings.copy(sound = v)
                            1 -> it.settings.copy(music = v)
                            else -> it.settings.copy(vibration = v)
                        })
                    }
                })
            }
        }
        VSpace(14.dp)
        GameButton("RESUME", onResume, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark, icon = R.drawable.ic_play)
        VSpace(8.dp)
        Row {
            GameButton("RESTART", onRestart, Modifier.weight(1f), color = PC.Sky, dark = PC.SkyDark, height = 48.dp, style = Type.button.copy(fontSize = 16.sp))
            Spacer(Modifier.width(8.dp))
            GameButton("QUIT", onQuit, Modifier.weight(1f), height = 48.dp, style = Type.button.copy(fontSize = 16.sp), sound = Sfx.UI_BACK)
        }
    }
}

@Composable
fun DownDialog(state: AppState, e: GameEvent.HumanDown) {
    val save by state.save.state.collectAsState()
    val fx = LocalFx.current
    val c = state.controller
    val why = when (e.cause) {
        Cause.SNIPPED -> "${e.killer ?: "A rival"} snipped your ribbon!"
        Cause.SURROUNDED -> "${e.killer ?: "A rival"} wrapped up all your land!"
        Cause.HAZARD -> "A Scissor Spinner cut your ribbon!"
    }
    GameDialog(onDismiss = null, tape = PC.Coral) {
        OutlinedText("POPPED!", Type.hero.copy(color = Color.White), Modifier.align(Alignment.CenterHorizontally), outline = PC.CoralDark, width = 11f)
        VSpace(6.dp)
        Text(why, style = Type.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VSpace(16.dp)
        if (e.canRevive) {
            Text("Keep your land and fly on with a shield?", style = Type.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            VSpace(10.dp)
            if (state.app.ads.rewardedAvailable) {
                GameButton("REVIVE (AD)", {
                    state.app.ads.showRewarded(Placements.REVIVE) { ok -> if (ok) c.revive() }
                }, Modifier.fillMaxWidth(), color = PC.Grape, dark = PC.GrapeDark, icon = R.drawable.ic_ad)
                VSpace(8.dp)
            }
            GameButton("REVIVE  ${Progress.REVIVE_COST}", {
                if (state.data.coins >= Progress.REVIVE_COST) {
                    state.save.update { it.copy(coins = it.coins - Progress.REVIVE_COST) }
                    fx.play(Sfx.UI_PURCHASE)
                    c.revive()
                } else fx.play(Sfx.UI_ERROR)
            }, Modifier.fillMaxWidth(), color = PC.Mint, dark = PC.MintDark, icon = R.drawable.ic_coin, enabled = save.coins >= Progress.REVIVE_COST)
            VSpace(8.dp)
        }
        GameButton("GIVE UP", { c.giveUp() }, Modifier.fillMaxWidth(), color = PC.Paper, dark = PC.PaperDim,
            style = Type.button.copy(color = PC.Ink, fontSize = 17.sp), height = 48.dp, sound = Sfx.UI_BACK)
    }
}

/** Win / lose results with star pops, coin count-up, sticker reveal and next steps. */
@Composable
fun ResultScreen(state: AppState, info: ResultInfo) {
    val fx = LocalFx.current
    val r = info.result
    val reward = info.reward
    val spec = remember(info.level) { LevelGenerator.spec(info.level) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shownStars by remember { mutableIntStateOf(0) }
    var coinsShown by remember { mutableIntStateOf(0) }
    var doubled by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 320f))
        if (r.won) {
            for (i in 1..r.stars) {
                delay(380)
                shownStars = i
                fx.play(when (i) { 1 -> Sfx.STAR_1; 2 -> Sfx.STAR_2; else -> Sfx.STAR_3 })
                fx.haptics.tick()
            }
        }
        delay(250)
        val steps = 20
        for (k in 1..steps) {
            coinsShown = reward.coins * k / steps
            if (k % 2 == 0) fx.play(Sfx.COIN_COUNT)
            delay(35)
        }
        if (reward.sticker != null) {
            delay(300)
            fx.play(Sfx.STICKER)
        }
    }
    Box(Modifier.fillMaxSize().background(PC.Cream.copy(alpha = 0.72f * appear.value.coerceIn(0f, 1f)))) {
        if (r.won) ConfettiRain()
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VSpace(28.dp)
            val title = when {
                r.won -> if (r.stars == 3) "PERFECT!" else "LEVEL COMPLETE!"
                r.reason == EndReason.TIME -> "TIME'S UP!"
                else -> "OUT!"
            }
            RibbonBanner(title, Modifier.widthIn(max = 380.dp).fillMaxWidth().graphicsLayer {
                scaleX = appear.value
                scaleY = appear.value
            }, color = if (r.won) PC.Mint else PC.Coral, height = 74.dp)
            VSpace(8.dp)
            if (r.won) StarRow(shownStars, size = 58.dp, raiseMiddle = true)
            VSpace(10.dp)
            PaperCard(Modifier.widthIn(max = 420.dp).fillMaxWidth(), tape = if (r.won) PC.Sun else PC.Sky) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(PC.Cream), contentAlignment = Alignment.Center) {
                        val map = info.map
                        if (map != null) {
                            val img = remember(map) { map.asImageBitmap() }
                            Image(img, null, Modifier.size(78.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                        } else ShapeThumb(info.level, null, PC.Sky, 70.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Level ${info.level} · ${ShapeLibrary.byId(info.shapeId).name}", style = Type.label)
                        Text(Worlds.name(Worlds.worldOf(info.level)), style = Type.small)
                        VSpace(4.dp)
                        Stat(R.drawable.ic_mode_claim, "Land", String.format("%.1f%% (best %.1f%%)", r.percent, r.bestPercent))
                        if (r.snips > 0) Stat(R.drawable.ic_scissors, "Snips", r.snips.toString())
                        if (spec.mode == Mode.COIN_RUSH) Stat(R.drawable.ic_star, "Paper stars", r.starsCollected.toString())
                        if (r.maxCombo > 1) Stat(R.drawable.ic_pop, "Best combo", "x${r.maxCombo}")
                    }
                }
                if (!r.won) {
                    VSpace(10.dp)
                    Text(spec.goal.summary(), style = Type.small)
                    if (spec.mode == Mode.CLAIM || spec.mode == Mode.SURVIVE) {
                        ProgressStrip(r.bestPercent / spec.goal.t1, Modifier.fillMaxWidth(), color = PC.Coral)
                        Text(String.format("%.1f%% of %.0f%%", r.bestPercent, spec.goal.t1), style = Type.small.copy(fontSize = 11.sp))
                    }
                    r.killerName?.let { VSpace(4.dp); Text("Popped by $it", style = Type.small) }
                }
                VSpace(12.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_coins_stack), null, Modifier.size(44.dp))
                    Spacer(Modifier.width(6.dp))
                    OutlinedText("+${coinsShown * (if (doubled) 2 else 1)}", Type.title.copy(color = PC.Sun), outline = PC.Ink, width = 9f)
                }
                if (r.won && state.app.ads.rewardedAvailable && !doubled) {
                    VSpace(6.dp)
                    GameButton("DOUBLE COINS", {
                        state.app.ads.showRewarded(Placements.DOUBLE_COINS) { ok ->
                            if (ok) {
                                doubled = true
                                state.save.update { it.copy(coins = it.coins + reward.coins) }
                                fx.play(Sfx.REWARD_BIG)
                            }
                        }
                    }, Modifier.fillMaxWidth(), color = PC.Grape, dark = PC.GrapeDark, icon = R.drawable.ic_ad, height = 46.dp, style = Type.button.copy(fontSize = 16.sp))
                }
            }
            if (reward.sticker != null) {
                VSpace(12.dp)
                PaperCard(Modifier.widthIn(max = 420.dp).fillMaxWidth(), tape = PC.Pink) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(
                            when (reward.stickerTier) { 3 -> Color(0xFFF2C14E); 2 -> Color(0xFFD5DAE4); else -> Color.White },
                        ), contentAlignment = Alignment.Center) {
                            ShapeThumb(null, reward.sticker, PC.Coral, 52.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(when (reward.stickerTier) { 3 -> "GOLD sticker!"; 2 -> "SILVER sticker!"; else -> "New sticker!" }, style = Type.heading)
                            Text("${ShapeLibrary.byId(reward.sticker).name} was added to your album.", style = Type.small)
                        }
                    }
                }
            }
            VSpace(16.dp)
            val next = info.level + 1
            if (r.won && next <= Worlds.TOTAL_LEVELS) {
                GameButton("NEXT LEVEL", { state.play(next, Boosters()) }, Modifier.widthIn(max = 380.dp).fillMaxWidth(), color = PC.Mint, dark = PC.MintDark,
                    icon = R.drawable.ic_next, height = 62.dp, pulse = true)
            } else if (!r.won) {
                GameButton("TRY AGAIN", { state.play(info.level, Boosters()) }, Modifier.widthIn(max = 380.dp).fillMaxWidth(), color = PC.Mint, dark = PC.MintDark,
                    icon = R.drawable.ic_restart, height = 62.dp, pulse = true)
            }
            VSpace(10.dp)
            Row(Modifier.widthIn(max = 380.dp).fillMaxWidth()) {
                if (r.won) {
                    GameButton("", { state.play(info.level, Boosters()) }, Modifier.weight(1f), color = PC.Sky, dark = PC.SkyDark, icon = R.drawable.ic_restart, height = 52.dp)
                    Spacer(Modifier.width(8.dp))
                }
                GameButton("", { state.tab = com.ngstudio.paperclash2.ui.Tab.HOME; state.reset(Screen.Main) }, Modifier.weight(1f), color = PC.Coral, dark = PC.CoralDark, icon = R.drawable.ic_nav_home, height = 52.dp, sound = Sfx.UI_BACK)
                Spacer(Modifier.width(8.dp))
                GameButton("", {
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { shareCard(context, info, state.data.nickname) }
                        if (uri != null) {
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_TEXT, "I folded Level ${info.level} into my colour in Paper Clash 2!")
                                // ClipData carries the read grant to the chooser too, so it can show the preview
                                clipData = android.content.ClipData.newRawUri("Paper Clash 2", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = Intent.createChooser(i, "Share your shape").apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(chooser)
                        }
                    }
                }, Modifier.weight(1f), color = PC.Grape, dark = PC.GrapeDark, icon = R.drawable.ic_share, height = 52.dp)
            }
            VSpace(40.dp)
        }
    }
}

@Composable
private fun Stat(icon: Int, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Image(painterResource(icon), null, Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text("$label: ", style = Type.small)
        Text(value, style = Type.label.copy(fontSize = 13.sp))
    }
}

/** Renders a shareable card (the painted shape, level and stars) into the cache and returns its URI. */
private fun shareCard(context: Context, info: ResultInfo, name: String): android.net.Uri? = try {
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = ACanvas(bmp)
    cv.drawColor(0xFFFFF6E6.toInt())
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val font = try { Typeface.createFromAsset(context.assets, "fonts/Baloo2-ExtraBold.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
    // board
    p.color = 0xFF2F8F6B.toInt()
    cv.drawRoundRect(RectF(90f, 260f, w - 90f, 260f + (w - 180f)), 60f, 60f, p)
    info.map?.let {
        val dst = RectF(130f, 300f, w - 130f, 300f + (w - 260f))
        val fp = Paint(Paint.FILTER_BITMAP_FLAG)
        cv.drawBitmap(it, null, dst, fp)
    }
    p.typeface = font
    p.textAlign = Paint.Align.CENTER
    p.color = 0xFF1F2A4A.toInt()
    p.textSize = 92f
    cv.drawText("PAPER CLASH 2", w / 2f, 170f, p)
    p.textSize = 64f
    p.color = 0xFFFF6B57.toInt()
    cv.drawText("Level ${info.level} · ${ShapeLibrary.byId(info.shapeId).name}", w / 2f, h - 190f, p)
    p.textSize = 52f
    p.color = 0xFF1F2A4A.toInt()
    val stars = "★".repeat(info.result.stars) + "☆".repeat(3 - info.result.stars)
    cv.drawText("${name.ifBlank { "I" }} claimed ${String.format("%.1f", info.result.bestPercent)}%  $stars", w / 2f, h - 110f, p)
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, "paperclash_${info.level}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    FileProvider.getUriForFile(context, "${context.packageName}.files", f)
} catch (_: Exception) {
    null
}

@Suppress("unused")
private val ease = FastOutSlowInEasing

@Suppress("unused")
private fun twn() = tween<Float>(300)
